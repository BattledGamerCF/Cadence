package dev.cadence.shaders.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: shaders.properties {@code sun=false}, {@code moon=false}, {@code stars=false}: the game's sun, moon or stars aren't
 * drawn while the pack is on (packs that draw their own: Bliss's moon, round suns), as OptiFine and Iris. The moon's draw is
 * marked so it gets renderStage MOON.
 */
@Mixin(SkyRenderer.class)
public abstract class ShaderSkyFlagsMixin {
  @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
  private void shaders$sunFlag(RenderPass pass, float alpha, PoseStack pose, CallbackInfo ci) {
    ShaderRuntime.drawingMoon = false;
    if (ShaderRuntime.isRendering() && !ShaderRuntime.get().renderFlag("sun", true)) ci.cancel();
  }

  @Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
  private void shaders$moonFlag(RenderPass pass, MoonPhase phase, float alpha, PoseStack pose, CallbackInfo ci) {
    if (ShaderRuntime.isRendering() && !ShaderRuntime.get().renderFlag("moon", true)) ci.cancel();
    else ShaderRuntime.drawingMoon = true;
  }

  /** The moon's draw gets renderStage MOON, not the sun's (ShaderRuntime.bindUniforms, issue #4). */
  @Inject(method = "renderMoon", at = @At("RETURN"))
  private void shaders$moonDone(RenderPass pass, MoonPhase phase, float alpha, PoseStack pose, CallbackInfo ci) {
    ShaderRuntime.drawingMoon = false;
  }

  @Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
  private void shaders$starsFlag(RenderPass pass, float brightness, PoseStack pose, CallbackInfo ci) {
    if (ShaderRuntime.isRendering() && !ShaderRuntime.get().renderFlag("stars", true)) ci.cancel();
  }
}
