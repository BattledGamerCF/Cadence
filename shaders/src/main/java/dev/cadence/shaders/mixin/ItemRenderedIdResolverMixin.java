package dev.cadence.shaders.mixin;

import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence: notes the item's pack id on its render state while a pack draws (ItemRenderedIdMixin). */
@Mixin(ItemModelResolver.class)
public abstract class ItemRenderedIdResolverMixin {
  @Inject(method = "appendItemLayers", at = @At("HEAD"))
  private void cadence$noteItemId(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level,
      ItemOwner owner, int seed, CallbackInfo ci) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime == null || stack.isEmpty()) return;
    ((dev.cadence.shaders.geometry.ItemIdHolder) state).cadence$setItemId(runtime.uniforms().itemId(stack));
  }
}
