package dev.lukka.oculus.files;

import org.bukkit.Bukkit;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileJail {

    public static File resolve(String requestedPath) {
        if (requestedPath == null) {
            throw new IllegalArgumentException("path_escape");
        }
        
        File root = Bukkit.getWorldContainer();
        if (root == null) {
            throw new IllegalArgumentException("path_escape");
        }

        Path rootPath;
        try {
            rootPath = root.toPath().toRealPath();
        } catch (Exception e) {
            rootPath = root.toPath().toAbsolutePath().normalize();
        }
        
        // Strip leading slashes so it's always relative to root
        String cleanPath = requestedPath;
        while (cleanPath.startsWith("/") || cleanPath.startsWith("\\")) {
            cleanPath = cleanPath.substring(1);
        }
        
        Path combined = rootPath.resolve(cleanPath).normalize();
        
        // Lexical check
        if (!combined.startsWith(rootPath)) {
            throw new IllegalArgumentException("path_escape");
        }

        // Canonical real path check (resolving symlinks)
        Path resolvedPath;
        if (Files.exists(combined, LinkOption.NOFOLLOW_LINKS)) {
            try {
                resolvedPath = combined.toRealPath();
            } catch (IOException e) {
                if (Files.isSymbolicLink(combined)) {
                    try {
                        Path target = Files.readSymbolicLink(combined);
                        if (!target.isAbsolute()) {
                            target = combined.getParent().resolve(target);
                        }
                        target = target.toAbsolutePath().normalize();
                        if (!target.startsWith(rootPath)) {
                            throw new IllegalArgumentException("path_escape");
                        }
                    } catch (IOException ignored) {}
                }
                resolvedPath = combined;
            }
        } else {
            // For non-existent files: find the existing ancestor and verify its real path
            Path existing = combined.getParent();
            List<Path> nonExistent = new ArrayList<>();
            nonExistent.add(combined.getFileName());

            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && !existing.equals(rootPath)) {
                nonExistent.add(existing.getFileName());
                existing = existing.getParent();
            }

            Path realParent;
            if (existing != null && Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    realParent = existing.toRealPath();
                } catch (IOException e) {
                    if (Files.isSymbolicLink(existing)) {
                        try {
                            Path target = Files.readSymbolicLink(existing);
                            if (!target.isAbsolute()) {
                                target = existing.getParent().resolve(target);
                            }
                            target = target.toAbsolutePath().normalize();
                            if (!target.startsWith(rootPath)) {
                                throw new IllegalArgumentException("path_escape");
                            }
                        } catch (IOException ignored) {}
                    }
                    realParent = existing.toAbsolutePath().normalize();
                }
            } else {
                realParent = rootPath;
            }

            if (!realParent.startsWith(rootPath)) {
                throw new IllegalArgumentException("path_escape");
            }

            Path candidate = realParent;
            for (int i = nonExistent.size() - 1; i >= 0; i--) {
                if (nonExistent.get(i) != null) {
                    candidate = candidate.resolve(nonExistent.get(i));
                }
            }
            resolvedPath = candidate.normalize();
        }

        // Jail check against real resolved path
        if (!resolvedPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("path_escape");
        }
        
        // Security checks against sensitive files on both paths
        checkSensitive(rootPath, combined);
        checkSensitive(rootPath, resolvedPath);
        
        return resolvedPath.toFile();
    }

    private static void checkSensitive(Path rootPath, Path path) {
        String relativeString = rootPath.relativize(path).toString().replace('\\', '/');
        String relativeLower = relativeString.toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";

        // Any session.lock (root or sub-world)
        if (fileName.equals("session.lock") || relativeLower.equals("session.lock") || relativeLower.endsWith("/session.lock")) {
            throw new IllegalArgumentException("path_escape");
        }

        // JWT key
        if (relativeLower.equals("plugins/oculus/jwt.key") || relativeLower.endsWith("/plugins/oculus/jwt.key")) {
            throw new IllegalArgumentException("path_escape");
        }

        // Oculus DB and SQLite artifacts (wal, shm)
        if (fileName.equals("oculus.db") || relativeLower.equals("plugins/oculus/oculus.db") || relativeLower.endsWith("/plugins/oculus/oculus.db") || relativeLower.equals("oculus.db")) {
            throw new IllegalArgumentException("path_escape");
        }
        if (fileName.equals("oculus.db-wal") || relativeLower.equals("plugins/oculus/oculus.db-wal") || relativeLower.endsWith("/plugins/oculus/oculus.db-wal") || relativeLower.equals("oculus.db-wal")) {
            throw new IllegalArgumentException("path_escape");
        }
        if (fileName.equals("oculus.db-shm") || relativeLower.equals("plugins/oculus/oculus.db-shm") || relativeLower.endsWith("/plugins/oculus/oculus.db-shm") || relativeLower.equals("oculus.db-shm")) {
            throw new IllegalArgumentException("path_escape");
        }

        // Audit log
        if (fileName.equals("audit.jsonl") || relativeLower.equals("plugins/oculus/audit.jsonl") || relativeLower.endsWith("/plugins/oculus/audit.jsonl") || relativeLower.equals("audit.jsonl")) {
            throw new IllegalArgumentException("path_escape");
        }
    }
}
