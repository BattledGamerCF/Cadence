package dev.cadence.shaders.cadence.mixin;

import dev.cadence.shaders.cadence.facecull.FaceGroups;
import dev.cadence.shaders.cadence.facecull.FaceGroupsHolder;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cadence (FaceCull): the compiled mesh keeps its build's face groups. */
@Mixin(CompiledSectionMesh.class)
public abstract class FaceGroupsMeshMixin implements FaceGroupsHolder {
	@Unique private Map<ChunkSectionLayer, FaceGroups> cadence$faceGroups;
	@Unique private int[] cadence$facePacked;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void cadence$keep(TranslucencyPointOfView pov, SectionCompiler.Results results, long start, CallbackInfo ci) {
		cadence$faceGroups = ((FaceGroupsHolder) (Object) results).cadence$faceGroups();
		cadence$facePacked = FaceGroups.pack(cadence$faceGroups);
	}

	@Override
	public int[] cadence$facePacked() {
		return cadence$facePacked;
	}

	@Override
	public Map<ChunkSectionLayer, FaceGroups> cadence$faceGroups() {
		return cadence$faceGroups;
	}

	@Override
	public void cadence$setFaceGroups(Map<ChunkSectionLayer, FaceGroups> groups) {
		cadence$faceGroups = groups;
		cadence$facePacked = FaceGroups.pack(groups);
	}
}
