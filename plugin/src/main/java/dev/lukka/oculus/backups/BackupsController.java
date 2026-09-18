package dev.lukka.oculus.backups;

import io.javalin.http.Context;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BackupsController {

    private final BackupService backupService;

    public BackupsController(BackupService backupService) {
        this.backupService = backupService;
    }

    public void getBackups(Context ctx) {
        File dir = backupService.getBackupDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".zip"));
        if (files == null) files = new File[0];

        List<Map<String, Object>> result = new ArrayList<>();
        for (File f : files) {
            Map<String, Object> map = new HashMap<>();
            map.put("name", f.getName());
            map.put("size", f.length());
            map.put("lastModified", f.lastModified());
            result.add(map);
        }

        result.sort((a, b) -> Long.compare((Long) b.get("lastModified"), (Long) a.get("lastModified")));
        ctx.json(result);
    }

    public void createBackup(Context ctx) {
        backupService.createBackup().thenAccept(success -> {
            // We just kick it off, or we could wait. 
            // The prompt asks to zip asynchronously.
            // So we return 202 immediately.
        });
        ctx.status(202).json(Map.of("status", "started"));
    }

    public void downloadBackup(Context ctx) {
        String filename = ctx.pathParam("file");
        if (!filename.endsWith(".zip") || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            ctx.status(400).json(Map.of("error", "invalid_file"));
            return;
        }

        File f = new File(backupService.getBackupDir(), filename);
        if (!f.exists()) {
            ctx.status(404).json(Map.of("error", "not_found"));
            return;
        }

        try {
            ctx.contentType("application/zip");
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            ctx.result(new FileInputStream(f));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error", "detail", e.getMessage()));
        }
    }
}
