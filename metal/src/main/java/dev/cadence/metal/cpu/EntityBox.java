// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.cpu;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * -Dcadence.cpu.entityBox=true: Sodium's entity culling (SodiumWorldRenderer.isEntityVisible, run by its wrapper around
 * EntityRenderer.shouldRender) computes the entity's culling box, then vanilla's shouldRender computes the same box again for the
 * frustum test. The box Sodium just computed is handed to vanilla's call for the same renderer, entity and partial tick, once:
 * set in isEntityVisible (cleared at its start), taken and cleared by shouldRender. Nothing changes the entity between the two.
 */
public final class EntityBox {
	public static final boolean AB = "ab".equals(System.getProperty("cadence.cpu.entityBox"));
	public static final boolean ON = Boolean.getBoolean("cadence.cpu.entityBox") || AB;
	/** Whether reuse is on for the current frame's entity pass (always, unless entityBox=ab alternates it). */
	public static boolean active = true;
	private static EntityRenderer<?, ?> renderer;
	private static Entity entity;
	private static float partialTicks;
	private static AABB box;
	private static Thread owner;

	private EntityBox() {
	}

	public static void clear() {
		box = null;
		entity = null;
		renderer = null;
	}

	public static void put(EntityRenderer<?, ?> r, Entity e, float pt, AABB b) {
		if (!active) return;
		renderer = r;
		entity = e;
		partialTicks = pt;
		box = b;
		owner = Thread.currentThread();
	}

	/** The box for (r, e, pt) if it's the one just computed on this thread, else null; either way the slot is emptied. */
	public static AABB take(EntityRenderer<?, ?> r, Entity e, float pt) {
		AABB b = box;
		boolean hit = b != null && renderer == r && entity == e && Float.floatToRawIntBits(partialTicks) == Float.floatToRawIntBits(pt) && owner == Thread.currentThread();
		clear();
		return hit ? b : null;
	}
}
