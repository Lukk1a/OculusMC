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

public class DashboardController {

    private final Oculus plugin;
    private final List<WaypointEntry> waypoints = new CopyOnWriteArrayList<>();

    // In-memory circular log buffer
    private static final int MAX_LOG_ENTRIES = 300;
    private static final Deque<LogEntry> logBuffer = new ConcurrentLinkedDeque<>();
    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)[§&][0-9a-fk-or]|\\x1B\\[[;\\d]*m");
    private static ConsoleLogHandler logHandler;
    // Analytics buffers
    private static final int MAX_HISTORY_ENTRIES = 120;
    private static final Deque<Map<String, Object>> statsHistory = new ConcurrentLinkedDeque<>();
    private static final Deque<Map<String, Object>> playersHistory = new ConcurrentLinkedDeque<>();
    private static final Map<String, Map<String, Object>> playerStats = new HashMap<>();


    
    public static void recordPlayerJoin(String name) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "join");
        event.put("player", name);
        event.put("timestamp", System.currentTimeMillis());
        playersHistory.add(event);
        if (playersHistory.size() > 1000) playersHistory.pollFirst();
        
        Map<String, Object> st = playerStats.computeIfAbsent(name, k -> new HashMap<>());
        st.put("joinCount", (Integer)st.getOrDefault("joinCount", 0) + 1);
        st.put("lastSeen", System.currentTimeMillis());
        st.put("sessionStart", System.currentTimeMillis());
    }
    
    public static void recordPlayerQuit(String name) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "quit");
        event.put("player", name);
        event.put("timestamp", System.currentTimeMillis());
        playersHistory.add(event);
        if (playersHistory.size() > 1000) playersHistory.pollFirst();
        
        Map<String, Object> st = playerStats.computeIfAbsent(name, k -> new HashMap<>());
        long start = (Long)st.getOrDefault("sessionStart", System.currentTimeMillis());
        long played = System.currentTimeMillis() - start;
        st.put("playtime", (Long)st.getOrDefault("playtime", 0L) + played);
        st.put("lastSeen", System.currentTimeMillis());
    }
    public DashboardController(Oculus plugin) {
        this.plugin = plugin;
    }

    public static void initializeLogCapture() {
        if (logHandler == null) {
            logHandler = new ConsoleLogHandler();
            try {
                java.util.logging.Logger.getLogger("").addHandler(logHandler);
            } catch (Throwable ignored) {}
            try {
                if (Bukkit.getServer() != null) {
                    Bukkit.getLogger().addHandler(logHandler);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void shutdownLogCapture() {
        if (logHandler != null) {
            try {
                java.util.logging.Logger.getLogger("").removeHandler(logHandler);
            } catch (Throwable ignored) {}
            try {
                if (Bukkit.getServer() != null) {
                    Bukkit.getLogger().removeHandler(logHandler);
                }
            } catch (Throwable ignored) {}
            logHandler = null;
        }
    }

    public static void appendManualLog(String level, String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        addLog(new LogEntry(time, level, msg));
    }

    private static void addLog(LogEntry entry) {
        logBuffer.add(entry);
        while (logBuffer.size() > MAX_LOG_ENTRIES) {
            logBuffer.pollFirst();
        }
    }

    
    public static void collectStats() {
        Map<String, Object> point = new HashMap<>();
        Server server = Bukkit.getServer();
        double[] tpsArr = server != null ? server.getTPS() : null;
        double tps = (tpsArr != null && tpsArr.length > 0) ? Math.min(20.0, tpsArr[0]) : 20.0;
        point.put("tps", Math.round(tps * 100.0) / 100.0);
        point.put("memoryUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        
        double cpuPercent = 0.0;
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
                if (load >= 0) {
                    cpuPercent = Math.round(load * 1000.0) / 10.0;
                } else {
                    double procLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                    if (procLoad >= 0) cpuPercent = Math.round(procLoad * 1000.0) / 10.0;
                }
            }
        } catch (Throwable ignored) {}
        point.put("cpuUsage", cpuPercent);
        point.put("timestamp", System.currentTimeMillis());
        
        statsHistory.add(point);
        while (statsHistory.size() > MAX_HISTORY_ENTRIES) {
            statsHistory.pollFirst();
        }
    }

    public void getStats(Context ctx) {
        Map<String, Object> stats = new HashMap<>();
        Server server = Bukkit.getServer();

        int players = server != null ? Bukkit.getOnlinePlayers().size() : 0;
        int maxPlayers = server != null ? server.getMaxPlayers() : 0;
        double[] tpsArr = server != null ? server.getTPS() : null;
        double tps = (tpsArr != null && tpsArr.length > 0) ? Math.min(20.0, tpsArr[0]) : 20.0;

        stats.put("players", players);
        stats.put("maxPlayers", maxPlayers);
        stats.put("tps", Math.round(tps * 100.0) / 100.0);
        stats.put("memoryUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        stats.put("memoryMax", Runtime.getRuntime().maxMemory());
        stats.put("memoryTotal", Runtime.getRuntime().totalMemory());

        // CPU & Disk Telemetry
        double cpuPercent = 0.0;
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean).getCpuLoad();
                if (load >= 0) {
                    cpuPercent = Math.round(load * 1000.0) / 10.0;
                } else {
                    double procLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                    if (procLoad >= 0) cpuPercent = Math.round(procLoad * 1000.0) / 10.0;
                }
            }
        } catch (Throwable ignored) {}
        stats.put("cpuUsage", cpuPercent);

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

        // Minecraft World & Chunk Telemetry
        int totalLoadedChunks = 0;
        int totalEntities = 0;
        List<String> worldNames = new ArrayList<>();
        if (server != null) {
            for (World w : Bukkit.getWorlds()) {
                worldNames.add(w.getName());
                totalLoadedChunks += w.getLoadedChunks().length;
                try {
                    totalEntities += w.getEntityCount();
                } catch (Throwable t) {
                    totalEntities += w.getEntities().size();
                }
            }
        }
        stats.put("worldCount", worldNames.size());
        stats.put("worlds", worldNames);
        stats.put("loadedChunks", totalLoadedChunks);
        stats.put("totalEntities", totalEntities);
        stats.put("serverVersion", server != null ? Bukkit.getBukkitVersion() : "Paper 26.2-124");
        stats.put("minecraftVersion", server != null ? Bukkit.getMinecraftVersion() : "26.2");

        // Player Roster
        List<Map<String, Object>> playerList = new ArrayList<>();
        if (server != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", player.getName());
                p.put("uuid", player.getUniqueId().toString());
                p.put("world", player.getWorld() != null ? player.getWorld().getName() : "world");
                p.put("x", Math.round(player.getLocation().getX() * 10.0) / 10.0);
                p.put("y", Math.round(player.getLocation().getY() * 10.0) / 10.0);
                p.put("z", Math.round(player.getLocation().getZ() * 10.0) / 10.0);
                p.put("yaw", Math.round(player.getLocation().getYaw() * 10.0) / 10.0);
                try {
                    p.put("health", Math.round(player.getHealth() * 10.0) / 10.0);
                    p.put("ping", player.getPing());
                } catch (Throwable ignored) {}
                playerList.add(p);
            }
        }
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
        List<LogEntry> logs = new ArrayList<>(logBuffer);
        ctx.json(logs);
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
        List<Map<String, Object>> detailedList = new ArrayList<>();
        Server server = Bukkit.getServer();
        if (server != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", player.getName());
                p.put("uuid", player.getUniqueId().toString());
                p.put("world", player.getWorld() != null ? player.getWorld().getName() : "world");
                p.put("x", Math.round(player.getLocation().getX() * 10.0) / 10.0);
                p.put("y", Math.round(player.getLocation().getY() * 10.0) / 10.0);
                p.put("z", Math.round(player.getLocation().getZ() * 10.0) / 10.0);
                p.put("yaw", Math.round(player.getLocation().getYaw() * 10.0) / 10.0);
                p.put("pitch", Math.round(player.getLocation().getPitch() * 10.0) / 10.0);
                try {
                    p.put("ping", player.getPing());
                    p.put("health", Math.round(player.getHealth() * 10.0) / 10.0);
                    p.put("maxHealth", Math.round(player.getMaxHealth() * 10.0) / 10.0);
                    p.put("food", player.getFoodLevel());
                } catch (Throwable ignored) {}
                p.put("gamemode", player.getGameMode() != null ? player.getGameMode().name() : "SURVIVAL");
                p.put("isOp", player.isOp());
                p.put("expLevel", player.getLevel());
                detailedList.add(p);
            }
        }
        ctx.json(detailedList);
    }

    public void postPlayerAction(Context ctx) {
        PlayerActionRequest req = ctx.bodyAsClass(PlayerActionRequest.class);
        if (req == null || req.player == null || req.action == null) {
            ctx.status(400).result("Missing player or action in request");
            return;
        }

        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
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
                        appendManualLog("INFO", "Kicked player " + player.getName() + ": " + reason);
                        break;
                    case "op":
                        player.setOp(true);
                        player.sendMessage("§eYou are now an operator.");
                        appendManualLog("INFO", "Opped player " + player.getName());
                        break;
                    case "deop":
                        player.setOp(false);
                        player.sendMessage("§eYou are no longer an operator.");
                        appendManualLog("INFO", "Deopped player " + player.getName());
                        break;
                    case "gamemode":
                        if (req.value != null) {
                            try {
                                GameMode gm = GameMode.valueOf(req.value.toUpperCase());
                                player.setGameMode(gm);
                                appendManualLog("INFO", "Set " + player.getName() + "'s gamemode to " + gm.name());
                            } catch (Throwable ignored) {}
                        }
                        break;
                    case "heal":
                        try {
                            player.setHealth(player.getMaxHealth());
                            player.setFoodLevel(20);
                            player.sendMessage("§aYou have been revitalized by console.");
                            appendManualLog("INFO", "Revitalized player " + player.getName());
                        } catch (Throwable ignored) {}
                        break;
                    case "teleport":
                        if ("spawn".equalsIgnoreCase(req.value)) {
                            player.teleport(player.getWorld().getSpawnLocation());
                            player.sendMessage("§aTeleported to world spawn.");
                            appendManualLog("INFO", "Teleported " + player.getName() + " to spawn");
                        }
                        break;
                    case "ban":
                        String banReason = (req.value != null && !req.value.trim().isEmpty()) ? req.value.trim() : "Banned by administrator";
                        try {
                            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(player.getName(), banReason, null, "Console");
                            player.kickPlayer("Banned: " + banReason);
                            appendManualLog("WARN", "Banned player " + player.getName() + ": " + banReason);
                        } catch (Throwable ignored) {}
                        break;
                }
            });
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
        PluginActionRequest req = ctx.bodyAsClass(PluginActionRequest.class);
        if (req == null || req.plugin == null || req.action == null) {
            ctx.status(400).result("Missing plugin or action in request");
            return;
        }

        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(req.plugin);
                if (target == null) return;

                switch (req.action.toLowerCase()) {
                    case "enable":
                        if (!target.isEnabled()) {
                            Bukkit.getPluginManager().enablePlugin(target);
                            appendManualLog("INFO", "Enabled plugin: " + target.getName());
                        }
                        break;
                    case "disable":
                        if (target.isEnabled()) {
                            Bukkit.getPluginManager().disablePlugin(target);
                            appendManualLog("WARN", "Disabled plugin: " + target.getName());
                        }
                        break;
                    case "reload":
                        try {
                            target.reloadConfig();
                            appendManualLog("INFO", "Reloaded config for plugin: " + target.getName());
                        } catch (Throwable ignored) {}
                        break;
                }
            });
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
            appendManualLog("INFO", "server.properties updated via Oculus web dashboard.");
            ctx.status(200).result("Properties saved successfully");
        } catch (Throwable t) {
            ctx.status(500).result("Failed to write server.properties: " + t.getMessage());
        }
    }

    public void postServerAction(Context ctx) {
        ServerActionRequest req = ctx.bodyAsClass(ServerActionRequest.class);
        if (req == null || req.action == null) {
            ctx.status(400).result("Missing server action");
            return;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("action", req.action);

        switch (req.action.toLowerCase()) {
            case "gc":
                long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.gc();
                long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long freedMB = Math.max(0, (before - after) / (1024 * 1024));
                appendManualLog("INFO", "Garbage collection executed. Reclaimed ~" + freedMB + " MB heap.");
                res.put("freedMB", freedMB);
                break;
            case "broadcast":
                if (req.value != null && !req.value.trim().isEmpty()) {
                    final String msg = req.value.trim();
                    if (plugin != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Bukkit.broadcastMessage("§b[Announcement] §f" + msg);
                            appendManualLog("INFO", "Broadcasted: " + msg);
                        });
                    }
                }
                break;
            case "time":
                if (plugin != null && req.value != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (World w : Bukkit.getWorlds()) {
                            switch (req.value.toLowerCase()) {
                                case "day": w.setTime(1000); break;
                                case "noon": w.setTime(6000); break;
                                case "night": w.setTime(13000); break;
                                case "midnight": w.setTime(18000); break;
                            }
                        }
                        appendManualLog("INFO", "Synchronized world time to: " + req.value);
                    });
                }
                break;
            case "weather":
                if (plugin != null && req.value != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
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
                        appendManualLog("INFO", "Synchronized world weather to: " + req.value);
                    });
                }
                break;
        }

        ctx.json(res);
    }


    public void executeCommand(Context ctx) {
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
        appendManualLog("CMD", "> /" + finalCmd);

        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Command execution error: " + t.getMessage());
                }
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "executed");
        result.put("command", finalCmd);
        ctx.json(result);
    }

    // Java Logging Handler for console capture
    public static class ConsoleLogHandler extends Handler {
        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getMessage() == null) return;
            String level = "INFO";
            if (record.getLevel() == java.util.logging.Level.SEVERE) {
                level = "ERROR";
            } else if (record.getLevel() == java.util.logging.Level.WARNING) {
                level = "WARN";
            }

            String msg = record.getMessage();
            msg = COLOR_PATTERN.matcher(msg).replaceAll("");

            String time = new SimpleDateFormat("HH:mm:ss").format(new Date(record.getMillis()));
            addLog(new LogEntry(time, level, msg));
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
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
        appendManualLog("INFO", "Waypoint registered: " + wp.name + " (" + wp.world + " " + wp.x + ", " + wp.y + ", " + wp.z + ")");
        Map<String, Object> res = new HashMap<>();
        res.put("status", "success");
        res.put("waypoint", wp);
        ctx.json(res);
    }

    public void deleteWaypoint(Context ctx) {
        String name = ctx.queryParam("name");
        if (name != null) {
            waypoints.removeIf(w -> w.name != null && w.name.equalsIgnoreCase(name));
            appendManualLog("INFO", "Waypoint removed: " + name);
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
