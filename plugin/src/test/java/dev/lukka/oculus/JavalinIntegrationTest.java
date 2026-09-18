package dev.lukka.oculus;

import dev.lukka.oculus.bootstrap.JavalinServer;
import dev.lukka.oculus.console.ConsoleService;
import dev.lukka.oculus.api.ImmediateExecutor;
import dev.lukka.oculus.integrations.apollo.ApolloService;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class JavalinIntegrationTest {

    private static JavalinServer server;
    private static int port;
    private static HttpClient client;
    private static File dataFolder;

    @BeforeAll
    static void setUp() throws Exception {
        client = HttpClient.newHttpClient();
        
        Oculus mockPlugin = org.mockito.Mockito.mock(Oculus.class);
        org.bukkit.configuration.file.FileConfiguration mockConfig = new org.bukkit.configuration.file.YamlConfiguration();
        mockConfig.set("http.port", 0);
        mockConfig.set("console.history", 300);
        mockConfig.set("auth.totp-issuer", "Test");
        mockConfig.set("http.allowlist", java.util.Collections.emptyList());
        
        org.mockito.Mockito.when(mockPlugin.getConfig()).thenReturn(mockConfig);
        org.mockito.Mockito.when(mockPlugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        
        dataFolder = new File(System.getProperty("java.io.tmpdir"), "oculus-test-" + System.currentTimeMillis());
        dataFolder.mkdirs();
        org.mockito.Mockito.when(mockPlugin.getDataFolder()).thenReturn(dataFolder);
        
        ImmediateExecutor executor = new ImmediateExecutor();
        org.mockito.Mockito.when(mockPlugin.getExecutor()).thenReturn(executor);
        dev.lukka.oculus.backups.BackupService mockBackup = org.mockito.Mockito.mock(dev.lukka.oculus.backups.BackupService.class);
        org.mockito.Mockito.when(mockPlugin.getBackupService()).thenReturn(mockBackup);
        
        ConsoleService consoleService = new ConsoleService(mockPlugin);
        DashboardController controller = new DashboardController(mockPlugin, consoleService, executor);
        ApolloService apolloService = new ApolloService(mockPlugin, null);
        
        java.lang.reflect.Field instanceField = dev.lukka.oculus.managers.DatabaseManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        dev.lukka.oculus.managers.DatabaseManager.initialize(mockPlugin);
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(JavalinIntegrationTest.class.getClassLoader());
        try {
            server = new JavalinServer(mockPlugin, controller, consoleService, apolloService);
            server.start();
            port = server.getApp().port();
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (dataFolder != null && dataFolder.exists()) {
            for (File f : dataFolder.listFiles()) {
                f.delete();
            }
            dataFolder.delete();
        }
    }

    @Test
    void test401WithoutJwt() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/stats"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void testMissingNodeReturns403() throws Exception {
        // We simulate a JWT with only 'dashboard.apollo.read'
        String token = new dev.lukka.oculus.auth.JwtService(new File(dataFolder, "jwt.key"), 900).generateAccessToken("viewer", "viewer", java.util.List.of("dashboard.apollo.read"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/stats"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    @Test
    void testValidNodeReturns200() throws Exception {
        String token = new dev.lukka.oculus.auth.JwtService(new File(dataFolder, "jwt.key"), 900).generateAccessToken("viewer", "viewer", java.util.List.of("dashboard.view"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/stats"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void testPostExpectations() throws Exception {
        String token = new dev.lukka.oculus.auth.JwtService(new File(dataFolder, "jwt.key"), 900).generateAccessToken("admin", "admin", java.util.List.of("dashboard.console.execute"));
        String payload = "{\"action\":\"gc\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/server/action"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Since Bukkit.getServer() is null, it should return 503 bukkit_unavailable
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"error\":\"bukkit_unavailable\""));
    }

    @Test
    void testStaticFiles() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        
        HttpRequest missingAsset = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/_next/static/missing.js"))
                .GET()
                .build();
        HttpResponse<String> missingAssetResponse = client.send(missingAsset, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, missingAssetResponse.statusCode());
    }

    @Test
    void testPackagesInstallIsUnregistered() throws Exception {
        String token = new dev.lukka.oculus.auth.JwtService(new File(dataFolder, "jwt.key"), 900)
                .generateAccessToken("admin", "admin", java.util.List.of("dashboard.packages.install"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/packages/install"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void testSpaRoutingFallback() throws Exception {
        HttpRequest clientRoute = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/dashboard/players"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(clientRoute, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/html"));
    }
}
