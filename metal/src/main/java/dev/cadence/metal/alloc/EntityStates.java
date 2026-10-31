package dev.cadence.metal.alloc;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Entity render states kept from frame to frame (mixin.alloc.EntityStateReuseMixin). Render thread only. */
public final class EntityStates {
	/** The world's entities are being gathered for drawing (LevelExtractor.extractVisibleEntities). */
	public static boolean gathering;

	private EntityStates() {
	}

	/** An entity's kept render state (EntityStateHolderMixin). */
	public interface Holder {
		/** The state kept for this renderer, null if none (or one kept for another renderer). */
		EntityRenderState cadence$keptState(Object renderer);

		void cadence$keepState(Object renderer, EntityRenderState state);
	}
}
