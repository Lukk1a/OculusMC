package dev.lukka.oculus.auth;

import dev.lukka.oculus.managers.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class AuthServiceConcurrencyTest {

    private static AuthService authService;
    private static UserRepository userRepository;

    @BeforeAll
    static void setup() throws Exception {
        File tempDir = Files.createTempDirectory("oculus-auth-concurrency").toFile();
        tempDir.deleteOnExit();

        Plugin mockPlugin = Mockito.mock(Plugin.class);
        Mockito.when(mockPlugin.getDataFolder()).thenReturn(tempDir);

        java.lang.reflect.Field instanceField = DatabaseManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        DatabaseManager.initialize(mockPlugin);

        try (Connection conn = DatabaseManager.getInstance().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM refresh_tokens");
            stmt.execute("DELETE FROM users");
        }

        userRepository = new UserRepository();
        File jwtKeyFile = new File(tempDir, "jwt.key");
        JwtService jwtService = new JwtService(jwtKeyFile, 900);
        TotpService totpService = new TotpService();
        authService = new AuthService(userRepository, jwtService, totpService, 7, 300, 5, 60, "OculusTest");

        // Create initial test user
        userRepository.createUser("concurrencyUser", "SecurePass123!", "Admin", null, null, null);
    }

    @Test
    public void testSequentialTokenRotationsDoNotLock() {
        // Issue first token manually or via login
        AuthService.LoginResult login = authService.login("concurrencyUser", "SecurePass123!");
        assertTrue(login.success);
        String currentRefreshToken = login.refreshToken;
        assertNotNull(currentRefreshToken);

        // Rotate token 10 times consecutively
        for (int i = 0; i < 10; i++) {
            AuthService.RefreshResult result = authService.refreshToken(currentRefreshToken);
            assertTrue(result.success, "Refresh failed on iteration " + i + ": " + result.error);
            assertNotNull(result.refreshToken);
            assertNotEquals(currentRefreshToken, result.refreshToken);
            currentRefreshToken = result.refreshToken;
        }

        // Test family reuse detection revokes entire family cleanly
        String reusedToken = login.refreshToken; // first token from login
        AuthService.RefreshResult reuseResult = authService.refreshToken(reusedToken);
        assertFalse(reuseResult.success);
        assertEquals("token_family_revoked", reuseResult.error);

        // Current active token should now also fail because family was revoked
        AuthService.RefreshResult nowRevoked = authService.refreshToken(currentRefreshToken);
        assertFalse(nowRevoked.success);
        assertEquals("token_family_revoked", nowRevoked.error);
    }

    @Test
    public void testConcurrentRefreshesAcrossMultipleThreads() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String username = "user_conc_" + i;
            userRepository.createUser(username, "Password123!", "Viewer", null, null, null);
            AuthService.LoginResult login = authService.login(username, "Password123!");
            final String token = login.refreshToken;

            tasks.add(() -> {
                AuthService.RefreshResult res = authService.refreshToken(token);
                return res.success;
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        for (Future<Boolean> future : futures) {
            assertTrue(future.get(), "Concurrent refresh must succeed without SQLITE_BUSY");
        }
    }
}
