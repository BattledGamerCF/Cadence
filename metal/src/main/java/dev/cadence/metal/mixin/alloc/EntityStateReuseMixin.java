package dev.cadence.metal.mixin.alloc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cadence.metal.alloc.EntityStates;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence (-Dcadence.alloc.entityStates, on by default): while the world's entities are gathered for drawing, each entity keeps
 * its render state from frame to frame instead of a new one each frame (with its item states, a few hundred bytes to a few KB:
 * the render thread's largest allocation, ~7 MB/s in a busy scene, and so young collections and their pauses). Every field is
 * written again by extractRenderState, as vanilla's own reuse of one state per renderer (1.21.2) needed; the inventory's
 * player and other GUI entities get new states as before.
 */
@Mixin(EntityRenderer.class)
abstract class EntityStateReuseMixin {
	@WrapOperation(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;createRenderState()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private EntityRenderState cadence$reuse(EntityRenderer<?, ?> renderer, Operation<EntityRenderState> original, Entity entity, float partialTick) {
		if (!EntityStates.gathering) return original.call(renderer);
		EntityStates.Holder holder = (EntityStates.Holder) entity;
		EntityRenderState kept = holder.cadence$keptState(renderer);
		if (kept != null) return kept;
		EntityRenderState state = original.call(renderer);
		holder.cadence$keepState(renderer, state);
		return state;
	}
}
