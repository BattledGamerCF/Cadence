// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.runtime.ShadowRenderer;
import dev.cadence.scene.SceneExtractionRequest;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loads off-camera shadow casters through Minecraft's normal extraction and mesh worker lifecycle.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorShaderMixin {
  @Shadow private SectionUpdateTracker sectionUpdateTracker;

  @Inject(method = "extract", at = @At("HEAD"))
  private void shaders$beginExtraction(CallbackInfo callback) {
    FeatureDrawContext.beginExtraction();
    ShadowRenderer.beginExtraction();
  }

  @Inject(method = "extractVisibleEntities", at = @At("RETURN"))
  private void shaders$extractCameraShadow(
      Camera camera,
      Frustum frustum,
      DeltaTracker deltaTracker,
      LevelRenderState output,
      CallbackInfo callback) {
    if (camera.isDetached() || ShadowRenderer.frustum() == null) return;
    var entity = camera.entity();
    if (entity == null || (entity instanceof LivingEntity living && living.isSleeping())) return;
    // Vanilla deliberately excludes this entity from the scene. Extract it once into a group
    // that participates only in the shadow replay of the same prepared vertex buffers.
    var minecraft = Minecraft.getInstance();
    float partial =
        deltaTracker.getGameTimeDeltaPartialTick(
            !minecraft.level.tickRateManager().isEntityFrozen(entity));
    var state = minecraft.levelRenderer.entityRenderDispatcher().extractEntity(entity, partial);
    FeatureDrawContext.shadowOnlyEntity(state);
    output.entityRenderStates.add(state);
  }

  @Redirect(
      method = "extract",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
  private ObjectArrayList<SectionRenderDispatcher.RenderSection> shaders$shadowSections(
      LevelRenderer renderer) {
    return ShadowRenderer.extractionSections(
        renderer, SceneExtractionRequest.Purpose.DIRTY_SECTIONS, sectionUpdateTracker);
  }

  @Redirect(
      method = "extractVisibleBlockEntities",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
  private ObjectArrayList<SectionRenderDispatcher.RenderSection> shaders$shadowBlockEntitySections(
      LevelRenderer renderer) {
    return ShadowRenderer.extractionSections(
        renderer, SceneExtractionRequest.Purpose.BLOCK_ENTITIES, null);
  }

  @ModifyVariable(method = "extractVisibleEntities", at = @At("HEAD"), argsOnly = true)
  private Frustum shaders$includeShadowEntityFrustum(Frustum cameraFrustum) {
    return ShadowRenderer.withShadowFrustum(cameraFrustum);
  }
}
