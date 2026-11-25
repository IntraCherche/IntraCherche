package eu.intracherche.intracherche.registry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Simple diagnostic tool to test indexed_at field behavior
 */
public class DebugIndexedAt {
    
    public static void main(String[] args) {
        try {
            // Create a simple test to see the indexed_at field behavior
            System.out.println("=== Debugging indexed_at field ===");
            
            // Create a RegistryDataSource to get connection
            Path dbPath = Path.of("db/registry.db");
            RegistryDataSource ds = new RegistryDataSource(dbPath);
            Connection conn = ds.connection();
            
            // Create diagnostic tool
            DatabaseDiagnosticTool diagnosticTool = new DatabaseDiagnosticTool(conn);
            
            // Show all files with indexed_at values
            diagnosticTool.dumpAllFilesWithIndexedAt();
            
            // Show files that would be selected for reindexing
            System.out.println("\n--- Files selected for reindexing ---");
            diagnosticTool.getFilesToReindex();
            
            conn.close();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
