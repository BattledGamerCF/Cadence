package dev.cadence.metal.mixin.logic;

import dev.cadence.metal.logic.AmbientParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The animate tick's ambient-particle lookups (AmbientParticles). */
@Mixin(ClientLevel.class)
abstract class AmbientParticlesMixin {
	@Unique
	private boolean cadence$ambientShortcut;

	@Inject(method = "animateTick", at = @At("HEAD"))
	private void cadence$ambientBegin(int x, int y, int z, CallbackInfo ci) {
		AmbientParticles.beginTick((ClientLevel) (Object) this, x, y, z);
	}

	@Inject(method = "animateTick", at = @At("RETURN"))
	private void cadence$ambientEnd(int x, int y, int z, CallbackInfo ci) {
		AmbientParticles.endTick();
	}

	@Inject(method = "doAnimateTick", at = @At("HEAD"))
	private void cadence$ambientCall(int x, int y, int z, int radius, RandomSource random, Block marker, BlockPos.MutableBlockPos pos, CallbackInfo ci) {
		this.cadence$ambientShortcut = AmbientParticles.call(x, y, z, radius);
	}

	@Redirect(method = "doAnimateTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;getValue(Lnet/minecraft/world/attribute/EnvironmentAttribute;Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;"))
	private Object cadence$ambientValue(EnvironmentAttributeSystem system, EnvironmentAttribute<?> attribute, BlockPos pos) {
		return AmbientParticles.value(system, attribute, pos, this.cadence$ambientShortcut);
	}
}
