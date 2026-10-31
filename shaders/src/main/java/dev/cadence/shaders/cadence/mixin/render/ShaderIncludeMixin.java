package dev.cadence.shaders.cadence.mixin.render;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cadence.shaders.cadence.render.VanillaRgss;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cadence (VanillaRgss): vanilla's shader includes as they are read. */
@Mixin(ShaderManager.class)
public abstract class ShaderIncludeMixin {
	@ModifyExpressionValue(method = "loadInclude", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/packs/resources/Resource;readAllAsString()Ljava/lang/String;"))
	private static String cadence$include(String source, Identifier location) {
		return VanillaRgss.patch(location.getPath(), source);
	}
}
