// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.PreparedSectionInfo;
import dev.cadence.sceneoptimizer.SectionInfoCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSection.class)
public abstract class RenderSectionInfoMixin implements PreparedSectionInfo {
  @Unique private SectionInfoCache scene$sectionInfo;

  @Override
  public SectionInfoCache scene$sectionInfoCache() {
    if (scene$sectionInfo == null) scene$sectionInfo = new SectionInfoCache();
    return scene$sectionInfo;
  }

  @Inject(method = "reset", at = @At("HEAD"))
  private void scene$releaseSectionInfo(CallbackInfo callback) {
    if (scene$sectionInfo != null) {
      scene$sectionInfo.close();
      scene$sectionInfo = null;
    }
  }
}
