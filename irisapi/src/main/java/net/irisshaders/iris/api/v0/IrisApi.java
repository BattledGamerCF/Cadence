package net.irisshaders.iris.api.v0;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * Iris API v0 as Iris declares it (its 26.1 branch: minor revision 2, with the v0.3 and v0.4 additions), here answered by
 * Cadence's shader packs (dev.cadence.irisapi). Written from the API's published signatures; no Iris code.
 */
public interface IrisApi {
	static IrisApi getInstance() {
		return IrisApiInternal.INSTANCE;
	}

	int getMinorApiRevision();

	boolean isShaderPackInUse();

	boolean isRenderingShadowPass();

	Object openMainIrisScreenObj(Object parent);

	String getMainScreenLanguageKey();

	IrisApiConfig getConfig();

	IrisTextVertexSink createTextVertexSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider);

	float getSunPathRotation();

	void assignPipeline(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline, IrisProgram program);

	void assignPipelineShadow(com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline, IrisShadowProgram program);

	void registerShadowRenderCallback(IrisShadowRenderCallback callback);

	boolean isReverseZDuringShaders();
}
