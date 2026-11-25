package eu.intracherche.intracherche.registry;

import eu.intracherche.intracherche.config.AppBeanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Owns SQLite connection
 */
public class RegistryDataSource {
    private final Connection connection;
    private static final Logger log = LoggerFactory.getLogger(RegistryDataSource.class);

    public RegistryDataSource(Path dbPath) throws SQLException {
        log.debug("Connecting to database at path " + dbPath.toAbsolutePath());
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        this.connection = DriverManager.getConnection(url);
        this.connection.setAutoCommit(false);
    }

    public Connection connection() {
        return connection;
    }
}
