package dev.lukka.oculus.audit;

import dev.lukka.oculus.managers.DatabaseManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AuditServiceTest {

    private static File tempDir;
    private static Plugin mockPlugin;

    @BeforeAll
    public static void setup() throws Exception {
        tempDir = Files.createTempDirectory("oculus-audit-test").toFile();
        tempDir.deleteOnExit();

        mockPlugin = Mockito.mock(Plugin.class);
        Mockito.when(mockPlugin.getDataFolder()).thenReturn(tempDir);

        java.lang.reflect.Field instanceField = DatabaseManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
        DatabaseManager.initialize(mockPlugin);

        try (Connection conn = DatabaseManager.getInstance().getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM audit_head");
        }
    }

    @Test
    public void testAuditServiceInitializationAndUtf8Logging() throws Exception {
        AuditService service = new AuditService(mockPlugin);

        // Log events with diverse non-ASCII UTF-8 characters
        String actor = "管理员_Admin_🚀";
        String action = "user_create_ユーザー";
        Map<String, Object> details = Map.of("note", "Café München ñ ö ü — 日本語");

        service.logEvent(action, actor, "127.0.0.1", details);
        service.logEvent("login", "user_2", "192.168.1.10", Map.of("status", "success"));

        List<String> lines = service.readAuditLines();
        assertEquals(2, lines.size());

        String firstLine = lines.get(0);
        assertTrue(firstLine.contains("管理员_Admin_🚀"));
        assertTrue(firstLine.contains("user_create_ユーザー"));
        assertTrue(firstLine.contains("Café München ñ ö ü — 日本語"));

        String secondLine = lines.get(1);
        assertTrue(secondLine.contains("user_2"));
    }
}
