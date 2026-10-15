// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.cpu;

import dev.cadence.metal.cpu.EntityBox;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** -Dcadence.cpu.entityBox: vanilla's frustum test reuses the box Sodium just computed (see EntityBox). */
@Mixin(EntityRenderer.class)
abstract class EntityBoxVanillaMixin {
	@Redirect(method = "shouldRender", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/world/phys/AABB;"))
	private AABB cadence$reuse(EntityRenderer<?, ?> self, Entity entity, float partialTicks) {
		AABB b = EntityBox.take(self, entity, partialTicks);
		return b != null ? b : ((EntityRendererBoxInvoker) self).cadence$box(entity, partialTicks);
	}
}
