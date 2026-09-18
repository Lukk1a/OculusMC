package dev.lukka.oculus.players;

import dev.lukka.oculus.api.ThreadExecutor;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PlayersController {
    
    private final ThreadExecutor executor;
    private final org.bukkit.plugin.Plugin plugin;

    public PlayersController(org.bukkit.plugin.Plugin plugin, ThreadExecutor executor) {
        this.plugin = plugin;
        this.executor = executor;
    }

    public void getInventory(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.future(() -> executor.supply(() -> {
            Player player = Bukkit.getServer() != null ? Bukkit.getPlayer(name) : null;
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            Map<String, Object> response = new HashMap<>();
            response.put("inventory", serializeInventory(player.getInventory()));
            response.put("enderChest", serializeInventory(player.getEnderChest()));
            return response;
        }).thenAccept(ctx::json));
    }

    private Map<Integer, Map<String, Object>> serializeInventory(Inventory inv) {
        Map<Integer, Map<String, Object>> map = new HashMap<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("type", item.getType().name());
                itemData.put("amount", item.getAmount());
                map.put(i, itemData);
            }
        }
        return map;
    }

    public void editSlot(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        String name = ctx.pathParam("name");
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }
        if (body == null || !body.containsKey("slot") || !body.containsKey("action")) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }

        String target = (String) body.get("target"); // "inventory" or "enderchest"
        int slot = ((Number) body.get("slot")).intValue();
        String action = (String) body.get("action"); // "clear", "set"
        
        ctx.future(() -> executor.run(() -> {
            Player player = Bukkit.getPlayer(name);
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            Inventory inv = "enderchest".equalsIgnoreCase(target) ? player.getEnderChest() : player.getInventory();
            
            if ("clear".equalsIgnoreCase(action)) {
                inv.setItem(slot, null);
            } else if ("set".equalsIgnoreCase(action)) {
                String type = (String) body.get("type");
                int amount = ((Number) body.getOrDefault("amount", 1)).intValue();
                inv.setItem(slot, new ItemStack(Material.valueOf(type.toUpperCase()), amount));
            }
        }).thenAccept(v -> ctx.json(Map.of("success", true))));
    }

    public void getPdc(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.future(() -> executor.supply(() -> {
            Player player = Bukkit.getServer() != null ? Bukkit.getPlayer(name) : null;
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            PersistentDataContainer pdc = player.getPersistentDataContainer();
            Map<String, String> data = new HashMap<>();
            for (NamespacedKey key : pdc.getKeys()) {
                if (pdc.has(key, PersistentDataType.STRING)) {
                    data.put(key.toString(), pdc.get(key, PersistentDataType.STRING));
                }
            }
            return data;
        }).thenAccept(ctx::json));
    }

    public void writePdc(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        String name = ctx.pathParam("name");
        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }
        if (body == null || !body.containsKey("key") || !body.containsKey("value")) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }
        String keyStr = body.get("key");
        String value = body.get("value");
        
        ctx.future(() -> executor.run(() -> {
            Player player = Bukkit.getPlayer(name);
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            String[] parts = keyStr.split(":");
            NamespacedKey key = parts.length == 2 ? new NamespacedKey(parts[0], parts[1]) : new NamespacedKey(plugin, keyStr);
            player.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }).thenAccept(v -> ctx.json(Map.of("success", true))));
    }

    public void deletePdc(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }
        String name = ctx.pathParam("name");
        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }
        if (body == null || !body.containsKey("key")) {
            ctx.status(400).json(Map.of("error", "bad_request"));
            return;
        }
        String keyStr = body.get("key");
        
        ctx.future(() -> executor.run(() -> {
            Player player = Bukkit.getPlayer(name);
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            String[] parts = keyStr.split(":");
            NamespacedKey key = parts.length == 2 ? new NamespacedKey(parts[0], parts[1]) : new NamespacedKey(plugin, keyStr);
            player.getPersistentDataContainer().remove(key);
        }).thenAccept(v -> ctx.json(Map.of("success", true))));
    }

    public void exportGuiYaml(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.future(() -> executor.supply(() -> {
            Player player = Bukkit.getServer() != null ? Bukkit.getPlayer(name) : null;
            if (player == null) throw new NotFoundResponse("Player not found online");
            
            Map<String, Object> layout = new HashMap<>();
            Map<Integer, Map<String, Object>> items = serializeInventory(player.getInventory());
            layout.put("layout", items);
            
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            Yaml yaml = new Yaml(options);
            return yaml.dump(layout);
        }).thenAccept(yamlStr -> {
            ctx.contentType("text/yaml");
            ctx.result((String) yamlStr);
        }));
    }
}

