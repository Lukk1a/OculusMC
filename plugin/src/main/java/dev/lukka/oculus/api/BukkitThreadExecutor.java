package dev.lukka.oculus.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Production {@link ThreadExecutor} — dispatches work via the Bukkit scheduler.
 */
public class BukkitThreadExecutor implements ThreadExecutor {

    private final Plugin plugin;

    public BukkitThreadExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> CompletableFuture<T> supply(Supplier<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> run(Runnable action) {
        return supply(() -> { action.run(); return null; });
    }
}
