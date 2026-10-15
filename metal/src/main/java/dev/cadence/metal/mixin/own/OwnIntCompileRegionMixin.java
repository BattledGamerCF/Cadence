// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * -Dcadence.own.int.compileRegionDrop: a section's compile task lets go of its RenderSectionRegion (the 27 section copies it compiled from,
 * plus the level and light engine) once doTask returns. Vanilla keeps the task as RenderSection.lastCompileTask until the section's next
 * compile or reset, and with it every region: ~78 MB of live heap at RD 16 (measured in heap dumps).
 *
 * No change in behaviour: in 26.3 doTask does the whole job before it returns (compile, setSectionMesh, the upload), the dispatcher's runTask
 * never runs a task again after doTask (its only re-queue is before it), and nothing but doTask reads the region. lastCompileTask is only
 * read by cancelTasks, whose cancel() on a finished task sets a flag nothing reads any more. Only the task's own field is written here
 * (the render thread may store a newer task in lastCompileTask meanwhile, so that field is left alone). A doTask that throws keeps it.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class OwnIntCompileRegionMixin {
	private static final boolean MCOPT$ON = Boolean.getBoolean("cadence.own.int.compileRegionDrop");
	private static final AtomicLong MCOPT$DROPPED = new AtomicLong();

	@Shadow
	@Final
	@Mutable
	private RenderSectionRegion region;

	@Inject(method = "doTask", at = @At("RETURN"))
	private void cadence$dropRegion(CallbackInfoReturnable<?> cir) {
		if (!MCOPT$ON) return;
		this.region = null;
		long n = MCOPT$DROPPED.incrementAndGet();
		if (n == 1 || n % 5_000 == 0) System.out.println("cadence-own: compile regions dropped after their compile: " + n);
	}
}
