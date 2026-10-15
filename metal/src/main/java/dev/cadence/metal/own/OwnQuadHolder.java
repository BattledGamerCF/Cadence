// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.own;

import org.jspecify.annotations.Nullable;

/** -Dcadence.own.mesh: mixed into SectionCompiler.Results and CompiledSectionMesh, carries a compile's OwnQuads to the upload hook. */
public interface OwnQuadHolder {
	@Nullable OwnQuads cadence$quads();

	void cadence$setQuads(@Nullable OwnQuads quads);
}
