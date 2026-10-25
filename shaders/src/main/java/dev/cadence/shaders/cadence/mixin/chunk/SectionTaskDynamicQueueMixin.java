package dev.cadence.shaders.cadence.mixin.chunk;

import dev.cadence.shaders.cadence.render.CompileQueue;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence: vanilla's compile queue backed by CompileQueue (same task choice, no O(n) scan per poll). In verify mode vanilla's
 * own queue keeps running and decides, and each poll's choices are compared.
 */
@Mixin(SectionTaskDynamicQueue.class)
public abstract class SectionTaskDynamicQueueMixin {
	@Unique private final CompileQueue cadence$queue = CompileQueue.ENABLED ? new CompileQueue() : null;
	/** Our own copy of vanilla's recompile quota (starts at vanilla's 2), so verify mode can compare two independent runs. */
	@Unique private final int[] cadence$quota = {2};
	@Unique private SectionRenderDispatcher.RenderSection.SectionTask cadence$choice;

	@Inject(method = "add", at = @At("HEAD"), cancellable = true)
	private void cadence$add(SectionRenderDispatcher.RenderSection.SectionTask task, CallbackInfo ci) {
		if (cadence$queue == null) return;
		cadence$queue.add(task);
		if (!CompileQueue.VERIFY) ci.cancel();
	}

	@Inject(method = "poll", at = @At("HEAD"), cancellable = true)
	private void cadence$poll(Vec3 camera, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir) {
		if (cadence$queue == null) return;
		cadence$choice = cadence$queue.poll(camera, cadence$quota);
		if (!CompileQueue.VERIFY) cir.setReturnValue(cadence$choice);
	}

	@Inject(method = "poll", at = @At("RETURN"))
	private void cadence$pollVerify(Vec3 camera, CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask> cir) {
		if (cadence$queue != null && CompileQueue.VERIFY) CompileQueue.verify(cadence$choice, cir.getReturnValue());
	}

	@Inject(method = "size", at = @At("HEAD"), cancellable = true)
	private void cadence$size(CallbackInfoReturnable<Integer> cir) {
		if (cadence$queue != null && !CompileQueue.VERIFY) cir.setReturnValue(cadence$queue.size());
	}

	@Inject(method = "clear", at = @At("HEAD"), cancellable = true)
	private void cadence$clear(CallbackInfo ci) {
		if (cadence$queue == null) return;
		cadence$queue.clear();
		if (!CompileQueue.VERIFY) ci.cancel();
	}
}
