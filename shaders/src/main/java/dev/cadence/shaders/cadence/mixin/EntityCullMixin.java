package dev.cadence.shaders.cadence.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.shaders.cadence.render.EntityCull;
import dev.cadence.shaders.runtime.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence: entities behind walls, and each entity's camera/shadow passes (EntityCull). */
@Mixin(LevelExtractor.class)
public abstract class EntityCullMixin {
	@Shadow @Final private LevelRenderer levelRenderer;

	@Inject(method = "extract", at = @At("HEAD"))
	private void cadence$beginEntityCull(CallbackInfo callback) {
		EntityCull.beginExtraction(levelRenderer, dev.cadence.shaders.runtime.ShaderRuntime.get() != null);
	}

	// a head/return pair, not a method wrap: the wrap boxed the call's doubles, float and long for every entity every frame
	@ModifyVariable(method = "isEntityVisible", at = @At("HEAD"), argsOnly = true)
	private Frustum cadence$resetUnion(Frustum frustum) {
		if (frustum instanceof ShadowRenderer.UnionFrustum union) union.reset();
		return frustum;
	}

	@ModifyReturnValue(method = "isEntityVisible", at = @At("RETURN"))
	private boolean cadence$cullEntity(boolean visible, @Local(argsOnly = true) Entity entity, @Local(argsOnly = true) Frustum frustum,
		@Local(argsOnly = true) float partial) {
		return EntityCull.decide(entity, frustum, partial, visible);
	}

	@Inject(method = "extractEntity", at = @At("RETURN"))
	private void cadence$entityPasses(Entity entity, float partial, CallbackInfoReturnable<EntityRenderState> callback) {
		EntityCull.extracted(callback.getReturnValue());
	}
}
