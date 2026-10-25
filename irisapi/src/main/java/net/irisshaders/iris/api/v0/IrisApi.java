package net.irisshaders.iris.api.v0;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

/**
 * Iris API v0 (minor revision 2), as Iris declares it: here answered by Cadence's shader packs (dev.cadence.irisapi). Written
 * from the API's published signatures; no Iris code.
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
}
