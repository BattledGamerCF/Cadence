// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnQuadHolder;
import dev.cadence.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** -Dcadence.own.mesh=true: a compile's own quads, from our mesher to the compiled mesh. */
@Mixin(SectionCompiler.Results.class)
abstract class OwnMeshResultsMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads cadence$quads;

	@Override
	public @Nullable OwnQuads cadence$quads() {
		return this.cadence$quads;
	}

	@Override
	public void cadence$setQuads(@Nullable OwnQuads quads) {
		this.cadence$quads = quads;
	}
}
