package dev.lukka.oculus.files;

import io.javalin.http.Context;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FilesController {

    private final org.bukkit.plugin.Plugin plugin;

    public FilesController() {
        this(null);
    }

    public FilesController(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
    }

    public void listFiles(Context ctx) {
        String dirParam = ctx.queryParam("path");
        if (dirParam == null) dirParam = "";
        
        File dir = FileJail.resolve(dirParam);
        
        if (!dir.exists() || !dir.isDirectory()) {
            ctx.status(404).json(Map.of("error", "not_found"));
            return;
        }
        
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        
        List<Map<String, Object>> result = new ArrayList<>();
        File root = Bukkit.getWorldContainer();
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        
        for (File f : files) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", f.getName());
            entry.put("isDirectory", f.isDirectory());
            entry.put("size", f.isDirectory() ? 0 : f.length());
            entry.put("lastModified", f.lastModified());
            
            // Generate relative path from root
            String rel = rootPath.relativize(f.toPath().toAbsolutePath().normalize()).toString().replace('\\', '/');
            entry.put("path", rel);
            
            result.add(entry);
        }
        
        // Sort directories first, then alphabetical
        result.sort((a, b) -> {
            boolean aDir = (Boolean) a.get("isDirectory");
            boolean bDir = (Boolean) b.get("isDirectory");
            if (aDir && !bDir) return -1;
            if (!aDir && bDir) return 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });
        
        ctx.json(result);
    }
    
    public void readFile(Context ctx) {
        String pathParam = ctx.queryParam("path");
        if (pathParam == null) {
            ctx.status(400).json(Map.of("error", "missing_path"));
            return;
        }
        
        try {
            File f = FileJail.resolve(pathParam);
            if (!f.exists() || f.isDirectory()) {
                ctx.status(404).json(Map.of("error", "not_found"));
                return;
            }
            
            ctx.contentType(Files.probeContentType(f.toPath()) != null ? Files.probeContentType(f.toPath()) : "application/octet-stream");
            ctx.result(new FileInputStream(f));
            
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error", "detail", e.getMessage()));
        }
    }
    
    public void writeFile(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }

        String pathParam = ctx.queryParam("path");
        if (pathParam == null) {
            ctx.status(400).json(Map.of("error", "missing_path"));
            return;
        }
        
        try {
            File f = FileJail.resolve(pathParam);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            
            long maxUploadBytes = 52428800L;
            if (plugin != null && plugin.getConfig() != null) {
                maxUploadBytes = plugin.getConfig().getLong("files.max-upload-bytes", 52428800L);
            }

            String contentLengthHeader = ctx.header("Content-Length");
            if (contentLengthHeader != null) {
                try {
                    long contentLength = Long.parseLong(contentLengthHeader);
                    if (contentLength > maxUploadBytes) {
                        ctx.status(413).json(Map.of("error", "payload_too_large"));
                        return;
                    }
                } catch (NumberFormatException ignored) {}
            }
            
            try (InputStream in = ctx.req().getInputStream();
                 FileOutputStream out = new FileOutputStream(f)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > maxUploadBytes) {
                        out.close();
                        f.delete();
                        ctx.status(413).json(Map.of("error", "payload_too_large"));
                        return;
                    }
                    out.write(buffer, 0, read);
                }
            }
            ctx.json(Map.of("success", true));
            
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error", "detail", e.getMessage()));
        }
    }
    
    public void deleteFile(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }

        String pathParam = ctx.queryParam("path");
        if (pathParam == null) {
            ctx.status(400).json(Map.of("error", "missing_path"));
            return;
        }
        if (pathParam.trim().isEmpty() || "/".equals(pathParam.trim()) || "\\".equals(pathParam.trim())) {
            ctx.status(400).json(Map.of("error", "cannot_delete_root"));
            return;
        }
        
        try {
            File f = FileJail.resolve(pathParam);
            if (!f.exists()) {
                ctx.status(404).json(Map.of("error", "not_found"));
                return;
            }

            try {
                if (f.toPath().toRealPath().equals(Bukkit.getWorldContainer().toPath().toRealPath())) {
                    ctx.status(400).json(Map.of("error", "cannot_delete_root"));
                    return;
                }
            } catch (Exception ignored) {}
            
            if (f.isDirectory()) {
                deleteDirectory(f);
            } else {
                f.delete();
            }
            
            ctx.json(Map.of("success", true));
            
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error", "detail", e.getMessage()));
        }
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    public void unzipFile(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }

        String pathParam = ctx.queryParam("path");
        if (pathParam == null) {
            ctx.status(400).json(Map.of("error", "missing_path"));
            return;
        }
        
        try {
            File zipFile = FileJail.resolve(pathParam);
            if (!zipFile.exists() || zipFile.isDirectory()) {
                ctx.status(404).json(Map.of("error", "not_found"));
                return;
            }
            
            File destDir = zipFile.getParentFile();
            if (destDir == null) {
                destDir = Bukkit.getWorldContainer();
            }
            long maxUploadBytes = 52428800L;
            if (plugin != null && plugin.getConfig() != null) {
                maxUploadBytes = plugin.getConfig().getLong("files.max-upload-bytes", 52428800L);
            }
            long maxUncompressedBytes = maxUploadBytes * 2;
            int maxEntries = 10000;
            double maxRatio = 100.0;

            int entryCount = 0;
            long totalBytes = 0;
            long compressedSize = Math.max(1, zipFile.length());
            
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile))) {
                java.util.zip.ZipEntry zipEntry = zis.getNextEntry();
                while (zipEntry != null) {
                    entryCount++;
                    if (entryCount > maxEntries) {
                        throw new IllegalArgumentException("zip_bomb_detected");
                    }

                    File newFile = new File(destDir, zipEntry.getName());
                    
                    String destDirPath = destDir.getCanonicalPath();
                    String destFilePath = newFile.getCanonicalPath();
                    if (!destFilePath.startsWith(destDirPath + File.separator) && !destFilePath.equals(destDirPath)) {
                        throw new IllegalArgumentException("zip_slip");
                    }
                    
                    String relativePath = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()
                        .relativize(newFile.toPath().toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                    FileJail.resolve(relativePath);
                    
                    if (zipEntry.isDirectory()) {
                        newFile.mkdirs();
                    } else {
                        if (newFile.getParentFile() != null) {
                            newFile.getParentFile().mkdirs();
                        }
                        try (FileOutputStream fos = new FileOutputStream(newFile)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                totalBytes += len;
                                if (totalBytes > maxUncompressedBytes) {
                                    throw new IllegalArgumentException("zip_bomb_detected");
                                }
                                if (totalBytes > 10 * 1024 * 1024 && ((double) totalBytes / compressedSize) > maxRatio) {
                                    throw new IllegalArgumentException("zip_bomb_detected");
                                }
                                fos.write(buffer, 0, len);
                            }
                        }
                    }
                    zipEntry = zis.getNextEntry();
                }
                zis.closeEntry();
            }
            
            ctx.json(Map.of("success", true));
            
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "internal_error", "detail", e.getMessage()));
        }
    }
}
