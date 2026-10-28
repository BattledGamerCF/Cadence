package dev.cadence.shaders.cadence.mixin.head;

import dev.cadence.shaders.cadence.head.HeadTracking;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (Head Tracking): Look turns the player before the camera follows it; Lean moves the camera once it has. */
@Mixin(Camera.class)
abstract class CameraHeadMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "update", at = @At("HEAD"))
	private void cadence$headLook(DeltaTracker delta, CallbackInfo ci) {
		HeadTracking.beforeCamera(Minecraft.getInstance());
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void cadence$headLean(float partialTicks, CallbackInfo ci) {
		float[] rotation = new float[2];
		Vec3 to = HeadTracking.lean((Camera) (Object) this, Minecraft.getInstance(), rotation);
		if (to == null) return;
		this.setRotation(rotation[0], rotation[1]);
		this.setPosition(to);
	}
}
