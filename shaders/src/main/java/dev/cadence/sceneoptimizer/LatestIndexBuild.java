// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One running build and one replacement at most; publication is polled by the owning thread. */
final class LatestIndexBuild<T> implements AutoCloseable {
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          0,
          1,
          10,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          task -> {
            Thread thread = new Thread(task, "Scene spatial index");
            thread.setDaemon(true);
            return thread;
          });
  private Future<T> pending;
  private Object identity;
  private long submitted, completed, cancelled;

  void submit(Object identity, Callable<T> build) {
    cancel();
    this.identity = java.util.Objects.requireNonNull(identity);
    pending = executor.submit(build);
    submitted++;
  }

  boolean pending() {
    return pending != null;
  }

  T poll(Object currentIdentity) {
    if (!java.util.Objects.equals(identity, currentIdentity)) return null;
    if (pending == null || !pending.isDone()) return null;
    Future<T> ready = pending;
    pending = null;
    try {
      T result = ready.get();
      completed++;
      return result;
    } catch (CancellationException cancelledBuild) {
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException failure) {
      throw new IllegalStateException("Spatial index worker failed", failure.getCause());
    }
  }

  void cancel() {
    if (pending != null) {
      pending.cancel(true);
      pending = null;
      cancelled++;
    }
    executor.getQueue().clear();
    identity = null;
  }

  Stats stats() {
    return new Stats(submitted, completed, cancelled, pending());
  }

  record Stats(long submitted, long completed, long cancelled, boolean pending) {}

  @Override
  public void close() {
    cancel();
    executor.shutdownNow();
  }
}
