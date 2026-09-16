package dev.lukka.oculus;

import org.bukkit.plugin.java.JavaPlugin;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import dev.lukka.oculus.managers.PluginManager;
import dev.lukka.oculus.listeners.PlayerListener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Oculus extends JavaPlugin {
    
    private Javalin app;

    @Override
    public void onEnable() {
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        
        // Initialize log capture buffer
        DashboardController.initializeLogCapture();
        getServer().getScheduler().runTaskTimerAsynchronously(this, DashboardController::collectStats, 600L, 600L);

        
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClassLoader());
        
        try {
            app = Javalin.create(config -> {
                config.staticFiles.add(staticFiles -> {
                    staticFiles.hostedPath = "/";
                    staticFiles.directory = "/www";
                    staticFiles.location = Location.CLASSPATH;
                    staticFiles.headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
                    staticFiles.headers.put("Pragma", "no-cache");
                });
            }).start(8080);
            
            // Register resilient SPA static web handlers
            configureWebRoutes(app);
            
            DashboardController controller = new DashboardController(this);
            app.get("/api/stats", controller::getStats);
            app.get("/api/stats/history", controller::getStatsHistory);
            app.get("/api/players/history", controller::getPlayersHistory);
            app.get("/api/players/{name}", controller::getPlayerDetails);

            app.get("/api/logs", controller::getLogs);
            app.get("/api/players/detailed", controller::getDetailedPlayers);
            app.post("/api/player/action", controller::postPlayerAction);
            app.get("/api/plugins", controller::getPlugins);
            app.post("/api/plugin/action", controller::postPluginAction);
            app.get("/api/properties", controller::getProperties);
            app.post("/api/properties", controller::postProperties);
            app.post("/api/server/action", controller::postServerAction);
            app.post("/api/command", controller::executeCommand);
            app.get("/api/map/overview", controller::getMapOverview);
            app.get("/api/map/meta", controller::getMapMeta);
            
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
        
        DashboardController.appendManualLog("INFO", "Oculus v" + getDescription().getVersion() + " initialized with embedded Javalin server on port 8080.");
        getLogger().info(getDescription().getName() + " has been enabled with Javalin on port 8080!");
    }

    public static void configureWebRoutes(Javalin app) {
        byte[] indexBytes = null;
        byte[] jsBytes = null;
        byte[] cssBytes = null;

        try (InputStream is = Oculus.class.getResourceAsStream("/www/index.html")) {
            if (is != null) {
                indexBytes = is.readAllBytes();
                String html = new String(indexBytes, StandardCharsets.UTF_8);

                Matcher jsMatcher = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(html);
                if (jsMatcher.find()) {
                    String jsRelPath = jsMatcher.group(1);
                    try (InputStream jsStream = Oculus.class.getResourceAsStream("/www" + jsRelPath)) {
                        if (jsStream != null) {
                            jsBytes = jsStream.readAllBytes();
                        }
                    }
                }

                Matcher cssMatcher = Pattern.compile("href=\"(/assets/[^\"]+\\.css)\"").matcher(html);
                if (cssMatcher.find()) {
                    String cssRelPath = cssMatcher.group(1);
                    try (InputStream cssStream = Oculus.class.getResourceAsStream("/www" + cssRelPath)) {
                        if (cssStream != null) {
                            cssBytes = cssStream.readAllBytes();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        final byte[] finalIndex = indexBytes;
        final byte[] finalJs = jsBytes;
        final byte[] finalCss = cssBytes;

        if (finalIndex != null) {
            app.get("/", ctx -> {
                ctx.contentType("text/html; charset=utf-8");
                ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                ctx.header("Pragma", "no-cache");
                ctx.header("Expires", "0");
                ctx.result(finalIndex);
            });
            app.get("/index.html", ctx -> {
                ctx.contentType("text/html; charset=utf-8");
                ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                ctx.header("Pragma", "no-cache");
                ctx.header("Expires", "0");
                ctx.result(finalIndex);
            });
        }

        // Resilient asset router: guarantees JS/CSS MIME types and handles any cached bundle hash
        if (finalJs != null || finalCss != null) {
            app.get("/assets/{file}", ctx -> {
                String file = ctx.pathParam("file");
                if (file.endsWith(".js") && finalJs != null) {
                    ctx.contentType("application/javascript; charset=utf-8");
                    ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                    ctx.result(finalJs);
                } else if (file.endsWith(".css") && finalCss != null) {
                    ctx.contentType("text/css; charset=utf-8");
                    ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                    ctx.result(finalCss);
                }
            });
        }
    }

    @Override
    public void onDisable() {
        DashboardController.shutdownLogCapture();
        if (app != null) {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(this.getClassLoader());
            try {
                app.stop();
            } finally {
                Thread.currentThread().setContextClassLoader(classLoader);
            }
        }
        getLogger().info(getDescription().getName() + " has been disabled!");
    }

    public Javalin getApp() {
        return app;
    }
}
