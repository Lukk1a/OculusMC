package dev.lukka.oculus.integrations.apollo;

import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import dev.lukka.oculus.auth.JwtService;

public final class ApolloService {
    private final Plugin plugin;
    private final ApolloGateway gateway;
    private final List<WaypointRequest> persistedWaypoints = new CopyOnWriteArrayList<>();
    private final Map<String, WaypointRequest> persistedWaypointsMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> playerXRayStates = new ConcurrentHashMap<>();

    public ApolloService(Plugin plugin, ApolloGateway gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
        
        if (gateway != null && Bukkit.getServer() != null) {
            try {
                org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
                if (pm != null && Bukkit.getScheduler() != null) {
                    pm.registerEvents(new org.bukkit.event.Listener() {
                        @org.bukkit.event.EventHandler
                        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (!event.getPlayer().isOnline()) return;
                                for (WaypointRequest req : persistedWaypoints) {
                                    try { gateway.sendWaypoint(event.getPlayer(), req); } catch(Exception ignored) {}
                                }
                            }, 40L);
                        }
                    }, plugin);
                }
            } catch (Throwable ignored) {}
        }
    }

    public boolean isAvailable() {
        return gateway != null;
    }

    public ApolloGateway getGateway() {
        return gateway;
    }

    public List<WaypointRequest> getWaypoints() {
        return Collections.unmodifiableList(persistedWaypoints);
    }

    public Map<String, WaypointRequest> getWaypointsMap() {
        return Collections.unmodifiableMap(persistedWaypointsMap);
    }

    public Map<String, Boolean> getPlayerXRayStates() {
        return Collections.unmodifiableMap(playerXRayStates);
    }

    public void listWaypoints(Context context) {
        if (!isAvailable()) { context.status(503).json(Map.of("error", "apollo_not_available")); return; }
        context.json(persistedWaypoints);
    }

    public void createWaypoint(Context context) {
        if (Bukkit.getServer() == null) { context.status(503).json(Map.of("error", "bukkit_unavailable")); return; }
        if (!isAvailable()) { context.status(503).json(Map.of("error", "apollo_not_available")); return; }
        if (!gateway.hasWaypoint()) { context.status(503).json(Map.of("error", "apollo_module_missing")); return; }
        WaypointRequest request = context.bodyAsClass(WaypointRequest.class);
        if (request == null || blank(request.name) || blank(request.world)) {
            context.status(400).json(Map.of("error", "invalid_waypoint_request")); return;
        }
        if (request.name.length() > 64) {
            context.status(400).json(Map.of("error", "waypoint_name_too_long")); return;
        }

        context.future(() -> runOnMainThread(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { gateway.sendWaypoint(p, request); } catch(Exception ignored) {}
            }
            persistedWaypoints.removeIf(w -> w.name.equalsIgnoreCase(request.name.trim()));
            persistedWaypoints.add(request);
            persistedWaypointsMap.put(request.name.trim().toLowerCase(), request);
            return true;
        }).handle((res, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if ("bukkit_unavailable".equals(cause.getMessage())) {
                    context.status(503).json(Map.of("error", "bukkit_unavailable"));
                } else {
                    context.status(500).json(Map.of("error", "apollo_delivery_failed"));
                }
                return null;
            }
            context.status(202).json(Map.of("status", "created", "name", request.name.trim()));
            return null;
        }));
    }

    public void deleteWaypoint(Context context) {
        if (Bukkit.getServer() == null) { context.status(503).json(Map.of("error", "bukkit_unavailable")); return; }
        if (!isAvailable()) { context.status(503).json(Map.of("error", "apollo_not_available")); return; }
        if (!gateway.hasWaypoint()) { context.status(503).json(Map.of("error", "apollo_module_missing")); return; }
        String name = context.queryParam("name");
        if (blank(name)) { context.status(400).json(Map.of("error", "missing_name")); return; }

        context.future(() -> runOnMainThread(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { gateway.removeWaypoint(p, name); } catch(Exception ignored) {}
            }
            persistedWaypoints.removeIf(w -> w.name.equalsIgnoreCase(name));
            persistedWaypointsMap.remove(name.toLowerCase());
            return true;
        }).handle((res, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if ("bukkit_unavailable".equals(cause.getMessage())) {
                    context.status(503).json(Map.of("error", "bukkit_unavailable"));
                } else {
                    context.status(500).json(Map.of("error", "apollo_delivery_failed"));
                }
                return null;
            }
            context.status(202).json(Map.of("status", "deleted", "name", name));
            return null;
        }));
    }

    public void sendTitle(Context context) {
        if (Bukkit.getServer() == null) { context.status(503).json(Map.of("error", "bukkit_unavailable")); return; }
        if (!isAvailable()) { context.status(503).json(Map.of("error", "apollo_not_available")); return; }
        if (!gateway.hasTitle()) { context.status(503).json(Map.of("error", "apollo_module_missing")); return; }
        TitleRequest req = context.bodyAsClass(TitleRequest.class);
        if (req == null || blank(req.player)) { context.status(400).json(Map.of("error", "invalid_request")); return; }
        
        context.future(() -> runOnMainThread(() -> {
            Player p = Bukkit.getPlayerExact(req.player.trim());
            if (p == null || !p.isOnline()) throw new IllegalArgumentException("player_not_online");
            gateway.sendTitle(p, req.title, req.subtitle);
            return p.getName();
        }).handle((playerName, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof IllegalArgumentException) {
                    context.status(404).json(Map.of("error", cause.getMessage()));
                } else if ("bukkit_unavailable".equals(cause.getMessage())) {
                    context.status(503).json(Map.of("error", "bukkit_unavailable"));
                } else {
                    context.status(500).json(Map.of("error", "apollo_delivery_failed"));
                }
                return null;
            }
            context.status(202).json(Map.of("status", "sent", "player", playerName));
            return null;
        }));
    }

    public void setXRay(Context context) {
        if (Bukkit.getServer() == null) { context.status(503).json(Map.of("error", "bukkit_unavailable")); return; }
        if (!isAvailable()) { context.status(503).json(Map.of("error", "apollo_not_available")); return; }
        if (!gateway.hasXRay()) { context.status(503).json(Map.of("error", "apollo_module_missing")); return; }
        
        XRayRequest req = context.bodyAsClass(XRayRequest.class);
        if (req == null || blank(req.player)) { context.status(400).json(Map.of("error", "invalid_request")); return; }

        context.future(() -> runOnMainThread(() -> {
            Player p = Bukkit.getPlayerExact(req.player.trim());
            if (p == null || !p.isOnline()) throw new IllegalArgumentException("player_not_online");
            gateway.setXRay(p, req.enable);
            playerXRayStates.put(p.getUniqueId().toString(), req.enable);
            return p.getName();
        }).handle((playerName, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof IllegalArgumentException) {
                    context.status(404).json(Map.of("error", cause.getMessage()));
                } else if ("bukkit_unavailable".equals(cause.getMessage())) {
                    context.status(503).json(Map.of("error", "bukkit_unavailable"));
                } else {
                    context.status(500).json(Map.of("error", "apollo_delivery_failed"));
                }
                return null;
            }
            context.status(202).json(Map.of("status", "updated", "player", playerName, "enabled", req.enable));
            return null;
        }));
    }

    private <T> CompletableFuture<T> runOnMainThread(java.util.function.Supplier<T> action) {
        if (Bukkit.getServer() == null) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("bukkit_unavailable"));
            return failed;
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
    
    public static class TitleRequest {
        public String player;
        public String title;
        public String subtitle;
    }

    public static class XRayRequest {
        public String player;
        public boolean enable;
    }
}
