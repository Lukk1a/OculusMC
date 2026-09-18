package dev.lukka.oculus.bootstrap;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClientIpResolver implements Handler {

    private static volatile List<String> defaultTrustedProxies = null;
    private final List<CidrMatcher> matchers;

    public static void setDefaultTrustedProxies(List<String> proxies) {
        defaultTrustedProxies = proxies;
    }

    public ClientIpResolver() {
        this(loadConfiguredTrustedProxies());
    }

    public ClientIpResolver(Plugin plugin) {
        this(plugin != null && plugin.getConfig() != null
                ? plugin.getConfig().getStringList("http.trusted-proxies")
                : Collections.emptyList());
    }

    public ClientIpResolver(List<String> trustedProxies) {
        List<CidrMatcher> list = new ArrayList<>();
        if (trustedProxies != null) {
            for (String cidr : trustedProxies) {
                if (cidr == null || cidr.isBlank()) continue;
                try {
                    list.add(new CidrMatcher(cidr.trim()));
                } catch (Exception ignored) {}
            }
        }
        this.matchers = Collections.unmodifiableList(list);
    }

    public boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isBlank() || matchers.isEmpty()) {
            return false;
        }
        for (CidrMatcher matcher : matchers) {
            if (matcher.matches(ip)) {
                return true;
            }
        }
        return false;
    }

    public String resolve(Context ctx) {
        String peerIp;
        try {
            peerIp = ctx.ip();
        } catch (Throwable t) {
            peerIp = ctx.req().getRemoteAddr();
        }
        if (peerIp == null || peerIp.isBlank()) {
            peerIp = ctx.req().getRemoteAddr();
        }
        if (peerIp == null || peerIp.isBlank()) {
            peerIp = "127.0.0.1";
        }

        // Do NOT unconditionally trust X-Forwarded-For.
        // Only inspect and parse X-Forwarded-For if the direct peer IP is in the configured trusted proxy list.
        if (!isTrustedProxy(peerIp)) {
            return peerIp;
        }

        String forwarded = ctx.header("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            if (forwarded.contains(",")) {
                return forwarded.split(",")[0].trim();
            }
            return forwarded.trim();
        }

        String realIp = ctx.header("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return peerIp;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        String ip = resolve(ctx);
        ctx.attribute("client-ip", ip);
    }

    private static List<String> loadConfiguredTrustedProxies() {
        if (defaultTrustedProxies != null) {
            return defaultTrustedProxies;
        }

        try {
            if (Bukkit.getServer() != null && Bukkit.getPluginManager() != null) {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("Oculus");
                if (plugin != null && plugin.getConfig() != null) {
                    List<String> list = plugin.getConfig().getStringList("http.trusted-proxies");
                    if (list != null && !list.isEmpty()) {
                        return list;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            dev.lukka.oculus.managers.DatabaseManager dm = dev.lukka.oculus.managers.DatabaseManager.getInstance();
            if (dm != null) {
                Field f = dev.lukka.oculus.managers.DatabaseManager.class.getDeclaredField("plugin");
                f.setAccessible(true);
                Plugin plugin = (Plugin) f.get(dm);
                if (plugin != null && plugin.getConfig() != null) {
                    List<String> list = plugin.getConfig().getStringList("http.trusted-proxies");
                    if (list != null && !list.isEmpty()) {
                        return list;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return Collections.emptyList();
    }
}
