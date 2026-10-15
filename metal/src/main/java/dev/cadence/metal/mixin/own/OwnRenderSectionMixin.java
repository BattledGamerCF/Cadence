// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.own;

import java.nio.ByteBuffer;
import dev.cadence.metal.own.OwnTerrain;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A compiled section's solid and cutout layers go to our arena on the worker that meshed them (vanilla's uber buffers and their
 * staging copy are skipped for those layers), and the section's mesh changes reach our section table.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class OwnRenderSectionMixin {
	@Shadow
	@Final
	public int index;
	@Shadow
	private long uploadedTime;
	@Shadow
	@Final
	private BlockPos.MutableBlockPos renderOrigin;
	@Shadow
	@Final
	SectionRenderDispatcher this$0;

	@Shadow
	public abstract SectionMesh getSectionMesh();

	@Shadow
	private void checkSectionMesh(CompiledSectionMesh compiledSectionMesh) {
	}

	@Shadow
	private void releaseSectionMesh(SectionMesh oldMesh) {
	}

	@Inject(method = "addSectionBuffersToUberBuffer", at = @At("HEAD"), cancellable = true)
	private void cadence$ownStore(ChunkSectionLayer layer, CompiledSectionMesh key, @Nullable ByteBuffer vertexBuffer, @Nullable ByteBuffer indexBuffer,
		CallbackInfoReturnable<Boolean> cir) {
		OwnTerrain own = OwnTerrain.get();
		if (own == null || !OwnTerrain.takes(layer) || key.getSectionDraw(layer) == null) return;
		if (layer == ChunkSectionLayer.TRANSLUCENT) {
			if (indexBuffer == null) return;  // (vanilla always sorts translucent quads)
			if (vertexBuffer == null) {
				// a resort: only the order changes; vanilla marks nothing for it either
				own.resorted(key, indexBuffer);
				cir.setReturnValue(true);
				return;
			}
			own.storeTranslucent(key, vertexBuffer, indexBuffer);
		} else {
			if (vertexBuffer == null) return;
			// -Dcadence.own.mesh: our mesher's quads (the MeshData is a placeholder)
			dev.cadence.metal.own.OwnQuads quads = key instanceof dev.cadence.metal.own.OwnQuadHolder h ? h.cadence$quads() : null;
			if (quads != null) {
				own.storeQuads(key, layer, quads.layers[layer == ChunkSectionLayer.SOLID ? 0 : 1]);
				own.storeTieGroups(key, quads.groups());  // (-Dcadence.own.mesh.tieGroups; the same array for both layers' calls)
			} else {
				own.store(key, layer, vertexBuffer);
			}
		}
		if (dev.cadence.metal.own.OwnLife.ON) dev.cadence.metal.own.OwnLife.delay();
		this.this$0.lock();
		try {
			// OwnLife (-Dcadence.own.int.lifeFix): a compile cancelled by a reset or a newer compile doesn't publish; its mesh is released
			java.util.concurrent.atomic.AtomicBoolean cancelled = null;
			SectionMesh prev = null;
			if (dev.cadence.metal.own.OwnLife.ON) {
				Object task = dev.cadence.metal.own.OwnLife.task();
				cancelled = task instanceof OwnIntSectionTaskAccess t ? t.cadence$cancelled() : null;
				if (dev.cadence.metal.own.OwnLife.preStore((SectionRenderDispatcher.RenderSection) (Object) this, key, layer, cancelled)) {
					this.releaseSectionMesh(key);
					cir.setReturnValue(true);
					return;
				}
				prev = this.getSectionMesh();
			}
			key.setVertexBufferUploaded(layer);
			key.setIndexBufferUploaded(layer);
			this.checkSectionMesh(key);
			if (dev.cadence.metal.own.OwnLife.CHECK) dev.cadence.metal.own.OwnLife.postStore((SectionRenderDispatcher.RenderSection) (Object) this, key, prev, cancelled);
		} finally {
			this.this$0.unlock();
		}
		cir.setReturnValue(true);
	}

	@Inject(method = "setSectionMesh", at = @At("RETURN"))
	private void cadence$ownPublished(SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.published(this.index, sectionMesh, this.renderOrigin.getX(), this.renderOrigin.getY(), this.renderOrigin.getZ(), this.uploadedTime);
	}

	@Inject(method = "reset", at = @At("TAIL"))
	private void cadence$ownCleared(CallbackInfo ci) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.cleared(this.index);
	}

	@Inject(method = "releaseSectionMesh", at = @At("HEAD"))
	private void cadence$ownReleased(SectionMesh oldMesh, CallbackInfo ci) {
		OwnTerrain own = OwnTerrain.get();
		if (own != null) own.released(oldMesh);
	}
}
