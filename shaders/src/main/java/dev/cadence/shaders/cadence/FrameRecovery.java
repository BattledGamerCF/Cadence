package dev.cadence.shaders.cadence;

import dev.cadence.shaders.cadence.mixin.FeatureDispatcherAccess;
import dev.cadence.shaders.cadence.mixin.FeatureFrameAccess;
import dev.cadence.shaders.cadence.mixin.LevelRendererTargetsAccess;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

/**
 * Cadence: after a shader-pack frame failed part way (ShaderSwitcher turns the pack off), what vanilla's LevelRenderer.render
 * releases at the end of a frame is released here, since the exception skipped it: the feature dispatcher's prepared frame
 * (otherwise the next frame stops the game with "PreparedFrame already in use") and the frame graph's target handles.
 * Render thread.
 */
public final class FrameRecovery {
	/** Self-test only (step failframe): the next sky pass throws, as the freed-target crash did, to exercise the recovery. */
	public static volatile boolean failNextSky;

	private FrameRecovery() {
	}

	public static void afterFailedFrame() {
		Minecraft mc = Minecraft.getInstance();
		try {
			var frame = ((FeatureDispatcherAccess) mc.gameRenderer.featureRenderDispatcher()).cadence$preparedFrame();
			var access = (FeatureFrameAccess) frame;
			if (access.cadence$context() != null) {
				try {
					frame.close();
				} catch (RuntimeException e) {
					// (a renderer's finishExecute failed too: the frame is marked free so the next one can begin)
					access.cadence$setContext(null);
					access.cadence$setSubmitNodeStorage(null);
				}
			}
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("cadence").warn("Couldn't release the failed frame's features", e);
		}
		try {
			if (mc.levelRenderer != null) ((LevelRendererTargetsAccess) mc.levelRenderer).cadence$targets().clear();
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("cadence").warn("Couldn't release the failed frame's targets", e);
		}
	}
}
