package dev.cadence.shaders.cadence;

import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import dev.cadence.shaders.runtime.ShaderRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cadence diagnostic: logs the terrain state once a second for 15 s after a shader switch and after first entering a world,
 * so a working launch-with-pack can be compared line by line with a live switch. Logging only; changes nothing.
 * Lines start with "[probe]". -Dcadence.probe=false turns it off.
 */
public final class SwitchProbe {
	private static final Logger LOGGER = LoggerFactory.getLogger("cadence-shaders");
	// Diagnostics only: on for self-test runs or with -Dcadence.probe=true (normal play keeps the log quiet).
	private static final boolean ON = Boolean.getBoolean("cadence.probe") || System.getProperty("cadence.selftest") != null;

	private SwitchProbe() {
	}

	/** Once the first world is entered (launch case). */
	public static void onFirstWorld() {
		if (!ON) return;
		Thread t = new Thread(() -> {
			Minecraft mc = Minecraft.getInstance();
			for (int i = 0; i < 600 && mc.level == null; i++) sleep(1000);
			if (mc.level != null) run("world entered");
		}, "cadence-probe-wait");
		t.setDaemon(true);
		t.start();
	}

	/** After a live switch. */
	public static void start(String reason) {
		if (!ON) return;
		Thread t = new Thread(() -> run(reason), "cadence-probe");
		t.setDaemon(true);
		t.start();
	}

	private static void run(String reason) {
		Minecraft mc = Minecraft.getInstance();
		for (int i = 0; i <= 15; i++) {
			int second = i;
			mc.execute(() -> log(mc, reason, second));
			sleep(1000);
		}
	}

	static void log(Minecraft mc, String reason, int second) {
		try {
			StringBuilder layers = new StringBuilder();
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				layers.append(' ').append(layer.label()).append("[stride=").append(layer.vertexFormat().getVertexSize())
					.append(" pipe=").append(layer.pipeline(false).getLocation()).append(']');
			}
			var renderer = mc.levelRenderer;
			var dispatcher = renderer.sectionRenderDispatcher();
			// Terrain actually present: of the visible sections, how many are still uncompiled, how many have anything to
			// draw, and how many have an uploaded solid-layer draw.
			int uncompiled = 0, renderable = 0, solidDraws = 0;
			for (var section : renderer.visibleSections()) {
				var mesh = section.getSectionMesh();
				if (mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) uncompiled++;
				if (mesh.hasRenderableLayers()) renderable++;
				if (mesh.getSectionDraw(ChunkSectionLayer.SOLID) != null) solidDraws++;
			}
			layers.append(" | sections uncompiled=").append(uncompiled).append(" renderable=").append(renderable)
				.append(" solidDraws=").append(solidDraws);
			LOGGER.info("[probe] {} +{}s: pack={} runtime={} terrainGeometry={} layout={}{} | dispatcher@{} {} queue={} visible={} nearby={} level={}",
				reason, second, ShaderSwitcher.active().isEmpty() ? "off" : ShaderSwitcher.active(), ShaderRuntime.get() != null,
				TerrainShaderGeometry.isEnabled(), TerrainShaderGeometry.layout(), layers,
				dispatcher == null ? "null" : Integer.toHexString(System.identityHashCode(dispatcher)),
				dispatcher == null ? "-" : dispatcher.getStats(), dispatcher == null ? -1 : dispatcher.getCompileQueueSize(),
				renderer.visibleSections().size(), renderer.nearbyVisibleSections().size(), mc.level != null);
		} catch (RuntimeException e) {
			LOGGER.info("[probe] {} +{}s: failed: {}", reason, second, e.toString());
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
