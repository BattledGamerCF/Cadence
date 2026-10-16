// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.shaders;

import dev.cadence.shaders.geometry.FeatureDrawContext;
import dev.cadence.shaders.geometry.TerrainShaderGeometry;
import dev.cadence.shaders.pack.CustomUniforms;
import dev.cadence.shaders.pack.MaterialMappings;
import dev.cadence.shaders.pack.ShaderPack;
import dev.cadence.shaders.pack.ShaderPackException;
import dev.cadence.shaders.runtime.BlockMaterialMap;
import dev.cadence.shaders.runtime.PackEnvironment;
import dev.cadence.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for the optional shader-pack loader, developed separately from GPU execution. */
public final class ShaderLoaderMod implements ClientModInitializer {
  public static final String ID = "cadence_shaders";
  private static final Logger LOGGER = LoggerFactory.getLogger(ID);

  @Override
  public void onInitializeClient() {
    dev.cadence.shaders.cadence.HeapTrim.apply();
    // Cadence: the render thread's scheduling role, and the heat/memory monitor (Heat Control, memory guard)
    dev.cadence.shaders.cadence.system.ThreadRoles.renderThread();
    dev.cadence.shaders.cadence.system.SystemMonitor.start();
    // Cadence: proof of the hand-off in the game log (the profile's own lines print before the log is up).
    LOGGER.info(
        "Cadence: pack at launch '{}', live switching {}, cadence.own={}, cadence.startup.mslCache={}",
        CadencePackGate.selectedPack(),
        CadencePackGate.hooksApplied() ? "on" : "off",
        System.getProperty("cadence.own"),
        System.getProperty("cadence.startup.mslCache"));
    dev.cadence.shaders.cadence.SwitchProbe.onFirstWorld();
    dev.cadence.shaders.cadence.CadenceSelfTest.maybeStart();
    Path selected = null;
    try {
      FabricLoader loader = FabricLoader.getInstance();
      ShaderLoaderConfig config =
          ShaderLoaderConfig.load(loader.getGameDir(), loader.getConfigDir());
      selected = config.pack();
      if (selected == null) {
        LOGGER.info("Cadence Shaders ready; no shader pack selected.");
        return;
      }
      activate(config);
    } catch (IOException | RuntimeException failure) {
      String context =
          selected == null ? "Cadence Shaders configuration" : "selected shader pack " + selected;
      LOGGER.error(
          "Cannot initialize {}; shader-pack rendering was not enabled.", context, failure);
      // Cadence: a pack that can't load must never stop the game (this threw out of Fabric's client entrypoint, which
      // fails the launch): the game starts without shaders and the player is told why when they join a world
      dev.cadence.shaders.cadence.system.CadenceMessages.launchFailure(
          (selected == null ? "Cadence Shaders' settings" : "Shader pack "
              + dev.cadence.shaders.cadence.ShaderToggle.display(selected.getFileName().toString()))
              + " couldn't load (" + failure.getMessage() + "), so shaders are off. Pick another pack or Off in Video Settings.");
    }
  }

  /**
   * Loads {@code config.pack()} and installs it as the active pack: terrain material IDs and vertex layout, draw tags, and the
   * runtime (whose GPU programs build on the next frame in a world). Cadence: also called by ShaderSwitcher for a pack chosen
   * in a running game, after the previous pack is deactivated; the caller then rebuilds every chunk. The CPU-side checks run
   * before anything global changes, so a pack that fails them changes nothing.
   */
  public static void activate(ShaderLoaderConfig config) throws IOException {
    ShaderPack pack = ShaderPack.load(config.pack());
    var options = config.effectiveOptions(pack);
    var environment = PackEnvironment.definitions();
    var properties = pack.properties(options, environment);
    // Validate CPU-side configuration before changing the terrain's vertex layout. Shader/GPU
    // resources are prepared by the runtime after a world and graphics device are available.
    CustomUniforms.compile(properties);
    String separateAo = properties.get("separateAo", "false");
    if (!separateAo.equals("true") && !separateAo.equals("false")) {
      throw new ShaderPackException("Expected true or false for separateAo, found " + separateAo);
    }
    BlockMaterialMap materials =
        new BlockMaterialMap(MaterialMappings.load(pack, options, environment));
    ShaderRuntime runtime = new ShaderRuntime(pack, options, properties);
    runtime.uniforms().setItemIdResolver(materials::itemId);
    TerrainShaderGeometry.configure(
        materials, Boolean.parseBoolean(separateAo), materials.allBlockIdsFitSigned16());
    FeatureDrawContext.configure(materials::entityTypeId, materials);
    ShaderRuntime.install(runtime);
    LOGGER.info(
        "Selected shader pack {} with {} cached block states; render programs initialize when"
            + " entering a world.",
        pack.name(),
        materials.stateCount());
  }

  /** Cadence: the inverse of {@link #activate}: no pack. The caller rebuilds every chunk (vanilla vertex layout again). */
  public static void deactivate() {
    ShaderRuntime old = ShaderRuntime.get();
    ShaderRuntime.install(null);
    if (old != null) old.close();
    TerrainShaderGeometry.disable();
    FeatureDrawContext.disable();
  }
}
