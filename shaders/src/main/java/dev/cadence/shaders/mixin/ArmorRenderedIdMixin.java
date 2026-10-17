package dev.cadence.shaders.mixin;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cadence: currentRenderedItemId for worn equipment, as Iris: an armour piece's draws carry its item's id, its trim's the
 * pack's trim_&lt;material&gt; id (Complementary: iron, diamond and netherite armour shine, trims glow).
 */
@Mixin(EquipmentLayerRenderer.class)
public abstract class ArmorRenderedIdMixin {
  @Unique private FeatureDrawContext.DrawTag cadence$previous;

  @Inject(
      method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
      at = @At("HEAD"))
  private void cadence$tagArmor(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ItemStack stack) {
    ShaderRuntime runtime = ShaderRuntime.get();
    cadence$previous = runtime == null || !FeatureDrawContext.ITEM_IDS || !ShaderRuntime.isRendering() || stack.isEmpty() ? null
        : FeatureDrawContext.setSubmission(FeatureDrawContext.submission().withItem(runtime.uniforms().itemId(stack)));
  }

  @Inject(
      method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorTrim(Lnet/minecraft/resources/Identifier;Z)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
  private void cadence$tagTrim(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ItemStack stack) {
    if (cadence$previous == null) return;
    ShaderRuntime runtime = ShaderRuntime.get();
    var trim = stack.get(DataComponents.TRIM);
    if (runtime == null || trim == null) return;
    String material = trim.material().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
    FeatureDrawContext.setSubmission(FeatureDrawContext.submission().withItem(runtime.uniforms().trimId(material)));
  }

  @Inject(
      method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
      at = @At("RETURN"))
  private void cadence$untagArmor(CallbackInfo ci) {
    if (cadence$previous == null) return;
    FeatureDrawContext.setSubmission(cadence$previous);
    cadence$previous = null;
  }
}
