package dev.cadence.irisapi;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.function.IntFunction;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.api.v0.IrisShadowProgram;
import net.irisshaders.iris.api.v0.IrisShadowRenderCallback;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;
import net.minecraft.client.Minecraft;

/**
 * The Iris API answered from Cadence (dev.cadence.api.Cadence, the shaders module's toggle and Shader Packs screen), reached
 * by name so this jar builds and loads without them: with Cadence's shaders module missing every answer is "no pack".
 */
public final class CadenceIrisApi implements IrisApi, IrisApiConfig {
	public static final CadenceIrisApi INSTANCE = new CadenceIrisApi();
	private static final MethodHandle PACK_ACTIVE = find("dev.cadence.api.Cadence", "shaderPackActive", MethodType.methodType(boolean.class));
	private static final MethodHandle SHADOW = find("dev.cadence.api.Cadence", "renderingShadowPass", MethodType.methodType(boolean.class));
	private static final MethodHandle SUN = find("dev.cadence.api.Cadence", "sunPathRotation", MethodType.methodType(float.class));
	private static final MethodHandle SET_ENABLED =
		find("dev.cadence.shaders.cadence.ui.ShaderToggle", "setEnabled", MethodType.methodType(boolean.class, boolean.class));

	private CadenceIrisApi() {
	}

	private static MethodHandle find(String owner, String name, MethodType type) {
		try {
			return MethodHandles.publicLookup().findStatic(Class.forName(owner, false, CadenceIrisApi.class.getClassLoader()), name, type);
		} catch (ReflectiveOperationException | LinkageError e) {
			return null;
		}
	}

	private static boolean ask(MethodHandle h) {
		try {
			return h != null && (boolean) h.invokeExact();
		} catch (Throwable t) {
			return false;
		}
	}

	/** 2, as Iris states it (the v0.3 / v0.4 methods are declared too). */
	@Override
	public int getMinorApiRevision() {
		return 2;
	}

	@Override
	public boolean isShaderPackInUse() {
		return ask(PACK_ACTIVE);
	}

	@Override
	public boolean isRenderingShadowPass() {
		return ask(SHADOW);
	}

	/** Cadence's Shader Packs screen (as Iris, the screen is returned for the caller to open), or null without the shaders module. */
	@Override
	public Object openMainIrisScreenObj(Object parent) {
		try {
			Class<?> screen = Class.forName("dev.cadence.shaders.cadence.ui.ShaderPacksScreen", true, CadenceIrisApi.class.getClassLoader());
			return screen.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance(parent);
		} catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
			return null;
		}
	}

	/**
	 * The text itself rather than Iris's key (options.iris.shaderPackSelection): callers make a translatable component of it,
	 * which shows a missing key as written, and mod language files only load with Fabric API, which Cadence doesn't need.
	 */
	@Override
	public String getMainScreenLanguageKey() {
		return "Shader Packs...";
	}

	@Override
	public IrisApiConfig getConfig() {
		return this;
	}

	@Override
	public IrisTextVertexSink createTextVertexSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider) {
		return new TextSink(maxQuadCount, bufferProvider);
	}

	@Override
	public float getSunPathRotation() {
		try {
			return SUN == null ? 0 : (float) SUN.invokeExact();
		} catch (Throwable t) {
			return 0;
		}
	}

	/**
	 * Iris draws a pipeline it is told about with the given pack program. Cadence's packs choose the program for every
	 * pipeline themselves (by the pipeline's shader); a mod's own pipeline is drawn as its shader says. No-op.
	 */
	@Override
	public void assignPipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline, IrisProgram program) {
	}

	@Override
	public void assignPipelineShadow(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline, IrisShadowProgram program) {
	}

	/** Accepted and kept; Cadence's shadow pass doesn't call mods' callbacks (a mod's own shadow drawing doesn't show). */
	@Override
	public void registerShadowRenderCallback(IrisShadowRenderCallback callback) {
		if (callback != null) SHADOW_CALLBACKS.add(callback);
	}

	/** Cadence keeps the game's depth convention while a pack draws (the pack's own passes get theirs translated). */
	@Override
	public boolean isReverseZDuringShaders() {
		return false;
	}

	private static final java.util.List<IrisShadowRenderCallback> SHADOW_CALLBACKS = new java.util.concurrent.CopyOnWriteArrayList<>();

	@Override
	public boolean areShadersEnabled() {
		return isShaderPackInUse();
	}

	/**
	 * Shaders off (the pack remembered) or back on with the last pack, as the K key; on the render thread. Unlike Iris it returns
	 * before the pack is running (packs load in the background): isShaderPackInUse turns true when it draws.
	 */
	@Override
	public void setShadersEnabledAndApply(boolean enabled) {
		if (SET_ENABLED == null) return;
		Minecraft.getInstance().execute(() -> {
			try {
				boolean ignored = (boolean) SET_ENABLED.invokeExact(enabled);
			} catch (Throwable t) {
				System.out.println("[cadence] iris api: setShadersEnabledAndApply failed: " + t);
			}
		});
	}
}
