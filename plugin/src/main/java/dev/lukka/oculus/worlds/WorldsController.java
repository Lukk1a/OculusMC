package dev.lukka.oculus.worlds;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lukka.oculus.api.ThreadExecutor;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.GameRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WorldsController {

    private final ThreadExecutor executor;

    public WorldsController(ThreadExecutor executor) {
        this.executor = executor;
    }

    public void getWorlds(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }

        try {
            List<Map<String, Object>> result = executor.supply(() -> {
                List<Map<String, Object>> worlds = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", w.getName());
                    map.put("time", w.getTime());
                    map.put("fullTime", w.getFullTime());
                    map.put("hasStorm", w.hasStorm());
                    map.put("isThundering", w.isThundering());
                    map.put("difficulty", w.getDifficulty().name());
                    
                    if (w.getWorldBorder() != null) {
                        map.put("borderSize", w.getWorldBorder().getSize());
                        map.put("borderCenter", Map.of(
                            "x", w.getWorldBorder().getCenter().getX(),
                            "z", w.getWorldBorder().getCenter().getZ()
                        ));
                    }
                    
                    Map<String, String> rules = new HashMap<>();
                    for (GameRule<?> rule : GameRule.values()) {
                        try {
                            Object val = w.getGameRuleValue(rule);
                            if (val != null) rules.put(rule.getName(), val.toString());
                        } catch (IllegalArgumentException ignored) {
                            // Some experimental game rules (e.g. max_minecart_speed) are in the enum
                            // but throw when accessed if experimental features are disabled.
                        }
                    }
                    map.put("gameRules", rules);
                    
                    worlds.add(map);
                }
                return worlds;
            }).get(5, TimeUnit.SECONDS);

            ctx.json(result);
        } catch (TimeoutException e) {
            ctx.status(504).json(Map.of("error", "main_thread_timeout"));
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).json(Map.of("error", "internal_error: " + e.getMessage(), "detail", String.valueOf(e.getMessage())));
        }
    }

    public void updateWorld(Context ctx) {
        if (Bukkit.getServer() == null) {
            ctx.status(503).json(Map.of("error", "bukkit_unavailable"));
            return;
        }

        String worldName = ctx.pathParam("name");
        JsonNode body;
        try {
            body = ctx.bodyAsClass(JsonNode.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid_body"));
            return;
        }

        try {
            Boolean success = executor.supply(() -> {
                World w = Bukkit.getWorld(worldName);
                if (w == null) return false;

                if (body.has("time")) {
                    w.setTime(body.get("time").asLong());
                }
                
                if (body.has("storm")) {
                    w.setStorm(body.get("storm").asBoolean());
                }
                
                if (body.has("thunder")) {
                    w.setThundering(body.get("thunder").asBoolean());
                }
                
                if (body.has("borderSize")) {
                    w.getWorldBorder().setSize(body.get("borderSize").asDouble());
                }
                
                if (body.has("gameRules")) {
                    JsonNode rules = body.get("gameRules");
                    rules.fieldNames().forEachRemaining(ruleName -> {
                        GameRule<?> rule = GameRule.getByName(ruleName);
                        if (rule != null) {
                            String val = rules.get(ruleName).asText();
                            if (rule.getType() == Boolean.class) {
                                w.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(val));
                            } else if (rule.getType() == Integer.class) {
                                try {
                                    w.setGameRule((GameRule<Integer>) rule, Integer.parseInt(val));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    });
                }
                
                return true;
            }).get(5, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(success)) {
                ctx.status(404).json(Map.of("error", "world_not_found"));
                return;
            }

            ctx.json(Map.of("success", true));
        } catch (TimeoutException e) {
            ctx.status(504).json(Map.of("error", "main_thread_timeout"));
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).json(Map.of("error", "internal_error: " + e.getMessage(), "detail", String.valueOf(e.getMessage())));
        }
    }
}
