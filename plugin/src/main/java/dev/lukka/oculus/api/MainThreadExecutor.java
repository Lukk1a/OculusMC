package dev.lukka.oculus.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class MainThreadExecutor {
    private final Plugin plugin;

    public MainThreadExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Void> run(Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    public <T> CompletableFuture<T> supply(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
