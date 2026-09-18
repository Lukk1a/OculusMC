package dev.lukka.oculus.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lukka.oculus.managers.DatabaseManager;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class UserRepository {

    private static final int ITERATIONS = 210000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class UserRecord {
        public final String id;
        public final String username;
        public final String passwordHash;
        public final String totpSecretEnc;
        public final boolean totpConfirmed;
        public final String role;
        public final Set<String> nodes;
        public final List<String> recoveryHashes;

        public UserRecord(String id, String username, String passwordHash, String totpSecretEnc,
                          boolean totpConfirmed, String role, Set<String> nodes, List<String> recoveryHashes) {
            this.id = id;
            this.username = username;
            this.passwordHash = passwordHash;
            this.totpSecretEnc = totpSecretEnc;
            this.totpConfirmed = totpConfirmed;
            this.role = role;
            this.nodes = nodes;
            this.recoveryHashes = recoveryHashes;
        }
    }

    public boolean createUser(String username, String password, String role, Set<String> customNodes,
                              String totpSecretEnc, List<String> recoveryCodes) {
        String hash = hashPassword(password);
        String id = UUID.randomUUID().toString();
        List<String> recoveryHashes = new ArrayList<>();
        if (recoveryCodes != null) {
            for (String code : recoveryCodes) {
                recoveryHashes.add(hashPassword(code));
            }
        }

        String nodesJson = "[]";
        try {
            nodesJson = MAPPER.writeValueAsString(customNodes != null ? customNodes : Collections.emptyList());
        } catch (Exception ignored) {}

        String recoveryJson = "[]";
        try {
            recoveryJson = MAPPER.writeValueAsString(recoveryHashes);
        } catch (Exception ignored) {}

        String sql = "INSERT INTO users (id, username, password_hash, totp_secret_enc, totp_confirmed, role, nodes_json, recovery_hashes) VALUES (?, ?, ?, ?, 0, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, username);
            stmt.setString(3, hash);
            stmt.setString(4, totpSecretEnc);
            stmt.setString(5, role != null ? role : "Admin");
            stmt.setString(6, nodesJson);
            stmt.setString(7, recoveryJson);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public UserRecord findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String id = rs.getString("id");
                    String user = rs.getString("username");
                    String passHash = rs.getString("password_hash");
                    String totpEnc = rs.getString("totp_secret_enc");
                    boolean confirmed = rs.getBoolean("totp_confirmed");
                    String role = rs.getString("role");
                    String nodesJson = rs.getString("nodes_json");
                    String recJson = rs.getString("recovery_hashes");

                    Set<String> nodes = new HashSet<>();
                    try {
                        if (nodesJson != null) {
                            List<String> list = MAPPER.readValue(nodesJson, new TypeReference<List<String>>() {});
                            if (list != null) nodes.addAll(list);
                        }
                    } catch (Exception ignored) {}

                    List<String> recoveryHashes = new ArrayList<>();
                    try {
                        if (recJson != null) {
                            List<String> list = MAPPER.readValue(recJson, new TypeReference<List<String>>() {});
                            if (list != null) recoveryHashes.addAll(list);
                        }
                    } catch (Exception ignored) {}

                    return new UserRecord(id, user, passHash, totpEnc, confirmed, role, nodes, recoveryHashes);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean verifyPassword(String username, String password) {
        UserRecord user = findByUsername(username);
        if (user == null || user.passwordHash == null) return false;
        return verifyPasswordHash(password, user.passwordHash);
    }

    public boolean verifyRecoveryCode(String username, String recoveryCode) {
        UserRecord user = findByUsername(username);
        if (user == null || user.recoveryHashes == null || user.recoveryHashes.isEmpty()) return false;
        for (int i = 0; i < user.recoveryHashes.size(); i++) {
            String storedHash = user.recoveryHashes.get(i);
            if (verifyPasswordHash(recoveryCode, storedHash)) {
                // Burn this recovery code so it can't be reused
                List<String> updated = new ArrayList<>(user.recoveryHashes);
                updated.remove(i);
                try {
                    String json = MAPPER.writeValueAsString(updated);
                    try (Connection conn = DatabaseManager.getInstance().getConnection();
                         PreparedStatement stmt = conn.prepareStatement("UPDATE users SET recovery_hashes = ? WHERE username = ?")) {
                        stmt.setString(1, json);
                        stmt.setString(2, username);
                        stmt.executeUpdate();
                    }
                } catch (Exception ignored) {}
                return true;
            }
        }
        return false;
    }

    public void confirmTotp(String username) {
        String sql = "UPDATE users SET totp_confirmed = 1 WHERE username = ?";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean hasUsers() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public boolean verifyPasswordHash(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        String[] parts = storedHash.split(":");
        if (parts.length != 3) return false;
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] hash = Base64.getDecoder().decode(parts[2]);
            byte[] testHash = pbkdf2(password.toCharArray(), salt, iterations, hash.length * 8);
            return MessageDigest.isEqual(hash, testHash);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
