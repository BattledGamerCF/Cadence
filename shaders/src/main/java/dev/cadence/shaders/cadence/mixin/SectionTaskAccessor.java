package dev.cadence.shaders.cadence.mixin;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Cadence: reads a compile task's cancelled flag for CompileQueue (vanilla's queue reads the protected field directly). */
@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface SectionTaskAccessor {
	@Accessor("isCancelled")
	AtomicBoolean cadence$isCancelled();
}
