package dev.lukka.oculus.files;

import io.javalin.http.Context;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class FilesControllerTest {

    private static File mockRoot;
    private Plugin plugin;
    private FileConfiguration config;
    private FilesController controller;
    private Context ctx;

    @BeforeAll
    static void initRoot() throws IOException {
        mockRoot = Files.createTempDirectory("oculus-files-test").toFile();
        mockRoot.deleteOnExit();
    }

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong(eq("files.max-upload-bytes"), anyLong())).thenReturn(1024L * 1024L); // 1 MB limit for test
        controller = new FilesController(plugin);
        ctx = mock(Context.class);
        when(ctx.status(anyInt())).thenReturn(ctx);
    }

    @Test
    void testMutatingEndpointsReturn503WhenBukkitUnavailable() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            when(ctx.queryParam("path")).thenReturn("test.txt");

            controller.writeFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("test.txt");

            controller.deleteFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("test.zip");

            controller.unzipFile(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));
        }
    }

    @Test
    void testWriteFileRejectsPayloadTooLargeByHeader() {
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            when(ctx.queryParam("path")).thenReturn("large.dat");
            when(ctx.header("Content-Length")).thenReturn("2097152"); // 2 MB > 1 MB

            controller.writeFile(ctx);

            verify(ctx).status(413);
            verify(ctx).json(Map.of("error", "payload_too_large"));
        }
    }

    @Test
    void testWriteFileRejectsPayloadTooLargeByStream() throws Exception {
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            when(ctx.queryParam("path")).thenReturn("stream_large.dat");
            when(ctx.header("Content-Length")).thenReturn(null);

            byte[] bigData = new byte[1024 * 1024 + 100];
            ByteArrayInputStream bais = new ByteArrayInputStream(bigData);
            HttpServletRequest req = mock(HttpServletRequest.class);
            ServletInputStream sis = new DelegatingServletInputStream(bais);
            when(req.getInputStream()).thenReturn(sis);
            when(ctx.req()).thenReturn(req);

            controller.writeFile(ctx);

            verify(ctx).status(413);
            verify(ctx).json(Map.of("error", "payload_too_large"));
            assertFalse(new File(mockRoot, "stream_large.dat").exists());
        }
    }

    @Test
    void testDeleteFileCannotDeleteRoot() {
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            when(ctx.queryParam("path")).thenReturn("");
            controller.deleteFile(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "cannot_delete_root"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.queryParam("path")).thenReturn("/");
            controller.deleteFile(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "cannot_delete_root"));
        }
    }

    @Test
    void testUnzipFileRejectsZipBomb() throws Exception {
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorldContainer).thenReturn(mockRoot);

            File zipFile = new File(mockRoot, "bomb.zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                // Write an entry with large uncompressed content exceeding limit (maxUncompressedBytes = 2MB)
                ZipEntry entry = new ZipEntry("bomb.txt");
                zos.putNextEntry(entry);
                byte[] chunk = new byte[8192];
                for (int i = 0; i < 300; i++) { // ~2.4MB > 2MB
                    zos.write(chunk);
                }
                zos.closeEntry();
            }

            when(ctx.queryParam("path")).thenReturn("bomb.zip");

            controller.unzipFile(ctx);

            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "zip_bomb_detected"));
            zipFile.delete();
        }
    }

    private static class DelegatingServletInputStream extends ServletInputStream {
        private final InputStream source;

        public DelegatingServletInputStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            return source.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return source.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            try {
                return source.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
        }
    }
}
