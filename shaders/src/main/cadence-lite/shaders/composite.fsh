#version 330 compatibility
// One full-screen pass for everything that needs the finished image: water reflections, light shafts, bloom source.
// Only enabled when at least one of them is on (shaders.properties).
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
in vec2 texcoord;
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform vec3 shadowLightPosition;
uniform float sunAngle;
uniform float rainStrength;
uniform ivec2 eyeBrightnessSmooth;
uniform int isEyeInWater;
#ifdef WATER_SSR
uniform sampler2D colortex3;
const vec4 colortex3ClearColor = vec4(0.0);
uniform vec3 skyColor;
#endif

#ifdef BLOOM
/* RENDERTARGETS: 0,2 */
layout(location = 1) out vec4 outBloom;
#else
/* RENDERTARGETS: 0 */
#endif
layout(location = 0) out vec4 outColor;

vec3 viewAt(vec2 uv, float depth) {
	vec4 p = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
	return p.xyz / p.w;
}

vec2 screenOf(vec3 view) {
	vec4 c = gbufferProjection * vec4(view, 1.0);
	return c.xy / c.w * 0.5 + 0.5;
}

float dither() {
	return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
}

void main() {
	vec3 col = texture(colortex0, texcoord).rgb;
	float depth = texture(depthtex0, texcoord).r;

#ifdef WATER_SSR
	vec4 w = texture(colortex3, texcoord);
	if (w.a > 0.0 && depth < 1.0) {
		vec3 vp = viewAt(texcoord, depth);
		vec3 N = normalize(w.xyz * 2.0 - 1.0);
		vec3 R = normalize(reflect(normalize(vp), N));
		// view-space march with growing steps, then a short binary refine
		float stepLen = 0.6 + 0.4 * dither();
		vec3 p = vp;
		vec2 hit = vec2(-1.0);
		for (int i = 0; i < 20; i++) {
			vec3 q = p + R * stepLen;
			vec2 s = screenOf(q);
			if (any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0))) || q.z > -0.05) break;
			float sceneZ = viewAt(s, texture(depthtex0, s).r).z;
			if (sceneZ > q.z && sceneZ - q.z < stepLen * 1.5 + 0.5) {
				vec3 a = p, b = q;
				for (int j = 0; j < 4; j++) {
					vec3 m = (a + b) * 0.5;
					vec2 ms = screenOf(m);
					if (viewAt(ms, texture(depthtex0, ms).r).z > m.z) b = m; else a = m;
				}
				hit = screenOf(b);
				break;
			}
			p = q;
			stepLen *= 1.35;
		}
		if (hit.x >= 0.0 && texture(depthtex0, hit).r < 1.0) {
			vec2 edge = smoothstep(0.0, 0.08, hit) * smoothstep(1.0, 0.92, hit);
			col = mix(col, texture(colortex0, hit).rgb, w.a * 0.9 * edge.x * edge.y);
		}
	}
#endif

	float sunH = sin(sunAngle * 6.2831853);
	bool day = sunAngle < 0.5;
	vec3 sunCol = day ? mix(vec3(1.0, 0.5, 0.25), vec3(1.0, 0.9, 0.75), smoothstep(0.0, 0.4, sunH)) : vec3(0.5, 0.62, 1.0) * 0.35;

#ifdef GODRAYS
	vec4 sc = gbufferProjection * vec4(shadowLightPosition, 1.0);
	float strength = smoothstep(0.02, 0.15, abs(sunH)) * (1.0 - rainStrength) * (isEyeInWater == 0 ? 1.0 : 0.0)
		* (float(eyeBrightnessSmooth.y) / 240.0) * (day ? 0.32 : 0.12);
	if (sc.w > 0.0 && strength > 0.001) {
		vec2 sun = sc.xy / sc.w * 0.5 + 0.5;
		vec2 delta = (sun - texcoord) / float(GODRAY_SAMPLES);
		vec2 s = texcoord + delta * dither();
		float acc = 0.0;
		for (int i = 0; i < GODRAY_SAMPLES; i++) {
			if (s.x >= 0.0 && s.x <= 1.0 && s.y >= 0.0 && s.y <= 1.0) acc += step(1.0, texture(depthtex0, s).r);
			s += delta;
		}
		float aspect = gbufferProjection[1][1] / gbufferProjection[0][0];
		float glow = exp(-length((texcoord - sun) * vec2(1.0 / aspect, 1.0)) * 2.5);
		col += sunCol * (acc / float(GODRAY_SAMPLES)) * glow * strength;
	}
#endif

	outColor = vec4(col, 1.0);
#ifdef BLOOM
	outBloom = vec4(max(col - 0.7, 0.0) * 1.6 + max(col - 1.0, 0.0), 1.0);
#endif
}
