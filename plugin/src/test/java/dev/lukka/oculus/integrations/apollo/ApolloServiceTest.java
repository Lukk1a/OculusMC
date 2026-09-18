package dev.lukka.oculus.integrations.apollo;

import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ApolloServiceTest {

    private Plugin plugin;
    private ApolloGateway gateway;
    private Context ctx;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        gateway = mock(ApolloGateway.class);
        ctx = mock(Context.class);
        when(ctx.status(anyInt())).thenReturn(ctx);
    }

    @Test
    void testIsAvailableReflectsGateway() {
        ApolloService unavailable = new ApolloService(plugin, null);
        assertFalse(unavailable.isAvailable());

        ApolloService available = new ApolloService(plugin, gateway);
        assertTrue(available.isAvailable());
    }

    @Test
    void testMutatingEndpointsReturn503WhenBukkitUnavailable() {
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(null);

            ApolloService service = new ApolloService(plugin, gateway);

            // createWaypoint
            service.createWaypoint(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // deleteWaypoint
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            service.deleteWaypoint(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // sendTitle
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            service.sendTitle(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));

            // setXRay
            reset(ctx);
            when(ctx.status(anyInt())).thenReturn(ctx);
            service.setXRay(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "bukkit_unavailable"));
        }
    }

    @Test
    void testValidationWhenModuleMissing() {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.plugin.PluginManager pm = mock(org.bukkit.plugin.PluginManager.class);
        when(server.getPluginManager()).thenReturn(pm);
        try (MockedStatic<Bukkit> bukkitMock = mockStatic(Bukkit.class)) {
            bukkitMock.when(Bukkit::getServer).thenReturn(server);
            bukkitMock.when(Bukkit::getPluginManager).thenReturn(pm);

            when(gateway.hasWaypoint()).thenReturn(false);
            ApolloService service = new ApolloService(plugin, gateway);

            service.createWaypoint(ctx);
            verify(ctx).status(503);
            verify(ctx).json(Map.of("error", "apollo_module_missing"));
        }
    }

    @Test
    void testThreadSafeCollections() {
        ApolloService service = new ApolloService(plugin, gateway);
        assertNotNull(service.getWaypoints());
        assertNotNull(service.getWaypointsMap());
        assertNotNull(service.getPlayerXRayStates());

        // Collections should be unmodifiable wrappers around thread-safe structures
        assertThrows(UnsupportedOperationException.class, () -> service.getWaypoints().add(new WaypointRequest()));
        assertThrows(UnsupportedOperationException.class, () -> service.getWaypointsMap().put("test", new WaypointRequest()));
        assertThrows(UnsupportedOperationException.class, () -> service.getPlayerXRayStates().put("uuid", true));
    }
}
