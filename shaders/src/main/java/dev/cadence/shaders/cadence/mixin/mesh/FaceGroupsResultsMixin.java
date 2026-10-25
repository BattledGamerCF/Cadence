package dev.cadence.shaders.cadence.mixin.mesh;

import dev.cadence.shaders.cadence.facecull.FaceGroups;
import dev.cadence.shaders.cadence.facecull.FaceGroupsHolder;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Cadence (FaceCull): a compile's face groups, carried to the compiled mesh. */
@Mixin(SectionCompiler.Results.class)
public abstract class FaceGroupsResultsMixin implements FaceGroupsHolder {
	@Unique private Map<ChunkSectionLayer, FaceGroups> cadence$faceGroups;

	@Override
	public Map<ChunkSectionLayer, FaceGroups> cadence$faceGroups() {
		return cadence$faceGroups;
	}

	@Override
	public void cadence$setFaceGroups(Map<ChunkSectionLayer, FaceGroups> groups) {
		cadence$faceGroups = groups;
	}
}
