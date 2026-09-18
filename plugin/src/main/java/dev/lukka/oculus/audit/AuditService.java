package dev.lukka.oculus.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lukka.oculus.managers.DatabaseManager;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AuditService {

    private final ObjectMapper mapper;
    private final File auditLogFile;
    private final Object lock = new Object();

    public AuditService(Plugin plugin) {
        this.mapper = new ObjectMapper();
        this.auditLogFile = new File(plugin.getDataFolder(), "audit.jsonl");
        initializeHead();
    }

    private void initializeHead() {
        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
            boolean exists = false;
            String checkSql = "SELECT last_hash FROM audit_head WHERE id = 1";
            try (PreparedStatement stmt = conn.prepareStatement(checkSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
            if (!exists) {
                String insertSql = "INSERT INTO audit_head (id, last_hash) VALUES (1, ?)";
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, "GENESIS");
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String getLastHash(Connection conn) throws SQLException {
        String sql = "SELECT last_hash FROM audit_head WHERE id = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("last_hash");
            }
        }
        return "GENESIS";
    }

    private void updateLastHash(Connection conn, String hash) throws SQLException {
        String sql = "UPDATE audit_head SET last_hash = ? WHERE id = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hash);
            stmt.executeUpdate();
        }
    }

    public void logEvent(String action, String actor, String ip, Map<String, Object> details) {
        synchronized (lock) {
            try (Connection conn = DatabaseManager.getInstance().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String prevHash = getLastHash(conn);
                    long timestamp = System.currentTimeMillis();

                    AuditEvent event = new AuditEvent(action, actor, ip, timestamp, details, prevHash);
                    String json = mapper.writeValueAsString(event);
                    String currentHash = calculateHash(json);
                    
                    event.hash = currentHash; // not modifying json, append as is or re-serialize?
                    // Better to re-serialize so hash is in the log.
                    String finalJson = mapper.writeValueAsString(event);

                    appendToFile(finalJson);
                    updateLastHash(conn, currentHash);
                    
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    e.printStackTrace();
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void appendToFile(String json) throws IOException {
        try (FileWriter fw = new FileWriter(auditLogFile, StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(json);
        }
    }

    public List<String> readAuditLines() throws IOException {
        if (!auditLogFile.exists()) {
            return Collections.emptyList();
        }
        return Files.readAllLines(auditLogFile.toPath(), StandardCharsets.UTF_8);
    }

    private String calculateHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static class AuditEvent {
        public String action;
        public String actor;
        public String ip;
        public long timestamp;
        public Map<String, Object> details;
        public String prevHash;
        public String hash;

        public AuditEvent() {}

        public AuditEvent(String action, String actor, String ip, long timestamp, Map<String, Object> details, String prevHash) {
            this.action = action;
            this.actor = actor;
            this.ip = ip;
            this.timestamp = timestamp;
            this.details = details;
            this.prevHash = prevHash;
        }
    }
}
