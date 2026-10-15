// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import dev.cadence.scene.SceneSelectionTicket;
import dev.cadence.scene.SceneVolume;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One running query and at most one replacement; caller operations never wait for completion. */
final class SelectionPrefetch<T> implements AutoCloseable {
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          0, 1, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
          task -> {
            Thread thread = new Thread(task, "Scene selection");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicLong workerNanos = new AtomicLong();
  private Ticket<T> pending;
  private long submitted, ready, missed, stale, failed;

  SceneSelectionTicket submit(
      Object request, Object identity, SpatialSectionIndex<T> index,
      SceneVolume volume, SceneVolume refinement) {
    cancel();
    var ticket = new Ticket<T>(this, request, identity);
    try {
      ticket.future = executor.submit(() -> {
        long started = System.nanoTime();
        try {
          return GeometricSelection.compute(index, volume, refinement);
        } finally {
          workerNanos.addAndGet(System.nanoTime() - started);
        }
      });
      pending = ticket;
      submitted++;
      return ticket;
    } catch (RuntimeException failure) {
      failed++;
      return null;
    }
  }

  GeometricSelection<T> consume(SceneSelectionTicket opaque, Object request, Object identity) {
    if (opaque == null) return null;
    if (!(opaque instanceof Ticket<?> ticket) || ticket.owner != this) {
      stale++;
      return null;
    }
    if (ticket.consumed || ticket != pending || ticket.request != request
        || identity == null || !Objects.equals(ticket.identity, identity)) {
      ticket.consumed = true;
      ticket.future.cancel(true);
      if (ticket == pending) pending = null;
      stale++;
      return null;
    }
    ticket.consumed = true;
    Ticket<T> current = pending;
    pending = null;
    if (!current.future.isDone()) {
      current.future.cancel(true);
      executor.getQueue().clear();
      missed++;
      return null;
    }
    try {
      // isDone guarantees this get does not wait; the Future publishes immutable arrays safely.
      GeometricSelection<T> result = current.future.get();
      ready++;
      return result;
    } catch (CancellationException cancelled) {
      stale++;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      failed++;
    } catch (ExecutionException failure) {
      failed++;
    }
    return null;
  }

  void failedPrefetch() {
    failed++;
  }

  void cancel() {
    if (pending != null) {
      pending.future.cancel(true);
      pending = null;
    }
    executor.getQueue().clear();
  }

  private void discard(Ticket<?> ticket) {
    ticket.consumed = true;
    ticket.future.cancel(true);
    if (ticket == pending) pending = null;
    if (ticket.future instanceof Runnable queued) executor.remove(queued);
  }

  Stats stats() {
    return new Stats(submitted, ready, missed, stale, failed, workerNanos.get());
  }

  /** Package-local readiness seam for deterministic CPU lifecycle tests; never waits. */
  boolean isDone(SceneSelectionTicket opaque) {
    return opaque instanceof Ticket<?> ticket && ticket.owner == this && ticket.future.isDone();
  }

  @Override
  public void close() {
    cancel();
    executor.shutdownNow();
  }

  record Stats(long submitted, long ready, long missed, long stale, long failed, long workerNanos) {}

  private static final class Ticket<T> implements SceneSelectionTicket {
    private final SelectionPrefetch<T> owner;
    private final Object request, identity;
    private Future<GeometricSelection<T>> future;
    private boolean consumed;

    private Ticket(SelectionPrefetch<T> owner, Object request, Object identity) {
      this.owner = owner;
      this.request = request;
      this.identity = identity;
    }

    @Override
    public void close() {
      owner.discard(this);
    }
  }
}
