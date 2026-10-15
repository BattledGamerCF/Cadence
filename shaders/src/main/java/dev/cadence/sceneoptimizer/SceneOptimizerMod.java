// Cadence: derived from Minecraft Shader Loader (c) Minecraft Metal Renderer contributors, MIT; modified.
package dev.cadence.sceneoptimizer;

import dev.cadence.scene.SceneViews;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

/** Optional scene preparation implementation, independent of shader-pack interpretation. */
public final class SceneOptimizerMod implements ClientModInitializer {
  private static final SpatialSceneProvider PROVIDER = new SpatialSceneProvider();

  @Override
  public void onInitializeClient() {
    // Cadence: off with the loader's hooks off, or for A/B (-Dcadence.sceneOptimizer=false).
    if (!dev.cadence.shaders.CadencePackGate.hooksApplied()
        || "false".equals(System.getProperty("cadence.sceneOptimizer"))) return;
    SceneViews.register(PROVIDER);
    LoggerFactory.getLogger("cadence_scene_optimizer")
        .info("Cadence scene optimizer ready: bounded spatial-index worker, view selection and retained layer sets.");
  }

  public static SpatialSceneProvider.Stats stats() {
    return PROVIDER.stats();
  }

  public static DrawMetadataMetrics.Stats drawMetadataStats() {
    return DrawMetadataMetrics.snapshot();
  }

  public static DrawWorkMetrics.Stats drawWorkStats() {
    return DrawWorkMetrics.snapshot();
  }
}
