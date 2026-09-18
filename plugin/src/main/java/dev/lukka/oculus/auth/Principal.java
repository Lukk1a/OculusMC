package dev.lukka.oculus.auth;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Principal {
    private final String username;
    private final String role;
    private final Set<String> nodes;

    public Principal(String username, String role, Collection<String> nodes) {
        this.username = username;
        this.role = role != null ? role : "Viewer";
        this.nodes = nodes != null ? Collections.unmodifiableSet(new HashSet<>(nodes)) : Collections.emptySet();
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public Set<String> getNodes() {
        return nodes;
    }

    public boolean hasNode(String requiredNode) {
        if (requiredNode == null || requiredNode.isEmpty()) {
            return true;
        }
        if ("Admin".equalsIgnoreCase(role)) {
            return true;
        }
        if (nodes.contains("*") || nodes.contains(requiredNode)) {
            return true;
        }
        for (String node : nodes) {
            if (node.endsWith(".*")) {
                String prefix = node.substring(0, node.length() - 2);
                if (requiredNode.equals(prefix) || requiredNode.startsWith(prefix + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Set<String> getRoleNodes(String role) {
        Set<String> nodes = new HashSet<>();
        // Viewer nodes
        nodes.add("dashboard.view");
        nodes.add("dashboard.console.read");
        nodes.add("dashboard.players.view");
        nodes.add("dashboard.apollo.read");

        if ("Viewer".equalsIgnoreCase(role)) {
            return nodes;
        }

        if ("Moderator".equalsIgnoreCase(role)) {
            nodes.add("dashboard.players.moderate");
            nodes.add("dashboard.console.execute");
            return nodes;
        }

        if ("Operator".equalsIgnoreCase(role)) {
            nodes.add("dashboard.players.moderate");
            nodes.add("dashboard.console.execute");
            nodes.add("dashboard.players.edit_inventory");
            nodes.add("dashboard.players.edit_pdc");
            nodes.add("dashboard.worlds.edit");
            nodes.add("dashboard.backups.manage");
            nodes.add("dashboard.scheduler.manage");
            nodes.add("dashboard.apollo.waypoints");
            return nodes;
        }

        if ("Admin".equalsIgnoreCase(role)) {
            nodes.add("*");
            return nodes;
        }

        return nodes;
    }
}
