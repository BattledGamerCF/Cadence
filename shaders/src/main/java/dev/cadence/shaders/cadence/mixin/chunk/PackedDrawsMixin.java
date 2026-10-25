package dev.cadence.shaders.cadence.mixin.chunk;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.DynamicGpuData;
import dev.cadence.shaders.cadence.alloc.PackedDraws;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cadence (PackedDraws): opaque/cutout draw groups get a pooled packed list; the pool is recycled around each prepare. */
@Mixin(LevelRenderer.class)
public abstract class PackedDrawsMixin {
	@ModifyArg(method = "extractSectionDrawGroups", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer$ChunkDrawGroup;<init>(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/List;)V"),
		index = 3)
	private List<?> cadence$packedList(List<?> draws, @Local ChunkSectionLayer layer) {
		return PackedDraws.packs(layer) ? PackedDraws.take() : draws;
	}

	/**
	 * Opaque/cutout: the record's ints are stashed and the sentinel goes to the list's add (no object). A plain redirect,
	 * not a wrap: MixinExtras' operation object per call was as much garbage as the records.
	 */
	@Redirect(method = "extractSectionDrawGroups", at = @At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData$IndexedDraw"))
	private DynamicGpuData.IndexedDraw cadence$stashDraw(int indexCount, int instanceCount, int firstIndex, int baseVertex, int baseInstance,
		@Local ChunkSectionLayer layer, @Local net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section) {
		if (PackedDraws.packs(layer)) return PackedDraws.stash(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
		// translucent (vanilla's reversed lists): the section's last records, reused when every value matches
		if (section instanceof dev.cadence.shaders.cadence.alloc.SectionInfoHolder holder) {
			DynamicGpuData.IndexedDraw[] last = holder.cadence$lastDraws();
			for (int i = 0; i < last.length; i++) {
				DynamicGpuData.IndexedDraw d = last[i];
				if (d != null && d.indexCount() == indexCount && d.instanceCount() == instanceCount && d.firstIndex() == firstIndex
					&& d.baseVertex() == baseVertex && d.baseInstance() == baseInstance) {
					if (i == 1) {
						last[1] = last[0];
						last[0] = d;
					}
					return d;
				}
			}
			DynamicGpuData.IndexedDraw d = new DynamicGpuData.IndexedDraw(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
			last[1] = last[0];
			last[0] = d;
			return d;
		}
		return new DynamicGpuData.IndexedDraw(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
	}

	@Inject(method = "prepareChunkRendersIndirect", at = @At("HEAD"))
	private void cadence$recycleBefore(CallbackInfoReturnable<?> cir) {
		PackedDraws.recycle();
	}

	@Inject(method = "prepareChunkRendersIndirect", at = @At("RETURN"))
	private void cadence$recycleAfter(CallbackInfoReturnable<?> cir) {
		PackedDraws.recycle();
	}
}
