package dev.cadence.shaders.cadence.mixin.chunk;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import dev.cadence.shaders.cadence.alloc.SectionInfoHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cadence: each visible section's per-frame instance record (its origin and fade-in visibility) is reused from the last
 * frame when all four values are the same, which after the fade-in is every frame until the section moves. Records are
 * immutable values: the bytes written are identical. Was the largest render-thread garbage source without shaders.
 */
@Mixin(LevelRenderer.class)
public abstract class SectionInfoReuseMixin {
	@Redirect(method = "extractSectionDrawGroups", at = @At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData$ChunkSectionInfo"))
	private DynamicGpuData.ChunkSectionInfo cadence$reuseInfo(int x, int y, int z, float visibility,
		@Local SectionRenderDispatcher.RenderSection section) {
		if (section instanceof SectionInfoHolder holder) {
			DynamicGpuData.ChunkSectionInfo last = holder.cadence$lastInfo();
			if (last != null && last.x() == x && last.y() == y && last.z() == z
				&& Float.floatToRawIntBits(last.visibility()) == Float.floatToRawIntBits(visibility)) return last;
			DynamicGpuData.ChunkSectionInfo info = new DynamicGpuData.ChunkSectionInfo(x, y, z, visibility);
			holder.cadence$setLastInfo(info);
			return info;
		}
		return new DynamicGpuData.ChunkSectionInfo(x, y, z, visibility);
	}
}
