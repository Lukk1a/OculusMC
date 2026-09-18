package dev.lukka.oculus.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.bukkit.Bukkit;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileJailTest {

    private static File mockRoot;

    @BeforeAll
    public static void setup() {
        mockRoot = new File(System.getProperty("user.dir"), "mock_server_root");
        mockRoot.mkdirs();
    }

    @Test
    public void testValidPaths() {
        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            File resolved = FileJail.resolve("logs/latest.log");
            assertTrue(resolved.getAbsolutePath().contains("logs" + File.separator + "latest.log"));
            
            File resolved2 = FileJail.resolve("/server.properties");
            assertTrue(resolved2.getAbsolutePath().endsWith("server.properties"));
        }
    }

    @Test
    public void testPathEscape() {
        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("../outside.txt"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("logs/../../etc/passwd"));
        }
    }

    @Test
    public void testSensitiveFiles() {
        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("session.lock"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("world/session.lock"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("world_nether/session.lock"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("sub/world/session.lock"));

            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/Oculus/jwt.key"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/oculus/jwt.key"));

            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/Oculus/oculus.db"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/Oculus/oculus.db-wal"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/Oculus/oculus.db-shm"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("oculus.db"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("oculus.db-wal"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("oculus.db-shm"));

            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("plugins/Oculus/audit.jsonl"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("audit.jsonl"));
            assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("sub/audit.jsonl"));
        }
    }

    @Test
    public void testSymlinkEscape() {
        try (MockedStatic<Bukkit> bukkitMock = Mockito.mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            File outsideDir = new File(mockRoot.getParentFile(), "jail_outside_dir");
            outsideDir.mkdirs();
            File outsideSecret = new File(outsideDir, "secret.txt");
            try {
                java.nio.file.Files.writeString(outsideSecret.toPath(), "top-secret");
            } catch (Exception ignored) {}

            File symlinkFile = new File(mockRoot, "symlink_secret");
            try {
                java.nio.file.Files.deleteIfExists(symlinkFile.toPath());
                java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), outsideSecret.toPath());
                assertThrows(IllegalArgumentException.class, () -> FileJail.resolve("symlink_secret"));
            } catch (java.nio.file.FileSystemException e) {
                // Windows without Developer Mode / admin privileges may restrict symlink creation
            } catch (Exception e) {
                // Ignore unexpected IO errors in test environment
            } finally {
                try {
                    java.nio.file.Files.deleteIfExists(symlinkFile.toPath());
                    java.nio.file.Files.deleteIfExists(outsideSecret.toPath());
                    outsideDir.delete();
                } catch (Exception ignored) {}
            }
        }
    }
}
