// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.backend.FrameLog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measurement only (-Dcadence.own.int.frameLog): the client tick's time in the frame it runs in. */
@Mixin(Minecraft.class)
public class FrameLogMinecraftMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void cadence$frameLogTickHead(CallbackInfo ci) {
		FrameLog.begin(FrameLog.TICK);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void cadence$frameLogTickReturn(CallbackInfo ci) {
		FrameLog.end(FrameLog.TICK);
	}
}
