package dev.lukka.oculus.console;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lukka.oculus.auth.JwtService;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsMessageContext;
import io.jsonwebtoken.Claims;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ConsoleWebSocket {
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "list", "tps", "say", "time", "weather", "gamemode",
            "teleport", "tp", "kick", "ban", "pardon", "save-all", "whitelist"
    );

    private final Plugin plugin;
    private final ConsoleService consoleService;
    private final JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<WsContext, AuthState> authenticatedClients = new ConcurrentHashMap<>();
    private final Map<WsContext, Long> lastPingTimes = new ConcurrentHashMap<>();

    public ConsoleWebSocket(Plugin plugin, ConsoleService consoleService, JwtService jwtService) {
        this.plugin = plugin;
        this.consoleService = consoleService;
        this.jwtService = jwtService;
    }

    public void register(Javalin app) {
        app.ws("/ws/console", ws -> {
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
            if (!authenticatedClients.containsKey(context) && context.session.isOpen()) {
                context.session.close(1008, "Auth timeout");
            }
        }, 100L);
    }

    private void handleClose(WsContext context) {
        authenticatedClients.remove(context);
        lastPingTimes.remove(context);
        consoleService.disconnect(context);
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
                
                List<String> nodes = claims.get("nodes", List.class);
                boolean hasRead = nodes != null && (nodes.contains("*") || nodes.contains("dashboard.console.read"));
                boolean hasExecute = nodes != null && (nodes.contains("*") || nodes.contains("dashboard.console.execute"));
                
                if (!hasRead) {
                    context.session.close(1008, "Missing permission dashboard.console.read");
                    return;
                }

                authenticatedClients.put(context, new AuthState(hasExecute));
                consoleService.addClient(context);
                
                context.send("{\"type\":\"hello\",\"protocolVersion\":1}");
                consoleService.sendHistory(context);
                return;
            }

            if ("ping".equals(msg.type)) {
                context.send("{\"type\":\"pong\"}");
                return;
            }

            AuthState authState = authenticatedClients.get(context);
            if (authState == null) {
                context.session.close(1008, "Not authenticated");
                return;
            }

            if ("command".equals(msg.type)) {
                if (!authState.canExecute) {
                    sendError(context, "command_error", "missing_permission");
                    return;
                }
                
                String command = normalize(msg.command);
                if (!isAllowed(command)) {
                    sendError(context, "command_error", "command_not_allowed");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    if (!dispatched) {
                        sendError(context, "command_error", "dispatch_failed");
                    }
                });
            } else if ("tab_complete".equals(msg.type)) {
                String commandLine = msg.command != null ? msg.command : "";
                
                // Allow tab completion regardless of execute perm? Usually yes, but maybe need execute.
                // We will just let them tab complete if they can read the console.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    CommandSender sender = Bukkit.getConsoleSender();
                    // In older Bukkit versions it might be Bukkit.getServer().tabComplete
                    List<String> completions = Bukkit.getServer().getCommandMap().tabComplete(sender, commandLine);
                    if (completions == null) {
                        completions = List.of();
                    }
                    try {
                        TabCompleteResult res = new TabCompleteResult();
                        res.type = "tab_complete_result";
                        res.results = completions;
                        context.send(mapper.writeValueAsString(res));
                    } catch (Exception ignored) {
                    }
                });
            }

        } catch (Exception error) {
            sendError(context, "error", "invalid_payload");
        }
    }

    private static String normalize(String command) {
        String value = command == null ? "" : command.trim();
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static boolean isAllowed(String command) {
        if (command.isBlank() || command.length() > 256) {
            return false;
        }
        return ALLOWED_COMMANDS.contains(command.split("\\s+", 2)[0].toLowerCase());
    }

    private static void sendError(WsContext context, String type, String error) {
        try {
            context.send("{\"type\":\"" + type + "\",\"error\":\"" + error + "\"}");
        } catch (Exception ignored) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class WsMessage {
        public String type;
        public String token;
        public String command;
    }

    public static final class TabCompleteResult {
        public String type;
        public List<String> results;
    }

    private static final class AuthState {
        public final boolean canExecute;
        public AuthState(boolean canExecute) {
            this.canExecute = canExecute;
        }
    }
}
