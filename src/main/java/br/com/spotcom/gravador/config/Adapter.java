package br.com.spotcom.gravador.config;

import java.nio.file.Path;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class Adapter {
    
    private final String gravador;
    private final int adapter;
    private final String serviceName;
    private final String praca;
    private final int rede;
    private int serviceId;
    private final String scale;
    private final String caminho;

    public Adapter(String gravador, int adapter, String serviceName, String praca, int rede, String scale, String caminho) {
        this.gravador = gravador;
        this.adapter = adapter;
        this.serviceName = serviceName;
        this.praca = praca;
        this.rede = rede;
        this.scale = scale;
        this.caminho = caminho;
    }

    public String getGravador() {
        return gravador;
    }

    public int getAdapter() {
        return adapter;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPraca() {
        return praca;
    }

    public int getRede() {
        return rede;
    }

    public int getServiceId() {
        return serviceId;
    }

    public void setServiceId(int serviceId) {
        this.serviceId = serviceId;
    }
    
    public Path getOrigem() {
        return Path.of(String.format("/dev/dvb/adapter%d/dvr0", adapter));
    }
    
    public Path getDestino() {
        // usa o caminho configurado ou um caminho padrão (forma antiga) como fallback
        if (this.caminho != null && !this.caminho.isBlank()) {
            return Path.of(this.caminho).toAbsolutePath();
        }
        return Path.of(String.format("adapter-%d-%d-%s-%03d", adapter, serviceId, praca, rede)).toAbsolutePath();
    }

    public String getScale() {
        return scale;
    }

}
