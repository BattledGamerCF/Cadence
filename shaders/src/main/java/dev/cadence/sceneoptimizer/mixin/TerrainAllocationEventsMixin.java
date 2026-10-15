// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import dev.cadence.sceneoptimizer.DrawMetadataMetrics;
import dev.cadence.sceneoptimizer.PreparedMeshDraws;
import java.util.Map;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tracks actual allocation-map changes; staging alone leaves the old allocation valid. */
@Mixin(UberGpuBuffer.class)
public abstract class TerrainAllocationEventsMixin {
  @Shadow @Final private Map<?, ?> allocationMap;

  @Inject(method = "freeAllocation", at = @At("HEAD"))
  private void scene$allocationRemoved(Object key, CallbackInfo callback) {
    scene$invalidate(key);
  }

  // Vanilla invokes this after putting the new allocation in allocationMap, even with no callback.
  @Inject(method = "runCallbackUnchecked", at = @At("HEAD"))
  private static void scene$allocationPublished(
      Object key, @Coerce Object entry, CallbackInfo callback) {
    scene$invalidate(key);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void scene$allocationsClosed(CallbackInfo callback) {
    if (DrawMetadataMetrics.ENABLED) {
      for (Object key : allocationMap.keySet()) scene$invalidate(key);
    }
  }

  @Unique
  private static void scene$invalidate(Object key) {
    if (DrawMetadataMetrics.ENABLED
        && key != null
        && key.getClass() == CompiledSectionMesh.class
        && key instanceof PreparedMeshDraws prepared) {
      prepared.scene$invalidateDrawAllocations();
    }
  }
}
