// Forward shading for every world program. A program file defines VSH or FSH and one kind:
// LIT (terrain, entities, hand, block entities, particles), WATER, UNLIT (sky, clouds, glowing eyes, beams).
// TERRAIN adds wind, ENTITY adds the hurt flash, FOLIAGE_SAFE marks programs that never get plant ids.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

#if defined LIT || defined WATER
#define SHADED
#endif

#ifdef VSH
// ---------------------------------------------------------------- vertex
out vec2 uv;
out vec2 lm;
out vec4 tint;
out float fogDist;
#ifdef SHADED
out vec3 viewN;
out vec3 shadowPos;
out float foliage;
uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform vec3 cameraPosition;
#endif
#ifdef WATER
out vec3 viewPos;
out vec3 worldPos;
out float isWater;
#endif
#if defined TERRAIN || defined WATER
in vec4 mc_Entity;
in vec2 mc_midTexCoord;
#endif

void main() {
	uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	tint = gl_Color;
	vec4 vp = gl_ModelViewMatrix * gl_Vertex;
#ifdef SHADED
	vec3 pp = (gbufferModelViewInverse * vp).xyz;
	foliage = 0.0;
#if defined TERRAIN
	float id = mc_Entity.x;
	foliage = (id >= ID_PLANT && id <= ID_TALL_UPPER) ? 1.0 : 0.0;
#ifdef WAVING
	if (foliage > 0.5) {
		bool top = gl_MultiTexCoord0.t < mc_midTexCoord.t;
		pp += wind(pp + cameraPosition, id, top, smoothstep(0.3, 0.9, lm.y));
		vp = gbufferModelView * vec4(pp, 1.0);
	}
#endif
#endif
	viewN = normalize(gl_NormalMatrix * gl_Normal);
	// normal offset: pushes the lookup off the surface, so no shadow acne without a big depth bias
	vec3 n = mat3(gbufferModelViewInverse) * viewN;
	float dist = length(pp);
	vec3 sp = pp + n * (0.05 + dist * 0.0025) * (1.0 - foliage);
	shadowPos = (shadowProjection * (shadowModelView * vec4(sp, 1.0))).xyz;
	fogDist = max(length(pp.xz), abs(pp.y));
#else
	fogDist = length(vp.xyz);
#endif
#ifdef WATER
	viewPos = vp.xyz;
	worldPos = pp + cameraPosition;
	isWater = mc_Entity.x == ID_WATER ? 1.0 : 0.0;
#endif
	gl_Position = gl_ProjectionMatrix * vp;
}

#else
// ---------------------------------------------------------------- fragment
in vec2 uv;
in vec2 lm;
in vec4 tint;
in float fogDist;
uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
#ifdef ENTITY
uniform vec4 entityColor;
#endif
#ifdef SHADED
in vec3 viewN;
in vec3 shadowPos;
in float foliage;
uniform sampler2DShadow shadowtex0;
uniform vec3 shadowLightPosition;
uniform float sunAngle;
uniform float rainStrength;
uniform float viewWidth;
#endif
#ifdef WATER
in vec3 viewPos;
in vec3 worldPos;
in float isWater;
uniform vec3 skyColor;
uniform mat4 gbufferModelView;
#endif

#if defined WATER && defined WATER_SSR
/* RENDERTARGETS: 0,3 */
layout(location = 1) out vec4 outWater;
#else
/* RENDERTARGETS: 0 */
#endif
layout(location = 0) out vec4 outColor;

#ifdef SHADED
float shadowVisibility() {
	vec3 p = distortShadow(shadowPos) * 0.5 + 0.5;
	if (any(lessThan(p, vec3(0.0))) || any(greaterThan(p, vec3(1.0)))) return 1.0;
	p.z -= 0.00012;
#if SHADOW_FILTER == 0
	return texture(shadowtex0, p);
#else
	float texel = 1.0 / float(shadowMapResolution);
#if SHADOW_FILTER == 1
	vec2 o = vec2(0.5, 1.5) * texel;
	return 0.25 * (texture(shadowtex0, p + vec3(-o.x, -o.y, 0.0)) + texture(shadowtex0, p + vec3(o.y, -o.x, 0.0))
		+ texture(shadowtex0, p + vec3(o.x, o.y, 0.0)) + texture(shadowtex0, p + vec3(-o.y, o.x, 0.0)));
#else
	// 12 taps on a rotated spiral; the rotation hides banding at the cost of fine noise
	float a = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) * 6.2831853;
	float s = 0.0;
	for (int i = 0; i < 12; i++) {
		float r = sqrt((float(i) + 0.5) / 12.0) * 2.2 * texel;
		float t = a + float(i) * 2.3999632;
		s += texture(shadowtex0, p + vec3(cos(t) * r, sin(t) * r, 0.0));
	}
	return s / 12.0;
#endif
#endif
}
#endif

void main() {
#ifdef NOTEX
	vec4 c = tint;
#else
	vec4 c = texture(gtexture, uv) * tint;
#endif
#if !defined WATER && !defined NOTEX
	if (c.a < 0.1) discard;
#endif
#ifdef ENTITY
	c.rgb = mix(c.rgb, entityColor.rgb, entityColor.a);
#endif

#ifdef UNLIT
#ifdef FOG
	c.rgb = mix(c.rgb, fogColor, clamp((fogDist - fogStart) / max(fogEnd - fogStart, 1.0), 0.0, 1.0));
	outColor = c;
#endif
	return;
#else
	vec3 light = texture(lightmap, lm).rgb;
#ifdef SHADED
	float sunH = sin(sunAngle * 6.2831853);
	bool day = sunAngle < 0.5;
	// fades out around sunrise and sunset, where the shadow light switches between sun and moon
	float fade = smoothstep(0.02, 0.12, abs(sunH)) * (1.0 - rainStrength * 0.85);
	float skyW = smoothstep(0.3, 0.95, lm.y) * fade * (day ? 1.0 : 0.45);
	vec3 L = normalize(shadowLightPosition);
	float NdotL = foliage > 0.5 ? 0.65 : dot(viewN, L);
	float vis = 0.0;
	if (NdotL > 0.0 && skyW > 0.001) {
#ifdef SHADOWS
		vis = shadowVisibility();
#else
		vis = 1.0;
#endif
	}
	float direct = vis * clamp(NdotL * 3.0, 0.0, 1.0);
	light *= mix(1.0, 1.0 - SHADOW_STRENGTH, skyW * (1.0 - direct));
	vec3 sunCol = day ? mix(vec3(1.0, 0.5, 0.25), vec3(1.0, 0.9, 0.75), smoothstep(0.0, 0.4, sunH)) : vec3(0.5, 0.62, 1.0) * 0.35;
#if SUN_LIGHT > 0
	light += sunCol * direct * max(NdotL, 0.0) * skyW * (SUN_LIGHT == 1 ? 0.16 : 0.3);
#endif
#endif
	c.rgb *= light;

#ifdef WATER
#if WATER > 0
	if (isWater > 0.5) {
		vec3 V = normalize(viewPos);
		vec3 N = viewN;
		// small waves only on top faces; two sine pairs are enough at this scale
		if (dot(viewN, mat3(gbufferModelView)[1]) > 0.5) {
			vec2 w = worldPos.xz;
			float t = frameTimeCounter;
			vec2 d = vec2(cos(w.x * 1.3 + t * 1.6) * 0.6 + cos((w.x + w.y) * 2.7 + t * 2.3) * 0.25,
			              cos(w.y * 1.1 + t * 1.3) * 0.6 + cos((w.y - w.x) * 2.9 + t * 2.1) * 0.25);
			N = normalize(mat3(gbufferModelView) * normalize(vec3(-d.x * 0.06, 1.0, -d.y * 0.06)));
		}
		float cosV = max(dot(-V, N), 0.0);
		float fres = 0.02 + 0.98 * pow(1.0 - cosV, 5.0);
		vec3 R = reflect(V, N);
		float up = dot(R, mat3(gbufferModelView)[1]);
		vec3 sky = mix(fogColor, skyColor, smoothstep(0.0, 0.4, up)) * smoothstep(0.2, 0.9, lm.y);
		c.rgb = mix(c.rgb, sky, fres * 0.85);
		c.a = mix(c.a, 1.0, fres * 0.7);
		float spec = pow(max(dot(R, L), 0.0), 220.0) * vis * fade * (day ? 2.5 : 0.8);
		c.rgb += sunCol * spec;
		c.a = min(1.0, c.a + spec);
#ifdef WATER_SSR
		outWater = vec4(N * 0.5 + 0.5, fres);
#endif
	}
#ifdef WATER_SSR
	else outWater = vec4(0.0);
#endif
#elif defined WATER_SSR
	outWater = vec4(0.0);
#endif
#endif

	c.rgb = mix(c.rgb, fogColor, clamp((fogDist - fogStart) / max(fogEnd - fogStart, 1.0), 0.0, 1.0));
	outColor = c;
#endif
}
#endif
