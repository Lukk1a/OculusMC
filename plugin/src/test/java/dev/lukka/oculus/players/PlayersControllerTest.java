package dev.lukka.oculus.players;

import dev.lukka.oculus.api.ImmediateExecutor;
import dev.lukka.oculus.api.ThreadExecutor;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class PlayersControllerTest {

    private Plugin plugin;
    private ThreadExecutor executor;
    private PlayersController controller;
    private Context ctx;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        executor = new ImmediateExecutor();
        controller = new PlayersController(plugin, executor);
        ctx = mock(Context.class);
        when(ctx.status(anyInt())).thenReturn(ctx);
    }

    @Test
    void testMutatingEndpointsReturn503WhenBukkitUnavailable() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("slot", 0, "action", "clear"));

            controller.editSlot(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("key", "test:key", "value", "val"));

            controller.writePdc(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("key", "test:key"));

            controller.deletePdc(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));
        }
    }

    @Test
    void testInvalidBodyReturns400BadRequest() {
        Server server = mock(Server.class);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(null);

            controller.editSlot(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "bad_request"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("slot", 0)); // Missing action

            controller.editSlot(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "bad_request"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("key", "test:key")); // Missing value

            controller.writePdc(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "bad_request"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            when(ctx.pathParam("name")).thenReturn("Steve");
            when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of()); // Missing key

            controller.deletePdc(ctx);
            verify(ctx).status(400);
            verify(ctx).json(Map.of("error", "bad_request"));
        }
    }
}
