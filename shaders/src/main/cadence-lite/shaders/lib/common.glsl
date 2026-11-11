// Shared helpers: shadow distortion, wind, colour.

// More shadow-map texels near the player.
vec3 distortShadow(vec3 p) {
	float d = length(p.xy) * 0.88 + 0.12;
	return vec3(p.xy / d, p.z * 0.25);
}

// Wind for plants. playerPos + cameraPosition = world position (stable across camera moves).
uniform float frameTimeCounter;
vec3 wind(vec3 worldPos, float id, bool top, float amount) {
	float t = frameTimeCounter;
	vec3 w = vec3(0.0);
	if (id == ID_LEAVES) {
		w.x = sin(t * 1.7 + worldPos.x * 0.9 + worldPos.y * 0.6);
		w.z = sin(t * 1.3 + worldPos.z * 0.8 + worldPos.y * 0.4);
		w.y = sin(t * 2.1 + worldPos.x + worldPos.z) * 0.5;
		return w * 0.035 * amount;
	}
	if (id == ID_PLANT || id == ID_TALL_LOWER || id == ID_TALL_UPPER) {
		if (!top && id != ID_TALL_UPPER) return w;
		float k = id == ID_TALL_UPPER ? (top ? 1.0 : 0.5) : (id == ID_TALL_LOWER ? 0.5 : 1.0);
		w.x = sin(t * 2.0 + worldPos.x * 0.7 + worldPos.z * 0.3) + sin(t * 3.3 + worldPos.z) * 0.35;
		w.z = sin(t * 1.6 + worldPos.z * 0.6 - worldPos.x * 0.2) * 0.6;
		return w * 0.07 * k * amount;
	}
	return w;
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Cadence HDR: the brightest value the display shows now, as a linear multiple of SDR white (1.0 = SDR, HDR off).
// This pack's colours are sRGB-encoded, so the encoded peak is its 1/2.2 power.
uniform float cadenceHdrPeak;
float hdrPeakEncoded() { return pow(max(cadenceHdrPeak, 1.0), 0.4545); }
// How far a light source goes past white: most of the headroom, at most 4x SDR (the rest is left for the sun).
float hdrEmissive() { return pow(clamp(cadenceHdrPeak * 0.6, 1.0, 4.0), 0.4545); }
