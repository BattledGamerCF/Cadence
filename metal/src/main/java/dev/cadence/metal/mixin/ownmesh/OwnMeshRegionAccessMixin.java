// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnRegionAccess;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** -Dcadence.own.mesh.prefill: the region's section copies, for OwnRegion's unpack. */
@Mixin(RenderSectionRegion.class)
abstract class OwnMeshRegionAccessMixin implements OwnRegionAccess {
	@Shadow
	@Final
	private SectionCopy[] sections;

	@org.spongepowered.asm.mixin.Unique
	private dev.cadence.metal.own.@org.jspecify.annotations.Nullable OwnRegion cadence$own;

	@Override
	public dev.cadence.metal.own.@org.jspecify.annotations.Nullable OwnRegion cadence$own() {
		return this.cadence$own;
	}

	@Override
	public void cadence$own(dev.cadence.metal.own.@org.jspecify.annotations.Nullable OwnRegion own) {
		this.cadence$own = own;
	}

	@Override
	public SectionCopy[] cadence$sections() {
		return this.sections;
	}
}
