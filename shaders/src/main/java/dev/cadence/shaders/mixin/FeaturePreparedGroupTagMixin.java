// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.shaders.geometry.FeatureDrawContext;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedGroup")
public abstract class FeaturePreparedGroupTagMixin {
  @Shadow @Final private int fromInclusive;

  @WrapMethod(method = "execute")
  private void shaders$replayTaggedGroup(
      FeatureFrameContext context,
      OitStage stage,
      RenderPass pass,
      FeatureRendererMap renderers,
      List<SubmitNode> submits,
      Operation<Void> original) {
    var tag = FeatureDrawContext.tag(submits.get(fromInclusive));
    if (!FeatureDrawContext.shouldDraw(tag)) return;
    var previous = FeatureDrawContext.setDrawTag(tag);
    try {
      original.call(context, stage, pass, renderers, submits);
    } finally {
      FeatureDrawContext.setDrawTag(previous);
    }
  }
}
