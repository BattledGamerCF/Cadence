package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.shaders.cadence.DynamicLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence: an entity's light (and so the hand's and its held item's) with this frame's dynamic lights (DynamicLights). */
@Mixin(EntityRenderer.class)
public abstract class DynamicLightsEntityMixin {
	@ModifyReturnValue(method = "getPackedLightCoords", at = @At("RETURN"))
	private int cadence$dynamicLight(int packed, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) float partial) {
		if (!DynamicLights.active()) return packed;
		Vec3 p = entity.getLightProbePosition(partial);
		return DynamicLights.raise(packed, p.x, p.y, p.z);
	}
}
