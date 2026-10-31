// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.backend;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

record MetalSampler(long handle, AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter, FilterMode getMagFilter,
	int getMaxAnisotropy, OptionalDouble getMaxLod, long ctx, boolean comparison) implements GpuSampler {
	/** MTLCompareFunctionLessEqual. */
	private static final int LESS_EQUAL = 3;

	static MetalSampler create(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		return make(ctx, u, v, min, mag, anisotropy, maxLod, false);
	}

	/** Cadence: this sampler's twin that compares depth (less-or-equal) for hardware shadow filtering; see MetalGpuSampler. */
	MetalSampler comparisonTwin() {
		return this.comparison ? this : make(this.ctx, this.getAddressModeU, this.getAddressModeV, this.getMinFilter, this.getMagFilter,
			this.getMaxAnisotropy, this.getMaxLod, true);
	}

	private static MetalSampler make(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod,
		boolean comparison) {
		// Same mip policy as the Vulkan backend: a max LOD at or below 0.25 means "base level only".
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? 2 : 0;
		int au = MetalConst.addressMode(u), av = MetalConst.addressMode(v), fmin = MetalConst.filter(min), fmag = MetalConst.filter(mag);
		int aniso = Math.max(1, anisotropy);
		float maxClamp = (float) Math.max(0.25, lod);
		long handle = comparison ? Native.samplerNewCompare(ctx, au, av, fmin, fmag, mipFilter, aniso, maxClamp, LESS_EQUAL)
			: Native.samplerNew(ctx, au, av, fmin, fmag, mipFilter, aniso, maxClamp);
		return new MetalSampler(handle, u, v, min, mag, anisotropy, maxLod, ctx, comparison);
	}

	// Samplers are created once and cached by the game for its lifetime.
	@Override
	public boolean isClosed() {
		return false;
	}

	@Override
	public void close() {
	}
}
