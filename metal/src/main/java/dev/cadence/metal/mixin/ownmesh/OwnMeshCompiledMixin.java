// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.ownmesh;

import dev.cadence.metal.own.OwnQuadHolder;
import dev.cadence.metal.own.OwnQuads;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * -Dcadence.own.mesh=true: the compiled mesh holds its compile's own quads until the upload hook (OwnRenderSectionMixin) has put
 * each own layer into the arena, on the same worker, in the same task.
 */
@Mixin(CompiledSectionMesh.class)
abstract class OwnMeshCompiledMixin implements OwnQuadHolder {
	@Unique
	private @Nullable OwnQuads cadence$quads;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void cadence$ownQuads(TranslucencyPointOfView translucencyPointOfView, SectionCompiler.Results results, long compileTaskStartTimeNs, CallbackInfo ci) {
		this.cadence$quads = ((OwnQuadHolder) (Object) results).cadence$quads();
	}

	@Override
	public @Nullable OwnQuads cadence$quads() {
		return this.cadence$quads;
	}

	@Override
	public void cadence$setQuads(@Nullable OwnQuads quads) {
		this.cadence$quads = quads;
	}
}
