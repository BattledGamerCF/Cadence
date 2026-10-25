package dev.cadence.shaders.cadence.mixin.system;

import dev.cadence.shaders.cadence.system.ThreadRoles;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (ThreadRoles): a chunk-building worker's QoS, set on its first section. */
@Mixin(SectionCompiler.class)
public abstract class ThreadRolesWorkerMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void cadence$workerRole(CallbackInfoReturnable<?> cir) {
		ThreadRoles.worker();
	}
}
