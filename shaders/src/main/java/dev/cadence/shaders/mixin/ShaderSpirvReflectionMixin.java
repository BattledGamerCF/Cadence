package dev.cadence.shaders.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence: descriptor set 1 holds a shader pack's images, buffer objects and 3D textures (MetalStorage). The game's
 * pipeline builder must not see them: it rejects resources a pipeline doesn't declare and renumbers everything into set
 * 0. The Metal backend binds set 1 itself. Nothing but pack shaders declares set 1.
 */
@Mixin(SPIRVModule.class)
public abstract class ShaderSpirvReflectionMixin {
  @ModifyReturnValue(method = "generateDescriptorList", at = @At("RETURN"))
  private static List<SpvModule.Reflection.Descriptor> shaders$hideSet1(List<SpvModule.Reflection.Descriptor> descriptors) {
    for (var d : descriptors) {
      if (d.descriptorSetIndex() == 1) {
        return descriptors.stream().filter(x -> x.descriptorSetIndex() != 1).toList();
      }
    }
    return descriptors;
  }
}
