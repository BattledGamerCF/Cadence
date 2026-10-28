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

	/** Why Metal couldn't start this launch (the game then runs on the next backend in the list), or null. */
	public static volatile @Nullable String failure;

	/**
	 * Metal's device; any failure (not only the native context's) becomes a BackendCreationException, so the game moves on to
	 * the next backend (OpenGL) instead of crashing, and the player is told once the game is up (a toast). -Dcadence.metal.fakeFailure=true
	 * fails it on purpose, to test that path.
	 */
	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		try {
			if (Boolean.getBoolean("cadence.metal.fakeFailure")) throw new IllegalStateException("a test failure (-Dcadence.metal.fakeFailure)");
			return new FrontendGpuDevice(new MetalDevice());
		} catch (BackendCreationException | RuntimeException | LinkageError e) {
			String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			failure = why;
			// (what Cadence's other parts read to know whether Metal draws: shader packs, the Graphics API option)
			System.setProperty("cadence.metal", "false");
			System.out.println("[cadence] Metal couldn't start (" + why + "); the game runs on Minecraft's own renderer instead");
			e.printStackTrace(System.out);
			tellLater(why);
			throw e instanceof BackendCreationException b ? b : new BackendCreationException("Cadence's Metal renderer couldn't start: " + why, BackendCreationException.Reason.OTHER);
		}
	}

	/** A toast once the game's window is up: Cadence's renderer isn't running, why, and that the game itself is fine. */
	private static void tellLater(String why) {
		Thread.ofVirtual().name("cadence-metal-failure").start(() -> {
			for (int i = 0; i < 1200; i++) {
				var mc = net.minecraft.client.Minecraft.getInstance();
				if (mc != null && mc.gui != null && (mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen || mc.level != null)) {  // (once loading is done)
					mc.execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(),
						new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10000L),
						net.minecraft.network.chat.Component.literal("Cadence's Metal renderer couldn't start"),
						net.minecraft.network.chat.Component.literal("Playing on Minecraft's own renderer. " + (why.length() > 90 ? why.substring(0, 90) + "..." : why))));
					return;
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
			}
		});
	}
}
