package dev.cadence.shaders.cadence.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cadence.shaders.cadence.render.AutoRenderDistance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence (AutoRenderDistance): the effective render distance under Auto's cap; the player's option itself is never changed. */
@Mixin(Options.class)
abstract class AutoRenderDistanceMixin {
	@ModifyReturnValue(method = "getEffectiveRenderDistance", at = @At("RETURN"))
	private int cadence$autoCap(int distance) {
		return AutoRenderDistance.effective(distance);
	}
}
