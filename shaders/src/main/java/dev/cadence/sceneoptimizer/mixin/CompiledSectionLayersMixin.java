// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer.mixin;

import dev.cadence.sceneoptimizer.PreparedSectionLayers;
import dev.cadence.sceneoptimizer.SectionLayerSets;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draw membership is immutable after vanilla mesh construction; upload readiness remains vanilla.
 */
@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionLayersMixin implements PreparedSectionLayers {
  @Shadow @Final private Map<ChunkSectionLayer, SectionMesh.SectionDraw> draws;
  @Unique private ChunkSectionLayer[] scene$drawLayers;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void scene$retainLayerSet(CallbackInfo callback) {
    int mask = 0;
    for (ChunkSectionLayer layer : draws.keySet()) mask |= 1 << layer.ordinal();
    scene$drawLayers = SectionLayerSets.forMask(mask);
  }

  @Override
  public ChunkSectionLayer[] scene$drawLayers() {
    return scene$drawLayers;
  }
}
