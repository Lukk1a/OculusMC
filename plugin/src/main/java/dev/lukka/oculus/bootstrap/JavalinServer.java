package dev.lukka.oculus.bootstrap;

import dev.lukka.oculus.DashboardController;
import dev.lukka.oculus.WorldMapRenderer;
import dev.lukka.oculus.console.ConsoleService;
import dev.lukka.oculus.console.ConsoleWebSocket;
import dev.lukka.oculus.integrations.apollo.ApolloService;
import dev.lukka.oculus.audit.AuditService;
import dev.lukka.oculus.auth.AuthService;
import dev.lukka.oculus.auth.AuthController;
import dev.lukka.oculus.auth.JwtService;
import dev.lukka.oculus.auth.Principal;
import dev.lukka.oculus.auth.TotpService;
import dev.lukka.oculus.auth.UserRepository;
import dev.lukka.oculus.telemetry.TelemetryService;
import dev.lukka.oculus.telemetry.TelemetryWebSocket;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import org.bukkit.plugin.Plugin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class JavalinServer {

    private final Plugin plugin;
    private final DashboardController controller;
    private final ConsoleService consoleService;
    private final ApolloService apolloService;
    private Javalin app;

    private final AuthService authService;
    private final JwtService jwtService;
    private final TotpService totpService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    private final TelemetryService telemetryService;
    private final TelemetryWebSocket telemetryWebSocket;

    private final List<CidrMatcher> allowedCidrMatchers = new ArrayList<>();

    public JavalinServer(Plugin plugin,
                         DashboardController controller,
                         ConsoleService consoleService,
                         ApolloService apolloService) {
        this.plugin = plugin;
        this.controller = controller;
        this.consoleService = consoleService;
        this.apolloService = apolloService;

        // JWT key file: plugins/Oculus/jwt.key
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File jwtKeyFile = new File(dataFolder, "jwt.key");
        long accessTtl = plugin.getConfig().getLong("auth.access-ttl-seconds", 900L);
        this.jwtService = new JwtService(jwtKeyFile, accessTtl);

        this.userRepository = new UserRepository();
        this.totpService = new TotpService();

        long refreshTtlDays = plugin.getConfig().getLong("auth.refresh-ttl-days", 7L);
        long preauthTtlSecs = plugin.getConfig().getLong("auth.preauth-ttl-seconds", 300L);
        int lockoutAttempts = plugin.getConfig().getInt("auth.lockout-attempts", 8);
        long lockoutSeconds = plugin.getConfig().getLong("auth.lockout-seconds", 300L);
        String totpIssuer = plugin.getConfig().getString("auth.totp-issuer", "Oculus");

        this.authService = new AuthService(
                userRepository,
                jwtService,
                totpService,
                refreshTtlDays,
                preauthTtlSecs,
                lockoutAttempts,
                lockoutSeconds,
                totpIssuer
        );

        this.auditService = new AuditService(plugin);

        // Parse CIDR allowlist
        List<String> allowlist = plugin.getConfig().getStringList("http.allowlist");
        if (allowlist.isEmpty()) {
            // Check legacy key if any
            allowlist = plugin.getConfig().getStringList("security.allowed-cidrs");
        }
        for (String cidr : allowlist) {
            try {
                if ("0.0.0.0/0".equals(cidr.trim()) || "*".equals(cidr.trim())) {
                    allowedCidrMatchers.clear(); // Open to all
                    break;
                }
                allowedCidrMatchers.add(new CidrMatcher(cidr.trim()));
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid CIDR in allowlist: " + cidr);
            }
        }

        this.telemetryService = new TelemetryService(plugin);
        this.telemetryWebSocket = new TelemetryWebSocket(plugin, telemetryService, jwtService);
        this.telemetryService.setWebSocket(telemetryWebSocket);
        this.telemetryService.start();
    }

    public void start() {
        String bind = plugin.getConfig().getString("http.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("http.port", 8080);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
        try {
            app = Javalin.create(cfg -> {
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory  = "/www";
                    sf.location   = Location.CLASSPATH;
                    sf.headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
                    sf.headers.put("Pragma", "no-cache");
                });
            }).start(bind, port);

            app.exception(UnauthorizedResponse.class, (e, ctx) -> {
                ctx.status(401);
                if (ctx.result() == null) {
                    ctx.json(Map.of("error", "unauthorized", "message", e.getMessage() != null ? e.getMessage() : "Unauthorized"));
                }
            });
            app.exception(ForbiddenResponse.class, (e, ctx) -> {
                ctx.status(403);
                if (ctx.result() == null) {
                    ctx.json(Map.of("error", "forbidden", "message", e.getMessage() != null ? e.getMessage() : "Forbidden"));
                }
            });

            setupFilters();
            registerRoutes();

        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void setupFilters() {
        app.before(new ClientIpResolver());

        // 1. CIDR Check (if allowlist configured)
        app.before("/api/*", ctx -> {
            if (!allowedCidrMatchers.isEmpty()) {
                String ip = ctx.attribute("client-ip");
                boolean allowed = false;
                for (CidrMatcher matcher : allowedCidrMatchers) {
                    if (matcher.matches(ip)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new UnauthorizedResponse("IP not allowed");
                }
            }
        });

        // 2. Rate limiting (Auth endpoints stricter: 30/min; general API: 120/min)
        Cache<String, Integer> authRateLimit = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
        Cache<String, Integer> apiRateLimit = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();

        app.before("/api/*", ctx -> {
            String ip = ctx.attribute("client-ip");
            if (ip == null) ip = "unknown";
            String path = ctx.path();

            if (path.startsWith("/api/auth/")) {
                Integer count = authRateLimit.get(ip, k -> 0);
                if (count != null && count >= 30) {
                    ctx.status(429).json(Map.of("error", "too_many_requests"));
                    throw new io.javalin.http.HttpResponseException(429, "Too many requests");
                }
                authRateLimit.put(ip, count != null ? count + 1 : 1);
            } else {
                Integer count = apiRateLimit.get(ip, k -> 0);
                if (count != null && count >= 120) {
                    ctx.status(429).json(Map.of("error", "too_many_requests"));
                    throw new io.javalin.http.HttpResponseException(429, "Too many requests");
                }
                apiRateLimit.put(ip, count != null ? count + 1 : 1);
            }
        });

        // 3. Origin check on mutating HTTP & auth handshake
        app.before("/api/*", ctx -> {
            String publicOrigin = plugin.getConfig().getString("http.public-origin");
            if (publicOrigin != null && !publicOrigin.isBlank()) {
                String method = ctx.method().name();
                String path = ctx.path();
                boolean isMutating = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) ||
                                     "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
                boolean isAuth = path.startsWith("/api/auth/");

                if (isMutating || isAuth) {
                    String reqOrigin = ctx.header("Origin");
                    if (reqOrigin != null && !reqOrigin.equalsIgnoreCase(publicOrigin.trim())) {
                        ctx.status(403).json(Map.of("error", "origin_mismatch"));
                        throw new ForbiddenResponse("Origin mismatch");
                    }
                }
            }
        });

        // 4. Auth & Principal resolution
        app.before("/api/*", ctx -> {
            String path = ctx.path();
            if (path.startsWith("/api/auth/") || path.equals("/api/health")) {
                return; // Public endpoints
            }

            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.status(401).json(Map.of("error", "unauthorized"));
                throw new UnauthorizedResponse("Missing or invalid token");
            }

            String token = authHeader.substring(7);
            Principal principal = jwtService.parsePrincipal(token);
            if (principal == null) {
                ctx.status(401).json(Map.of("error", "invalid_token"));
                throw new UnauthorizedResponse("Invalid token");
            }

            ctx.attribute("principal", principal);
            ctx.attribute("username", principal.getUsername());
        });
    }

    private void require(Context ctx, String node) {
        Principal principal = ctx.attribute("principal");
        if (principal == null) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            throw new UnauthorizedResponse("Not logged in");
        }

        if (!principal.hasNode(node)) {
            ctx.status(403).json(Map.of("error", "forbidden", "node", node));
            throw new ForbiddenResponse("Forbidden: missing " + node);
        }
    }

    public void stop() {
        if (app == null) return;
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(plugin.getClass().getClassLoader());
        try {
            app.stop();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public Javalin getApp() {
        return app;
    }

    // ── Route registration ────────────────────────────────────────────────────

    private void registerRoutes() {
        // Health check
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        // Auth routes
        new AuthController(authService, auditService, plugin).register(app);

        // API routes with plan.md node permissions
        app.get("/api/stats",            ctx -> { require(ctx, "dashboard.view"); controller.getStats(ctx); });
        app.get("/api/stats/history",    ctx -> { require(ctx, "dashboard.view"); controller.getStatsHistory(ctx); });
        app.get("/api/players/history",  ctx -> { require(ctx, "dashboard.players.view"); controller.getPlayersHistory(ctx); });
        app.get("/api/players/{name}",   ctx -> { require(ctx, "dashboard.players.view"); controller.getPlayerDetails(ctx); });
        app.get("/api/logs",             ctx -> { require(ctx, "dashboard.view"); controller.getLogs(ctx); });
        app.get("/api/players/detailed", ctx -> { require(ctx, "dashboard.players.view"); controller.getDetailedPlayers(ctx); });
        app.post("/api/player/action",   ctx -> { require(ctx, "dashboard.players.moderate"); controller.postPlayerAction(ctx); });
        app.get("/api/plugins",          ctx -> { require(ctx, "dashboard.view"); controller.getPlugins(ctx); });
        app.post("/api/plugin/action",   ctx -> { require(ctx, "dashboard.console.execute"); controller.postPluginAction(ctx); });
        app.get("/api/properties",       ctx -> { require(ctx, "dashboard.view"); controller.getProperties(ctx); });
        app.post("/api/properties",      ctx -> { require(ctx, "dashboard.console.execute"); controller.postProperties(ctx); });
        app.post("/api/server/action",   ctx -> { require(ctx, "dashboard.console.execute"); controller.postServerAction(ctx); });
        app.post("/api/command",         ctx -> { require(ctx, "dashboard.console.execute"); controller.executeCommand(ctx); });
        app.get("/api/map/overview",     ctx -> { require(ctx, "dashboard.view"); controller.getMapOverview(ctx); });
        app.get("/api/map/meta",         ctx -> { require(ctx, "dashboard.view"); controller.getMapMeta(ctx); });
        app.get("/api/waypoints",        ctx -> { require(ctx, "dashboard.view"); controller.getWaypoints(ctx); });
        app.post("/api/waypoint",        ctx -> { require(ctx, "dashboard.apollo.waypoints"); controller.postWaypoint(ctx); });
        app.delete("/api/waypoint",      ctx -> { require(ctx, "dashboard.apollo.waypoints"); controller.deleteWaypoint(ctx); });

        // Apollo
        app.get("/api/apollo/waypoints", ctx -> { require(ctx, "dashboard.apollo.read"); apolloService.listWaypoints(ctx); });
        app.post("/api/apollo/waypoint", ctx -> { require(ctx, "dashboard.apollo.waypoints"); apolloService.createWaypoint(ctx); });
        app.delete("/api/apollo/waypoint", ctx -> { require(ctx, "dashboard.apollo.waypoints"); apolloService.deleteWaypoint(ctx); });
        app.post("/api/apollo/title",    ctx -> { require(ctx, "dashboard.apollo.staff"); apolloService.sendTitle(ctx); });
        app.post("/api/apollo/xray",     ctx -> { require(ctx, "dashboard.apollo.staff"); apolloService.setXRay(ctx); });

        // Files
        dev.lukka.oculus.files.FilesController filesController = new dev.lukka.oculus.files.FilesController(plugin);
        app.get("/api/files/list",     ctx -> { require(ctx, "dashboard.files.read"); filesController.listFiles(ctx); });
        app.get("/api/files/read",     ctx -> { require(ctx, "dashboard.files.read"); filesController.readFile(ctx); });
        app.post("/api/files/write",   ctx -> { require(ctx, "dashboard.files.write"); filesController.writeFile(ctx); });
        app.post("/api/files/unzip",   ctx -> { require(ctx, "dashboard.files.write"); filesController.unzipFile(ctx); });
        app.delete("/api/files/delete",ctx -> { require(ctx, "dashboard.files.write"); filesController.deleteFile(ctx); });

        // Players
        dev.lukka.oculus.players.PlayersController playersController = new dev.lukka.oculus.players.PlayersController(plugin, ((dev.lukka.oculus.Oculus) plugin).getExecutor());
        app.get("/api/players/{name}/inventory", ctx -> { require(ctx, "dashboard.players.view"); playersController.getInventory(ctx); });
        app.post("/api/players/{name}/inventory/edit", ctx -> { require(ctx, "dashboard.players.edit_inventory"); playersController.editSlot(ctx); });
        app.get("/api/players/{name}/pdc", ctx -> { require(ctx, "dashboard.players.view"); playersController.getPdc(ctx); });
        app.post("/api/players/{name}/pdc", ctx -> { require(ctx, "dashboard.players.edit_pdc"); playersController.writePdc(ctx); });
        app.delete("/api/players/{name}/pdc", ctx -> { require(ctx, "dashboard.players.edit_pdc"); playersController.deletePdc(ctx); });
        app.get("/api/players/{name}/export-gui", ctx -> { require(ctx, "dashboard.players.view"); playersController.exportGuiYaml(ctx); });

        // Worlds
        dev.lukka.oculus.worlds.WorldsController worldsController = new dev.lukka.oculus.worlds.WorldsController(((dev.lukka.oculus.Oculus) plugin).getExecutor());
        app.get("/api/worlds",         ctx -> { require(ctx, "dashboard.view"); worldsController.getWorlds(ctx); });
        app.post("/api/worlds/{name}/action", ctx -> { require(ctx, "dashboard.worlds.edit"); worldsController.updateWorld(ctx); });

        // Backups
        dev.lukka.oculus.backups.BackupsController backupsController = new dev.lukka.oculus.backups.BackupsController(((dev.lukka.oculus.Oculus) plugin).getBackupService());
        app.get("/api/backups",        ctx -> { require(ctx, "dashboard.backups.manage"); backupsController.getBackups(ctx); });
        app.post("/api/backups/create",ctx -> { require(ctx, "dashboard.backups.manage"); backupsController.createBackup(ctx); });
        app.get("/api/backups/{file}", ctx -> { require(ctx, "dashboard.backups.manage"); backupsController.downloadBackup(ctx); });

        // Packages: /api/packages/install remains unregistered pending repository domain allowlisting per AGENTS.md Invariant 5

        // WebSocket
        new ConsoleWebSocket(plugin, consoleService, jwtService).register(app);
        telemetryWebSocket.register(app);

        // Next.js HTML resolve order (trailingSlash: true)
        app.error(404, ctx -> {
            String path = ctx.path();
            if (path.startsWith("/api/") || path.startsWith("/ws/")) {
                return; // Leave as 404 JSON or standard response
            }
            
            // Next.js App Router client-side navigation RSC payloads might be requested with dots,
            // but generated as directories on the filesystem during static export.
            if (path.contains("__next") && path.endsWith(".txt")) {
                int lastSlash = path.lastIndexOf('/');
                String dir = lastSlash == -1 ? "" : path.substring(0, lastSlash + 1);
                String file = path.substring(lastSlash + 1);

                if (file.endsWith(".__PAGE__.txt") && file.startsWith("__next.")) {
                    String middle = file.substring("__next.".length(), file.length() - ".__PAGE__.txt".length());
                    int firstDot = middle.indexOf('.');
                    String rewrittenFile;
                    if (firstDot != -1) {
                        rewrittenFile = "__next." + middle.substring(0, firstDot) + "/" + middle.substring(firstDot + 1).replace(".", "/") + "/__PAGE__.txt";
                    } else {
                        rewrittenFile = "__next." + middle + "/__PAGE__.txt";
                    }

                    String resourcePath = "/www" + (dir.startsWith("/") ? dir : "/" + dir) + rewrittenFile;
                    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                        if (is != null) {
                            ctx.contentType("text/x-component");
                            ctx.result(is.readAllBytes());
                            ctx.status(200);
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            }
            
            // Do not force text/html fallback on missing static assets
            if (isStaticAsset(path)) {
                return;
            }
            
            if (path.startsWith("/")) path = path.substring(1);
            
            // Try /www/{path}/index.html first (trailingSlash pages)
            if (!path.isEmpty() && tryServeClasspath(ctx, "/www/" + path + "/index.html")) {
                ctx.status(200);
                return;
            }
            // Try /www/{path}.html
            if (!path.isEmpty() && tryServeClasspath(ctx, "/www/" + path + ".html")) {
                ctx.status(200);
                return;
            }
            // Fallback: SPA root index.html
            if (tryServeClasspath(ctx, "/www/index.html")) {
                ctx.status(200);
            }
        });
    }

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "svg", "ico",
            "webp", "woff", "woff2", "ttf", "eot", "map", "txt", "json", "xml"
    );

    private boolean isStaticAsset(String path) {
        if (path.startsWith("/_next/")) {
            return true;
        }
        int lastDot = path.lastIndexOf('.');
        int lastSlash = path.lastIndexOf('/');
        if (lastDot > lastSlash && lastDot != -1) {
            String ext = path.substring(lastDot + 1).toLowerCase();
            return STATIC_EXTENSIONS.contains(ext);
        }
        return false;
    }

    private boolean tryServeClasspath(Context ctx, String resource) {
        try (InputStream is = getClass().getResourceAsStream(resource)) {
            if (is != null) {
                ctx.contentType("text/html; charset=utf-8");
                ctx.result(is.readAllBytes());
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
