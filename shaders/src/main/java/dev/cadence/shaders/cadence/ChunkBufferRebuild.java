package dev.cadence.shaders.cadence;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence: rebuilds the chunk dispatcher's per-layer GPU buffers ("uber buffers") for a new terrain vertex size, in place.
 *
 * <p>Each layer's vertex buffer aligns its allocations to the vertex size of {@code layer.pipeline(false)} as it was when the
 * dispatcher was made (SectionRenderDispatcher.lambda$new$0): 128 MB heaps aligned to the vertex size, 32 MB index heaps
 * aligned to 8. A shader pack changes the terrain vertex size, so after a live switch the old buffers misplace every new
 * mesh (each draw's base vertex is offset / vertex size). Disposing the whole dispatcher instead left the world empty, so this
 * replaces only those buffers, exactly as vanilla builds them, at the point in invalidateCompiledGeometry where every
 * section's allocation has been released and the compile queue cleared (LevelRendererRebufferMixin), under the
 * dispatcher's copy lock. The old buffers close through the backend's deferred release, so frames in flight stay valid.
 */
public final class ChunkBufferRebuild {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence-shaders");
	private static volatile boolean pending;

	private ChunkBufferRebuild() {
	}

	/** The next chunk reload rebuilds the buffers. */
	public static void request() {
		pending = true;
	}

	/** Called from invalidateCompiledGeometry after releaseAllBuffers and clearCompileQueue. */
	public static void rebuildIfPending(SectionRenderDispatcher dispatcher) {
		if (!pending || dispatcher == null) return;
		pending = false;
		dispatcher.lock();
		try {
			Field mapField = SectionRenderDispatcher.class.getDeclaredField("chunkUberBuffers");
			Field stagingField = SectionRenderDispatcher.class.getDeclaredField("stagingBuffer");
			mapField.setAccessible(true);
			stagingField.setAccessible(true);
			@SuppressWarnings("unchecked")
			Map<ChunkSectionLayer, Object> map = (Map<ChunkSectionLayer, Object>) mapField.get(dispatcher);
			StagingBuffer staging = (StagingBuffer) stagingField.get(dispatcher);
			Class<?> record = Class.forName("net.minecraft.client.renderer.chunk.SectionRenderDispatcher$SectionUberBuffers");
			Constructor<?> make = record.getDeclaredConstructor(UberGpuBuffer.class, UberGpuBuffer.class);
			Method vertexOf = record.getDeclaredMethod("vertexBuffer"), indexOf = record.getDeclaredMethod("indexBuffer");
			make.setAccessible(true);
			vertexOf.setAccessible(true);
			indexOf.setAccessible(true);
			StringBuilder sizes = new StringBuilder();
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				int vertexSize = layer.pipeline(false).getVertexFormatBinding(0).getVertexSize();
				UberGpuBuffer<?> vertex = new UberGpuBuffer<>(layer.label(), 32, ChunkHeaps.vertexBytes(), vertexSize, staging);
				UberGpuBuffer<?> index = new UberGpuBuffer<>(layer.label(), 64, ChunkHeaps.indexBytes(), 8, staging);
				Object old = map.put(layer, make.newInstance(vertex, index));
				if (old != null) {
					((UberGpuBuffer<?>) vertexOf.invoke(old)).close();
					((UberGpuBuffer<?>) indexOf.invoke(old)).close();
				}
				sizes.append(' ').append(layer.label()).append('=').append(vertexSize);
			}
			LOGGER.info("Chunk buffers rebuilt for the new terrain vertex size:{}", sizes);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOGGER.error("Couldn't rebuild the chunk buffers; terrain may draw wrongly until a restart", e);
		} finally {
			dispatcher.unlock();
		}
	}
}
