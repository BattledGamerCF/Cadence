// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/** Native Metal rendering for Minecraft's renderpearl layer, next to Mojang's OpenGL and Vulkan (MoltenVK) backends. */
public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void loadLibrary() {
	}

	@Override
	public void unloadLibrary() {
	}

	@Override
	public long createWindow(@Nullable String title, int width, int height, long flags) {
		// Cadence (Retina): Retina off draws the game at the screen's point size, a quarter of the pixels, and macOS scales it up
		if (!Retina.on()) flags &= ~SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY;
		return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		return new FrontendGpuDevice(new MetalDevice());
	}
}
