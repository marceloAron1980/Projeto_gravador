package br.com.spotcom.gravador;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.CaptureInput;
import com.github.kokorin.jaffree.ffmpeg.ChannelInput;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.FFmpegResultFuture;
import com.github.kokorin.jaffree.ffmpeg.UrlOutput;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class Gravador {

    private final Path origem;
    private final Path dirDestino;
    private final int pid;
    private final String scale;
    private FFmpegResultFuture ffmpegFuture;
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private SeekableByteChannel inputStream;

    public Gravador(Path origem, Path dirDestino, int pid, String scale) {
        this.origem = origem;
        this.dirDestino = dirDestino;
        this.pid = pid;
        this.scale = scale;
    }

    public void start(LocalDate data) throws IOException {
        try {            
            if (isRunning()) {
                throw new IllegalStateException("Gravador já está rodando!");
            }
            
            closeInput();
            this.inputStream = Files.newByteChannel(origem);
            
            ffmpegFuture = buildFFmpeg(data).executeAsync();
            ffmpegFuture.toCompletableFuture().whenComplete((result, ex) -> {
                closeInput();
            });
        } finally {
            restarting.set(false);
        }
    }
    
    private void closeInput() {
        if (this.inputStream != null) {
            try {
                this.inputStream.close();
            } catch (IOException ex) {
                System.err.println("Falha ao encerrar input channel: " + ex.toString());
            }
            this.inputStream = null;
        }
    }

    public boolean isRestarting() {
        return restarting.get();
    }

    public boolean restart() {
        return restarting.compareAndSet(false, true);
    }

    public boolean isRunning() {
        return ffmpegFuture != null && !ffmpegFuture.isDone() && !ffmpegFuture.isCancelled();
    }

    private FFmpeg buildFFmpeg(LocalDate data) throws IOException {
        String sdir = data.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path dir = dirDestino.resolve(sdir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            try {
                dir = Files.createDirectory(dir);
            } catch (FileAlreadyExistsException ex) {
                // pode continuar
            } catch (IOException | RuntimeException ex) {
                throw new IOException("Não foi possível criar o diretório " + dir.toString());
            }
        }

        var ffmpeg = FFmpeg.atPath()
                .addInput(ChannelInput
                        .fromChannel(inputStream)
                        .setFormat("mpegts")
                        .addArgument("-fix_sub_duration")
                )
                .addArguments("-map", String.format("0:p:%d:0", pid))
                .addArguments("-map", String.format("0:p:%d:1", pid));

        ffmpeg.addOutput(UrlOutput
                        .toPath(dir.resolve("%H%M%S.mp4"))
                        .setFormat("segment")
                        .addArguments("-segment_time", "600") // 10 minutos
                        .addArguments("-segment_atclocktime", "1")
                        .addArguments("-segment_clocktime_offset", "0")
                        .addArguments("-reset_timestamps", "1")
                        // .addArguments("-x264-params", "keyint=12:no-scenecut=1")
                        // .addArguments("-force_key_frames", "expr:gte(t,n_forced*600)")
                        .addArguments("-force_key_frames", "expr:gte(t,n_forced*1)")
                        // .addArguments("-force_key_frames", "expr:if(isnan(prev_forced_n),1,eq(n,prev_forced_n+10))")
                        .addArguments("-c:v", "libx264")
                        // .addArguments("-vf", "scale=" + scale)
                        .addArguments("-c:a", "aac")
                        .addArguments("-copytb", "1")
                        .addArguments("-abort_on", "empty_output")
                        .addArguments("-strftime", "1")
                );
        
        if (scale != null && !scale.isBlank()) {
            ffmpeg.addArguments("-vf", "scale=" + scale);
        }
        
        ffmpeg.setOverwriteOutput(true);
        ffmpeg.setLogLevel(LogLevel.ERROR);

                // .addArguments("-force_key_frames", "expr:if(isnan(prev_forced_n),1,eq(n,prev_forced_n+10))")
                // .addArguments("-crf", "20")
                // .addArguments("-g", "48")
                // .addArguments("-keyint_min", "48")
                // .addArguments("-sc_threshold", "0")
                // .setLogLevel(LogLevel.ERROR)
                /*
                .setOutputListener(line -> {
                    System.out.println("[FFMPEG] " + line);
                    return true;
                });
                */

        return ffmpeg;
    }

    public void stop() {
        // if (!isRunning()) return;
        ffmpegFuture.graceStop();
    }

    public void stopAndWait(long timeout) throws InterruptedException, TimeoutException, ExecutionException {
        stop();
        // if (!isRunning()) return;
        ffmpegFuture.get(timeout, TimeUnit.MILLISECONDS);
    }

    public void forceStop() {
        if (ffmpegFuture == null) {
            return;
        }
        ffmpegFuture.forceStop();
        try {
            ffmpegFuture.get(5000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ex) {
            // noop
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        Path dir = Path.of("temp");
        var ffmpeg = FFmpeg.atPath()
                .addInput(CaptureInput
                        .LinuxX11Grab.captureDisplayAndScreen(1, 0)
                        .setCaptureFrameRate(30)
                        .setCaptureCursor(true)
                )
                .addOutput(UrlOutput
                        .toPath(dir.resolve("%H%M%S.mp4"))
                        .setFormat("segment")
                        .addArguments("-segment_time", "10")
                        .addArguments("-segment_atclocktime", "1")
                        .addArguments("-segment_clocktime_offset", "0")
                        .addArguments("-strftime", "1")
                        .addArguments("-reset_timestamps", "1")
                        .setDuration(30, TimeUnit.SECONDS)
                )
                .setOverwriteOutput(true);
        ffmpeg.addArguments("-vf", "scale=230:180");
        ffmpeg.execute();
    }
}
