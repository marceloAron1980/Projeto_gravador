package br.com.spotcom.gravador;

import br.com.spotcom.gravador.config.Configuracao;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class GerenciadorUpload {

    private final Map<Upload, Thread> adapters;
    private Thread thread;
    private volatile boolean shutdown = false;

    public GerenciadorUpload(Configuracao configuracao) {
        this.adapters = new HashMap<>(configuracao.getAdapters().size());
        configuracao.getAdapters().forEach(e -> this.adapters.put(new Upload(e, configuracao), null));
    }

    public void start() {
        this.thread = new Thread(this::handle);
        this.thread.start();
    }

    public void stopAndWait(long timeout) throws InterruptedException {
        this.shutdown = true;
        if (this.thread != null) {
            this.thread.join(timeout);
        }
    }

    private void handle() {
        try {
            while (!shutdown) {
                // inicia / reinicia uploads
                for (var e : adapters.entrySet()) {
                    if (!e.getKey().isShutdown() && (e.getValue() == null || !e.getValue().isAlive())) {
                        var p = new Thread(e.getKey());
                        e.setValue(p);
                        p.start();
                    }
                }

                try {
                    TimeUnit.MILLISECONDS.sleep(1000);
                } catch (InterruptedException ex) {
                    // noop
                }
            }
        } finally {
            this.adapters.keySet().forEach(e -> e.stop());
            this.adapters.values().forEach(e -> {
                if (e != null) {
                    try {
                        e.join(2000);
                    } catch (InterruptedException ex) {
                        // noop
                    }
                }
            });
        }
    }
}
