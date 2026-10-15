// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional sampled diagnostics. Call markers only at existing closed-render-pass boundaries. */
public final class ShaderFrameProfiler implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger("cadence_shaders");
  private static final int MAX_MARKERS = 128;
  private static final int RING_SIZE = 3;

  interface Queries extends AutoCloseable {
    void mark(int index);

    OptionalLong value(int index);

    @Override
    void close();
  }

  interface QueryFactory {
    Queries create(int count);
  }

  public record Stage(String from, String to, double cpuSubmitMs, double gpuMs) {}

  public record Sample(
      String type,
      int schemaVersion,
      long frame,
      String dimension,
      int width,
      int height,
      double cpuSubmitMs,
      double gpuMs,
      double markerCpuMs,
      int markers,
      boolean truncated,
      long skippedSamples,
      List<Stage> stages) {}

  private final int sampleEvery;
  private final LongSupplier clock;
  private final Consumer<Sample> output;
  private final Slot[] slots = new Slot[RING_SIZE];
  private QueryFactory queryFactory;
  private JsonOutput jsonOutput;
  private Slot active;
  private long frame, skippedSamples, completed;
  private double cpuSum, gpuSum;
  private boolean closed;

  /** The shader-quality property cadence.shaders.profile is deliberately unrelated. */
  public static ShaderFrameProfiler fromSystemProperties() {
    if (!Boolean.getBoolean("cadence.shaders.profiler.enabled")) return null;
    int every = Math.max(1, Integer.getInteger("cadence.shaders.profiler.sampleEvery", 30));
    Path path =
        Path.of(
            System.getProperty("cadence.shaders.profiler.output", "logs/shader-profile.jsonl"));
    JsonOutput output = new JsonOutput(path);
    ShaderFrameProfiler result = new ShaderFrameProfiler(every, null, System::nanoTime, output);
    result.jsonOutput = output;
    LOGGER.info(
        "Shader profiler enabled: every {} frames, output {}. GPU markers add ordered blit"
            + " encoders; compare performance with profiling disabled. CPU values measure render"
            + " thread submission wall time, not thread CPU usage. No query waits are used.",
        every,
        path.toAbsolutePath());
    return result;
  }

  ShaderFrameProfiler(
      int sampleEvery, QueryFactory queryFactory, LongSupplier clock, Consumer<Sample> output) {
    if (sampleEvery < 1) throw new IllegalArgumentException("Sampling interval must be positive");
    this.sampleEvery = sampleEvery;
    this.queryFactory = queryFactory;
    this.clock = clock;
    this.output = output;
  }

  public void beginFrame(GpuDevice device, String dimension, int width, int height) {
    if (closed) return;
    try {
      if (active != null) abortFrame();
      poll();
      long next = frame++;
      if (next % sampleEvery != 0) return;
      if (queryFactory == null) {
        queryFactory =
            count -> {
              var pool = device.createTimestampQueryPool(count);
              return new Queries() {
                public void mark(int index) {
                  device.createCommandEncoder().writeTimestamp(pool, index);
                }

                public OptionalLong value(int index) {
                  return pool.getValue(index);
                }

                public void close() {
                  pool.close();
                }
              };
            };
      }
      for (int i = 0; i < slots.length; i++) {
        if (slots[i] == null) slots[i] = new Slot(queryFactory.create(MAX_MARKERS));
        if (!slots[i].pending) {
          active = slots[i];
          active.reset(next, dimension, width, height);
          write("frame.begin", false);
          return;
        }
      }
      skippedSamples++;
    } catch (RuntimeException failure) {
      disable(failure);
    }
  }

  public void mark(String name) {
    if (active == null || closed) return;
    try {
      write(name, false);
    } catch (RuntimeException failure) {
      disable(failure);
    }
  }

  private void write(String name, boolean last) {
    // Reserve one slot for frame.end, preserving total-frame timing for very long pack graphs.
    if (active.labels.size() >= MAX_MARKERS - (last ? 0 : 1)) {
      active.truncated = true;
      return;
    }
    int index = active.labels.size();
    long before = clock.getAsLong();
    active.queries.mark(index);
    active.markerNanos += clock.getAsLong() - before;
    active.labels.add(name);
    active.cpuNanos.add(before);
  }

  public void endFrame() {
    if (active == null || closed) return;
    try {
      write("frame.end", true);
      active.pending = true;
      active = null;
    } catch (RuntimeException failure) {
      disable(failure);
    }
  }

  public void abortFrame() {
    if (active == null) return;
    // Keep the pool unavailable until its already-recorded GPU writes have completed.
    active.discard = true;
    active.pending = !active.labels.isEmpty();
    active = null;
    skippedSamples++;
  }

  private void poll() {
    for (Slot slot : slots) {
      if (slot == null || !slot.pending) continue;
      int count = slot.labels.size();
      if (slot.queries.value(count - 1).isEmpty()) continue;
      long[] values = new long[count];
      boolean ready = true;
      for (int i = 0; i < count; i++) {
        OptionalLong value = slot.queries.value(i);
        if (value.isEmpty()) {
          ready = false;
          break;
        }
        values[i] = value.getAsLong();
      }
      if (!ready) continue;
      slot.pending = false;
      if (slot.discard || count < 2) continue;
      List<Stage> stages = new ArrayList<>(count - 1);
      for (int i = 1; i < count; i++) {
        if (values[i] < values[i - 1])
          throw new IllegalStateException("Non-monotonic shader GPU timestamps");
        stages.add(
            new Stage(
                slot.labels.get(i - 1),
                slot.labels.get(i),
                (slot.cpuNanos.get(i) - slot.cpuNanos.get(i - 1)) / 1e6,
                (values[i] - values[i - 1]) / 1e6));
      }
      Sample sample =
          new Sample(
              "frame",
              1,
              slot.frame,
              slot.dimension,
              slot.width,
              slot.height,
              (slot.cpuNanos.getLast() - slot.cpuNanos.getFirst()) / 1e6,
              (values[count - 1] - values[0]) / 1e6,
              slot.markerNanos / 1e6,
              count,
              slot.truncated,
              skippedSamples,
              List.copyOf(stages));
      output.accept(sample);
      completed++;
      cpuSum += sample.cpuSubmitMs();
      gpuSum += sample.gpuMs();
      if (completed % 10 == 0)
        LOGGER.info(
            "Shader profiler: {} resolved samples, mean submission {} ms, mean GPU {} ms, {}"
                + " skipped samples (profiling overhead included)",
            completed,
            String.format(java.util.Locale.ROOT, "%.3f", cpuSum / completed),
            String.format(java.util.Locale.ROOT, "%.3f", gpuSum / completed),
            skippedSamples);
    }
  }

  private void disable(RuntimeException failure) {
    LOGGER.warn(
        "Shader profiler disabled after a diagnostic failure; rendering continues", failure);
    close();
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    active = null;
    for (Slot slot : slots) {
      if (slot == null) continue;
      try {
        slot.queries.close();
      } catch (RuntimeException failure) {
        LOGGER.warn("Cannot release shader profiling queries", failure);
      }
    }
    if (jsonOutput != null) jsonOutput.close();
  }

  private static final class Slot {
    final Queries queries;
    final List<String> labels = new ArrayList<>();
    final List<Long> cpuNanos = new ArrayList<>();
    long frame, markerNanos;
    String dimension;
    int width, height;
    boolean pending, truncated, discard;

    Slot(Queries queries) {
      this.queries = queries;
    }

    void reset(long frame, String dimension, int width, int height) {
      labels.clear();
      cpuNanos.clear();
      markerNanos = 0;
      truncated = discard = false;
      this.frame = frame;
      this.dimension = dimension;
      this.width = width;
      this.height = height;
    }
  }

  private static final class JsonOutput implements Consumer<Sample>, AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path path;
    private final ThreadPoolExecutor writer;
    private boolean failed;

    JsonOutput(Path path) {
      this.path = path.toAbsolutePath();
      writer =
          new ThreadPoolExecutor(
              1,
              1,
              0,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(32),
              task -> {
                Thread thread = new Thread(task, "Shader profiler output");
                thread.setDaemon(true);
                return thread;
              },
              new ThreadPoolExecutor.DiscardPolicy());
    }

    public void accept(Sample sample) {
      writer.execute(
          () -> {
            if (failed) return;
            try {
              Files.createDirectories(path.getParent());
              Files.writeString(
                  path,
                  GSON.toJson(sample) + "\n",
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND);
            } catch (IOException failure) {
              failed = true;
              LOGGER.warn("Cannot write shader profiler output {}", path, failure);
            }
          });
    }

    public void close() {
      writer.shutdown();
    }
  }
}
