package dev.cadence.metal.mixin.logic;

import java.util.Map;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AmbientParticles: the system's per-attribute samplers (to check the attribute's layers). */
@Mixin(EnvironmentAttributeSystem.class)
public interface AmbientSystemAccess {
	@Accessor("attributeSamplers")
	Map<EnvironmentAttribute<?>, Object> cadence$attributeSamplers();
}
