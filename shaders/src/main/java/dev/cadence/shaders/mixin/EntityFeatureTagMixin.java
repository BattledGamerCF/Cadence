// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags an entity's submitted features for the pack. Cadence: a head/return pair with a small stack of the previous tags
 * instead of a method wrap (the wrap boxed the call's three doubles for every entity every frame). An exception inside
 * submit is a crash report either way (vanilla rethrows it), so no tag needs restoring on that path.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityFeatureTagMixin {
  @Unique private Object[] shaders$previous = new Object[8];
  @Unique private int shaders$depth;

  @Inject(method = "submit", at = @At("HEAD"))
  private void shaders$tagEntity(
      EntityRenderState state, net.minecraft.client.renderer.state.level.CameraRenderState camera, double x, double y, double z,
      com.mojang.blaze3d.vertex.PoseStack poses, net.minecraft.client.renderer.SubmitNodeCollector output, CallbackInfo ci) {
    var previous = FeatureDrawContext.setSubmission(FeatureDrawContext.entityTag(state));
    if (shaders$depth == shaders$previous.length)
      shaders$previous = java.util.Arrays.copyOf(shaders$previous, shaders$depth * 2);
    shaders$previous[shaders$depth++] = previous;
  }

  @Inject(method = "submit", at = @At("RETURN"))
  private void shaders$untagEntity(
      EntityRenderState state, net.minecraft.client.renderer.state.level.CameraRenderState camera, double x, double y, double z,
      com.mojang.blaze3d.vertex.PoseStack poses, net.minecraft.client.renderer.SubmitNodeCollector output, CallbackInfo ci) {
    if (shaders$depth == 0) return;
    var previous = shaders$previous[--shaders$depth];
    shaders$previous[shaders$depth] = null;
    FeatureDrawContext.setSubmission((FeatureDrawContext.DrawTag) previous);
  }
}
