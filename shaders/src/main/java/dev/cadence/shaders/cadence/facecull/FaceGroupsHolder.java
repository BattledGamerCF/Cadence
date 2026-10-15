package dev.cadence.shaders.cadence.facecull;

import dev.cadence.shaders.cadence.facecull.FaceGroups;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Cadence (FaceCull): implemented by SectionCompiler.Results and CompiledSectionMesh through mixins. */
public interface FaceGroupsHolder {
	Map<ChunkSectionLayer, FaceGroups> cadence$faceGroups();

	void cadence$setFaceGroups(Map<ChunkSectionLayer, FaceGroups> groups);

	/** The same groups packed into one array (FaceGroups.pack), or null; only on compiled meshes. */
	default int[] cadence$facePacked() {
		return null;
	}
}
