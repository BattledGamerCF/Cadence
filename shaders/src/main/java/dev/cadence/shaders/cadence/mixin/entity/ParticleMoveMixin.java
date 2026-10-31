package dev.cadence.shaders.cadence.mixin.entity;

import dev.cadence.shaders.cadence.render.ParticlePhysics;
import java.util.List;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence: particles in open air skip the collision search (ParticlePhysics). */
@Mixin(Particle.class)
public abstract class ParticleMoveMixin {
	@Redirect(method = "move", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cadence$collide(CollisionContext context, Vec3 movement, AABB box, Level level, List<VoxelShape> entities) {
		if (ParticlePhysics.enabled && entities.isEmpty()) {
			if (ParticlePhysics.noShapes(level, box.expandTowards(movement))) {
				ParticlePhysics.skipped++;
				return movement;
			}
			ParticlePhysics.searched++;
		}
		return Entity.collideBoundingBox(context, movement, box, level, entities);
	}
}
