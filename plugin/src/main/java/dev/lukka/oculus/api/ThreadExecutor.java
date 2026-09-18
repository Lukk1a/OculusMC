package dev.lukka.oculus.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Abstraction over Bukkit's main-thread scheduler.
 * Production: {@link BukkitThreadExecutor}. Tests: {@link ImmediateExecutor}.
 */
public interface ThreadExecutor {

    /**
     * Schedules {@code action} to run on the Bukkit main thread and returns a future
     * that completes with the result. If the server is unavailable, implementations
     * may complete the future exceptionally.
     */
    <T> CompletableFuture<T> supply(Supplier<T> action);

    /**
     * Schedules {@code action} to run on the Bukkit main thread and returns a future
     * that completes with {@code null} when done.
     */
    CompletableFuture<Void> run(Runnable action);
}
