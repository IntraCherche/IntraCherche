package eu.intracherche.intracherche.registry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Detailed diagnostic tool to test indexed_at field behavior including debug logging
 */
public class DebugIndexedAtDetailed {
    
    public static void main(String[] args) {
        try {
            // Create a simple test to see the indexed_at field behavior
            System.out.println("=== Detailed Debugging indexed_at field ===");
            
            // Create a RegistryDataSource to get connection
            Path dbPath = Path.of("db/registry.db");
            RegistryDataSource ds = new RegistryDataSource(dbPath);
            Connection conn = ds.connection();
            
            // Create a FileRegistryDao to test the findFilesToIndex method
            FileRegistryDao dao = new FileRegistryDao(conn);
            
            // This will trigger the debug logging from findFilesToIndex method
            System.out.println("Calling findFilesToIndex() to trigger debug logging...");
            var files = dao.findFilesToIndex();
            
            System.out.println("Found " + files.size() + " files to index");
            
            conn.close();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
