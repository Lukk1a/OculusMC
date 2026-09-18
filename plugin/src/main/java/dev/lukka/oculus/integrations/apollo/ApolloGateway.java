package dev.lukka.oculus.integrations.apollo;

import org.bukkit.entity.Player;

public interface ApolloGateway {
    void sendWaypoint(Player player, WaypointRequest request);
    void removeWaypoint(Player player, String name);
    void sendTitle(Player player, String title, String subtitle);
    void setXRay(Player player, boolean enable);
    boolean hasWaypoint();
    boolean hasTitle();
    boolean hasXRay();
}
