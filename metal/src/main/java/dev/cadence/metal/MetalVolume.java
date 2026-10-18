package dev.cadence.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;

/**
 * Cadence: 3D textures, which the game's GPU API doesn't offer: a shader pack's raw volume textures (Photon's cloud noise,
 * texture.deferred.colortex6 = image/worley.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE), sampled as sampler3D. Made
 * through the device as an ordinary texture while a depth is set for this thread (MetalDevice.createTexture), filled with one
 * copy (MetalEncoder.writeToVolume).
 */
public final class MetalVolume {
	private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

	private MetalVolume() {
	}

	static int creatingDepth() {
		Integer d = DEPTH.get();
		return d == null ? 0 : d;
	}

	/** A width x height x depth texture of {@code format}, sampled (not drawn into); null when the device isn't Cadence's. */
	public static GpuTexture create(GpuDevice device, String label, GpuFormat format, int width, int height, int depth) {
		DEPTH.set(depth);
		try {
			GpuTexture texture = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format,
				width, height, 1, 1);
			if (unwrap(texture) == null) {
				texture.close();
				return null;
			}
			return texture;
		} finally {
			DEPTH.remove();
		}
	}

	/** Fills the whole volume: rows, then images (depth slices), in the texture's format. */
	public static void upload(GpuTexture texture, ByteBuffer pixels, int width, int height, int depth) {
		MetalTexture t = unwrap(texture);
		if (t == null) throw new IllegalArgumentException("not a Cadence volume texture: " + texture);
		t.encoder().writeToVolume(t, pixels, width, height, depth);
	}

	private static MetalTexture unwrap(GpuTexture texture) {
		return texture instanceof MetalTexture m ? m : null;
	}
}
