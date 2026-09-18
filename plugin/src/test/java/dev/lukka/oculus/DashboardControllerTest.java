package dev.lukka.oculus;

import dev.lukka.oculus.api.ImmediateExecutor;
import dev.lukka.oculus.api.ThreadExecutor;
import dev.lukka.oculus.console.ConsoleService;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DashboardControllerTest {

    private Plugin plugin;
    private ConsoleService consoleService;
    private ThreadExecutor executor;
    private DashboardController controller;
    private Context ctx;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        consoleService = mock(ConsoleService.class);
        executor = mock(ThreadExecutor.class);
        controller = new DashboardController(plugin, consoleService, executor);
        ctx = mock(Context.class);
        when(ctx.status(anyInt())).thenReturn(ctx);
    }

    @Test
    void testGetStatsHeadlessReturnsOk() {
        // When Bukkit.getServer() is null (headless)
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            controller.getStats(ctx);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(ctx).json(captor.capture());
            Map<String, Object> stats = captor.getValue();
            assertNotNull(stats);
            assertEquals(0, stats.get("players"));
            assertEquals(0, stats.get("worldCount"));
            verify(executor, never()).supply(any());
        }
    }

    @Test
    void testGetStatsHopsToMainThread() throws Exception {
        Server server = mock(Server.class);
        when(server.getMaxPlayers()).thenReturn(50);
        when(server.getTPS()).thenReturn(new double[]{19.95, 20.0, 20.0});

        World world = mock(World.class);
        when(world.getName()).thenReturn("world_nether");
        org.bukkit.Chunk[] chunks = new org.bukkit.Chunk[12];
        when(world.getLoadedChunks()).thenReturn(chunks);
        when(world.getEntityCount()).thenReturn(45);

        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        org.bukkit.Location loc = mock(org.bukkit.Location.class);
        when(loc.getX()).thenReturn(100.0);
        when(loc.getY()).thenReturn(64.0);
        when(loc.getZ()).thenReturn(-200.0);
        when(loc.getYaw()).thenReturn(90.0f);
        when(loc.getPitch()).thenReturn(0.0f);
        when(player.getLocation()).thenReturn(loc);
        when(player.getWorld()).thenReturn(world);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkitMock.when(Bukkit::getOnlinePlayers).thenReturn((Collection) List.of(player));
            bukkitMock.when(Bukkit::getBukkitVersion).thenReturn("Paper 26.2");
            bukkitMock.when(Bukkit::getMinecraftVersion).thenReturn("26.2");

            ThreadExecutor realExecutor = new ImmediateExecutor();
            DashboardController realController = new DashboardController(plugin, consoleService, realExecutor);

            realController.getStats(ctx);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(ctx).json(captor.capture());
            Map<String, Object> stats = captor.getValue();
            assertEquals(1, stats.get("players"));
            assertEquals(1, stats.get("worldCount"));
            assertEquals(12, stats.get("loadedChunks"));
            assertEquals(45, stats.get("totalEntities"));
        }
    }

    @Test
    void testGetStatsTimeoutReturns504MainThreadTimeout() throws Exception {
        Server server = mock(Server.class);

        CompletableFuture future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("timed out"));
        when(executor.supply(any())).thenReturn(future);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            controller.getStats(ctx);

            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));
        }
    }

    @Test
    void testGetDetailedPlayersTimeoutReturns504() throws Exception {
        Server server = mock(Server.class);

        CompletableFuture future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("timed out"));
        when(executor.supply(any())).thenReturn(future);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            controller.getDetailedPlayers(ctx);

            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));
        }
    }

    @Test
    void testPostActionsHeadlessReturn503BukkitUnavailable() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            controller.postPlayerAction(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            controller.postPluginAction(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            controller.postServerAction(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            controller.executeCommand(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));
        }
    }

    @Test
    void testPostServerActionTimeoutReturns504() throws Exception {
        Server server = mock(Server.class);
        DashboardController.ServerActionRequest req = new DashboardController.ServerActionRequest();
        req.action = "gc";
        when(ctx.bodyAsClass(DashboardController.ServerActionRequest.class)).thenReturn(req);

        CompletableFuture future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("timed out"));
        when(executor.run(any())).thenReturn(future);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            controller.postServerAction(ctx);

            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));
        }
    }

    @Test
    void testExecuteCommandTimeoutReturns504() throws Exception {
        Server server = mock(Server.class);
        DashboardController.CommandRequest req = new DashboardController.CommandRequest("say hello");
        when(ctx.bodyAsClass(DashboardController.CommandRequest.class)).thenReturn(req);

        CompletableFuture future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new ExecutionException(new TimeoutException("timed out")));
        when(executor.run(any())).thenReturn(future);

        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);

            controller.executeCommand(ctx);

            verify(ctx).status(504);
            verify(ctx).json(Map.of("error", "main_thread_timeout"));
        }
    }
}
