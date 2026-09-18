package dev.lukka.oculus.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// Optional import for spark, we should check availability at runtime
// import me.lucko.spark.api.SparkProvider;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class TelemetryService implements Listener {
    private final Plugin plugin;
    private TelemetryWebSocket ws;
    private final ObjectMapper mapper = new ObjectMapper();
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final MemoryMXBean memBean;
    private boolean sparkAvailable = false;

    // KPIs
    public final AtomicInteger dau = new AtomicInteger(0);
    public final AtomicInteger mau = new AtomicInteger(0);
    public final AtomicInteger deaths = new AtomicInteger(0);

    public TelemetryService(Plugin plugin) {
        this.plugin = plugin;
        this.osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.memBean = ManagementFactory.getMemoryMXBean();
        
        try {
            Class.forName("me.lucko.spark.api.SparkProvider");
            sparkAvailable = true;
        } catch (ClassNotFoundException e) {
            sparkAvailable = false;
        }
    }

    public void setWebSocket(TelemetryWebSocket ws) {
        this.ws = ws;
    }

    public void start() {
        if (Bukkit.getServer() == null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::collectAndBroadcast, 20L, 40L); // every 2 seconds
    }

    private void collectAndBroadcast() {
        if (ws == null) return;
        
        Map<String, Object> data = new HashMap<>();
        data.put("type", "telemetry");
        
        // Memory
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();
        data.put("heapUsed", heapUsage.getUsed());
        data.put("heapMax", heapUsage.getMax());
        data.put("nonHeapUsed", nonHeapUsage.getUsed());
        
        // JVM CPU Load
        double cpuLoad = osBean.getCpuLoad();
        if (cpuLoad < 0) cpuLoad = 0;
        data.put("cpuLoad", cpuLoad);

        // TPS
        double[] tps = getTps();
        data.put("tps", tps);

        // Spark data if available
        if (sparkAvailable) {
            // (Spark integration omitted due to API version inconsistencies)
        }

        // Main thread data (Worlds, entities, chunks)
        Bukkit.getScheduler().runTask(plugin, () -> {
            int loadedChunks = 0;
            int totalEntities = 0;
            for (World world : Bukkit.getWorlds()) {
                loadedChunks += world.getLoadedChunks().length;
                totalEntities += world.getEntities().size();
            }
            data.put("loadedChunks", loadedChunks);
            data.put("totalEntities", totalEntities);
            
            // KPIs
            data.put("dau", dau.get());
            data.put("mau", mau.get());
            data.put("deaths", deaths.get());
            
            // Add current online players
            data.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
            data.put("maxPlayers", Bukkit.getMaxPlayers());

            try {
                String json = mapper.writeValueAsString(data);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> ws.broadcast(json));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private double[] getTps() {
        try {
            return Bukkit.getTPS();
        } catch (NoSuchMethodError | Exception e) {
            return new double[]{20.0, 20.0, 20.0}; // Fallback for non-Paper servers
        }
    }
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        dau.incrementAndGet(); // Note: This is session based, real DAU would check unique users per day
        mau.incrementAndGet();
    }
    
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        deaths.incrementAndGet();
    }
}
