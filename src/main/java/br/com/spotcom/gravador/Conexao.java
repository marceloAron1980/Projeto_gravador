package br.com.spotcom.gravador;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 *
 * @author mikemurussi
 */
public class Conexao {
    
    private static final String URL_CONEXAO = "jdbc:mysql://%s/%s";
    private final String host;
    private final String database;
    private final String user;
    private final String password;
    
    public Conexao(String host, String database, String user, String password) {
        this.host = host;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    public Connection createConnection() throws SQLException {
        String url = String.format(URL_CONEXAO, host, database);
        var connection = DriverManager.getConnection(url, user, password);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }
    
}
