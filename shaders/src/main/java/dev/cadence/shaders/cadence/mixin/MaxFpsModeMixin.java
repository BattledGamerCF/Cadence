package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.MaxFpsMode;
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
 * Cadence (MaxFpsMode): the world's drawing skipped; the frame keeps its clear (in Minecraft's sky blue, as a world whose blocks
 * haven't loaded looks, instead of black) and its GUI.
 */
@Mixin(GameRenderer.class)
public abstract class MaxFpsModeMixin {
	/** Vanilla's day sky (plains), 0x78A7FF. */
	@Unique private static final Vector4fc CADENCE_SKY = new Vector4f(120 / 255f, 167 / 255f, 1f, 1f);

	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void cadence$maxFps(CallbackInfo ci) {
		if (MaxFpsMode.on()) ci.cancel();
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"),
		index = 1)
	private Vector4fc cadence$maxFpsSky(Vector4fc color) {
		return MaxFpsMode.on() && net.minecraft.client.Minecraft.getInstance().level != null ? CADENCE_SKY : color;
	}
}
