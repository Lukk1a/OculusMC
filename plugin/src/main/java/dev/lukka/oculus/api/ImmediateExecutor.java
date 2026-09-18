package dev.lukka.oculus.api;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Test {@link ThreadExecutor} — executes work immediately on the calling thread.
 * Safe when {@code Bukkit.getServer()} is {@code null}.
 */
public class ImmediateExecutor implements ThreadExecutor {

    @Override
    public <T> CompletableFuture<T> supply(Supplier<T> action) {
        try {
            return CompletableFuture.completedFuture(action.get());
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public CompletableFuture<Void> run(Runnable action) {
        try {
            action.run();
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
