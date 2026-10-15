package dev.cadence.shaders.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: shaders.properties {@code clouds=off}: the game's clouds aren't drawn while the pack is on. Complementary,
 * MakeUp, I Like Vanilla and Sildur's 2.02 draw their own in a screen pass; the game's on top were a second, wrong layer.
 */
@Mixin(CloudRenderer.class)
public abstract class ShaderCloudFlagMixin {
  @Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"),
      cancellable = true)
  private void shaders$cloudFlag(CloudStatus status, RenderPass pass, CallbackInfo ci) {
    if (ShaderRuntime.isRendering() && "off".equalsIgnoreCase(ShaderRuntime.get().renderFlagValue("clouds"))) ci.cancel();
  }
}
