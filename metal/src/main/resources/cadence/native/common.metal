// Cadence native shaders (NativeShaders): what every program shares, written from vanilla's shaders/include/*.glsl.
// Uniform blocks are std140 as the game fills them (vec3 members 16-byte aligned, as Metal's float3; Globals' ivec3/vec3
// packed to 12 bytes, as std140 packs a scalar after them).
#include <metal_stdlib>
using namespace metal;

struct Projection { float4x4 ProjMat; };
struct DynamicTransforms { float4x4 ModelViewMat; float4x4 TextureMat; float4 ColorModulator; float3 ModelOffset; };
struct Lighting { float3 Light0_Direction; float3 Light1_Direction; };
struct Fog {
	float4 FogColor;
	float FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogSkyEnd, FogCloudsEnd;
};
struct Globals {
	packed_int3 CameraBlockPos;
	float GlintAlpha;
	packed_float3 CameraOffset;
	float GameTime;
	float2 ScreenSize;
	int MenuBlurRadius;
	int UseRgss;
};

// fog.glsl
static inline float linear_fog_value(float d, float fogStart, float fogEnd) {
	if (d <= fogStart) return 0.0f;
	else if (d >= fogEnd) return 1.0f;
	return (d - fogStart) / (fogEnd - fogStart);
}
static inline float total_fog_value(float sph, float cyl, float envStart, float envEnd, float rdStart, float rdEnd) {
	return max(linear_fog_value(sph, envStart, envEnd), linear_fog_value(cyl, rdStart, rdEnd));
}
static inline float4 apply_fog(float4 c, float sph, float cyl, float envStart, float envEnd, float rdStart, float rdEnd, float4 fogColor) {
	float f = total_fog_value(sph, cyl, envStart, envEnd, rdStart, rdEnd);
	return float4(mix(c.rgb, fogColor.rgb, f * fogColor.a), c.a);
}
static inline float4 apply_fog(float4 c, float sph, float cyl, constant Fog &fog) {
	return apply_fog(c, sph, cyl, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd, fog.FogColor);
}
static inline float fog_spherical_distance(float3 pos) { return length(pos); }
static inline float fog_cylindrical_distance(float3 pos) { return max(length(pos.xz), abs(pos.y)); }

// light.glsl
static inline float2 minecraft_compute_light(float3 l0, float3 l1, float3 n) { return float2(dot(l0, n), dot(l1, n)); }
static inline float4 minecraft_mix_light_separate(float2 light, float4 color) {
	float2 v = max(float2(0.0f), light);
	float accum = min(1.0f, (v.x + v.y) * 0.6f + 0.4f);
	return float4(color.rgb * accum, color.a);
}
static inline float4 minecraft_mix_light(float3 l0, float3 l1, float3 n, float4 color) {
	return minecraft_mix_light_separate(minecraft_compute_light(l0, l1, n), color);
}

// sample_lightmap.glsl
static inline float4 sample_lightmap(texture2d<float> lightMap, sampler s, int2 uv) {
	return lightMap.sample(s, clamp(float2(uv) / 256.0f + 0.5f / 16.0f, float2(0.5f / 16.0f), float2(15.5f / 16.0f)), level(0.0f));
}

// the backend's row order (SPIRV-Cross's flip_vert_y, as every translated vertex stage does)
static inline float4 native_flip(float4 p) { return float4(p.x, -p.y, p.z, p.w); }
