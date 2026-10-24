package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.render.MaxFpsMode;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence (MaxFpsMode): the world's drawing skipped, and the camera and fog it would be drawn with not worked out; the frame is
 * its clear (black) and the mode's two lines of text.
 */
// (priority 500: applied before GameRendererWorldScaleMixin's renderLevel HEAD/RETURN pair, so a cancel never splits it)
@Mixin(value = GameRenderer.class, priority = 500)
public abstract class MaxFpsModeMixin {
	@Unique private static final Vector4fc CADENCE_BLACK = new Vector4f(0f, 0f, 0f, 1f);

	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFps(CallbackInfo ci) {
		if (MaxFpsMode.on() || dev.cadence.metal.record.Record.active()) ci.cancel();
	}

	@Inject(method = "extractCamera", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFpsCamera(CallbackInfo ci) {
		if (MaxFpsMode.on() || dev.cadence.metal.record.Record.active()) ci.cancel();
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"),
		index = 1)
	private Vector4fc cadence$maxFpsSky(Vector4fc color) {
		return MaxFpsMode.on() && net.minecraft.client.Minecraft.getInstance().level != null ? CADENCE_BLACK : color;
	}
}
