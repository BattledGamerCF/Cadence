// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cadence.metal.own.OwnLife;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * OwnLife: the compile task running on this thread and the section position it compiles, for our store's check. The whole doTask is
 * wrapped and the previous context restored in finally, so an exception can't leave the thread pointing at the task.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class OwnIntLifeTaskMixin {
	@Shadow
	@Final
	SectionRenderDispatcher.RenderSection this$1;

	@WrapMethod(method = "doTask")
	private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult cadence$lifeTask(SectionBufferBuilderPack buffers,
		Operation<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> original) {
		if (!OwnLife.ON) return original.call(buffers);
		Object[] prev = OwnLife.enter(this, this.this$1.getSectionNode());
		try {
			return original.call(buffers);
		} finally {
			OwnLife.exit(prev);
		}
	}
}
