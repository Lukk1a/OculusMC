package dev.lukka.oculus.auth;

import dev.lukka.oculus.audit.AuditService;
import dev.lukka.oculus.bootstrap.CidrMatcher;
import dev.lukka.oculus.bootstrap.JavalinServer;
import dev.lukka.oculus.managers.DatabaseManager;
import io.javalin.Javalin;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GateSecurityTest {

    private static Javalin app;
    private static int port;
    private static HttpClient client;
    private static File tempDir;
    private static AuthService authService;
    private static JwtService jwtService;
    private static TotpService totpService;
    private static UserRepository userRepository;
    private static Plugin mockPlugin;

    @BeforeAll
    static void setup() throws Exception {
        tempDir = Files.createTempDirectory("oculus-gate-test").toFile();
        tempDir.deleteOnExit();

        mockPlugin = Mockito.mock(Plugin.class);
        Mockito.when(mockPlugin.getDataFolder()).thenReturn(tempDir);
        org.bukkit.configuration.file.FileConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("http.bind", "127.0.0.1");
        config.set("http.port", 0);
        config.set("http.public-origin", "http://localhost:3000");
        config.set("auth.access-ttl-seconds", 900);
        config.set("auth.refresh-ttl-days", 7);
        config.set("auth.preauth-ttl-seconds", 300);
        config.set("auth.lockout-attempts", 3);
        config.set("auth.lockout-seconds", 60);
        config.set("auth.totp-issuer", "OculusTest");
        Mockito.when(mockPlugin.getConfig()).thenReturn(config);
        Mockito.when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("GateSecurityTest"));

        java.lang.reflect.Field instanceField = DatabaseManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        DatabaseManager.initialize(mockPlugin);

        // Reset database tables
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM refresh_tokens");
            stmt.execute("DELETE FROM users");
            stmt.execute("DELETE FROM audit_head");
        }

        File jwtKeyFile = new File(tempDir, "jwt.key");
        jwtService = new JwtService(jwtKeyFile, 900);
        userRepository = new UserRepository();
        totpService = new TotpService();
        authService = new AuthService(userRepository, jwtService, totpService, 7, 300, 3, 60, "OculusTest");
        AuditService auditService = new AuditService(mockPlugin);

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(GateSecurityTest.class.getClassLoader());
        try {
            app = Javalin.create().start("127.0.0.1", 0);
            port = app.port();

            // Filters
            app.before("/api/*", ctx -> {
                String origin = mockPlugin.getConfig().getString("http.public-origin");
                if (origin != null && !origin.isBlank()) {
                    String method = ctx.method().name();
                    String path = ctx.path();
                    boolean isMutating = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
                    if (isMutating || path.startsWith("/api/auth/")) {
                        String reqOrigin = ctx.header("Origin");
                        if (reqOrigin != null && !reqOrigin.equalsIgnoreCase(origin.trim())) {
                            ctx.status(403).json(Map.of("error", "origin_mismatch"));
                            ctx.skipRemainingHandlers();
                        }
                    }
                }
            });

            app.before("/api/*", ctx -> {
                String path = ctx.path();
                if (path.startsWith("/api/auth/") || path.equals("/api/health")) {
                    return;
                }
                String authHeader = ctx.header("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    ctx.status(401).json(Map.of("error", "unauthorized"));
                    throw new io.javalin.http.HttpResponseException(401, "unauthorized");
                }
                String token = authHeader.substring(7);
                Principal principal = jwtService.parsePrincipal(token);
                if (principal == null) {
                    ctx.status(401).json(Map.of("error", "invalid_token"));
                    throw new io.javalin.http.HttpResponseException(401, "invalid_token");
                }
                ctx.attribute("principal", principal);
            });

            // RBAC helper
            java.util.function.BiConsumer<io.javalin.http.Context, String> requireNode = (ctx, node) -> {
                Principal p = ctx.attribute("principal");
                if (p == null || !p.hasNode(node)) {
                    ctx.status(403).json(Map.of("error", "forbidden", "node", node));
                    throw new io.javalin.http.HttpResponseException(403, "forbidden");
                }
            };

            // Routes
            new AuthController(authService, auditService, mockPlugin).register(app);
            app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
            app.get("/api/stats", ctx -> {
                requireNode.accept(ctx, "dashboard.view");
                ctx.json(Map.of("tps", 20.0));
            });
            app.post("/api/command", ctx -> {
                requireNode.accept(ctx, "dashboard.console.execute");
                ctx.json(Map.of("status", "executed"));
            });
            app.post("/api/packages/install", ctx -> {
                requireNode.accept(ctx, "dashboard.packages.install");
                new dev.lukka.oculus.packages.PackagesController().installPackage(ctx);
            });

        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }

        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    @Order(1)
    void testUnauthenticatedRequestsReturn401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/stats"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());

        HttpRequest reqCmd = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/command"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"tps\"}"))
                .build();
        HttpResponse<String> respCmd = client.send(reqCmd, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, respCmd.statusCode());
    }

    @Test
    @Order(2)
    void testSetupInitialAdminAndVerifyTotpFlow() throws Exception {
        // Check setup status
        HttpRequest statusReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/setup-status"))
                .GET()
                .build();
        HttpResponse<String> statusResp = client.send(statusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statusResp.statusCode());
        assertTrue(statusResp.body().contains("\"setupRequired\":true"));

        // Setup admin
        String setupPayload = "{\"username\":\"admin\",\"password\":\"password123\"}";
        HttpRequest setupReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/setup"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.ofString(setupPayload))
                .build();
        HttpResponse<String> setupResp = client.send(setupReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, setupResp.statusCode());
        assertTrue(setupResp.body().contains("\"totpRequired\":true"));
        assertTrue(setupResp.body().contains("\"qrPngBase64\""));
        assertFalse(setupResp.body().contains("refreshToken")); // No refreshToken in body!

        // Check Oculus-PreAuth cookie
        Optional<String> setCookie = setupResp.headers().firstValue("Set-Cookie");
        assertTrue(setCookie.isPresent());
        assertTrue(setCookie.get().contains("Oculus-PreAuth="));
        assertTrue(setCookie.get().contains("HttpOnly"));

        String preAuthCookie = setCookie.get().split(";")[0];

        // Second setup attempt must 409 setup_already_done
        HttpResponse<String> setupResp2 = client.send(setupReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, setupResp2.statusCode());
        assertTrue(setupResp2.body().contains("setup_already_done"));

        // Verify TOTP with generated secret
        UserRepository.UserRecord record = userRepository.findByUsername("admin");
        assertNotNull(record);
        assertNotNull(record.totpSecretEnc);

        dev.samstevens.totp.code.DefaultCodeGenerator generator = new dev.samstevens.totp.code.DefaultCodeGenerator();
        String currentCode = generator.generate(record.totpSecretEnc, Math.floorDiv(System.currentTimeMillis(), 1000L * 30));

        HttpRequest totpReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/totp"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("Cookie", preAuthCookie)
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + currentCode + "\"}"))
                .build();
        HttpResponse<String> totpResp = client.send(totpReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, totpResp.statusCode());
        assertTrue(totpResp.body().contains("\"accessToken\""));
        assertFalse(totpResp.body().contains("refreshToken")); // refresh token is in cookie, not in body!

        // Verify Oculus-Refresh cookie set and Oculus-PreAuth cleared
        List<String> cookies = totpResp.headers().allValues("Set-Cookie");
        assertTrue(cookies.stream().anyMatch(c -> c.contains("Oculus-Refresh=") && c.contains("HttpOnly")));
    }

    @Test
    @Order(3)
    void testOriginMismatchRejection() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://evil-attacker.com")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"password123\"}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode(), "Expected 403 but got " + resp.statusCode() + " with body: " + resp.body());
        assertTrue(resp.body().contains("origin_mismatch"), "Body did not contain origin_mismatch: " + resp.body());
    }

    @Test
    @Order(4)
    void testRbacViewerCannotExecuteCommandOrInstallPackage() throws Exception {
        // Issue Viewer token
        String viewerToken = jwtService.generateAccessToken("testviewer", "Viewer", Principal.getRoleNodes("Viewer"));

        // Viewer can view stats (dashboard.view)
        HttpRequest statsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/stats"))
                .header("Authorization", "Bearer " + viewerToken)
                .GET()
                .build();
        HttpResponse<String> statsResp = client.send(statsReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, statsResp.statusCode());

        // Viewer cannot execute command (requires dashboard.console.execute) -> 403
        HttpRequest cmdReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/command"))
                .header("Authorization", "Bearer " + viewerToken)
                .header("Origin", "http://localhost:3000")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"tps\"}"))
                .build();
        HttpResponse<String> cmdResp = client.send(cmdReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, cmdResp.statusCode());

        // Packages install is disabled -> 403
        HttpRequest pkgReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/packages/install?url=http://evil.com/malicious.jar&filename=malicious.jar"))
                .header("Authorization", "Bearer " + viewerToken)
                .header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> pkgResp = client.send(pkgReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, pkgResp.statusCode());
    }

    @Test
    @Order(5)
    void testRefreshReuseKillsTokenFamily() throws Exception {
        // Authenticate admin to get a refresh token
        UserRepository.UserRecord record = userRepository.findByUsername("admin");
        dev.samstevens.totp.code.DefaultCodeGenerator generator = new dev.samstevens.totp.code.DefaultCodeGenerator();
        String currentCode = generator.generate(record.totpSecretEnc, Math.floorDiv(System.currentTimeMillis(), 1000L * 30));

        AuthService.LoginResult loginResult = authService.login("admin", "password123");
        assertTrue(loginResult.totpRequired);

        AuthService.TotpResult totpResult = authService.verifyTotp(loginResult.preAuthToken, currentCode);
        assertTrue(totpResult.success);
        String initialRefreshToken = totpResult.refreshToken;

        // Test refresh over HTTP using Oculus-Refresh cookie
        HttpRequest httpRefreshReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/refresh"))
                .header("Origin", "http://localhost:3000")
                .header("Cookie", "Oculus-Refresh=" + initialRefreshToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> httpRefreshResp = client.send(httpRefreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, httpRefreshResp.statusCode());
        assertTrue(httpRefreshResp.body().contains("\"accessToken\""));
        assertFalse(httpRefreshResp.body().contains("refreshToken"));
        Optional<String> rotatedCookieHeader = httpRefreshResp.headers().firstValue("Set-Cookie");
        assertTrue(rotatedCookieHeader.isPresent());
        assertTrue(rotatedCookieHeader.get().contains("Oculus-Refresh="));
        String rotatedRefreshToken = rotatedCookieHeader.get().split(";")[0].substring("Oculus-Refresh=".length());

        // Second refresh with the OLD token (reuse attempt) over HTTP: Must 401 revoked
        HttpResponse<String> reuseHttpResp = client.send(httpRefreshReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, reuseHttpResp.statusCode());
        assertTrue(reuseHttpResp.body().contains("revoked"));

        // Now the rotated token must ALSO fail because the family was revoked
        HttpRequest victimHttpReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/refresh"))
                .header("Origin", "http://localhost:3000")
                .header("Cookie", "Oculus-Refresh=" + rotatedRefreshToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> victimHttpResp = client.send(victimHttpReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, victimHttpResp.statusCode());
        assertTrue(victimHttpResp.body().contains("revoked"));
    }

    @Test
    @Order(6)
    void testLockoutAfterFailedAttempts() {
        assertFalse(authService.isLockedOut("admin"));
        authService.login("admin", "wrongpassword");
        authService.login("admin", "wrongpassword");
        authService.login("admin", "wrongpassword"); // 3rd failure triggers lockout (configured to 3 attempts)

        assertTrue(authService.isLockedOut("admin"));
        AuthService.LoginResult lockedOutLogin = authService.login("admin", "password123");
        assertFalse(lockedOutLogin.success);
        assertEquals("locked_out", lockedOutLogin.error);
    }

    @Test
    @Order(7)
    void testCidrMatcherValidatesSubnets() {
        CidrMatcher ipv4 = new CidrMatcher("192.168.1.0/24");
        assertTrue(ipv4.matches("192.168.1.50"));
        assertTrue(ipv4.matches("192.168.1.254"));
        assertFalse(ipv4.matches("192.168.2.1"));

        CidrMatcher singleIp = new CidrMatcher("10.0.0.1");
        assertTrue(singleIp.matches("10.0.0.1"));
        assertFalse(singleIp.matches("10.0.0.2"));
    }
}
