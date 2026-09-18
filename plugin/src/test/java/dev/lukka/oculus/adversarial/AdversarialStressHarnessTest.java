package dev.lukka.oculus.adversarial;

import dev.lukka.oculus.DashboardController;
import dev.lukka.oculus.api.ThreadExecutor;
import dev.lukka.oculus.auth.*;
import dev.lukka.oculus.bootstrap.ClientIpResolver;
import dev.lukka.oculus.console.ConsoleService;
import dev.lukka.oculus.files.FileJail;
import dev.lukka.oculus.files.FilesController;
import dev.lukka.oculus.integrations.apollo.ApolloGateway;
import dev.lukka.oculus.integrations.apollo.ApolloService;
import dev.lukka.oculus.managers.DatabaseManager;
import dev.lukka.oculus.players.PlayersController;
import io.javalin.http.Context;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AdversarialStressHarnessTest {

    private static File tempDir;
    private static AuthService authService;
    private static UserRepository userRepository;

    @BeforeAll
    static void setupSuite() throws Exception {
        tempDir = Files.createTempDirectory("oculus-adversarial-test").toFile();
        tempDir.deleteOnExit();

        Plugin mockPlugin = Mockito.mock(Plugin.class);
        Mockito.when(mockPlugin.getDataFolder()).thenReturn(tempDir);

        Field instanceField = DatabaseManager.class.getDeclaredField("instance");
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
        authService = new AuthService(userRepository, jwtService, totpService, 7, 300, 5, 60, "OculusAdversarial");
    }

    // =========================================================================
    // 1. SQLite Concurrency & Family Reuse Stress Test
    // =========================================================================

    @Test
    void testSQLiteConcurrencyUnderHeavyMultiThreadContention() throws Exception {
        int threadCount = 16;
        int rotationsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String username = "stress_user_" + i;
            userRepository.createUser(username, "Password123!", "Admin", null, null, null);
            AuthService.LoginResult login = authService.login(username, "Password123!");
            assertTrue(login.success, "Login must succeed for " + username);
            final String initialToken = login.refreshToken;

            tasks.add(() -> {
                startLatch.await();
                String curToken = initialToken;
                for (int r = 0; r < rotationsPerThread; r++) {
                    AuthService.RefreshResult res = authService.refreshToken(curToken);
                    if (!res.success) {
                        return false;
                    }
                    curToken = res.refreshToken;
                }
                return true;
            });
        }

        startLatch.countDown();
        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        for (int i = 0; i < futures.size(); i++) {
            assertTrue(futures.get(i).get(), "Thread " + i + " must complete all rotations without SQLITE_BUSY");
        }
    }

    @Test
    void testTokenFamilyReuseDetectionUnderConcurrentRace() throws Exception {
        String username = "family_race_user";
        userRepository.createUser(username, "Password123!", "Admin", null, null, null);
        AuthService.LoginResult login = authService.login(username, "Password123!");
        assertTrue(login.success);

        String rootToken = login.refreshToken;
        // Rotate once: rootToken is now revoked, nextToken is active
        AuthService.RefreshResult firstRotation = authService.refreshToken(rootToken);
        assertTrue(firstRotation.success);
        String activeToken = firstRotation.refreshToken;
        assertNotEquals(rootToken, activeToken);

        // Concurrently launch 10 threads presenting the revoked rootToken
        int raceThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(raceThreads);
        CountDownLatch latch = new CountDownLatch(1);
        List<Callable<String>> raceTasks = new ArrayList<>();

        for (int i = 0; i < raceThreads; i++) {
            raceTasks.add(() -> {
                latch.await();
                AuthService.RefreshResult res = authService.refreshToken(rootToken);
                return res.success ? "SUCCESS" : res.error;
            });
        }

        latch.countDown();
        List<Future<String>> raceFutures = executor.invokeAll(raceTasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        for (Future<String> future : raceFutures) {
            String outcome = future.get();
            assertNotEquals("SUCCESS", outcome, "Revoked token refresh MUST never succeed");
            assertEquals("token_family_revoked", outcome, "Must report token_family_revoked");
        }

        // Entire family MUST be invalidated: activeToken must now also fail
        AuthService.RefreshResult activeCheck = authService.refreshToken(activeToken);
        assertFalse(activeCheck.success);
        assertEquals("token_family_revoked", activeCheck.error);
    }

    // =========================================================================
    // 2. IP Resolution & Anti-Spoofing
    // =========================================================================

    @Test
    void testClientIpResolverRejectsSpoofedHeadersFromUntrustedPeers() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1", "172.16.0.0/16"));

        // Untrusted public peer IP
        Context untrustedCtx = mock(Context.class);
        when(untrustedCtx.ip()).thenReturn("198.51.100.42");
        when(untrustedCtx.header("X-Forwarded-For")).thenReturn("127.0.0.1, 10.0.0.5");
        when(untrustedCtx.header("X-Real-IP")).thenReturn("127.0.0.1");

        String resolved = resolver.resolve(untrustedCtx);
        assertEquals("198.51.100.42", resolved, "Must ignore spoofed headers from untrusted peer");

        // Untrusted peer attempting loopback spoofing
        Context loopbackSpoofCtx = mock(Context.class);
        when(loopbackSpoofCtx.ip()).thenReturn("203.0.113.10");
        when(loopbackSpoofCtx.header("X-Forwarded-For")).thenReturn("192.168.1.1");
        assertEquals("203.0.113.10", resolver.resolve(loopbackSpoofCtx));

        // Empty trusted proxy list rejects all forwarded headers
        ClientIpResolver emptyResolver = new ClientIpResolver(Collections.emptyList());
        Context anyCtx = mock(Context.class);
        when(anyCtx.ip()).thenReturn("10.0.0.1");
        when(anyCtx.header("X-Forwarded-For")).thenReturn("8.8.8.8");
        assertEquals("10.0.0.1", emptyResolver.resolve(anyCtx));
    }

    @Test
    void testClientIpResolverParsesHeadersOnlyFromTrustedProxies() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.1", "172.16.0.0/16"));

        // Direct trusted single IP
        Context trustedDirectCtx = mock(Context.class);
        when(trustedDirectCtx.ip()).thenReturn("10.0.0.1");
        when(trustedDirectCtx.header("X-Forwarded-For")).thenReturn("203.0.113.195, 10.0.0.1");
        assertEquals("203.0.113.195", resolver.resolve(trustedDirectCtx));

        // Trusted CIDR range match
        Context trustedCidrCtx = mock(Context.class);
        when(trustedCidrCtx.ip()).thenReturn("172.16.50.12");
        when(trustedCidrCtx.header("X-Forwarded-For")).thenReturn("198.51.100.88");
        assertEquals("198.51.100.88", resolver.resolve(trustedCidrCtx));

        // Trusted CIDR fallback to X-Real-IP when X-Forwarded-For is absent
        Context realIpCtx = mock(Context.class);
        when(realIpCtx.ip()).thenReturn("172.16.1.1");
        when(realIpCtx.header("X-Forwarded-For")).thenReturn(null);
        when(realIpCtx.header("X-Real-IP")).thenReturn("198.51.100.99");
        assertEquals("198.51.100.99", resolver.resolve(realIpCtx));
    }

    // =========================================================================
    // 3. Path Jail Traversal, Symlink Escapes & Sensitive Database/Audit Artifacts
    // =========================================================================

    @Test
    void testPathJailRejectsDirectoryTraversalSequences() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(tempDir);

            String[] traversalAttacks = {
                    "../escape.txt",
                    "..\\escape.txt",
                    "../../etc/passwd",
                    "/../etc/shadow",
                    "\\..\\windows\\system32\\cmd.exe",
                    "..\\..\\windows\\system32\\cmd.exe",
                    "logs/../../outside.txt",
                    "world/../../../sensitive.dat",
                    "plugins/../../oculus.db",
                    "C:/Windows/System32",
                    "D:/outside_jail_file.txt"
            };

            for (String attack : traversalAttacks) {
                assertThrows(IllegalArgumentException.class, () -> FileJail.resolve(attack),
                        "Path traversal attack must be rejected: " + attack);
            }

            // Verify leading slash paths are strictly jailed inside world container
            File jailedLeadingSlash = FileJail.resolve("/server.properties");
            assertTrue(jailedLeadingSlash.toPath().toAbsolutePath().normalize()
                    .startsWith(tempDir.toPath().toAbsolutePath().normalize()),
                    "Leading slash path must remain jailed inside world container");

        }
    }

    @Test
    void testPathJailRejectsSensitiveDatabaseAuditAndLockFiles() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(tempDir);

            String[] sensitiveTargets = {
                    "oculus.db",
                    "oculus.db-wal",
                    "oculus.db-shm",
                    "OCULUS.DB",
                    "oculus.DB-WAL",
                    "plugins/oculus/oculus.db",
                    "plugins/oculus/oculus.db-wal",
                    "plugins/oculus/oculus.db-shm",
                    "plugins/Oculus/oculus.db",
                    "plugins/Oculus/oculus.db-wal",
                    "plugins/Oculus/jwt.key",
                    "plugins/oculus/jwt.key",
                    "audit.jsonl",
                    "AUDIT.JSONL",
                    "plugins/oculus/audit.jsonl",
                    "plugins/Oculus/audit.jsonl",
                    "sub/audit.jsonl",
                    "session.lock",
                    "world/session.lock",
                    "world_nether/session.lock",
                    "world_the_end/DIM1/session.lock",
                    "SESSION.LOCK"
            };

            for (String target : sensitiveTargets) {
                assertThrows(IllegalArgumentException.class, () -> FileJail.resolve(target),
                        "Sensitive artifact must be blocked: " + target);
            }
        }
    }

    @Test
    void testPathJailSymlinkEscapeAttempt() throws Exception {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(tempDir);

            File outsideDir = Files.createTempDirectory("oculus-jail-outside").toFile();
            File secretFile = new File(outsideDir, "classified.txt");
            Files.writeString(secretFile.toPath(), "secret-content");

            File symlink = new File(tempDir, "link_to_outside");
            try {
                Files.deleteIfExists(symlink.toPath());
                Files.createSymbolicLink(symlink.toPath(), outsideDir.toPath());
                assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("link_to_outside/classified.txt"));
            } catch (java.nio.file.FileSystemException e) {
                // Windows privilege restrictions for symlink creation handled gracefully
            } finally {
                Files.deleteIfExists(symlink.toPath());
                Files.deleteIfExists(secretFile.toPath());
                outsideDir.delete();
            }
        }
    }

    // =========================================================================
    // 4. DoS Mitigations (Oversized Uploads & Zip Bombs)
    // =========================================================================

    @Test
    void testFilesControllerOversizedUploadByHeaderAndStream() throws Exception {
        Plugin plugin = mock(Plugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(eq("files.max-upload-bytes"), anyLong())).thenReturn(1024L * 1024L); // 1MB limit

        FilesController controller = new FilesController(plugin);
        Server server = mock(Server.class);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(tempDir);

            // A) Rejection via Content-Length header
            Context headerCtx = mock(Context.class);
            when(headerCtx.status(anyInt())).thenReturn(headerCtx);
            when(headerCtx.queryParam("path")).thenReturn("oversized_header.dat");
            when(headerCtx.header("Content-Length")).thenReturn("5242880"); // 5MB > 1MB

            controller.writeFile(headerCtx);
            verify(headerCtx).status(413);
            verify(headerCtx).json(Map.of("error", "payload_too_large"));

            // B) Rejection via streamed data exceeding limit
            Context streamCtx = mock(Context.class);
            when(streamCtx.status(anyInt())).thenReturn(streamCtx);
            when(streamCtx.queryParam("path")).thenReturn("oversized_stream.dat");
            when(streamCtx.header("Content-Length")).thenReturn(null); // No header

            byte[] bigData = new byte[1024 * 1024 + 1024]; // 1MB + 1KB
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getInputStream()).thenReturn(new TestServletInputStream(new ByteArrayInputStream(bigData)));
            when(streamCtx.req()).thenReturn(req);

            controller.writeFile(streamCtx);
            verify(streamCtx).status(413);
            verify(streamCtx).json(Map.of("error", "payload_too_large"));
            assertFalse(new File(tempDir, "oversized_stream.dat").exists(), "Partial file must be deleted");
        }
    }

    @Test
    void testFilesControllerZipBombAndRootDeletionGuards() throws Exception {
        Plugin plugin = mock(Plugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(eq("files.max-upload-bytes"), anyLong())).thenReturn(512L * 1024L); // 512KB limit

        FilesController controller = new FilesController(plugin);
        Server server = mock(Server.class);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(tempDir);

            // A) Zip Bomb exceeding uncompressed size limit
            File bombZip = new File(tempDir, "bomb_expansion.zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(bombZip))) {
                ZipEntry entry = new ZipEntry("huge.dat");
                zos.putNextEntry(entry);
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 200; i++) { // ~1.6MB > 1MB uncompressed limit
                    zos.write(chunk);
                }
                zos.closeEntry();
            }

            Context zipCtx = mock(Context.class);
            when(zipCtx.status(anyInt())).thenReturn(zipCtx);
            when(zipCtx.queryParam("path")).thenReturn("bomb_expansion.zip");

            controller.unzipFile(zipCtx);
            verify(zipCtx).status(400);
            verify(zipCtx).json(Map.of("error", "zip_bomb_detected"));
            bombZip.delete();

            // B) Root deletion prevention
            String[] rootPaths = {"", "/", "\\"};
            for (String rPath : rootPaths) {
                Context delCtx = mock(Context.class);
                when(delCtx.status(anyInt())).thenReturn(delCtx);
                when(delCtx.queryParam("path")).thenReturn(rPath);

                controller.deleteFile(delCtx);
                verify(delCtx).status(400);
                verify(delCtx).json(Map.of("error", "cannot_delete_root"));
            }
        }
    }

    // =========================================================================
    // 5. Bukkit Thread Safety, Honest Non-Mocking (503), & Timeouts (504)
    // =========================================================================

    @Test
    void testHonestNonMockingReturns503WhenBukkitServerIsNull() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            // FilesController mutating endpoints
            FilesController filesCtrl = new FilesController(mock(Plugin.class));
            Context ctx = mock(Context.class);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("any.txt");

            filesCtrl.writeFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("any.txt");
            filesCtrl.deleteFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("any.zip");
            filesCtrl.unzipFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // PlayersController mutating endpoints
            PlayersController playersCtrl = new PlayersController(mock(Plugin.class), mock(ThreadExecutor.class));
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            playersCtrl.editSlot(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            playersCtrl.writePdc(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            playersCtrl.deletePdc(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // ApolloService mutating endpoints
            ApolloGateway gateway = mock(ApolloGateway.class);
            when(gateway.hasWaypoint()).thenReturn(true);
            when(gateway.hasTitle()).thenReturn(true);
            when(gateway.hasXRay()).thenReturn(true);
            ApolloService apolloService = new ApolloService(mock(Plugin.class), gateway);

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            apolloService.createWaypoint(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("name")).thenReturn("wp1");
            apolloService.deleteWaypoint(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            apolloService.sendTitle(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            apolloService.setXRay(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // DashboardController mutating endpoints
            DashboardController dashCtrl = new DashboardController(mock(Plugin.class), mock(ConsoleService.class), mock(ThreadExecutor.class));
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            dashCtrl.postPlayerAction(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            dashCtrl.postServerAction(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            dashCtrl.executeCommand(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));
        }
    }

    @Test
    void testMainThreadTimeoutReturns504MainThreadTimeout() throws Exception {
        Server server = mock(Server.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("testDashCtrl"));
        ThreadExecutor executor = mock(ThreadExecutor.class);
        DashboardController dashCtrl = new DashboardController(plugin, mock(ConsoleService.class), executor);
        Context ctx = mock(Context.class);
        when(ctx.status(anyInt())).thenReturn(ctx);

        CompletableFuture supplyFuture = mock(CompletableFuture.class);
        when(supplyFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("timed out"));
        when(executor.supply(any())).thenReturn(supplyFuture);

        CompletableFuture runFuture = mock(CompletableFuture.class);
        when(runFuture.get(anyLong(), any(TimeUnit.class))).thenThrow(new ExecutionException(new TimeoutException("timed out")));
        when(executor.run(any())).thenReturn(runFuture);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            // getStats timeout
            dashCtrl.getStats(ctx);
            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));

            // getDetailedPlayers timeout
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            dashCtrl.getDetailedPlayers(ctx);
            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));

            // postServerAction timeout
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            DashboardController.ServerActionRequest req = new DashboardController.ServerActionRequest();
            req.action = "reload";
            when(ctx.bodyAsClass(DashboardController.ServerActionRequest.class)).thenReturn(req);
            dashCtrl.postServerAction(ctx);
            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));

            // executeCommand timeout
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            DashboardController.CommandRequest cmdReq = new DashboardController.CommandRequest("stop");
            when(ctx.bodyAsClass(DashboardController.CommandRequest.class)).thenReturn(cmdReq);
            dashCtrl.executeCommand(ctx);
            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));
        }
    }

    private static class TestServletInputStream extends ServletInputStream {
        private final InputStream in;

        TestServletInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            try {
                return in.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {}
    }
}
