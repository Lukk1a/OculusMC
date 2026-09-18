package dev.lukka.oculus;

import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.text.SimpleDateFormat;
import java.util.*;
import org.bukkit.plugin.Plugin;
import dev.lukka.oculus.Oculus;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;
import dev.lukka.oculus.console.ConsoleService;
import dev.lukka.oculus.api.ThreadExecutor;
import java.util.concurrent.TimeUnit;

public class DashboardController {

    private final Plugin plugin;
    private final ConsoleService consoleService;
    private final ThreadExecutor executor;
    private final List<WaypointEntry> waypoints = new CopyOnWriteArrayList<>();

    private static final int MAX_HISTORY_ENTRIES = 120;
    private static final Deque<Map<String, Object>> statsHistory = new ConcurrentLinkedDeque<>();
    private static final Deque<Map<String, Object>> playersHistory = new ConcurrentLinkedDeque<>();
    private static final Map<String, Map<String, Object>> playerStats = new java.util.concurrent.ConcurrentHashMap<>();

    private static double getRoundedTps() {
        Server server = Bukkit.getServer();
        double[] tpsArr = server != null ? server.getTPS() : null;
        double tps = (tpsArr != null && tpsArr.length > 0) ? Math.min(20.0, tpsArr[0]) : 20.0;
        return Math.round(tps * 100.0) / 100.0;
    }

    private static double getCpuPercent() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
                if (load >= 0) {
                    return Math.round(load * 1000.0) / 10.0;
                } else {
                    double procLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                    if (procLoad >= 0) return Math.round(procLoad * 1000.0) / 10.0;
                }
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private static double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    private static Map<String, Object> serializePlayer(Player player, boolean detailed) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", player.getName());
        p.put("uuid", player.getUniqueId().toString());
        p.put("world", player.getWorld() != null ? player.getWorld().getName() : "world");
        org.bukkit.Location loc = player.getLocation();
        p.put("x", round1(loc.getX()));
        p.put("y", round1(loc.getY()));
        p.put("z", round1(loc.getZ()));
        p.put("yaw", round1(loc.getYaw()));
        try {
            p.put("health", round1(player.getHealth()));
            p.put("ping", player.getPing());
        } catch (Throwable ignored) {}

        if (detailed) {
            p.put("pitch", round1(loc.getPitch()));
            try {
                p.put("maxHealth", round1(player.getMaxHealth()));
                p.put("food", player.getFoodLevel());
            } catch (Throwable ignored) {}
            p.put("gamemode", player.getGameMode() != null ? player.getGameMode().name() : "SURVIVAL");
            p.put("isOp", player.isOp());
            p.put("expLevel", player.getLevel());
        }
        return p;
    }

    private static class WorldAndPlayerSnapshot {
        final List<String> worldNames;
        final int loadedChunks;
        final int totalEntities;
        final List<Map<String, Object>> playerList;

        WorldAndPlayerSnapshot(List<String> worldNames, int loadedChunks, int totalEntities, List<Map<String, Object>> playerList) {
            this.worldNames = worldNames;
            this.loadedChunks = loadedChunks;
            this.totalEntities = totalEntities;
            this.playerList = playerList;
        }
    }

    private static void recordPlayerHistory(String type, String name) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("player", name);
        event.put("timestamp", System.currentTimeMillis());
        playersHistory.add(event);
        while (playersHistory.size() > 1000) {
            playersHistory.pollFirst();
        }
    }

    public static void recordPlayerJoin(String name) {
        recordPlayerHistory("join", name);
        Map<String, Object> st = playerStats.computeIfAbsent(name, k -> new HashMap<>());
        st.put("joinCount", (Integer)st.getOrDefault("joinCount", 0) + 1);
        st.put("lastSeen", System.currentTimeMillis());
        st.put("sessionStart", System.currentTimeMillis());
    }

    public static void recordPlayerQuit(String name) {
        recordPlayerHistory("quit", name);
        Map<String, Object> st = playerStats.computeIfAbsent(name, k -> new HashMap<>());
        long start = (Long)st.getOrDefault("sessionStart", System.currentTimeMillis());
        long played = System.currentTimeMillis() - start;
        st.put("playtime", (Long)st.getOrDefault("playtime", 0L) + played);
        st.put("lastSeen", System.currentTimeMillis());
    }

    private void handleExecutionException(Context ctx, Exception e) {
        if (e instanceof java.util.concurrent.TimeoutException ||
            (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof java.util.concurrent.TimeoutException)) {
            ctx.status(504).json(Map.of("error", "main_thread_timeout"));
        } else {
            ctx.status(500).json(Map.of("error", "internal_error", "message", e.getMessage() != null ? e.getMessage() : "Execution failed"));
        }
    }

    public DashboardController(Plugin plugin, ConsoleService consoleService, ThreadExecutor executor) {
        this.plugin = plugin;
        this.consoleService = consoleService;
        this.executor = executor;
    }


    public static void collectStats() {
        Map<String, Object> point = new HashMap<>();
        point.put("tps", getRoundedTps());
        point.put("memoryUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        point.put("cpuUsage", getCpuPercent());
        point.put("timestamp", System.currentTimeMillis());
        
        statsHistory.add(point);
        while (statsHistory.size() > MAX_HISTORY_ENTRIES) {
            statsHistory.pollFirst();
        }
    }

    public void getStats(Context ctx) {
        Map<String, Object> stats = new HashMap<>();
        Server server = Bukkit.getServer();

        int maxPlayers = server != null ? server.getMaxPlayers() : 0;

        stats.put("maxPlayers", maxPlayers);
        stats.put("tps", getRoundedTps());
        stats.put("memoryUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        stats.put("memoryMax", Runtime.getRuntime().maxMemory());
        stats.put("memoryTotal", Runtime.getRuntime().totalMemory());
        stats.put("cpuUsage", getCpuPercent());

        // Disk Storage
        try {
            File root = new File(".");
            long totalDisk = root.getTotalSpace();
            long freeDisk = root.getFreeSpace();
            long usedDisk = totalDisk - freeDisk;
            stats.put("diskTotal", totalDisk);
            stats.put("diskUsed", usedDisk);
            stats.put("diskFree", freeDisk);
            stats.put("diskUsagePercent", totalDisk > 0 ? Math.round(((double) usedDisk / totalDisk) * 100.0) : 0);
        } catch (Throwable ignored) {
            stats.put("diskUsagePercent", 0);
        }

        // JVM Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        stats.put("uptimeMs", uptimeMs);

        // Minecraft World & Chunk Telemetry + Player Roster via main thread hop
        List<String> worldNames = new ArrayList<>();
        int totalLoadedChunks = 0;
        int totalEntities = 0;
        List<Map<String, Object>> playerList = new ArrayList<>();

        if (server != null) {
            try {
                WorldAndPlayerSnapshot snapshot = executor.supply(() -> {
                    int chunks = 0;
                    int entities = 0;
                    List<String> worlds = new ArrayList<>();
                    for (World w : Bukkit.getWorlds()) {
                        worlds.add(w.getName());
                        chunks += w.getLoadedChunks().length;
                        try {
                            entities += w.getEntityCount();
                        } catch (Throwable t) {
                            entities += w.getEntities().size();
                        }
                    }
                    List<Map<String, Object>> roster = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        roster.add(serializePlayer(player, false));
                    }
                    return new WorldAndPlayerSnapshot(worlds, chunks, entities, roster);
                }).get(3, TimeUnit.SECONDS);

                worldNames = snapshot.worldNames;
                totalLoadedChunks = snapshot.loadedChunks;
                totalEntities = snapshot.totalEntities;
                playerList = snapshot.playerList;
            } catch (Exception e) {
                handleExecutionException(ctx, e);
                return;
            }
        }

        stats.put("players", playerList.size());
        stats.put("worldCount", worldNames.size());
        stats.put("worlds", worldNames);
        stats.put("loadedChunks", totalLoadedChunks);
        stats.put("totalEntities", totalEntities);
        stats.put("serverVersion", server != null ? Bukkit.getBukkitVersion() : "Paper 26.2-124");
        stats.put("minecraftVersion", server != null ? Bukkit.getMinecraftVersion() : "26.2");
        stats.put("playerList", playerList);
        stats.put("waypoints", waypoints);

        ctx.json(stats);
    }

    
    public void getStatsHistory(Context ctx) {
        ctx.json(new ArrayList<>(statsHistory));
    }
    
    public void getPlayersHistory(Context ctx) {
        ctx.json(new ArrayList<>(playersHistory));
    }
    
    public void getPlayerDetails(Context ctx) {
        String name = ctx.pathParam("name");
        Map<String, Object> stats = playerStats.getOrDefault(name, new HashMap<>());
        ctx.json(stats);
    }

    public void getLogs(Context ctx) {
        if (consoleService != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            List<LogEntry> logs = consoleService.getHistory().stream()
                    .map(e -> new LogEntry(sdf.format(new Date(e.timestamp())), e.level(), e.message()))
                    .collect(java.util.stream.Collectors.toList());
            ctx.json(logs);
        } else {
            ctx.json(new ArrayList<>());
        }
    }

    public void getMapOverview(Context ctx) {
        String world = ctx.queryParam("world");
        boolean refresh = "true".equalsIgnoreCase(ctx.queryParam("refresh"));
        byte[] png = WorldMapRenderer.getOverviewMapPng(world != null ? world : "world", refresh);
        ctx.contentType("image/png");
        ctx.header("Cache-Control", "public, max-age=15");
        ctx.result(png);
    }

    public void getMapMeta(Context ctx) {
        String world = ctx.queryParam("world");
        ctx.json(WorldMapRenderer.getMapMeta(world != null ? world : "world"));
    }

    public void getDetailedPlayers(Context ctx) {
        Server server = Bukkit.getServer();
        if (server == null) {
            ctx.json(new ArrayList<>());
            return;
        }

        try {
            List<Map<String, Object>> detailedList = executor.supply(() -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    list.add(serializePlayer(player, true));
                }
                return list;
            }).get(3, TimeUnit.SECONDS);

            ctx.json(detailedList);
        } catch (Exception e) {
            handleExecutionException(ctx, e);
        }
    }

    public void postPlayerAction(Context ctx) {
        if (org.bukkit.Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        PlayerActionRequest req = ctx.bodyAsClass(PlayerActionRequest.class);
        if (req == null || req.player == null || req.action == null) {
            ctx.status(400).result("Missing player or action in request");
            return;
        }

        try {
            executor.run(() -> {
                Player player = Bukkit.getPlayerExact(req.player);
                if (player == null) {
                    player = Bukkit.getPlayer(req.player);
                }
                if (player == null) return;

                switch (req.action.toLowerCase()) {
                    case "kick":
                        String reason = (req.value != null && !req.value.trim().isEmpty())
                                ? req.value.trim() : "Disconnected by console administrator.";
                        player.kickPlayer(reason);
                        plugin.getLogger().info("Kicked player " + player.getName() + ": " + reason);
                        break;
                    case "op":
                        player.setOp(true);
                        player.sendMessage("§eYou are now an operator.");
                        plugin.getLogger().info("Opped player " + player.getName());
                        break;
                    case "deop":
                        player.setOp(false);
                        player.sendMessage("§eYou are no longer an operator.");
                        plugin.getLogger().info("Deopped player " + player.getName());
                        break;
                    case "gamemode":
                        if (req.value != null) {
                            try {
                                GameMode gm = GameMode.valueOf(req.value.toUpperCase());
                                player.setGameMode(gm);
                                plugin.getLogger().info("Set " + player.getName() + "'s gamemode to " + gm.name());
                            } catch (Throwable ignored) {}
                        }
                        break;
                    case "heal":
                        try {
                            player.setHealth(player.getMaxHealth());
                            player.setFoodLevel(20);
                            player.sendMessage("§aYou have been revitalized by console.");
                            plugin.getLogger().info("Revitalized player " + player.getName());
                        } catch (Throwable ignored) {}
                        break;
                    case "teleport":
                        if ("spawn".equalsIgnoreCase(req.value)) {
                            player.teleport(player.getWorld().getSpawnLocation());
                            player.sendMessage("§aTeleported to world spawn.");
                            plugin.getLogger().info("Teleported " + player.getName() + " to spawn");
                        }
                        break;
                    case "ban":
                        String banReason = (req.value != null && !req.value.trim().isEmpty()) ? req.value.trim() : "Banned by administrator";
                        try {
                            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(player.getName(), banReason, null, "Console");
                            player.kickPlayer("Banned: " + banReason);
                            plugin.getLogger().warning("Banned player " + player.getName() + ": " + banReason);
                        } catch (Throwable ignored) {}
                        break;
                }
                            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            handleExecutionException(ctx, e);
            return;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("player", req.player);
        res.put("action", req.action);
        ctx.json(res);
    }

    public void getPlugins(Context ctx) {
        List<Map<String, Object>> plugins = new ArrayList<>();
        Server server = Bukkit.getServer();
        if (server != null) {
            for (org.bukkit.plugin.Plugin p : Bukkit.getPluginManager().getPlugins()) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", p.getName());
                info.put("version", p.getDescription().getVersion());
                info.put("authors", p.getDescription().getAuthors());
                info.put("description", p.getDescription().getDescription() != null ? p.getDescription().getDescription() : "");
                info.put("website", p.getDescription().getWebsite() != null ? p.getDescription().getWebsite() : "");
                info.put("enabled", p.isEnabled());
                plugins.add(info);
            }
        }
        ctx.json(plugins);
    }

    public void postPluginAction(Context ctx) {
        if (org.bukkit.Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        PluginActionRequest req = ctx.bodyAsClass(PluginActionRequest.class);
        if (req == null || req.plugin == null || req.action == null) {
            ctx.status(400).result("Missing plugin or action in request");
            return;
        }

        try {
            executor.run(() -> {
                org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(req.plugin);
                if (target == null) return;

                switch (req.action.toLowerCase()) {
                    case "enable":
                        if (!target.isEnabled()) {
                            Bukkit.getPluginManager().enablePlugin(target);
                            plugin.getLogger().info("Enabled plugin: " + target.getName());
                        }
                        break;
                    case "disable":
                        if (target.isEnabled()) {
                            Bukkit.getPluginManager().disablePlugin(target);
                            plugin.getLogger().warning("Disabled plugin: " + target.getName());
                        }
                        break;
                    case "reload":
                        try {
                            target.reloadConfig();
                            plugin.getLogger().info("Reloaded config for plugin: " + target.getName());
                        } catch (Throwable ignored) {}
                        break;
                }
                            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            handleExecutionException(ctx, e);
            return;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("plugin", req.plugin);
        res.put("action", req.action);
        ctx.json(res);
    }

    public void getProperties(Context ctx) {
        Map<String, String> propMap = new LinkedHashMap<>();
        File propFile = new File("server.properties");
        if (propFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propFile)) {
                Properties props = new Properties();
                props.load(fis);
                for (String key : props.stringPropertyNames()) {
                    propMap.put(key, props.getProperty(key));
                }
            } catch (Throwable t) {
                ctx.status(500).result("Failed to load server.properties: " + t.getMessage());
                return;
            }
        }
        ctx.json(propMap);
    }

    @SuppressWarnings("unchecked")
    public void postProperties(Context ctx) {
        Map<String, String> newProps = ctx.bodyAsClass(Map.class);
        if (newProps == null || newProps.isEmpty()) {
            ctx.status(400).result("Empty properties payload");
            return;
        }

        File propFile = new File("server.properties");
        Properties props = new Properties();
        if (propFile.exists()) {
            try (FileInputStream fis = new FileInputStream(propFile)) {
                props.load(fis);
            } catch (Throwable ignored) {}
        }

        for (Map.Entry<String, String> entry : newProps.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                props.setProperty(entry.getKey(), entry.getValue());
            }
        }

        try (FileOutputStream fos = new FileOutputStream(propFile)) {
            props.store(fos, "Minecraft server properties (Synchronized via Oculus)");
            plugin.getLogger().info("server.properties updated via Oculus web dashboard.");
            ctx.status(200).result("Properties saved successfully");
        } catch (Throwable t) {
            ctx.status(500).result("Failed to write server.properties: " + t.getMessage());
        }
    }

    public void postServerAction(Context ctx) {
        if (org.bukkit.Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        ServerActionRequest req = ctx.bodyAsClass(ServerActionRequest.class);
        if (req == null || req.action == null) {
            ctx.status(400).result("Missing server action");
            return;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("action", req.action);

        try {
            executor.run(() -> {
                switch (req.action.toLowerCase()) {
                    case "gc":
                long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.gc();
                long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long freedMB = Math.max(0, (before - after) / (1024 * 1024));
                plugin.getLogger().info("Garbage collection executed. Reclaimed ~" + freedMB + " MB heap.");
                res.put("freedMB", freedMB);
                        break;
                    case "broadcast":
                        if (req.value != null && !req.value.trim().isEmpty()) {
                            final String msg = req.value.trim();
                            Bukkit.broadcastMessage("§b[Announcement] §f" + msg);
                            plugin.getLogger().info("Broadcasted: " + msg);
                        }
                        break;
                    case "time":
                        if (req.value != null) {
                        for (World w : Bukkit.getWorlds()) {
                            switch (req.value.toLowerCase()) {
                                case "day": w.setTime(1000); break;
                                case "noon": w.setTime(6000); break;
                                case "night": w.setTime(13000); break;
                                case "midnight": w.setTime(18000); break;
                            }
                        }
                        plugin.getLogger().info("Synchronized world time to: " + req.value);
                        }
                        break;
                    case "weather":
                        if (req.value != null) {
                        for (World w : Bukkit.getWorlds()) {
                            switch (req.value.toLowerCase()) {
                                case "clear":
                                    w.setStorm(false);
                                    w.setThundering(false);
                                    break;
                                case "rain":
                                    w.setStorm(true);
                                    w.setThundering(false);
                                    break;
                                case "thunder":
                                    w.setStorm(true);
                                    w.setThundering(true);
                                    break;
                            }
                        }
                        plugin.getLogger().info("Synchronized world weather to: " + req.value);
                        }
                        break;
                }
                            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            handleExecutionException(ctx, e);
            return;
        }

        ctx.json(res);
    }


    public void executeCommand(Context ctx) {
        if (org.bukkit.Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        CommandRequest req = ctx.bodyAsClass(CommandRequest.class);
        if (req == null || req.command == null || req.command.trim().isEmpty()) {
            ctx.status(400).result("Missing command parameter");
            return;
        }

        String cmd = req.command.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }

        final String finalCmd = cmd;
        plugin.getLogger().info("> /" + finalCmd);

        try {
            executor.run(() -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Command execution error: " + t.getMessage());
                }
                            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            handleExecutionException(ctx, e);
            return;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "executed");
        result.put("command", finalCmd);
        ctx.json(result);
    }



    public static class LogEntry {
        public String time;
        public String level;
        public String msg;

        public LogEntry() {}

        public LogEntry(String time, String level, String msg) {
            this.time = time;
            this.level = level;
            this.msg = msg;
        }
    }



    public static class CommandRequest {
        public String command;

        public CommandRequest() {}

        public CommandRequest(String command) {
            this.command = command;
        }
    }

    public static class PlayerActionRequest {
        public String player;
        public String action;
        public String value;

        public PlayerActionRequest() {}
    }

    public static class PluginActionRequest {
        public String plugin;
        public String action;

        public PluginActionRequest() {}
    }

    public void getWaypoints(Context ctx) {
        ctx.json(new ArrayList<>(waypoints));
    }

    public void postWaypoint(Context ctx) {
        WaypointEntry wp = ctx.bodyAsClass(WaypointEntry.class);
        if (wp == null || wp.name == null || wp.name.trim().isEmpty()) {
            ctx.status(400).result("Invalid waypoint data");
            return;
        }
        waypoints.removeIf(w -> w.name != null && w.name.equalsIgnoreCase(wp.name));
        waypoints.add(wp);
        plugin.getLogger().info("Waypoint registered: " + wp.name + " (" + wp.world + " " + wp.x + ", " + wp.y + ", " + wp.z + ")");
        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("waypoint", wp);
        ctx.json(res);
    }

    public void deleteWaypoint(Context ctx) {
        String name = ctx.queryParam("name");
        if (name != null) {
            waypoints.removeIf(w -> w.name != null && w.name.equalsIgnoreCase(name));
            plugin.getLogger().info("Waypoint removed: " + name);
        }
        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        ctx.json(res);
    }

    public static class WaypointEntry {
        public String name;
        public String world;
        public double x;
        public double y;
        public double z;
        public String color;

        public WaypointEntry() {}
        public WaypointEntry(String name, String world, double x, double y, double z, String color) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
        }
    }

    public static class ServerActionRequest {
        public String action;
        public String value;

        public ServerActionRequest() {}
    }
}
