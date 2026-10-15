// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import dev.cadence.metal.own.OwnMaterials;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native shading on our near terrain (OwnMaterials): the class of every block the compiler visits, for the store pass. */
@Mixin(SectionCompiler.class)
abstract class OwnIntCompilerMixin {
	@Inject(method = "compile", at = @At("HEAD"))
	private void cadence$compileBegin(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnMaterials.ON) OwnMaterials.compiling(true);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void cadence$compileEnd(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (OwnMaterials.ON) OwnMaterials.compiling(false);
	}

	@Redirect(method = "compile", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState cadence$ownMaterial(RenderSectionRegion region, BlockPos pos) {
		BlockState state = region.getBlockState(pos);
		if (OwnMaterials.ON) OwnMaterials.block(pos.getX(), pos.getY(), pos.getZ(), state);
		return state;
	}
}
