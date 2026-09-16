package dev.lukka.oculus;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class JavalinIntegrationTest {

    private static Javalin app;
    private static int port;
    private static HttpClient client;

    @BeforeAll
    static void setUp() {
        client = HttpClient.newHttpClient();
        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(JavalinIntegrationTest.class.getClassLoader());
        try {
            app = Javalin.create(config -> {
                config.staticFiles.add("/www", Location.CLASSPATH);
            }).start(0);
            
            port = app.port();
            Oculus.configureWebRoutes(app);
            DashboardController controller = new DashboardController(null);
            app.get("/api/stats", controller::getStats);
            app.get("/api/logs", controller::getLogs);
            app.get("/api/players/detailed", controller::getDetailedPlayers);
            app.post("/api/player/action", controller::postPlayerAction);
            app.get("/api/plugins", controller::getPlugins);
            app.post("/api/plugin/action", controller::postPluginAction);
            app.get("/api/properties", controller::getProperties);
            app.post("/api/properties", controller::postProperties);
            app.post("/api/server/action", controller::postServerAction);
            app.post("/api/waypoint", controller::postWaypoint);
            app.get("/api/waypoints", controller::getWaypoints);
            app.delete("/api/waypoint", controller::deleteWaypoint);
            app.post("/api/command", controller::executeCommand);
            app.get("/api/map/overview", controller::getMapOverview);
            app.get("/api/map/meta", controller::getMapMeta);
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
    }

    @AfterAll
    static void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void testStaticFilesRootServesIndexHtml() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Oculus") || response.body().contains("<div id=\"root\"></div>"));
    }

    @Test
    void testAssetFallbackHandlesAnyJsHash() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/assets/index-any-hash.js"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("javascript"));
    }

    @Test
    void testGetStatsEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/stats"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"players\""));
        assertTrue(response.body().contains("\"tps\""));
        assertTrue(response.body().contains("\"memoryUsed\""));
        assertTrue(response.body().contains("\"playerList\""));
        assertTrue(response.body().contains("\"waypoints\""));
        assertTrue(response.body().contains("\"cpuUsage\""));
        assertTrue(response.body().contains("\"diskUsagePercent\""));
    }

    @Test
    void testGetLogsEndpoint() throws Exception {
        DashboardController.appendManualLog("INFO", "Test log message for unit test");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/logs"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Test log message for unit test"));
    }

    @Test
    void testGetDetailedPlayersEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/players/detailed"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void testPostPlayerActionEndpoint() throws Exception {
        String payload = "{\"player\":\"TestPlayer\",\"action\":\"heal\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/player/action"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"success\""));
    }

    @Test
    void testGetPluginsEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/plugins"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void testPostServerActionGc() throws Exception {
        String payload = "{\"action\":\"gc\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/server/action"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"success\""));
    }

    @Test
    void testPostWaypointAndGetWaypoints() throws Exception {
        String waypointPayload = "{\"name\":\"Spawn\",\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"color\":\"#e4f222\"}";
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/waypoint"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(waypointPayload))
                .build();

        HttpResponse<String> postRes = client.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, postRes.statusCode());

        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/waypoints"))
                .GET()
                .build();

        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getRes.statusCode());
        assertTrue(getRes.body().contains("\"name\":\"Spawn\""));

        // Test delete
        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/waypoint?name=Spawn"))
                .DELETE()
                .build();

        HttpResponse<String> deleteRes = client.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleteRes.statusCode());
    }

    @Test
    void testExecuteCommandEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/command"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"tps\"}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"executed\""));
        assertTrue(response.body().contains("\"command\":\"tps\""));
    }

    @Test
    void testMapOverviewEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/map/overview?world=world"))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("image/png"));
        assertTrue(response.body().length > 0);
    }

    @Test
    void testMapMetaEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/map/meta?world=world"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"world\":\"world\""));
        assertTrue(response.body().contains("\"bounds\""));
    }
}
