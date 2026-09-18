package dev.lukka.oculus.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lukka.oculus.auth.JwtService;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import io.jsonwebtoken.Claims;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public final class TelemetryWebSocket {

    private final Plugin plugin;
    private final TelemetryService telemetryService;
    private final JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final CopyOnWriteArraySet<WsContext> authenticatedClients = new CopyOnWriteArraySet<>();
    private final Map<WsContext, Long> lastPingTimes = new ConcurrentHashMap<>();

    public TelemetryWebSocket(Plugin plugin, TelemetryService telemetryService, JwtService jwtService) {
        this.plugin = plugin;
        this.telemetryService = telemetryService;
        this.jwtService = jwtService;
    }

    public void register(Javalin app) {
        app.ws("/ws/telemetry", ws -> {
            ws.onConnect(this::handleConnect);
            ws.onClose(this::handleClose);
            ws.onError(this::handleClose);
            ws.onMessage(this::handleMessage);
        });

        // Ping timeout checker
        if (Bukkit.getServer() != null) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                long now = System.currentTimeMillis();
                for (Map.Entry<WsContext, Long> entry : lastPingTimes.entrySet()) {
                    if (now - entry.getValue() > 45000) {
                        entry.getKey().session.close(1008, "Ping timeout");
                    }
                }
            }, 200L, 200L); // check every 10 seconds
        }
    }

    private void handleConnect(WsContext context) {
        lastPingTimes.put(context, System.currentTimeMillis());
        // 5-second auth timeout
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!authenticatedClients.contains(context) && context.session.isOpen()) {
                context.session.close(1008, "Auth timeout");
            }
        }, 100L);
    }

    private void handleClose(WsContext context) {
        authenticatedClients.remove(context);
        lastPingTimes.remove(context);
    }

    private void handleMessage(WsMessageContext context) {
        lastPingTimes.put(context, System.currentTimeMillis());

        try {
            WsMessage msg = mapper.readValue(context.message(), WsMessage.class);

            if ("auth".equals(msg.type)) {
                if (msg.token == null) {
                    context.session.close(1008, "Missing token");
                    return;
                }
                Claims claims = jwtService.verifyToken(msg.token);
                if (claims == null) {
                    context.session.close(1008, "Invalid token");
                    return;
                }

                authenticatedClients.add(context);
                context.send("{\"type\":\"hello\",\"protocolVersion\":1}");
                return;
            }

            if ("ping".equals(msg.type)) {
                context.send("{\"type\":\"pong\"}");
                return;
            }

            if (!authenticatedClients.contains(context)) {
                context.session.close(1008, "Not authenticated");
                return;
            }

        } catch (Exception error) {
            try {
                context.send("{\"type\":\"error\",\"error\":\"invalid_payload\"}");
            } catch (Exception ignored) {
            }
        }
    }
    
    public void broadcast(String message) {
        for (WsContext ctx : authenticatedClients) {
            if (ctx.session.isOpen()) {
                ctx.send(message);
            } else {
                authenticatedClients.remove(ctx);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WsMessage {
        public String type;
        public String token;
    }
}
