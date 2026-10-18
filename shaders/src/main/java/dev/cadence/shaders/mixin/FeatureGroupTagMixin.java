// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.shaders.geometry.FeatureDrawContext;
import java.util.Collection;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PhaseSubmitGrouper")
public abstract class FeatureGroupTagMixin {
  @Unique private FeatureDrawContext.DrawTag shaders$previousTag = FeatureDrawContext.NONE;
  @Unique private FeatureDrawContext.DrawTag shaders$incomingTag = FeatureDrawContext.NONE;

  @WrapMethod(method = "accept")
  private void shaders$acceptTag(
      SubmitNode submit, boolean strictlyOrdered, Operation<Void> original) {
    shaders$incomingTag = FeatureDrawContext.tag(submit);
    original.call(submit, strictlyOrdered);
  }

  @WrapMethod(method = "acceptFeatureGroup")
  private void shaders$partitionTags(
      FeatureRendererType<?> type,
      Collection<SubmitNode> submits,
      boolean strictlyOrdered,
      Operation<Void> original) {
    // Cadence: nothing tagged (no shader pack drawing): one group, as vanilla, without partitioning (a map and lists per
    // feature group per frame, ~1-2% of the render thread's garbage in a busy scene)
    if (FeatureDrawContext.untagged()) {
      shaders$incomingTag = FeatureDrawContext.NONE;
      original.call(type, submits, strictlyOrdered);
      return;
    }
    for (var group : FeatureDrawContext.partition(submits, strictlyOrdered)) {
      shaders$incomingTag = FeatureDrawContext.tag(group.getFirst());
      original.call(type, group, strictlyOrdered);
    }
  }

  @ModifyExpressionValue(
      method = "addOrExtendGroup",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedGroup;strictlyOrdered:Z"))
  private boolean shaders$keepTagsSeparate(
      boolean previousStrictlyOrdered, @Local(argsOnly = true) boolean incomingStrictlyOrdered) {
    return shaders$incomingTag.equals(shaders$previousTag)
        ? previousStrictlyOrdered
        : !incomingStrictlyOrdered;
  }

  @Inject(method = "addOrExtendGroup", at = @At("RETURN"))
  private void shaders$rememberGroupTag(CallbackInfo callback) {
    shaders$previousTag = shaders$incomingTag;
  }
}
