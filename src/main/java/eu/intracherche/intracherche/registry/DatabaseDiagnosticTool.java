package eu.intracherche.intracherche.registry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic tool to examine database contents and help debug indexed_at field issues.
 */
public class DatabaseDiagnosticTool {
    
    private final Connection conn;
    
    public DatabaseDiagnosticTool(Connection conn) {
        this.conn = conn;
    }
    
    /**
     * Dumps all file records with their indexed_at values for debugging
     */
    public void dumpAllFilesWithIndexedAt() throws SQLException {
        String sql = """
            SELECT id, path, mtime, size, indexed_at, last_seen, is_smb, smb_share_name
            FROM file_registry 
            ORDER BY path
            """;
            
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            System.out.println("=== DATABASE FILE RECORDS ===");
            System.out.printf("%-36s %-50s %-15s %-15s %-20s %-15s %-10s %-20s%n", 
                "ID", "PATH", "MTIME", "SIZE", "INDEXED_AT", "LAST_SEEN", "IS_SMB", "SMB_SHARE_NAME");
            System.out.println("----------------------------------------------------------------------------------------");
            
            while (rs.next()) {
                String id = rs.getString("id");
                String path = rs.getString("path");
                long mtime = rs.getLong("mtime");
                long size = rs.getLong("size");
                long indexedAt = rs.getLong("indexed_at");
                boolean wasNull = rs.wasNull();
                long lastSeen = rs.getLong("last_seen");
                boolean isSmb = rs.getBoolean("is_smb");
                String smbShareName = rs.getString("smb_share_name");
                
                System.out.printf("%-36s %-50s %-15d %-15d %-20d %-15d %-10s %-20s%n", 
                    id, path, mtime, size, indexedAt, lastSeen, isSmb, smbShareName);
            }
        }
    }
    
    /**
     * Gets specific file record information for debugging
     */
    public void getFileRecordInfo(String path) throws SQLException {
        String sql = """
            SELECT id, path, mtime, size, indexed_at, last_seen, is_smb, smb_share_name
            FROM file_registry 
            WHERE path = ?
            """;
            
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("=== SPECIFIC FILE RECORD ===");
                    System.out.printf("ID: %s%n", rs.getString("id"));
                    System.out.printf("PATH: %s%n", rs.getString("path"));
                    System.out.printf("MTIME: %d%n", rs.getLong("mtime"));
                    System.out.printf("SIZE: %d%n", rs.getLong("size"));
                    System.out.printf("INDEXED_AT: %d%n", rs.getLong("indexed_at"));
                    System.out.printf("LAST_SEEN: %d%n", rs.getLong("last_seen"));
                    System.out.printf("IS_SMB: %s%n", rs.getBoolean("is_smb"));
                    System.out.printf("SMB_SHARE_NAME: %s%n", rs.getString("smb_share_name"));
                } else {
                    System.out.println("No record found for path: " + path);
                }
            }
        }
    }
    
    /**
     * Gets all files that would be selected for reindexing (indexed_at IS NULL OR indexed_at < mtime)
     */
    public void getFilesToReindex() throws SQLException {
        String sql = """
            SELECT id, path, mtime, size, indexed_at, last_seen, is_smb, smb_share_name
            FROM file_registry 
            WHERE indexed_at IS NULL OR indexed_at < mtime
            ORDER BY path
            """;
            
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            
            System.out.println("=== FILES SELECTED FOR REINDEXING ===");
            System.out.printf("%-36s %-50s %-15s %-15s %-20s %-15s %-10s %-20s%n", 
                "ID", "PATH", "MTIME", "SIZE", "INDEXED_AT", "LAST_SEEN", "IS_SMB", "SMB_SHARE_NAME");
            System.out.println("----------------------------------------------------------------------------------------");
            
            while (rs.next()) {
                String id = rs.getString("id");
                String path = rs.getString("path");
                long mtime = rs.getLong("mtime");
                long size = rs.getLong("size");
                long indexedAt = rs.getLong("indexed_at");
                boolean wasNull = rs.wasNull();
                long lastSeen = rs.getLong("last_seen");
                boolean isSmb = rs.getBoolean("is_smb");
                String smbShareName = rs.getString("smb_share_name");
                
                System.out.printf("%-36s %-50s %-15d %-15d %-20d %-15d %-10s %-20s%n", 
                    id, path, mtime, size, indexedAt, lastSeen, isSmb, smbShareName);
            }
        }
    }
}