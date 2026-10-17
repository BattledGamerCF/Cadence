package dev.cadence.shaders.cadence;

import dev.cadence.shaders.CadencePackGate;
import dev.cadence.shaders.ShaderLoaderConfig;
import dev.cadence.shaders.ShaderLoaderMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence: switches shader packs in a running game. Possible when this launch has the loader's hooks applied
 * (CadencePackGate.hooksApplied: a pack selected at launch, or live switching on). A switch, on the render thread between
 * frames: the old pack's runtime is closed (its GPU resources freed) and terrain geometry reset; the new pack is loaded and
 * installed (its programs compile on the next frame in a world); every chunk is rebuilt (the vertex layout depends on the
 * pack), as F3+A does. A pack that fails to load leaves shaders off and says why in chat.
 */
public final class ShaderSwitcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence-shaders");
	/** The pack in use now, "" for off. */
	private static volatile String active = CadencePackGate.selectedPack();

	private ShaderSwitcher() {
	}

	public static String active() {
		return active;
	}

	private static volatile boolean failing;
	private static volatile Runnable pendingOff;

	/**
	 * A frame with the pack failed (a program the GPU compiler refused, a missing resource): shaders go off for this session
	 * and chat says why, instead of the game stopping with a crash report. Once per failure. The pack goes off when the next
	 * frame begins (beforeFrame), never in the middle of this one: the rest of this frame's draws still belong to the pack.
	 */
	public static void failedInFrame(Throwable failure) {
		if (failing) return;
		failing = true;
		String pack = active;
		LOGGER.error("Shader pack {} failed while drawing; shaders are off", pack, failure);
		Minecraft mc = Minecraft.getInstance();
		pendingOff = () -> {
			try {
				ShaderLoaderMod.deactivate();
				active = "";
				reload(mc, MODE);
				Throwable cause = failure;
				while (cause.getCause() != null) cause = cause.getCause();
				String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
				if (why.length() > 160) why = why.substring(0, 160) + "...";
				tell(mc, Component.literal("Shader pack " + ShaderToggle.display(pack) + " stopped: " + why + " — shaders are off.")
					.withStyle(ChatFormatting.RED));
			} finally {
				failing = false;
			}
		};
	}

	/** Called as a frame begins: a pack that failed in the last frame goes off now. */
	public static void beforeFrame() {
		Runnable off = pendingOff;
		if (off == null) return;
		pendingOff = null;
		off.run();
	}

	/** How the chunks are reloaded after a switch: -Dcadence.switchMode, default rebuffer (see reload). */
	private static final String MODE = System.getProperty("cadence.switchMode", "rebuffer");

	/** Switch to {@code pack} (a name in shaderpacks/, already saved to the config) or "" for off. */
	public static void apply(String pack) {
		apply(pack, MODE);
	}

	/** Reloads the active pack (its options or quality profile changed). */
	public static void reload() {
		String pack = active;
		if (!pack.isEmpty()) apply(pack, MODE, true);
	}

	/** As apply(pack), with the chunk reload mode named: rebuffer, dispose or plain (see reload). */
	public static void apply(String pack, String mode) {
		apply(pack, mode, false);
	}

	/** Cadence: the newest switch asked for; an older one still preparing is dropped when it finishes. */
	private static final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

	private static void apply(String pack, String mode, boolean force) {
		if (!CadencePackGate.hooksApplied() || (!force && pack.equals(active))) return;
		Minecraft mc = Minecraft.getInstance();
		int ticket = generation.incrementAndGet();
		if (pack.isEmpty()) {
			mc.execute(() -> finish(mc, ticket, pack, mode, null, null, System.nanoTime()));
			return;
		}
		// Cadence: the pack is read and its programs and images prepared on a worker thread while the old picture keeps
		// drawing (0.4-0.6 s of render-thread work at each switch or settings change before); then it's installed between
		// two frames. -Dcadence.switchOnRenderThread=true: all of it on the render thread, as before.
		long t0 = System.nanoTime();
		Runnable prepare = () -> {
			ShaderLoaderMod.Prepared prepared = null;
			Exception failure = null;
			try {
				FabricLoader loader = FabricLoader.getInstance();
				ShaderLoaderConfig config = ShaderLoaderConfig.load(loader.getGameDir(), loader.getConfigDir());
				if (config.pack() == null) throw new IllegalStateException("no pack saved in the config");
				prepared = ShaderLoaderMod.prepare(config);
				if (generation.get() == ticket) prepared.runtime().awaitWarm();
			} catch (Exception | LinkageError e) {
				failure = e instanceof Exception x ? x : new RuntimeException(e);
			}
			var ready = prepared;
			var failed = failure;
			mc.execute(() -> finish(mc, ticket, pack, mode, ready, failed, t0));
		};
		if (Boolean.getBoolean("cadence.switchOnRenderThread")) mc.execute(prepare);
		else Thread.ofPlatform().daemon().name("cadence-pack-switch").start(prepare);
	}

	private static void finish(Minecraft mc, int ticket, String pack, String mode, ShaderLoaderMod.Prepared prepared,
			Exception failure, long t0) {
		if (generation.get() != ticket) { // a newer switch was asked for meanwhile
			if (prepared != null) prepared.runtime().close();
			return;
		}
		long installStart = System.nanoTime();
		String before = active;
		Object geometryBefore = dev.cadence.shaders.geometry.TerrainShaderGeometry.configurationKey();
		{
			try {
				if (failure != null) throw failure;
				ShaderLoaderMod.deactivate();
				active = "";
				if (prepared != null) {
					ShaderLoaderMod.install(prepared);
					active = pack;
				}
				LOGGER.info("Shaders switched to {} in {} ms ({} ms on the render thread; chunks rebuilding)",
					pack.isEmpty() ? "off" : pack, (System.nanoTime() - t0) / 1_000_000, (System.nanoTime() - installStart) / 1_000_000);
				tell(mc, Component.literal("Shaders: " + (pack.isEmpty() ? "Off" : ShaderToggle.display(pack))).withStyle(ChatFormatting.GRAY));
			} catch (Exception | LinkageError e) {
				LOGGER.error("Couldn't switch to shader pack {}; shaders are off", pack, e);
				try {
					ShaderLoaderMod.deactivate();
				} catch (RuntimeException ignored) {
					// already off
				}
				active = "";
				tell(mc, Component.literal("Couldn't load shader pack " + ShaderToggle.display(pack) + ": " + e.getMessage()
					+ " — shaders are off.").withStyle(ChatFormatting.RED));
			}
			// Cadence: a quality preset (or the same pack again) usually leaves every block's pack id and the vertex layout as
			// they were; the compiled chunks are then exactly what a rebuild would produce, so they are kept (a preset change
			// takes about a second instead of rebuilding the world). -Dcadence.keepChunksOnReload=false: always rebuild.
			String difference = dev.cadence.shaders.geometry.TerrainShaderGeometry.geometryDifference(geometryBefore,
				dev.cadence.shaders.geometry.TerrainShaderGeometry.configurationKey());
			boolean keep = !"false".equals(System.getProperty("cadence.keepChunksOnReload")) && difference == null;
			if (keep) LOGGER.info("Shader reload: terrain geometry unchanged (same layout and block ids), chunks kept");
			else {
				LOGGER.info("Shader reload: chunks rebuilt ({})", difference == null ? "keepChunksOnReload=false" : difference);
				reload(mc, mode);
			}
			RenderScale.packSwitched(before, active);
			if (!before.equals(active)) dev.cadence.shaders.cadence.system.HeatControl.workloadChanged();
			RenderScale.settle(); // the new pack's first frames say nothing about its cost
			SwitchProbe.start("switch to " + (pack.isEmpty() ? "off" : pack) + " (" + mode + ")");
		}
	}

	/**
	 * Every chunk rebuilt in the new terrain vertex layout. The dispatcher's per-layer GPU buffers align allocations to the
	 * vertex size they were made with, so they must change too.
	 * <ul>
	 * <li>rebuffer (default): vanilla's reload of every chunk (LevelExtractor.allChanged: a new section tracker, every
	 * section dirty, then invalidateCompiledGeometry), with the per-layer buffers rebuilt in place at its safe point
	 * (ChunkBufferRebuild).</li>
	 * <li>dispose: vanilla's level-change sequence (setLevel(null) then setLevel(level): the dispatcher disposed and a new
	 * one made). Left an empty world in testing; kept for comparison.</li>
	 * <li>plain: allChanged only; the old buffers keep the old alignment (scrambled terrain). For comparison only.</li>
	 * </ul>
	 */
	private static void reload(Minecraft mc, String mode) {
		if (mc.level == null) return;
		switch (mode) {
			case "dispose" -> {
				var level = mc.level;
				mc.levelExtractor.setLevel(null);
				mc.levelExtractor.setLevel(level);
			}
			case "plain" -> mc.levelExtractor.allChanged();
			default -> {
				ChunkBufferRebuild.request();
				mc.levelExtractor.allChanged();
			}
		}
	}

	private static void tell(Minecraft mc, Component message) {
		if (mc.player != null) mc.player.sendSystemMessage(message);
	}
}
