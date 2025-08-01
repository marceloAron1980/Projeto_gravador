package br.com.spotcom.gravador;

import br.com.spotcom.gravador.config.Configuracao;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class GerenciadorGravacao {
    
    private final Configuracao configuracao;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    public GerenciadorGravacao(Configuracao configuracao) {
        this.configuracao = configuracao;
    }
    
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("O serviço já está rodando");
        }
        var thread = new Thread(this::handle);
        thread.setDaemon(false);
        thread.start();
    }
    
    public void stop() {
        shutdown.set(true);
    }
    
    public void stopAndWait(long timeout) throws InterruptedException {
        stop();
        stopSignal.await(timeout, TimeUnit.MILLISECONDS);
    }
    
    public boolean isRunning() {
        return running.get();
    }
    
    public boolean isShutdown() {
        return shutdown.get();
    }
    
    public void handle() {
        var executorService = Executors.newCachedThreadPool();
        List<Gravador> gravadores = new ArrayList<>();
        try {
            configuracao.getAdapters().forEach(e -> {
                var origem = e.getOrigem();
                var dirDestino = e.getDestino();

                if (!Files.exists(dirDestino) || !Files.isDirectory(dirDestino)) {
                    try {
                        dirDestino = Files.createDirectory(dirDestino);
                    } catch (FileAlreadyExistsException ex) {
                        // pode continuar
                    } catch (IOException | RuntimeException ex) {
                        System.out.println("Não foi possível criar o diretório " + dirDestino.toString());
                        return;
                    }
                }

                var gravador = new Gravador(origem, dirDestino, e.getServiceId(), e.getScale());
                gravadores.add(gravador);
            });
                        
            LocalDate data = LocalDate.now();
            while (!isShutdown()) {
                // se trocou o dia, deve reiniciar gravadores
                if (!LocalDate.now().equals(data)) {
                    data = LocalDate.now();
                    final LocalDate dataInicio = data;
                    
                    // reinicia gravadores com a nova data em segundo plano (para um não esperar o outro)
                    gravadores.forEach(g -> {
                        // reinicia se já não estava reiniciando
                        if (g.restart()) {
                            executorService.submit(() -> {
                                stopGravador(g);
                                startGravador(g, dataInicio);
                            });
                        }
                    });
                }
                
                // reinicia gravadores parados
                for (var g: gravadores) {
                    if (!g.isRunning() && !g.isRestarting()) {
                        // inicia se já não estava em processo de reinicio
                        if (g.restart()) startGravador(g, data);
                    }
                }

                try {
                    // dorme até o próximo segundo exato
                    long delay = Instant.now().until(Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1), ChronoUnit.MICROS);
                    TimeUnit.MICROSECONDS.sleep(delay);
                } catch (InterruptedException ex) {
                    // noop
                }
            }
        } finally {
            System.out.println(LocalDateTime.now().toString() + " - Parando todas as gravações");
            
            // sinaliza encerramento a todos os gravadores
            gravadores.forEach(e -> e.stop());
            
            // aguarda gravadores encerrarem
            gravadores.forEach(g -> stopGravador(g));

            // shutdown do executorService
            executorService.shutdown();
            try {
                executorService.awaitTermination(5000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                System.err.println("Não foi possível encerrar normalmente o ExecutorService!");
            }

            System.out.println(LocalDateTime.now().toString() + " - Gravações finalizadas");
            running.set(false);
            stopSignal.countDown();
        }
    }
    
    private static void stopGravador(Gravador gravador) {
        System.out.println(LocalDateTime.now().toString() + " - Parando gravador");
        try {
            gravador.stopAndWait(10000);
        } catch (ExecutionException | InterruptedException | TimeoutException ex) {
            ex.printStackTrace(System.err);
            System.err.println("Não foi possível encerrar normalmente o gravador! Forçando encerramento.");
            gravador.forceStop();
            if (gravador.isRunning()) {
                System.err.println("Não foi possível encerrar ffmpeg!");
            }
        }
    }
    
    private static void startGravador(Gravador gravador, LocalDate data) {
        System.out.println(LocalDateTime.now().toString() + " - Iniciando gravador");
        try {
            gravador.start(data);
        } catch (IOException ex) {
            System.err.println(ex);
        }
    }
    
}
