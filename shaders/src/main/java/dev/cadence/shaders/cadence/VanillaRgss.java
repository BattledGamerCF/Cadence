package dev.cadence.shaders.cadence;

import org.slf4j.LoggerFactory;

/**
 * Cadence: vanilla's own terrain shader (used while a shader pack was on at launch and is now off, or with Cadence's own
 * terrain renderer off) gets the RGSS shortcut Cadence's terrain shader has (own/terrain.metal sampleRGSS): with RGSS texture
 * filtering vanilla takes 9 atlas samples per terrain pixel and returns mix(nearest, rgss, blend), and wherever blend is 0 (a
 * texel at least a pixel on screen: most of the frame) that is the nearest sample exactly, so the 8 RGSS samples are skipped
 * there. Applied to include/texture_sampling.glsl as it is read (ShaderIncludeMixin), only when it is vanilla's text (a
 * resource pack's own version is left alone). -Dcadence.vanillaRgss=false: off; =verify: no shortcut, terrain pixels where
 * mix(nearest, rgss, 0) would differ from the nearest sample drawn magenta (the check of the one assumption).
 */
public final class VanillaRgss {
	/** (volatile: the self-test's vanillargss=on|off step, then reload) */
	public static volatile String MODE = System.getProperty("cadence.vanillaRgss", "true");
	private static final String BLEND = "    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);\n";
	private static final String RESULT = "    return mix(nearestColor, rgssColor, blendFactor);\n";

	private VanillaRgss() {
	}

	public static String patch(String path, String source) {
		if (!path.endsWith("texture_sampling.glsl") || MODE.equals("false")) return source;
		if (source.indexOf(BLEND) < 0 || source.indexOf(RESULT) < 0 || source.indexOf("vec4 sampleRGSS(") < 0) return source;
		String patched = MODE.equals("verify")
			? source.replace(RESULT, "    if (blendFactor == 0.0 && any(notEqual(mix(nearestColor, rgssColor, blendFactor), nearestColor))) return vec4(1.0, 0.0, 1.0, 1.0);\n" + RESULT)
			: source.replace(BLEND, BLEND + "    if (blendFactor == 0.0) return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize); // Cadence (VanillaRgss)\n");
		LoggerFactory.getLogger("cadence").info("Vanilla terrain RGSS: {}", MODE.equals("verify") ? "verification" : "unused samples skipped");
		return patched;
	}
}
