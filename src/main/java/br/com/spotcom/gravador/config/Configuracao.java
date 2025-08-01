package br.com.spotcom.gravador.config;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.json.Json;
import javax.json.JsonReader;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class Configuracao {
    
    private Path channelsFile;
    private final List<Adapter> adapters = new ArrayList<>();
    private String gravador;
    private URI servidor;
    private boolean uploadAtivo;
    
    public static Configuracao load(File file) throws IOException, ConfigurationException {
        try (var reader = new FileReader(file)) {
            return load(reader);
        }
    }
    
    public static Configuracao load(Reader reader) throws ConfigurationException {
        var config = new Configuracao();
        try (JsonReader jsonReader = Json.createReader(reader)) {
            var jsonObject = jsonReader.readObject();
            config.gravador = jsonObject.getString("gravador", null);
            if (config.gravador == null) {
                throw new IllegalArgumentException("Nome do gravador não configurado!");
            }

            String url = jsonObject.getString("servidor", "localhost:8080");
            try {
                config.servidor = new URI("http://" + url);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("URL configurada para o servidor inválida: " + url);
            }
            
            config.uploadAtivo = jsonObject.getBoolean("upload", true);

            String channelsFile = jsonObject.getString("channels-file", null);
            if (channelsFile == null) {
                throw new IllegalArgumentException("Caminho para \"channels-file\" não configurado.");
            }
            config.channelsFile = Path.of(channelsFile);
            if (!Files.exists(config.channelsFile)) {
                throw new IllegalArgumentException("Arquivo configurado em \"channels-file\" não existe.");
            }
            
            var jsonArray = jsonObject.getJsonArray("adapters");
            if (jsonArray != null) {
                for (var e: jsonArray) {
                    var obj = e.asJsonObject();
                    var adapter = new Adapter(
                            obj.getString("gravador", config.gravador),
                            obj.getInt("adapter", -1),
                            obj.getString("service-name", null),
                            obj.getString("praca", null),
                            obj.getInt("rede", 0),
                            obj.getString("scale", ""),
                            obj.getString("caminho", "")
                    );
                    if (adapter.getAdapter() < 0) {
                        throw new IllegalArgumentException("Falta o número do adapter");
                    }
                    if (adapter.getServiceName() == null) {
                        throw new IllegalArgumentException("Falta o \"service-name\" do adapter " + adapter.getAdapter());
                    }
                    if (adapter.getPraca()== null) {
                        throw new IllegalArgumentException("Falta a \"praca\" do adapter " + adapter.getAdapter());
                    }
                    if (adapter.getRede() <= 0) {
                        throw new IllegalArgumentException("Falta a \"rede\" do adapter " + adapter.getAdapter());
                    }
                    if (config.adapters.stream().anyMatch(a -> a.getAdapter() == adapter.getAdapter())) {
                        throw new IllegalArgumentException("Adapter " + adapter.getAdapter() + " duplicado!");
                    }
                    config.adapters.add(adapter);
                }
            }
        }
        
        // carrega serviceId do channels-file
        var ini = new HierarchicalINIConfiguration(config.channelsFile.toFile());
        for (var adapter: config.adapters) {
            var service = ini.getSection(adapter.getServiceName());
            if (service == null) throw new IllegalArgumentException("Service \"" + adapter.getServiceName() + "\" não encontrado no channels.conf configurado.");
            String serviceId = service.getString("SERVICE_ID");
            if (serviceId == null) throw new IllegalArgumentException("ServiceId não encontrado para \"" + adapter.getServiceName() + "\" no channels.conf configurado.");
            try {
                adapter.setServiceId(Integer.parseInt(serviceId));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("ServiceId inválido em \"" + adapter.getServiceName() + "\" no channels.conf configurado.");
            }
        }
        
        return config;
    }

    public Path getChannelsFile() {
        return channelsFile;
    }

    public List<Adapter> getAdapters() {
        return Collections.unmodifiableList(adapters);
    }

    public URI getServidor() {
        return servidor;
    }

    public String getGravador() {
        return gravador;
    }

    public boolean isUploadAtivo() {
        return uploadAtivo;
    }
    
}
