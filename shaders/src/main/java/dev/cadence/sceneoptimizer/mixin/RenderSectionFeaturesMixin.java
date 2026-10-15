// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.SectionMeshChanges;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionFeaturesMixin {
  @Inject(method = "setSectionMesh", at = @At("RETURN"))
  private void scene$meshPublished(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> callback) {
    SectionMeshChanges.changed((SectionRenderDispatcher.RenderSection) (Object) this);
  }

  @Inject(method = "reset", at = @At("RETURN"))
  private void scene$meshReset(CallbackInfo callback) {
    SectionMeshChanges.changed((SectionRenderDispatcher.RenderSection) (Object) this);
  }
}
