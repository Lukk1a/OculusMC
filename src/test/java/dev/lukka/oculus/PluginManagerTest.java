package dev.lukka.oculus;

import dev.lukka.oculus.managers.PluginManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    @Test
    void testSingletonInstance() {
        PluginManager first = PluginManager.getInstance();
        PluginManager second = PluginManager.getInstance();
        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void testInitialize() {
        assertDoesNotThrow(() -> PluginManager.getInstance().initialize());
    }
}
