package br.com.spotcom.gravador;

import br.com.spotcom.gravador.config.Adapter;
import br.com.spotcom.gravador.config.Configuracao;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class Upload implements Runnable {

    private final Adapter adapter;
    private final Configuracao configuracao;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final HttpClient client;

    public Upload(Adapter adapter, Configuracao configuracao) {
        this.adapter = adapter;
        this.configuracao = configuracao;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
    
    @Override
    public void run() {
        LocalDateTime ultimoArquivoEnviado = null;
        
        var dir = adapter.getDestino();
        while (!isShutdown()) {
            try {             
                // recupera status da API (horário do último arquivo recebido)
                if (ultimoArquivoEnviado == null) {
                    ultimoArquivoEnviado = getUltimoArquivoRecebido();
                }
                
                // seleciona próximo arquivo para envio após horário do último enviado (se nulo retornará o primeiro disponível)
                Path proximoArquivoEnvio = procuraProximoArquivo(dir, ultimoArquivoEnviado);
                                
                if (proximoArquivoEnvio == null) {
                    // ainda não existe arquivo disponível para envio, aguarda 5 segundos
                    TimeUnit.MILLISECONDS.sleep(5000);
                } else {
                    LocalDateTime horaArquivo = parseHoraArquivo(proximoArquivoEnvio);
                    
                    // efetua upload do arquivo
                    upload(proximoArquivoEnvio, horaArquivo);

                    // atualiza horário do último arquivo enviado
                    ultimoArquivoEnviado = horaArquivo;
                }
            } catch (InterruptedException ex) {
                // noop
            } catch (IOException | RuntimeException ex) {
                LOG.log(Level.SEVERE, ex.toString(), ex);
                
                // espera 5 seg. antes de tentar continuar
                try {
                    TimeUnit.MILLISECONDS.sleep(5000);
                } catch (InterruptedException e) {
                    // noop
                }
            }
        }
    }
        
    private void upload(Path arquivo, LocalDateTime dataHoraArquivo) throws FileNotFoundException, InterruptedException {
        var endpoint = String.format("/upload/%s/%s/%3d/%s/%s", 
                adapter.getGravador(),
                adapter.getPraca(),
                adapter.getRede(),
                dataHoraArquivo.format(DateTimeFormatter.ISO_LOCAL_DATE),
                arquivo.getFileName().toString()
        );

        URI uri = getEndPointURI(endpoint);

        var request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(arquivo))
                .timeout(Duration.ofMinutes(10))
                .build();
        
        // tenta enviar novamente se houver problemas de conexão (desiste após 3 tentativas)
        int tentativas = 0;
        while (!isShutdown() && tentativas < 3) {
            tentativas++;
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != HttpURLConnection.HTTP_CREATED) {
                    throw new RuntimeException("Falha no upload do arquivo " + arquivo.toString() + ". Servidor retornou status HTTP " + response.statusCode());
                }
                return;
            } catch (IOException | InterruptedException ex) {
                LOG.log(Level.SEVERE, "Falha no upload do arquivo " + arquivo.toString(), ex);
            }
            
            // espera um pouco antes de tentar novamente
            TimeUnit.MILLISECONDS.sleep(5000);
        }
        
        // se chegou até aqui significa que não conseguiu enviar
        LOG.log(Level.SEVERE, "Falha no Upload do arquivo {0}, desistindo.", arquivo.toString());
    }
        
    public boolean isShutdown() {
        return shutdown.get();
    }
    
    public void stop() {
        shutdown.set(true);        
    }
        
    /**
     * Consulta a API e retorna o último arquivo recebido.
     * 
     * @return data e hora do último arquivo recebido pela API.
     */
    private LocalDateTime getUltimoArquivoRecebido() throws IOException, InterruptedException {
        var endpoint = String.format("/upload/%s/%s/%3d/latest", 
                adapter.getGravador(),
                adapter.getPraca(),
                adapter.getRede()
        );

        URI uri = getEndPointURI(endpoint);

        var request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == HttpURLConnection.HTTP_OK) {
            System.out.println(response.body());
            return LocalDateTime.parse(response.body(), DateTimeFormatter.ISO_DATE_TIME);
        }

        return null;
    }
    
    private static Path procuraProximoArquivo(Path dir, LocalDateTime ultimoArquivoEnviado) throws IOException {
        var currentDir = ultimoArquivoEnviado != null ? dir.resolve(ultimoArquivoEnviado.format(DateTimeFormatter.BASIC_ISO_DATE)) : null;
        var currentFile = currentDir != null & ultimoArquivoEnviado != null ? currentDir.resolve(ultimoArquivoEnviado.format(DateTimeFormatter.ofPattern("HHmmss")) + ".mp4") : null;
        
        // data: yyyymmdd
        var dirPattern = Pattern.compile("\\d{8}");
        DirectoryStream.Filter<Path> dirFilter = path -> {
            return dirPattern.matcher(path.getFileName().toString()).matches()
                    && (currentDir == null || path.compareTo(currentDir) >= 0)
                    && Files.isDirectory(path);
        };

        // hhmmss.mp4
        var filePattern = Pattern.compile("\\d{6}\\.mp4");
        DirectoryStream.Filter<Path> fileFilter = path -> {
            return filePattern.matcher(path.getFileName().toString()).matches()
                    && (currentFile == null || path.compareTo(currentFile) > 0)
                    && Files.isReadable(path);
        };
        
        // TreeSet é ordenado pela ordem natural. É necessário ler todos pois DirectoryStream não possui ordem específica
        TreeSet<Path> arquivos = new TreeSet<>();
        try (var dirStream = Files.newDirectoryStream(dir, dirFilter)) {
            for (var dirPath : dirStream) {
                try (var fileStream = Files.newDirectoryStream(dirPath, fileFilter)) {
                    for (var path : fileStream) {
                        arquivos.add(path);
                    }
                }
            }
        }

        // só retorna o arquivo se já existe um próximo (size > 1), evitando assim retornar arquivos que ainda estão sendo gravados
        if (arquivos.size() < 2) {
            return null;
        }
        
        // retorna o primeiro arquivo filtrado (que seja maior que o horário do último arquivo gravado e não esteja atualmente sendo gravado, ou seja, existe um próximo arquivo)
        return arquivos.first();        
        
        /*
        // encontra próximo arquivo (o stream não possui ordem específica por isso é necessário o teste adicional para encontrar o "menor dos maiores")
        Path proximo = null;
        try (var dirStream = Files.newDirectoryStream(dir, dirFilter)) {
            for (var dirPath : dirStream) {
                try (var fileStream = Files.newDirectoryStream(dirPath, fileFilter)) {
                    for (var path : fileStream) {
                        if (proximo == null || path.getFileName().compareTo(proximo.getFileName()) < 0) {
                            proximo = path;
                        }
                    }
                }
            }
        }
        
        return proximo;
        */
    }
    
    private static LocalDateTime parseHoraArquivo(Path arquivo) {
        // diretório de data: yyyymmdd
        var dirData = getParentDir(arquivo);
        var data = LocalDate.parse(dirData.toString(), DateTimeFormatter.BASIC_ISO_DATE);
        
        // nome do arquivo: hhmmss.mp4
        var nomeArquivo = arquivo.getFileName().toString();
        int h = Integer.parseInt(nomeArquivo.substring(0, 2));
        int m = Integer.parseInt(nomeArquivo.substring(2, 4));
        int s = Integer.parseInt(nomeArquivo.substring(4, 6));
        
        return data.atTime(h, m, s);
    }
    
    private static Path getParentDir(Path path) {
        return path.getName(path.getNameCount() - 2);
    }
    
    private URI getEndPointURI(String endpoint) {
        try {
            return configuracao.getServidor().resolve(endpoint);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new RuntimeException("URI inválida: " + configuracao.getServidor() + endpoint, ex);
        }        
    }

    private static final Logger LOG = Logger.getLogger(Upload.class.getName());

}
