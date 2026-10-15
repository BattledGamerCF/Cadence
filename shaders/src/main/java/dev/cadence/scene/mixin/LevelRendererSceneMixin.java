// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.scene.mixin;

import dev.cadence.scene.SceneViews;
import dev.cadence.scene.ScopedSectionSelection;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adapts the shared direct/indirect draw builder, without changing camera visibility ownership. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSceneMixin {
  @Redirect(
      method = "extractSectionDrawGroups",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
  private ObjectListIterator<RenderSection> scene$viewSections(
      ObjectArrayList<RenderSection> original) {
    return ScopedSectionSelection.iterator((LevelRenderer) (Object) this, original);
  }

  @Inject(
      method = {"close", "resetLevelRenderData", "invalidateCompiledGeometry"},
      at = @At("HEAD"))
  private void scene$invalidate(CallbackInfo callback) {
    SceneViews.worldChanged();
  }
}
