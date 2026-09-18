package dev.lukka.oculus.scheduler;

import dev.lukka.oculus.backups.BackupService;
import org.bukkit.plugin.Plugin;

import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SchedulerService {

    private final Plugin plugin;
    private final BackupService backupService;
    private ScheduledExecutorService scheduler;
    private String backupCron;

    public SchedulerService(Plugin plugin, BackupService backupService) {
        this.plugin = plugin;
        this.backupService = backupService;
        this.backupCron = plugin.getConfig().getString("backups.cron", "0 4 * * *"); // Default 4 AM
    }

    public void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Run every minute at the top of the minute
        long initialDelay = 60 - (System.currentTimeMillis() / 1000) % 60;
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (matchesCron(backupCron, System.currentTimeMillis())) {
                    plugin.getLogger().info("Starting scheduled backup...");
                    backupService.createBackup();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in scheduler: " + e.getMessage());
            }
        }, initialDelay, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private boolean matchesCron(String cron, long timeMillis) {
        if (cron == null || cron.isEmpty() || cron.equals("none")) return false;
        
        String[] parts = cron.split("\\s+");
        if (parts.length != 5) return false;
        
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timeMillis);
        
        int minute = cal.get(Calendar.MINUTE);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1; // 1-12
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0-6 (Sun-Sat)
        
        return matchField(parts[0], minute) &&
               matchField(parts[1], hour) &&
               matchField(parts[2], dayOfMonth) &&
               matchField(parts[3], month) &&
               matchField(parts[4], dayOfWeek);
    }

    private boolean matchField(String field, int value) {
        if (field.equals("*")) return true;
        if (field.contains(",")) {
            for (String p : field.split(",")) {
                if (matchSingleField(p, value)) return true;
            }
            return false;
        }
        return matchSingleField(field, value);
    }
    
    private boolean matchSingleField(String field, int value) {
        try {
            if (field.startsWith("*/")) {
                int step = Integer.parseInt(field.substring(2));
                return value % step == 0;
            }
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
