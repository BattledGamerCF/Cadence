package dev.cadence.shaders.cadence.mixin.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cadence (WorldScale): vanilla's sky renderer keeps the main target it was made with (LevelRenderer makes it lazily, inside a
 * frame). With World Resolution below 100% that can be the scaled world target, which is freed when the scale changes or a
 * pack turns on (the next sky pass then crashed on its freed texture), or the full-size target while the world is scaled (the
 * sky then went into an image the upscale overwrites). It draws into the current main target instead: the same target whenever
 * nothing is scaled.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererTargetMixin {
	@Redirect(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;",
		opcode = Opcodes.GETFIELD))
	private RenderTarget cadence$currentMain(SkyRenderer sky) {
		if (dev.cadence.shaders.cadence.render.FrameRecovery.failNextSky) {
			dev.cadence.shaders.cadence.render.FrameRecovery.failNextSky = false;
			throw new IllegalStateException("self-test: injected frame failure");
		}
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}
}
