package dev.lukka.oculus.auth;

import dev.lukka.oculus.audit.AuditService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;
    private final Plugin plugin;

    private static final String PREAUTH_COOKIE = "Oculus-PreAuth";
    private static final String REFRESH_COOKIE = "Oculus-Refresh";

    public AuthController(AuthService authService, AuditService auditService, Plugin plugin) {
        this.authService = authService;
        this.auditService = auditService;
        this.plugin = plugin;
    }

    public void register(Javalin app) {
        app.get("/api/auth/setup-status", this::setupStatus);
        app.post("/api/auth/setup", this::setup);
        app.post("/api/auth/login", this::login);
        app.post("/api/auth/totp", this::totp);
        app.post("/api/auth/refresh", this::refresh);
        app.post("/api/auth/logout", this::logout);
    }

    private boolean isSecureCookie(Context ctx) {
        String secureConfig = plugin.getConfig().getString("http.cookie-secure", "auto");
        if ("true".equalsIgnoreCase(secureConfig)) return true;
        if ("false".equalsIgnoreCase(secureConfig)) return false;
        // auto
        String proto = ctx.header("X-Forwarded-Proto");
        if ("https".equalsIgnoreCase(proto)) return true;
        return ctx.req().isSecure();
    }

    private void setCookie(Context ctx, String name, String value, int maxAge) {
        boolean secure = isSecureCookie(ctx);
        Cookie cookie = new Cookie(name, value, "/api/auth", maxAge, secure, 0, true, null, null, SameSite.STRICT);
        ctx.cookie(cookie);
    }

    private void clearCookie(Context ctx, String name) {
        boolean secure = isSecureCookie(ctx);
        Cookie cookie = new Cookie(name, "", "/api/auth", 0, secure, 0, true, null, null, SameSite.STRICT);
        ctx.cookie(cookie);
    }

    private void setupStatus(Context ctx) {
        ctx.json(Map.of("setupRequired", authService.isSetupRequired()));
    }

    private void setup(Context ctx) {
        if (!authService.isSetupRequired()) {
            ctx.status(409).json(Map.of("error", "setup_already_done"));
            return;
        }

        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        String username = body != null ? body.get("username") : null;
        String password = body != null ? body.get("password") : null;

        if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        username = username.trim();

        AuthService.SetupResult result = authService.setupInitialAdmin(username, password);
        if (!result.success) {
            if ("setup_already_done".equals(result.error)) {
                ctx.status(409).json(Map.of("error", "setup_already_done"));
            } else {
                ctx.status(500).json(Map.of("error", result.error));
            }
            return;
        }

        auditService.logEvent("setup", username, ctx.attribute("client-ip"), Map.of());

        // Set PreAuth cookie (Path=/api/auth, HttpOnly, SameSite=Strict)
        int preauthTtl = plugin.getConfig().getInt("auth.preauth-ttl-seconds", 300);
        setCookie(ctx, PREAUTH_COOKIE, result.preAuthToken, preauthTtl);

        Map<String, Object> resp = new HashMap<>();
        resp.put("totpRequired", true);
        resp.put("qrPngBase64", result.qrPngBase64);
        resp.put("otpauthUrl", result.otpauthUrl);
        resp.put("recoveryCodes", result.recoveryCodes);
        ctx.json(resp);
    }

    private void login(Context ctx) {
        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        String username = body != null ? body.get("username") : null;
        String password = body != null ? body.get("password") : null;

        if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        username = username.trim();

        AuthService.LoginResult result = authService.login(username, password);
        if (!result.success) {
            auditService.logEvent("login_failed", username, ctx.attribute("client-ip"), Map.of("reason", result.error));
            if ("locked_out".equals(result.error)) {
                ctx.status(429).json(Map.of("error", "locked_out"));
            } else {
                ctx.status(401).json(Map.of("error", "invalid_credentials"));
            }
            return;
        }

        if (result.totpRequired) {
            int preauthTtl = plugin.getConfig().getInt("auth.preauth-ttl-seconds", 300);
            setCookie(ctx, PREAUTH_COOKIE, result.preAuthToken, preauthTtl);
            auditService.logEvent("login_password_ok", username, ctx.attribute("client-ip"), Map.of("totpRequired", true));
            ctx.json(Map.of("totpRequired", true));
            return;
        }

        // Direct login without TOTP
        int refreshTtlDays = plugin.getConfig().getInt("auth.refresh-ttl-days", 7);
        setCookie(ctx, REFRESH_COOKIE, result.refreshToken, refreshTtlDays * 24 * 3600);
        clearCookie(ctx, PREAUTH_COOKIE);

        auditService.logEvent("login_success", username, ctx.attribute("client-ip"), Map.of("role", result.principal.getRole()));

        ctx.json(Map.of(
                "accessToken", result.accessToken,
                "role", result.principal.getRole(),
                "nodes", result.principal.getNodes()
        ));
    }

    private void totp(Context ctx) {
        String preAuthToken = ctx.cookie(PREAUTH_COOKIE);
        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        // Fallback to body token if client could not send cookie (e.g. testing)
        if (preAuthToken == null && body != null) {
            preAuthToken = body.get("preAuthToken");
        }

        String code = body != null ? body.get("code") : null;
        if (code == null || preAuthToken == null || code.trim().isEmpty()) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return;
        }

        AuthService.TotpResult result = authService.verifyTotp(preAuthToken, code);
        if (!result.success) {
            auditService.logEvent("totp_failed", "unknown", ctx.attribute("client-ip"), Map.of("reason", result.error));
            if ("locked_out".equals(result.error)) {
                ctx.status(429).json(Map.of("error", "locked_out"));
            } else {
                ctx.status(401).json(Map.of("error", "totp_invalid"));
            }
            return;
        }

        // Clear PreAuth cookie, set Refresh cookie
        clearCookie(ctx, PREAUTH_COOKIE);
        int refreshTtlDays = plugin.getConfig().getInt("auth.refresh-ttl-days", 7);
        setCookie(ctx, REFRESH_COOKIE, result.refreshToken, refreshTtlDays * 24 * 3600);

        auditService.logEvent("totp_success", result.principal.getUsername(), ctx.attribute("client-ip"), Map.of("role", result.principal.getRole()));

        ctx.json(Map.of(
                "accessToken", result.accessToken,
                "role", result.principal.getRole(),
                "nodes", result.principal.getNodes()
        ));
    }

    private void refresh(Context ctx) {
        String refreshToken = ctx.cookie(REFRESH_COOKIE);
        if (refreshToken == null) {
            // Check body as fallback for headless / test clients
            try {
                Map<String, String> body = ctx.bodyAsClass(Map.class);
                if (body != null) refreshToken = body.get("refreshToken");
            } catch (Exception ignored) {}
        }

        if (refreshToken == null) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return;
        }

        AuthService.RefreshResult result = authService.refreshToken(refreshToken);
        if (!result.success) {
            clearCookie(ctx, REFRESH_COOKIE);
            ctx.status(401).json(Map.of("error", "revoked"));
            return;
        }

        int refreshTtlDays = plugin.getConfig().getInt("auth.refresh-ttl-days", 7);
        setCookie(ctx, REFRESH_COOKIE, result.refreshToken, refreshTtlDays * 24 * 3600);

        ctx.json(Map.of(
                "accessToken", result.accessToken,
                "role", result.principal.getRole(),
                "nodes", result.principal.getNodes()
        ));
    }

    private void logout(Context ctx) {
        String refreshToken = ctx.cookie(REFRESH_COOKIE);
        if (refreshToken == null) {
            try {
                Map<String, String> body = ctx.bodyAsClass(Map.class);
                if (body != null) refreshToken = body.get("refreshToken");
            } catch (Exception ignored) {}
        }

        String authHeader = ctx.header("Authorization");
        String jwtToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtToken = authHeader.substring(7);
        }

        authService.logout(jwtToken, refreshToken);
        clearCookie(ctx, PREAUTH_COOKIE);
        clearCookie(ctx, REFRESH_COOKIE);

        ctx.json(Map.of("success", true));
    }
}
