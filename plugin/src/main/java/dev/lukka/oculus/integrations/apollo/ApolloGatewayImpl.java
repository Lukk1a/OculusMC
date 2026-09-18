package dev.lukka.oculus.integrations.apollo;

import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.module.waypoint.WaypointModule;
import com.lunarclient.apollo.module.waypoint.Waypoint;
import com.lunarclient.apollo.common.location.ApolloBlockLocation;
import com.lunarclient.apollo.module.title.TitleModule;
import com.lunarclient.apollo.module.title.Title;
import com.lunarclient.apollo.module.title.TitleType;
import com.lunarclient.apollo.module.staffmod.StaffModModule;
import com.lunarclient.apollo.module.staffmod.StaffMod;
import org.bukkit.entity.Player;
import java.awt.Color;
import java.util.Collections;
import net.kyori.adventure.text.Component;

public class ApolloGatewayImpl implements ApolloGateway {
    @Override
    public void sendWaypoint(Player player, WaypointRequest request) {
        WaypointModule waypointModule = Apollo.getModuleManager().getModule(WaypointModule.class);
        if (waypointModule == null) throw new UnsupportedOperationException("apollo_module_missing");
        Color color = Color.WHITE;
        try { if (request.color != null) color = Color.decode(request.color); } catch(Exception e) {}
        waypointModule.displayWaypoint(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), Waypoint.builder()
            .name(request.name)
            .location(ApolloBlockLocation.builder()
                .world(request.world)
                .x(request.x).y(request.y).z(request.z)
                .build())
            .color(color)
            .preventRemoval(false)
            .hidden(false)
            .build());
    }

    @Override
    public void removeWaypoint(Player player, String name) {
        WaypointModule waypointModule = Apollo.getModuleManager().getModule(WaypointModule.class);
        if (waypointModule == null) throw new UnsupportedOperationException("apollo_module_missing");
        waypointModule.removeWaypoint(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), name);
    }

    @Override
    public void sendTitle(Player player, String title, String subtitle) {
        TitleModule titleModule = Apollo.getModuleManager().getModule(TitleModule.class);
        if (titleModule == null) throw new UnsupportedOperationException("apollo_module_missing");
        if (title != null && !title.isEmpty()) {
            titleModule.displayTitle(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), Title.builder()
                .type(TitleType.TITLE)
                .message(Component.text(title))
                .scale(1.0f)
                .build());
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            titleModule.displayTitle(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), Title.builder()
                .type(TitleType.SUBTITLE)
                .message(Component.text(subtitle))
                .scale(1.0f)
                .build());
        }
    }

    @Override
    public void setXRay(Player player, boolean enable) {
        StaffModModule modModule = Apollo.getModuleManager().getModule(StaffModModule.class);
        if (modModule == null) throw new UnsupportedOperationException("apollo_module_missing");
        if (enable) {
            modModule.enableStaffMods(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), Collections.singletonList(StaffMod.XRAY));
        } else {
            modModule.disableStaffMods(Apollo.getPlayerManager().getPlayer(player.getUniqueId()).orElseThrow(), Collections.singletonList(StaffMod.XRAY));
        }
    }

    @Override
    public boolean hasWaypoint() {
        return Apollo.getModuleManager().getModule(WaypointModule.class) != null;
    }

    @Override
    public boolean hasTitle() {
        return Apollo.getModuleManager().getModule(TitleModule.class) != null;
    }

    @Override
    public boolean hasXRay() {
        return Apollo.getModuleManager().getModule(StaffModModule.class) != null;
    }
}

