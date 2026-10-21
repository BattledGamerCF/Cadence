package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.shaders.cadence.render.CrispText;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Cadence (CrispText): world text groups held back while World Resolution draws the world smaller, drawn at full size after. */
@Mixin(targets = "net.minecraft.client.renderer.feature.FeatureRenderDispatcher$PreparedGroup")
public abstract class WorldTextDeferMixin {
  @Shadow @Final private FeatureRendererType<?> featureType;

  @WrapMethod(method = "execute")
  private void cadence$deferText(
      FeatureFrameContext context,
      OitStage stage,
      RenderPass pass,
      FeatureRendererMap renderers,
      List<SubmitNode> submits,
      Operation<Void> original) {
    // (only the ordinary passes: an Improved Transparency stage's draws are made for its own targets, so they stay put)
    if (featureType == TextFeatureRenderer.TYPE && stage == null && CrispText.deferring()) {
      CrispText.defer(later -> original.call(context, stage, later, renderers, submits));
      return;
    }
    original.call(context, stage, pass, renderers, submits);
  }
}
