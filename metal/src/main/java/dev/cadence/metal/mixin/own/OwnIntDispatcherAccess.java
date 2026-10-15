// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import java.util.Map;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** -Dcadence.own.int.lists: vanilla's per-layer uber buffers. */
@Mixin(SectionRenderDispatcher.class)
public interface OwnIntDispatcherAccess {
	@Accessor("chunkUberBuffers")
	Map<?, ?> cadence$uberBuffers();
}
