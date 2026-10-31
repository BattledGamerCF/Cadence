package dev.cadence.metal.mixin.alloc;

import dev.cadence.metal.alloc.EntityStates;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cadence (EntityStateReuseMixin): the entity's kept render state, and the renderer it is for. */
@Mixin(Entity.class)
abstract class EntityStateHolderMixin implements EntityStates.Holder {
	@Unique private Object cadence$stateRenderer;
	@Unique private EntityRenderState cadence$state;

	@Override
	public EntityRenderState cadence$keptState(Object renderer) {
		return renderer == this.cadence$stateRenderer ? this.cadence$state : null;
	}

	@Override
	public void cadence$keepState(Object renderer, EntityRenderState state) {
		this.cadence$stateRenderer = renderer;
		this.cadence$state = state;
	}
}
