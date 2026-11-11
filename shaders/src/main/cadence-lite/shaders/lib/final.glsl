// Bloom (from colortex2's mip chain), tone and colour grade. With everything at its neutral value this is one read and one write.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
in vec2 texcoord;
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelView;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform vec3 sunPosition;
/*
const int colortex0Format = R11F_G11F_B10F;
const int colortex2Format = R11F_G11F_B10F;
*/
#ifdef BLOOM
uniform sampler2D colortex2;
const bool colortex2MipmapEnabled = true;
#endif
layout(location = 0) out vec4 outColor;

void main() {
	vec3 c = texture(colortex0, texcoord).rgb;
	// the sky: vanilla's gradient from the fog colour at the horizon to the sky colour above, under the sun, moon and stars
	// (drawn here: Minecraft 26 gives the sky program no colour of its own)
#ifndef NO_SKY
	if (texture(depthtex0, texcoord).r >= 1.0) {
		vec4 v = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, 1.0, 1.0);
		vec3 V = normalize(v.xyz / v.w), U = gbufferModelView[1].xyz;
		float up = dot(V, U);
		float dayK = smoothstep(-0.15, 0.1, dot(normalize(sunPosition), U));
		// (a dark navy floor at night, as vanilla's night sky has)
		vec3 top = max(skyColor, vec3(0.012, 0.016, 0.045) * (1.0 - dayK));
		vec3 sky = mix(fogColor, top, smoothstep(-0.05, 0.4, up));
		// the sun and moon: vanilla's squares, with a soft glow round the sun (brighter than white in HDR)
		vec3 S = normalize(sunPosition);
		vec3 right = normalize(cross(S, U)), across = cross(right, S);
		float d = dot(V, S);
		if (d > 0.0) {
			vec3 l = V / d - S;
			vec2 q = abs(vec2(dot(l, right), dot(l, across)));
			float disc = 1.0 - smoothstep(0.075, 0.08, max(q.x, q.y));
			sky += vec3(1.0, 0.93, 0.75) * (disc * 1.6 * hdrPeakEncoded() + pow(d, 300.0) * 0.35 + pow(d, 12.0) * 0.08);
		} else {
			vec3 l = V / -d + S;
			vec2 q = abs(vec2(dot(l, right), dot(l, across)));
			sky += vec3(0.82, 0.86, 0.95) * (1.0 - smoothstep(0.05, 0.055, max(q.x, q.y))) * 0.9;
		}
		// stars: a few fixed bright points, at night only
		vec3 cell = floor(V * 220.0);
		float h = fract(sin(dot(cell, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
		sky += step(0.9975, h) * (1.0 - dayK) * smoothstep(0.0, 0.2, up) * 0.7;
		c += sky;
	}
#endif
#ifdef BLOOM
	vec3 b = textureLod(colortex2, texcoord, 2.0).rgb * 0.30 + textureLod(colortex2, texcoord, 3.0).rgb * 0.28
	       + textureLod(colortex2, texcoord, 4.0).rgb * 0.24 + textureLod(colortex2, texcoord, 5.5).rgb * 0.18;
	c += b * BLOOM_STRENGTH * 4.0;
#endif
#ifdef TONEMAP
	// keeps values below ~0.75 almost untouched (vanilla look), rolls highlights off instead of clipping
	// (in HDR it rolls off toward the display's peak instead of SDR white)
	float top = hdrPeakEncoded() - 0.75;
	vec3 hi = 0.75 + top * (1.0 - exp(-(c - 0.75) * (1.0 / top)));
	c = mix(c, hi, step(0.75, c));
#endif
	float l = luma(c);
	// vibrance: pushes muted colours more than already-saturated ones
	float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
	c = mix(vec3(l), c, 1.0 + (VIBRANCE - 1.0) * (1.6 - sat));
	c = (c - 0.5) * CONTRAST + 0.5;
#ifndef TONEMAP
	// HDR without the tone curve: the brightest part of the range (sky near the sun, snow, clouds, light sources) reaches past
	// SDR white, as the loader's Auto-HDR does for other packs; nothing changes in SDR
	float m = max(c.r, max(c.g, c.b));
	float t = clamp((m - 0.8) * 5.0, 0.0, 1.0);
	c *= pow(1.0 + (max(cadenceHdrPeak, 1.0) * 0.7 - 1.0) * max(t * t, 0.0) * step(1.0, cadenceHdrPeak * 0.7), 0.4545);
#endif
	outColor = vec4(clamp(c, 0.0, hdrPeakEncoded()), 1.0);
}
