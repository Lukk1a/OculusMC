package dev.lukka.oculus.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.lukka.oculus.managers.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final TotpService totpService;

    private final long refreshTtlDays;
    private final long preauthTtlSeconds;
    private final int lockoutAttempts;
    private final long lockoutSeconds;
    private final String totpIssuer;

    // PreAuth state: preAuthToken -> PreAuthSession
    public static class PreAuthSession {
        public final String username;
        public final String pendingTotpSecret; // only during setup
        public final boolean isSetup;
        public final long expiresAt;

        public PreAuthSession(String username, String pendingTotpSecret, boolean isSetup, long expiresAt) {
            this.username = username;
            this.pendingTotpSecret = pendingTotpSecret;
            this.isSetup = isSetup;
            this.expiresAt = expiresAt;
        }
    }

    private final Cache<String, PreAuthSession> preAuthCache;

    // Lockout state: username -> failed count & lock expiration
    private static class LockoutState {
        int attempts;
        long lockedUntil;
    }
    private final Cache<String, LockoutState> lockoutCache;

    public AuthService(UserRepository userRepository,
                       JwtService jwtService,
                       TotpService totpService,
                       long refreshTtlDays,
                       long preauthTtlSeconds,
                       int lockoutAttempts,
                       long lockoutSeconds,
                       String totpIssuer) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.totpService = totpService;
        this.refreshTtlDays = refreshTtlDays > 0 ? refreshTtlDays : 7;
        this.preauthTtlSeconds = preauthTtlSeconds > 0 ? preauthTtlSeconds : 300;
        this.lockoutAttempts = lockoutAttempts > 0 ? lockoutAttempts : 8;
        this.lockoutSeconds = lockoutSeconds > 0 ? lockoutSeconds : 300;
        this.totpIssuer = (totpIssuer != null && !totpIssuer.isBlank()) ? totpIssuer : "Oculus";

        this.preAuthCache = Caffeine.newBuilder()
                .expireAfterWrite(this.preauthTtlSeconds, TimeUnit.SECONDS)
                .build();

        this.lockoutCache = Caffeine.newBuilder()
                .expireAfterWrite(this.lockoutSeconds, TimeUnit.SECONDS)
                .build();
    }

    public boolean isSetupRequired() {
        return !userRepository.hasUsers();
    }

    public boolean isLockedOut(String username) {
        if (username == null) return false;
        LockoutState state = lockoutCache.getIfPresent(username.toLowerCase());
        if (state == null) return false;
        if (System.currentTimeMillis() < state.lockedUntil) {
            return true;
        }
        return false;
    }

    private void recordFailedAttempt(String username) {
        if (username == null) return;
        String key = username.toLowerCase();
        LockoutState state = lockoutCache.get(key, k -> new LockoutState());
        if (state != null) {
            state.attempts++;
            if (state.attempts >= lockoutAttempts) {
                state.lockedUntil = System.currentTimeMillis() + (lockoutSeconds * 1000L);
            }
        }
    }

    private void clearLockout(String username) {
        if (username != null) {
            lockoutCache.invalidate(username.toLowerCase());
        }
    }

    public SetupResult setupInitialAdmin(String username, String password) {
        if (!isSetupRequired()) {
            return new SetupResult(false, "setup_already_done", null, null, null, null, false);
        }

        String secret = totpService.generateSecret();
        String qrBase64 = totpService.generateQrCodeBase64(secret, username + "@" + totpIssuer, totpIssuer);
        String otpauthUrl = totpService.getOtpAuthUrl(secret, username + "@" + totpIssuer, totpIssuer);
        String[] recoveryCodes = totpService.generateRecoveryCodes();

        // Create the admin user in DB (totp_confirmed = 0)
        boolean created = userRepository.createUser(username, password, "Admin", Principal.getRoleNodes("Admin"), secret, List.of(recoveryCodes));
        if (!created) {
            return new SetupResult(false, "database_error", null, null, null, null, false);
        }

        // Issue PreAuth token
        String preAuthToken = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + (preauthTtlSeconds * 1000L);
        preAuthCache.put(preAuthToken, new PreAuthSession(username, secret, true, expiresAt));

        return new SetupResult(true, null, preAuthToken, qrBase64, otpauthUrl, List.of(recoveryCodes), true);
    }

    public LoginResult login(String username, String password) {
        if (username == null || password == null) {
            return new LoginResult(false, "invalid_credentials", null, false, null, null, null);
        }

        if (isLockedOut(username)) {
            return new LoginResult(false, "locked_out", null, false, null, null, null);
        }

        UserRepository.UserRecord user = userRepository.findByUsername(username);
        if (user == null || !userRepository.verifyPassword(username, password)) {
            recordFailedAttempt(username);
            return new LoginResult(false, "invalid_credentials", null, false, null, null, null);
        }

        clearLockout(username);

        // Mandatory TOTP on first admin and all users with TOTP secret
        boolean requireTotp = user.totpSecretEnc != null;
        if (requireTotp) {
            String preAuthToken = UUID.randomUUID().toString();
            long expiresAt = System.currentTimeMillis() + (preauthTtlSeconds * 1000L);
            preAuthCache.put(preAuthToken, new PreAuthSession(username, user.totpSecretEnc, !user.totpConfirmed, expiresAt));
            return new LoginResult(true, null, preAuthToken, true, null, null, null);
        }

        // If no TOTP (e.g., non-admin without 2FA), issue tokens directly
        Set<String> effectiveNodes = new HashSet<>(user.nodes);
        effectiveNodes.addAll(Principal.getRoleNodes(user.role));
        String accessToken = jwtService.generateAccessToken(username, user.role, effectiveNodes);
        String refreshToken = issueRefreshToken(username, UUID.randomUUID().toString());

        return new LoginResult(true, null, null, false, accessToken, refreshToken, new Principal(username, user.role, effectiveNodes));
    }

    public TotpResult verifyTotp(String preAuthToken, String code) {
        if (preAuthToken == null || code == null) {
            return new TotpResult(false, "invalid_preauth", null, null, null);
        }

        PreAuthSession session = preAuthCache.getIfPresent(preAuthToken);
        if (session == null || System.currentTimeMillis() > session.expiresAt) {
            preAuthCache.invalidate(preAuthToken);
            return new TotpResult(false, "invalid_preauth", null, null, null);
        }

        if (isLockedOut(session.username)) {
            return new TotpResult(false, "locked_out", null, null, null);
        }

        UserRepository.UserRecord user = userRepository.findByUsername(session.username);
        if (user == null) {
            return new TotpResult(false, "user_not_found", null, null, null);
        }

        String secret = user.totpSecretEnc != null ? user.totpSecretEnc : session.pendingTotpSecret;
        boolean valid = totpService.verifyCode(secret, code);

        // Check recovery codes if 6-digit TOTP fails
        if (!valid) {
            valid = userRepository.verifyRecoveryCode(session.username, code);
        }

        if (!valid) {
            recordFailedAttempt(session.username);
            return new TotpResult(false, "totp_invalid", null, null, null);
        }

        clearLockout(session.username);
        preAuthCache.invalidate(preAuthToken);

        if (!user.totpConfirmed) {
            userRepository.confirmTotp(session.username);
        }

        Set<String> effectiveNodes = new HashSet<>(user.nodes);
        effectiveNodes.addAll(Principal.getRoleNodes(user.role));
        String accessToken = jwtService.generateAccessToken(user.username, user.role, effectiveNodes);
        String refreshToken = issueRefreshToken(user.username, UUID.randomUUID().toString());

        return new TotpResult(true, null, accessToken, refreshToken, new Principal(user.username, user.role, effectiveNodes));
    }

    public RefreshResult refreshToken(String token) {
        if (token == null || token.isBlank()) {
            return new RefreshResult(false, "invalid_refresh_token", null, null, null);
        }

        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
            String id;
            String username;
            String familyId;
            boolean revoked;
            long expiresAt;

            String sql = "SELECT id, username, family_id, revoked, expires_at FROM refresh_tokens WHERE token = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, token);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return new RefreshResult(false, "invalid_refresh_token", null, null, null);
                    }

                    id = rs.getString("id");
                    username = rs.getString("username");
                    familyId = rs.getString("family_id");
                    revoked = rs.getBoolean("revoked");
                    expiresAt = rs.getTimestamp("expires_at").getTime();
                }
            }

            // ResultSet and PreparedStatement are fully closed before any mutating or secondary queries
            if (revoked) {
                // Refresh token reuse detected! Immediately revoke entire family
                revokeFamily(familyId, conn);
                return new RefreshResult(false, "token_family_revoked", null, null, null);
            }

            if (System.currentTimeMillis() > expiresAt) {
                return new RefreshResult(false, "token_expired", null, null, null);
            }

            // Revoke old token
            revokeToken(id, conn);

            UserRepository.UserRecord user = userRepository.findByUsername(username);
            if (user == null) {
                return new RefreshResult(false, "user_not_found", null, null, null);
            }

            // Rotate: issue new refresh token with same familyId
            String newRefresh = issueRefreshToken(username, familyId, conn);
            Set<String> effectiveNodes = new HashSet<>(user.nodes);
            effectiveNodes.addAll(Principal.getRoleNodes(user.role));
            String newAccessToken = jwtService.generateAccessToken(username, user.role, effectiveNodes);

            return new RefreshResult(true, null, newAccessToken, newRefresh, new Principal(username, user.role, effectiveNodes));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return new RefreshResult(false, "internal_error", null, null, null);
    }

    private void revokeToken(String id, Connection conn) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = 1 WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        }
    }

    public void revokeFamily(String familyId, Connection conn) throws SQLException {
        String sql = "UPDATE refresh_tokens SET revoked = 1 WHERE family_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, familyId);
            stmt.executeUpdate();
        }
    }

    private String issueRefreshToken(String username, String familyId) {
        try (Connection conn = DatabaseManager.getInstance().getConnection()) {
            return issueRefreshToken(username, familyId, conn);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String issueRefreshToken(String username, String familyId, Connection conn) throws SQLException {
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + (refreshTtlDays * 24L * 60L * 60L * 1000L);

        String sql = "INSERT INTO refresh_tokens (id, username, token, family_id, revoked, expires_at) VALUES (?, ?, ?, ?, 0, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, username);
            stmt.setString(3, token);
            stmt.setString(4, familyId);
            stmt.setTimestamp(5, new java.sql.Timestamp(expiresAt));
            stmt.executeUpdate();
        }

        return token;
    }

    public void logout(String jwtToken, String refreshToken) {
        if (jwtToken != null) {
            io.jsonwebtoken.Claims claims = jwtService.verifyToken(jwtToken);
            if (claims != null && claims.getId() != null) {
                jwtService.revokeToken(claims.getId());
            }
        }

        if (refreshToken != null) {
            try (Connection conn = DatabaseManager.getInstance().getConnection()) {
                String sql = "UPDATE refresh_tokens SET revoked = 1 WHERE token = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, refreshToken);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    // Result classes
    public static class SetupResult {
        public final boolean success;
        public final String error;
        public final String preAuthToken;
        public final String qrPngBase64;
        public final String otpauthUrl;
        public final List<String> recoveryCodes;
        public final boolean totpRequired;

        public SetupResult(boolean success, String error, String preAuthToken, String qrPngBase64,
                           String otpauthUrl, List<String> recoveryCodes, boolean totpRequired) {
            this.success = success;
            this.error = error;
            this.preAuthToken = preAuthToken;
            this.qrPngBase64 = qrPngBase64;
            this.otpauthUrl = otpauthUrl;
            this.recoveryCodes = recoveryCodes;
            this.totpRequired = totpRequired;
        }
    }

    public static class LoginResult {
        public final boolean success;
        public final String error;
        public final String preAuthToken;
        public final boolean totpRequired;
        public final String accessToken;
        public final String refreshToken;
        public final Principal principal;

        public LoginResult(boolean success, String error, String preAuthToken, boolean totpRequired,
                           String accessToken, String refreshToken, Principal principal) {
            this.success = success;
            this.error = error;
            this.preAuthToken = preAuthToken;
            this.totpRequired = totpRequired;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.principal = principal;
        }
    }

    public static class TotpResult {
        public final boolean success;
        public final String error;
        public final String accessToken;
        public final String refreshToken;
        public final Principal principal;

        public TotpResult(boolean success, String error, String accessToken, String refreshToken, Principal principal) {
            this.success = success;
            this.error = error;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.principal = principal;
        }
    }

    public static class RefreshResult {
        public final boolean success;
        public final String error;
        public final String accessToken;
        public final String refreshToken;
        public final Principal principal;

        public RefreshResult(boolean success, String error, String accessToken, String refreshToken, Principal principal) {
            this.success = success;
            this.error = error;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.principal = principal;
        }
    }
}
