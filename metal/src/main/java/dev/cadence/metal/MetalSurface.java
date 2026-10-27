// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import org.lwjgl.sdl.SDLMetal;

/**
 * A CAMetalLayer on the SDL window. The drawable is fetched at blit time rather than frame start: holding it for
 * the whole frame only adds latency and can stall on a drawable the display hasn't released yet.
 */
final class MetalSurface implements GpuSurfaceBackend {
	/** With vsync off, present only the last frame that makes each refresh (see mc_pace); -Dcadence.metal.pace=false presents every frame. */
	private static final boolean PACE = Boolean.parseBoolean(System.getProperty("cadence.metal.pace", "true"));
	/** How long before a refresh a finished frame has to reach the compositor to be shown on it. */
	/** -Dcadence.metal.paceMarginMs: how long before a refresh the paced present must be done (the compositor's latch); default 2. */
	private static final double PACE_MARGIN_S = Double.parseDouble(System.getProperty("cadence.metal.paceMarginMs", "2")) / 1000;
	/** -Dcadence.metal.drawables=2|3: the layer's maximumDrawableCount (default 3). */
	private static final int DRAWABLES = Integer.getInteger("cadence.metal.drawables", 0);
	/**
	 * -Dcadence.metal.paceSync=true: the paced presents (vsync off) go out with display sync on, so each latches at a vblank. On
	 * the laptop's 120 Hz 5K display at ~1800 fps, paced presents without display sync reach the screen in pairs ~1.9 ms apart
	 * every 16.7 ms (a 60 Hz cadence); with vsync the grid is a clean 8.33 ms.
	 */
	private static final boolean PACE_SYNC = Boolean.getBoolean("cadence.metal.paceSync");
	/**
	 * -Dcadence.metal.paceAdapt=true: the pacer's margin learns the compositor's latch from scanout times (mc_pace in mcmetal.m): a
	 * paced frame shown more than half a refresh after the refresh it was aimed at adds lead, a frame on time takes a little away.
	 */
	static final boolean PACE_ADAPT = Boolean.getBoolean("cadence.metal.paceAdapt");
	/** The layer marked opaque (the compositor skips the blend; full screen can go direct to display); -Dcadence.metal.opaque=false: as before. */
	static final boolean OPAQUE = !"false".equals(System.getProperty("cadence.metal.opaque"));
	private boolean paced;
	private final long ctx;
	private final MetalEncoder encoder;
	private final long view;
	private final long layer;

	MetalSurface(long ctx, MetalEncoder encoder, long window) {
		this.ctx = ctx;
		this.encoder = encoder;
		this.view = SDLMetal.SDL_Metal_CreateView(window);
		this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
	}

	/** The window's surface (one per game) and its last configuration, for the vsync override. Render thread. */
	private static MetalSurface current;

	/** The window's CAMetalLayer (0 before the first configure). */
	static long currentLayer() {
		MetalSurface s = current;
		return s == null ? 0 : s.layer;
	}
	private GpuSurface.Configuration lastConfig;
	/** Render Max FPS: no vsync while it's on, whatever the option says (MetalBridge.uncappedPresent). */
	private static boolean immediate;

	/** Presents latched to the display's refreshes (FramePacing: a frame cap at the display's rate), whatever the VSync option. */
	private static boolean sync;

	static void sync(boolean on) {
		if (sync == on) return;
		sync = on;
		MetalSurface s = current;
		if (s != null && s.lastConfig != null) s.configure(s.lastConfig);
	}

	/** Vsync off (on: true) or back to the option (false); applied at once. Render thread. */
	static void immediate(boolean on) {
		if (immediate == on) return;
		immediate = on;
		MetalSurface s = current;
		if (s != null && s.lastConfig != null) s.configure(s.lastConfig);
	}

	@Override
	public void configure(GpuSurface.Configuration config) {
		current = this;
		this.lastConfig = config;
		boolean vsync = config.presentMode() == GpuSurface.PresentMode.FIFO && !immediate;
		this.paced = !vsync && PACE;
		if (PACE_ADAPT) Native.paceAdapt(this.paced ? 1 : 0);
		Native.layerConfigure(this.ctx, this.layer, config.width(), config.height(), (vsync || (PACE_SYNC || sync && !immediate) && this.paced ? 1 : 0) | DRAWABLES << 8
			| (Retina.on() ? 0 : 1 << 16) | (OPAQUE ? 0 : 1 << 17) | (Hdr.layerHdr() ? 1 << 18 : 0));
	}

	/** The last configuration again (HDR changed the layer's format). */
	void reconfigure() {
		if (this.lastConfig != null) this.configure(this.lastConfig);
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() {
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		Hdr.frame(this);
		// cadence.rec hook: the opt-in recorder (-Dcadence.rec, dev.cadence.metal.rec) takes the finished frame, GUI included. Rec.ON is a constant false without it.
		if (dev.cadence.metal.rec.Rec.ON) dev.cadence.metal.rec.Rec.frame(this.encoder, textureView);
		// (Instant Replay: nothing unless it's on; not while Render Max FPS presents its own frame, which this isn't, BH4-04)
		if (!dev.cadence.metal.record.Record.active()) dev.cadence.metal.rec.Replay.frame(this.encoder, textureView);
		long t0 = WaitStats.ON ? System.nanoTime() : 0;
		// Frame Generation: every real frame is presented (the cap times them), Smooth's generated one first
		boolean fg = dev.cadence.metal.framegen.FrameGen.running() && !MetalEncoder.PRESENT_ACQUIRE;
		if (fg) dev.cadence.metal.framegen.FrameGen.beforePresent(((MetalTexture.View) textureView).handle);
		if (!fg && this.paced && !Native.pace(PACE_MARGIN_S)) {
			if (WaitStats.ON) WaitStats.wait(WaitStats.PACE, System.nanoTime() - t0, 0);
			return;
		}
		long t1 = WaitStats.ON ? System.nanoTime() : 0;
		if (WaitStats.ON && this.paced) WaitStats.wait(WaitStats.PACE, t1 - t0, 0);
		if (MetalEncoder.PRESENT_ACQUIRE) {
			this.encoder.presentAcquire(this.layer, textureView); // no nextDrawable here: the present side acquires it
			return;
		}
		MetalEvents.Operation event = MetalEvents.begin("nextDrawable", -1, 0);
		long drawable;
		try {
			drawable = Native.layerNext(this.layer);
		} finally {
			MetalEvents.end(event);
		}
		if (WaitStats.ON) WaitStats.wait(WaitStats.DRAWABLE, System.nanoTime() - t1, 0);
		if (drawable == 0) return; // no drawable (window hidden): skip the frame's present
		long record = dev.cadence.metal.record.Record.presentHandle();
		if (record != 0) this.encoder.presentHandle(drawable, record, dev.cadence.metal.record.Record.presentFence());
		else this.encoder.presentTexture(drawable, textureView);
		this.encoder.afterGpuFinishes(() -> Native.release(drawable));
	}

	@Override
	public void present() {
		// The present was scheduled on the frame's command buffer in blitFromTexture and happens when it's committed.
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}

	@Override
	public void close() {
		SDLMetal.SDL_Metal_DestroyView(this.view);
	}
}
