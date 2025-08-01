package br.com.spotcom.gravador;

import br.com.spotcom.gravador.config.Adapter;
import br.com.spotcom.gravador.config.Configuracao;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class GerenciadorZap implements Runnable {

    private final String channelsFile;
    private final Map<Adapter, Process> adapters;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public GerenciadorZap(Configuracao configuracao) {
        this.channelsFile = configuracao.getChannelsFile().toAbsolutePath().toString();
        this.adapters = new HashMap<>(configuracao.getAdapters().size());
        configuracao.getAdapters().forEach(e -> this.adapters.putIfAbsent(e, null));
    }

    private Process start(Adapter adapter) throws IOException {
        var zap = new ProcessBuilder(
                "dvbv5-zap",
                "-P",
                "-r",
                "-cc=BR",
                "-c",
                channelsFile,
                "-a",
                String.valueOf(adapter.getAdapter()),
                adapter.getServiceName()
        );
        return zap
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    @Override
    public void run() {
        try {
            while (!isShutdown()) {
                // reinicia adaptadores parados
                for (var e: adapters.entrySet()) {
                    if (e.getValue() == null || !e.getValue().isAlive()) {
                        try {
                            var p = start(e.getKey());
                            e.setValue(p);
                        } catch (IOException ex) {
                            LOG.log(Level.SEVERE, "não foi possível iniciar dvbv5-zap para adapter " + e.getKey().getAdapter(), ex);
                        }
                    }
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(1000);
                } catch (InterruptedException ex) {
                    // noop
                }
            }
        } finally {
            // envia sinal para encerrar processos
            adapters.values().stream().filter(e -> e != null).forEach(e -> e.destroy());
            
            // aguarda encerramento
            for (var e: adapters.values()) {
                if (e != null) {
                    try {
                        e.waitFor(5000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        // noop
                    }
                }
            }
            
            // força encerramento se ainda existirem processos rodando
            adapters.values().stream().filter(e -> e != null && e.isAlive()).forEach(e -> e.destroyForcibly());
        }
    }
    
    public boolean isShutdown() {
        return shutdown.get();
    }
    
    public void stop() {
        shutdown.set(true);
    }

    private static final Logger LOG = Logger.getLogger(GerenciadorZap.class.getName());

}
