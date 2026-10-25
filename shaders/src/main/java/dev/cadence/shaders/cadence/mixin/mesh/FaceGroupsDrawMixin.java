package dev.cadence.shaders.cadence.mixin.mesh;

import com.llamalad7.mixinextras.sugar.Local;
import dev.cadence.shaders.cadence.facecull.FaceCull;
import dev.cadence.shaders.cadence.facecull.FaceGroupsHolder;
import java.util.List;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Cadence (FaceCull): one section layer's indirect draw split into the face groups that can face the viewer. */
@Mixin(LevelRenderer.class)
public abstract class FaceGroupsDrawMixin {
	// a redirect, not a wrap: MixinExtras allocated an operation object per draw per frame
	@Redirect(method = "extractSectionDrawGroups", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 2))
	private boolean cadence$split(List<Object> list, Object element, @Local SectionMesh mesh, @Local BlockPos origin, @Local ChunkSectionLayer layer) {
		if (element == dev.cadence.shaders.cadence.alloc.PackedDraws.SENTINEL) {
			// PackedDraws: the record wasn't built, its ints are stashed
			if (list instanceof dev.cadence.shaders.cadence.alloc.PackedDrawList packed) {
				if (FaceCull.packedGroups && mesh instanceof FaceGroupsHolder h) {
					// one array per mesh (FaceGroups.pack) instead of the map, its table, the groups and their arrays
					FaceCull.addPacked(packed, h.cadence$facePacked(), layer.ordinal() * dev.cadence.shaders.cadence.facecull.FaceGroups.STRIDE,
						h.cadence$faceGroups(), origin, layer);
					return true;
				}
				var groups = mesh instanceof FaceGroupsHolder h && h.cadence$faceGroups() != null ? h.cadence$faceGroups().get(layer) : null;
				FaceCull.addPacked(packed, groups, origin, layer);
				return true;
			}
			element = dev.cadence.shaders.cadence.alloc.PackedDraws.materialize();
		}
		if (!(element instanceof DynamicGpuData.IndexedDraw draw) || !(mesh instanceof FaceGroupsHolder holder)
			|| holder.cadence$faceGroups() == null) return list.add(element);
		FaceCull.addDraws(list, draw, holder.cadence$faceGroups().get(layer), origin, layer);
		return true;
	}
}
