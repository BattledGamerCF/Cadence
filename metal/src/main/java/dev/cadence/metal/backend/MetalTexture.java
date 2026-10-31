// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

class MetalTexture extends BaseGpuTexture {
	final long handle;
	/** The encoder log: the submit this texture was last bound in (cold binds: not in the last 30). */
	long encLastBind = -1000;
	/** -Dcadence.metal.residentAnim: in the animation frames' residency set (removed before release), and its bytes. */
	boolean resident;
	long residentBytes;
	/** Created with ShaderWrite usage (a compute kernel may write it). */
	boolean shaderWrite;
	/** HDR: the game's frame colour made as RGBA16Float (the game still sees RGBA8). */
	boolean floatFrame;
	private final MetalEncoder encoder;
	private boolean closed;

	/**
	 * A clear that hasn't touched memory yet. Tile GPUs pay a full framebuffer write for a standalone clear and another
	 * read when the next pass loads it; folding the clear into the next pass's load action costs nothing.
	 */
	@Nullable Vector4fc pendingColorClear;
	double pendingDepthClear = Double.NaN;

	MetalTexture(MetalEncoder encoder, long handle, @GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height, int layers, int mips) {
		super(usage, label, format, width, height, layers, mips);
		this.encoder = encoder;
		this.handle = handle;
		GpuMemory.LIVE_TEXTURES.add(this);
	}

	/**
	 * Cadence: a mip level's extent as Metal defines it, never below 1. BaseGpuTexture shifts without a clamp, so narrow
	 * textures (a shader pack's 1-pixel-high buffers) report 0 for their small mips. Vanilla's mip counts never get there:
	 * unchanged for it. (Found and fixed the same way in minecraft-metal-renderer, MIT.)
	 */
	@Override
	public int getWidth(int mip) {
		return Math.max(1, super.getWidth(mip));
	}

	@Override
	public int getHeight(int mip) {
		return Math.max(1, super.getHeight(mip));
	}

	MetalEncoder encoder() {
		return this.encoder;
	}

	boolean hasPendingClear() {
		return this.pendingColorClear != null || !Double.isNaN(this.pendingDepthClear);
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			GpuMemory.LIVE_TEXTURES.remove(this);
			if (this.resident) {
				Native.textureResident(this.encoder.ctx, this.handle, 0);
				MetalDevice.residentReleased(this);
			}
			this.encoder.releaseLater(this.handle);
		}
	}

	static final class View extends BaseGpuTextureView {
		final long handle;
		private final boolean ownsHandle;
		private final MetalEncoder encoder;
		private boolean closed;

		View(MetalEncoder encoder, MetalTexture texture, int baseMip, int mips) {
			super(texture, baseMip, mips);
			this.encoder = encoder;
			// A view of the whole texture is the texture itself; only sub-ranges need a real MTLTexture view.
			this.ownsHandle = baseMip != 0 || mips != texture.getMipLevels();
			this.handle = this.ownsHandle ? Native.textureView(texture.handle, baseMip, mips) : texture.handle;
		}

		MetalTexture metalTexture() {
			return (MetalTexture) this.texture();
		}

		@Override
		public boolean isClosed() {
			return this.closed || this.texture().isClosed();
		}

		@Override
		public void close() {
			if (!this.closed) {
				this.closed = true;
				if (this.ownsHandle) this.encoder.releaseLater(this.handle);
			}
		}
	}

	static MetalTexture of(GpuTextureView view) {
		return (MetalTexture) view.texture();
	}
}
