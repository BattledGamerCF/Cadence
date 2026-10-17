package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: currentRenderedItemId, as Iris: an item's draws (in hand, dropped, in a frame, worn) carry its item.properties
 * id (ItemRenderedIdResolverMixin notes it on the render state). Complementary, Unbound, Pegasus and I Like Vanilla pick
 * an item's material with it (glowing torches and glowstone, shining ores, armour trims); without it every item was plain.
 */
@Mixin(ItemStackRenderState.class)
public abstract class ItemRenderedIdMixin implements dev.cadence.shaders.geometry.ItemIdHolder {
  @Unique private int cadence$itemId;
  @Unique private FeatureDrawContext.DrawTag cadence$previous;

  @Override
  public void cadence$setItemId(int id) {
    cadence$itemId = id;
  }

  @Inject(method = "clear", at = @At("HEAD"))
  private void cadence$clearItemId(CallbackInfo ci) {
    cadence$itemId = 0;
  }

  @Inject(method = "submit", at = @At("HEAD"))
  private void cadence$tagItem(com.mojang.blaze3d.vertex.PoseStack poses, SubmitNodeCollector output, int light, int overlay,
      int outline, CallbackInfo ci) {
    cadence$previous = cadence$itemId == 0 || !FeatureDrawContext.ITEM_IDS || !ShaderRuntime.isRendering() ? null
        : FeatureDrawContext.setSubmission(FeatureDrawContext.submission().withItem(cadence$itemId));
  }

  @Inject(method = "submit", at = @At("RETURN"))
  private void cadence$untagItem(com.mojang.blaze3d.vertex.PoseStack poses, SubmitNodeCollector output, int light, int overlay,
      int outline, CallbackInfo ci) {
    if (cadence$previous == null) return;
    FeatureDrawContext.setSubmission(cadence$previous);
    cadence$previous = null;
  }
}
