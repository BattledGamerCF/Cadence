package dev.cadence.metal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cadence.api.MetalHandles;
import dev.cadence.api.RenderContext;
import dev.cadence.api.RenderHook;
import dev.cadence.api.RenderStage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Cadence internal: the API's render hooks (dev.cadence.api.Cadence#onRender) and the state other Cadence modules report to it.
 * With no hook at a stage, firing it is one array-length check.
 */
public final class ApiHooks {
	private ApiHooks() {
	}

	private static final RenderHook[][] HOOKS = new RenderHook[RenderStage.values().length][];

	static {
		for (int i = 0; i < HOOKS.length; i++) HOOKS[i] = new RenderHook[0];
		// -Dcadence.api.selftest=true: a hook at every stage that logs, once each, what it was given (the API's own test)
		if (Boolean.getBoolean("cadence.api.selftest")) {
			for (RenderStage stage : RenderStage.values()) {
				boolean[] once = {false};
				add(stage, c -> {
					if (once[0]) return;
					once[0] = true;
					System.out.println("[cadence] api selftest: " + c.stage() + " pass=" + (c.pass() != null) + " target=" + c.mainTarget().width + "x"
						+ c.mainTarget().height + " camera=" + c.camera() + " metal=" + (c.metal() == null ? "none" : "device " + (c.metal().device() != 0)
						+ " queue " + (c.metal().commandQueue() != 0) + " cmd " + (c.metal().commandBuffer() != 0) + " encoder " + (c.metal().renderEncoder() != 0)
						+ " color " + (c.metal().mainColor() != 0) + " depth " + (c.metal().mainDepth() != 0)));
				});
			}
		}
	}

	/** The shaders module: the pack drawing now ("" none). */
	public static volatile String shaderPack = "";

	/** The shaders module: whether the pack's shadow pass is drawing now (render thread). */
	public static volatile boolean shadowPass;

	/** The shaders module: the pack's sunPathRotation in degrees (0 without a pack). */
	public static volatile float sunPathRotation;

	public static synchronized void add(RenderStage stage, RenderHook hook) {
		RenderHook[] old = HOOKS[stage.ordinal()];
		RenderHook[] now = java.util.Arrays.copyOf(old, old.length + 1);
		now[old.length] = hook;
		HOOKS[stage.ordinal()] = now;
	}

	public static synchronized void remove(RenderStage stage, RenderHook hook) {
		RenderHook[] old = HOOKS[stage.ordinal()];
		HOOKS[stage.ordinal()] = java.util.Arrays.stream(old).filter(h -> h != hook).toArray(RenderHook[]::new);
	}

	/** Whether any hook waits at the stage (the caller then gathers what fire needs). */
	public static boolean wanted(RenderStage stage) {
		return HOOKS[stage.ordinal()].length > 0;
	}

	private static final Matrix4f IDENTITY = new Matrix4f();

	/** Runs the stage's hooks; one that throws is reported and removed (a broken addon must not take the game down). */
	public static void fire(RenderStage stage, @Nullable RenderPass pass, Vec3 camera, @Nullable Matrix4fc modelView, @Nullable Matrix4fc projection,
		float partialTick) {
		RenderHook[] hooks = HOOKS[stage.ordinal()];
		if (hooks.length == 0) return;
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		MetalHandles metal = null;
		if (MetalCompute.available()) {
			long color = main.getColorTexture() != null ? MetalBridge.textureHandle(main.getColorTexture()) : 0;
			long depth = main.getDepthTexture() != null ? MetalBridge.textureHandle(main.getDepthTexture()) : 0;
			metal = new MetalHandles(MetalCompute.apiHandle(0), MetalCompute.apiHandle(1), MetalCompute.apiHandle(2), MetalCompute.apiHandle(3), color, depth);
		}
		RenderContext context = new RenderContext(stage, pass, main, camera, modelView != null ? modelView : IDENTITY, projection != null ? projection : IDENTITY,
			partialTick, metal);
		for (RenderHook hook : hooks) {
			try {
				hook.render(context);
			} catch (RuntimeException | LinkageError e) {
				System.out.println("[cadence] api: a render hook at " + stage + " failed and was removed: " + e);
				e.printStackTrace(System.out);
				remove(stage, hook);
			}
		}
	}
}
