package dev.cadence.shaders.cadence.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Cadence (EntityCull): the renderer's own culling box and opt-out, as vanilla's frustum test uses them. */
@Mixin(EntityRenderer.class)
public interface EntityRendererCullInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB cadence$cullBox(Entity entity, float partial);

	@Invoker("affectedByCulling")
	boolean cadence$affectedByCulling(Entity entity);
}
