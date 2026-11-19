package dev.cadence.metal.mixin.lod;

import java.util.List;
import dev.cadence.metal.lod.Lod;
import net.minecraft.client.renderer.Octree;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far terrain with vanilla's terrain (a shader pack): vanilla's sections more than the render distance above or below the
 * camera's are left out of the visible list, as our own terrain's cull does, and the far terrain draws those chunks
 * (Lod.updateMask). Vanilla's own rule is horizontal only: from high up it drew the land below, past the pack's
 * render-distance fog, as a fog-coloured square over the far terrain.
 */
@Mixin(SectionOcclusionGraph.class)
abstract class LodVerticalMixin {
	@Inject(method = "lambda$addSectionsInFrustum$0", at = @At("HEAD"), cancellable = true)
	private static void cadence$lodVertical(List<?> visible, List<?> nearby, Octree.Node node, boolean full, int depth, boolean near, CallbackInfo ci) {
		var section = node.getSection();
		if (section != null && Lod.verticallyFar(section.getSectionNode())) ci.cancel();
	}
}
