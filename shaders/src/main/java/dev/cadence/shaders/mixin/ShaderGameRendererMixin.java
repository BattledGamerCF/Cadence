// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class ShaderGameRendererMixin {
  @ModifyArg(
      method = "renderLevel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"),
      index = 0)
  private Matrix4f shaders$projection(Matrix4f projection) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime != null) {
      runtime.uniforms().captureProjection(projection);
      runtime.beginFrame();
    }
    return projection;
  }

  @WrapMethod(method = "renderLevel")
  private void shaders$frame(Operation<Void> original) {
    dev.cadence.shaders.cadence.ShaderSwitcher.beforeFrame(); // Cadence: a pack that failed last frame goes off here
    boolean success = false;
    try {
      original.call();
      success = true;
    } catch (RuntimeException failure) {
      // Cadence: with a pack, a failed frame turns shaders off (and says why) instead of crashing the game
      if (ShaderRuntime.get() == null) throw failure;
      dev.cadence.shaders.cadence.ShaderSwitcher.failedInFrame(failure);
    } finally {
      ShaderRuntime runtime = ShaderRuntime.get();
      if (runtime != null) {
        if (success) {
          try {
            runtime.endFrame();
          } catch (RuntimeException failure) { // Cadence: as a failed frame (the pack goes off next frame)
            dev.cadence.shaders.cadence.ShaderSwitcher.failedInFrame(failure);
            try {
              runtime.abortFrame();
            } catch (RuntimeException ignored) {
              // already failing
            }
          }
        } else {
          try {
            runtime.abortFrame();
          } catch (RuntimeException ignored) {
            // already failing; the switcher turns the pack off
          }
        }
      }
    }
  }

  @Inject(method = "render3dHud", at = @At("HEAD"))
  private void shaders$hand(CallbackInfo ci) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime != null) runtime.beginHand();
  }

  @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
  private void shaders$orderedTransparency(CallbackInfoReturnable<Boolean> cir) {
    // Terrain sorting and feature preparation can query this before beginFrame.
    if (ShaderRuntime.get() != null) cir.setReturnValue(false);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void shaders$close(CallbackInfo ci) {
    if (ShaderRuntime.get() != null) ShaderRuntime.get().close();
  }
}
