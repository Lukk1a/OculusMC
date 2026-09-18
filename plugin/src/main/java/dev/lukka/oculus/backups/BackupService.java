package dev.lukka.oculus.backups;

import dev.lukka.oculus.api.ThreadExecutor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;

public class BackupService {

    private final Plugin plugin;
    private final ThreadExecutor executor;
    private final File backupDir;
    private final int retainCount;

    public BackupService(Plugin plugin, ThreadExecutor executor) {
        this.plugin = plugin;
        this.executor = executor;
        
        String dirName = plugin.getConfig().getString("backups.dir", "backups");
        File tempDir = new File(Bukkit.getWorldContainer(), dirName);
        try {
            Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            Path backupPath = tempDir.toPath().toAbsolutePath().normalize();
            if (!backupPath.startsWith(root)) {
                tempDir = new File(Bukkit.getWorldContainer(), "backups");
            }
        } catch (Exception e) {
            tempDir = new File(Bukkit.getWorldContainer(), "backups");
        }
        this.backupDir = tempDir;
        this.retainCount = plugin.getConfig().getInt("backups.retain", 7);
    }

    public CompletableFuture<Boolean> createBackup() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Bukkit.getServer() != null) {
                    executor.supply(() -> {
                        Bukkit.getServer().savePlayers();
                        for (org.bukkit.World w : Bukkit.getWorlds()) {
                            w.save();
                        }
                        return true;
                    }).get(15, TimeUnit.SECONDS); // save-all can be slow
                }

                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }

                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
                File zipFile = new File(backupDir, "backup_" + timestamp + ".zip");
                File rootDir = Bukkit.getWorldContainer().getAbsoluteFile().toPath().normalize().toFile();
                
                try (FileOutputStream fos = new FileOutputStream(zipFile);
                     ZipOutputStream zos = new ZipOutputStream(fos)) {
                    zipFolder(rootDir, rootDir, zos);
                }
                
                enforceRetention();
                return true;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create backup", e);
                return false;
            }
        });
    }

    private void zipFolder(File rootDir, File currentDir, ZipOutputStream zos) throws Exception {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        
        for (File f : files) {
            String relative = rootDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
            
            if (f.isDirectory()) {
                if (relative.equalsIgnoreCase(backupDir.getName())) continue; // Skip backups dir
                
                ZipEntry entry = new ZipEntry(relative + "/");
                zos.putNextEntry(entry);
                zos.closeEntry();
                zipFolder(rootDir, f, zos);
            } else {
                if (relative.equalsIgnoreCase("session.lock")) continue;
                
                ZipEntry entry = new ZipEntry(relative);
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buffer = new byte[1024 * 64];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private void enforceRetention() {
        File[] backups = backupDir.listFiles((dir, name) -> name.endsWith(".zip"));
        if (backups == null || backups.length <= retainCount) return;

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
        
        int toDelete = backups.length - retainCount;
        for (int i = 0; i < toDelete; i++) {
            backups[i].delete();
        }
    }
    
    public File getBackupDir() {
        return backupDir;
    }
}
