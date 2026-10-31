package dev.cadence.metal.mixin.logic;

import java.util.List;
import net.minecraft.world.attribute.EnvironmentAttributeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** AmbientParticles: one attribute's layers. */
@Mixin(targets = "net.minecraft.world.attribute.EnvironmentAttributeSystem$ValueSampler")
public interface AmbientSamplerAccess {
	@Accessor("layers")
	List<EnvironmentAttributeLayer<?>> cadence$layers();
}
