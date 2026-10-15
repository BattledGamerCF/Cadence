// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.MeshDrawCache;
import dev.cadence.sceneoptimizer.PreparedMeshDraws;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledMeshDrawsMixin implements PreparedMeshDraws {
  @Unique private MeshDrawCache scene$drawCache;

  @Override
  public MeshDrawCache scene$drawCache() {
    if (scene$drawCache == null) scene$drawCache = new MeshDrawCache();
    return scene$drawCache;
  }

  @Override
  public void scene$invalidateDrawAllocations() {
    if (scene$drawCache != null) scene$drawCache.invalidateAllocations();
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void scene$releaseMetadata(CallbackInfo callback) {
    if (scene$drawCache != null) {
      scene$drawCache.close();
      scene$drawCache = null;
    }
  }
}
