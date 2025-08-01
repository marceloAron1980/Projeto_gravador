package br.com.spotcom.gravador;

import br.com.spotcom.gravador.config.Configuracao;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.configuration.ConfigurationException;

/**
 *
 * @author Michael Murussi <mike at performatica.com.br>
 */
public class Main {
    
    public static void main(String[] args) {
        System.out.println(LocalDateTime.now().toString() + " - Carregando configuração");
        Configuracao config;
        try {
            config = Configuracao.load(new File("config.json"));
        } catch (IOException | ConfigurationException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, "falha ao ler configuração", ex);
            return;
        }
        
        System.out.println(LocalDateTime.now().toString() + " - Iniciando zap");
        var zap = new GerenciadorZap(config);
        var zapThread = new Thread(zap);
        zapThread.start();
        
        // aguarda 5 segundos para zap estar pronto
        try {
            Thread.sleep(5000);
        } catch (InterruptedException ex) {
            // noop
        }
        
        System.out.println(LocalDateTime.now().toString() + " - Iniciando gravadores");
        GerenciadorGravacao gerenciador = new GerenciadorGravacao(config);
        gerenciador.start();
        
        final GerenciadorUpload gerenciadorUpload = new GerenciadorUpload(config);
        if (config.isUploadAtivo()) {
            System.out.println(LocalDateTime.now().toString() + " - Iniciando upload");
            gerenciadorUpload.start();
        }
        
        // shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                gerenciador.stopAndWait(15000);
            } catch (InterruptedException ex) {
                System.out.println(LocalDateTime.now().toString() + " - " + ex.toString());
            }
            zap.stop();
            if (zapThread.isAlive()) {
                try {
                    zapThread.join(10000);
                } catch (InterruptedException ex) {
                    System.out.println(LocalDateTime.now().toString() + " - " + ex.toString());
                }
            }
            try {
                gerenciadorUpload.stopAndWait(5000);
            } catch (InterruptedException ex) {
                System.out.println(LocalDateTime.now().toString() + " - " + ex.toString());
            }
        }));
        
        try {
            zapThread.join();
        } catch (InterruptedException ex) {
           System.out.println(LocalDateTime.now().toString() + " - " + ex.toString());
        }
    }
    
}
