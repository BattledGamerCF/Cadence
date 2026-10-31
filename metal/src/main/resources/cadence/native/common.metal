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

// Order-independent transparency (oit.glsl and its includes), for the OIT variants of the programs that support it (each
// says "native: oit"). Vanilla's phases: OIT_DEPTH_BOUNDS and OIT_TRANSMITTANCE (both with OIT_ALPHA_ONLY: no colour), then
// OIT_ACCUMULATE. The backend defines neither RENDERPEARL_DEPTH_IS_ZERO_TO_ONE nor RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE
// (as for the translated programs), so the GL branches. NATIVE_OIT_FS_PARAMS goes at the end of native_fs's parameters,
// NATIVE_FS_OUT is native_fs's return type, and the program ends its fragment stage with native_oit_alpha_only (alpha-only
// phases) or passes its colour through native_oit_accumulate (OIT_ACCUMULATE), where vanilla calls executeAlphaOnlyPhase
// and sampleColorForAccumulation.
#ifdef OIT
#define OIT_NUMBER_OF_DEPTH_BINS OIT_COEFF_COUNT
constant float OIT_FULLY_OPAQUE_ALPHA = 0.99f;
constant float OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE = 0.02f;

static inline float oit_device_to_linear_depth(float deviceDepth, constant Projection &projection) {
	deviceDepth = (deviceDepth - 0.5f) * 2.0f;
	return projection.ProjMat[3][2] / (deviceDepth + projection.ProjMat[2][2]);
}
static inline float oit_to_absorbance(float transmittance) { return clamp(-log(max(transmittance, 0.0001f)), 0.0f, 4.0f); }
static inline float oit_to_transmittance(float absorbance) { return clamp(exp(-absorbance), 0.0001f, 1.0f); }

#if defined(OIT_TRANSMITTANCE) || defined(OIT_ACCUMULATE)
// oit_depth_sample.glsl
constant float OIT_HIGH_PRECISION_DEPTH_THRESHOLD = 10.0f;
#define OIT_LOW_PRECISION_DEPTH_BINS 2
static inline float oit_normalize_depth(float fragmentDeviceDepth, float2 fragXY, texture2d<float> bounds, constant Projection &projection) {
	float4 depthBoundsSample = bounds.read(uint2(int2(fragXY)), 0);
	float closestBoundLinearDepth = -depthBoundsSample.r;
	float furthestBoundLinearDepth = depthBoundsSample.g;
	float fragmentLinearDepth = oit_device_to_linear_depth(fragmentDeviceDepth, projection);
	float range = max(furthestBoundLinearDepth - closestBoundLinearDepth, 0.0001f);
	float depthWithinBounds = clamp(fragmentLinearDepth - closestBoundLinearDepth, 0.0f, range);
	const float depthFractionWithHighPrecision = float(OIT_NUMBER_OF_DEPTH_BINS - OIT_LOW_PRECISION_DEPTH_BINS) / float(OIT_NUMBER_OF_DEPTH_BINS);
	float depthWithHighPrecision = depthFractionWithHighPrecision * range;
	if (depthWithHighPrecision <= OIT_HIGH_PRECISION_DEPTH_THRESHOLD) return depthWithinBounds / range;
	else if (depthWithinBounds <= OIT_HIGH_PRECISION_DEPTH_THRESHOLD)
		return (depthWithinBounds / OIT_HIGH_PRECISION_DEPTH_THRESHOLD) * depthFractionWithHighPrecision;
	return depthFractionWithHighPrecision + ((depthWithinBounds - OIT_HIGH_PRECISION_DEPTH_THRESHOLD)
		/ (range - OIT_HIGH_PRECISION_DEPTH_THRESHOLD)) * (1.0f - depthFractionWithHighPrecision);
}
#endif

#if !defined(OIT_DEPTH_BOUNDS) && !defined(OIT_TRANSMITTANCE)
// oit_sample.glsl (as oit.glsl includes it: the accumulate phase, and the composite with no phase)
static inline float oit_sample_absorbance(thread const float *coefficients, float originalDepth, float currentAbsorbance) {
	float averageAbsorbance = coefficients[0];
	if (averageAbsorbance == 0.0f) return 0.0f;
	float depthMeasuredInBins = originalDepth * float(OIT_NUMBER_OF_DEPTH_BINS - 1);
	float depth = depthMeasuredInBins / float(OIT_NUMBER_OF_DEPTH_BINS);
	float currentAverageAbsorbanceContribution = currentAbsorbance * (1.0f - depth);
	averageAbsorbance -= currentAverageAbsorbanceContribution;
	int treeIndexB = clamp(int(floor(depthMeasuredInBins)), 0, OIT_NUMBER_OF_DEPTH_BINS - 1);
	bool shouldSampleA = treeIndexB >= 1;
	int treeIndexA = shouldSampleA ? (treeIndexB - 1) : treeIndexB;
	treeIndexB += OIT_COEFF_COUNT;
	treeIndexA += OIT_COEFF_COUNT;
	float sampleB = averageAbsorbance;
	float sampleA = shouldSampleA ? averageAbsorbance : 0.0f;
	for (int waveletLevel = 0; waveletLevel <= OIT_WAVELET_RANK; waveletLevel++) {
		int power = OIT_WAVELET_RANK - waveletLevel;
		float waveletWidth = exp2(float(-power));
		float waveletHeightScale = exp2(float(power) * 0.5f);
		int parentIndexB = treeIndexB >> 1;
		int isRightHalfB = treeIndexB & 1;
		int waveletSignB = 1 - (isRightHalfB << 1);
		float indexAtParentLevel = float(parentIndexB & ((1 << power) - 1));
		float depthOffsetRelativeToWavelet = depth - waveletWidth * indexAtParentLevel;
		float currentDifferenceCoefficient = (float(isRightHalfB) * waveletWidth + float(waveletSignB) * depthOffsetRelativeToWavelet)
			* waveletHeightScale * currentAbsorbance;
		float differenceCoefficientB = coefficients[parentIndexB];
		differenceCoefficientB -= currentDifferenceCoefficient;
		sampleB -= waveletHeightScale * differenceCoefficientB * float(waveletSignB);
		treeIndexB = parentIndexB;
		if (shouldSampleA) {
			int parentIndexA = treeIndexA >> 1;
			float differenceCoefficientA = (parentIndexA == parentIndexB) ? differenceCoefficientB : coefficients[parentIndexA];
			int isRightHalfA = treeIndexA & 1;
			int waveletSignA = 1 - (isRightHalfA << 1);
			sampleA -= waveletHeightScale * differenceCoefficientA * float(waveletSignA);
			treeIndexA = parentIndexA;
		}
	}
	float lerpAlpha = depthMeasuredInBins >= float(OIT_NUMBER_OF_DEPTH_BINS) ? 1.0f : fract(depthMeasuredInBins);
	return mix(sampleA, sampleB, lerpAlpha);
}

#if OIT_COEFF_COUNT > 8
#define NATIVE_OIT_COEFF_PARAMS , texture2d<float> Coeff0 [[texture(NATIVE_SLOT_Coeff0)]], texture2d<float> Coeff1 [[texture(NATIVE_SLOT_Coeff1)]], \
	texture2d<float> Coeff2 [[texture(NATIVE_SLOT_Coeff2)]], texture2d<float> Coeff3 [[texture(NATIVE_SLOT_Coeff3)]]
#define NATIVE_OIT_COEFF_ARGS , Coeff0, Coeff1, Coeff2, Coeff3
#define NATIVE_OIT_COEFF_DECL , texture2d<float> Coeff0, texture2d<float> Coeff1, texture2d<float> Coeff2, texture2d<float> Coeff3
#elif OIT_COEFF_COUNT > 4
#define NATIVE_OIT_COEFF_PARAMS , texture2d<float> Coeff0 [[texture(NATIVE_SLOT_Coeff0)]], texture2d<float> Coeff1 [[texture(NATIVE_SLOT_Coeff1)]]
#define NATIVE_OIT_COEFF_ARGS , Coeff0, Coeff1
#define NATIVE_OIT_COEFF_DECL , texture2d<float> Coeff0, texture2d<float> Coeff1
#else
#define NATIVE_OIT_COEFF_PARAMS , texture2d<float> Coeff0 [[texture(NATIVE_SLOT_Coeff0)]]
#define NATIVE_OIT_COEFF_ARGS , Coeff0
#define NATIVE_OIT_COEFF_DECL , texture2d<float> Coeff0
#endif

static inline float oit_sample_transmittance(uint2 pos, float depth, float currentTransmittance NATIVE_OIT_COEFF_DECL) {
	float coefficients[OIT_COEFF_COUNT];
	float4 coeffSamples[OIT_COEFF_COUNT / 4];
	coeffSamples[0] = Coeff0.read(pos, 0);
#if OIT_COEFF_COUNT > 4
	coeffSamples[1] = Coeff1.read(pos, 0);
#endif
#if OIT_COEFF_COUNT > 8
	coeffSamples[2] = Coeff2.read(pos, 0);
	coeffSamples[3] = Coeff3.read(pos, 0);
#endif
	for (int i = 0; i < OIT_COEFF_COUNT / 4; i++)
		for (int j = 0; j < 4; j++) coefficients[i * 4 + j] = coeffSamples[i][j];
	return oit_to_transmittance(oit_sample_absorbance(coefficients, depth, oit_to_absorbance(currentTransmittance)));
}
#endif

#if defined(OIT_ALPHA_ONLY) && defined(OIT_DEPTH_BOUNDS)
#define NATIVE_OIT_FS_PARAMS , constant Projection &oitProjection [[buffer(NATIVE_SLOT_Projection)]]
#define NATIVE_FS_OUT float4
// executeAlphaOnlyPhase with oit_depth_bounds.glsl's calculateDepthBounds
static inline float4 native_oit_alpha_only(float4 fragCoord, float alpha, constant Projection &projection) {
	if (alpha < 0.01f) discard_fragment();
	float fragmentDeviceDepth = fragCoord.z;
	float fragmentLinearDepth = oit_device_to_linear_depth(fragmentDeviceDepth, projection);
	float opaqueFragmentDeviceDepth = alpha > OIT_FULLY_OPAQUE_ALPHA ? fragmentDeviceDepth : 0.0f;
	return float4(-fragmentLinearDepth, fragmentLinearDepth, fragmentDeviceDepth, opaqueFragmentDeviceDepth);
}
#define NATIVE_OIT_ALPHA_ONLY(fragCoord, alpha) native_oit_alpha_only(fragCoord, alpha, oitProjection)
#elif defined(OIT_ALPHA_ONLY) && defined(OIT_TRANSMITTANCE)
#define NATIVE_OIT_FS_PARAMS , constant Projection &oitProjection [[buffer(NATIVE_SLOT_Projection)]], \
	texture2d<float> DepthBoundsSampler [[texture(NATIVE_SLOT_DepthBoundsSampler)]]
struct NativeOitCoeffs {
	float4 coeff0 [[color(0)]];
#if OIT_COEFF_ATTACHMENT_COUNT > 1
	float4 coeff1 [[color(1)]];
#endif
#if OIT_COEFF_ATTACHMENT_COUNT > 2
	float4 coeff2 [[color(2)]];
	float4 coeff3 [[color(3)]];
#endif
};
#define NATIVE_FS_OUT NativeOitCoeffs
// executeAlphaOnlyPhase with oit_add_transmittance.glsl's addTransmittance
static inline NativeOitCoeffs native_oit_alpha_only(float4 fragCoord, float alpha, constant Projection &projection, texture2d<float> bounds) {
	NativeOitCoeffs out = {};
#ifdef OIT_ADDITIVE
	discard_fragment();
	return out;
#else
	float transmittance = 1.0f - alpha;
	float coefficients[OIT_COEFF_COUNT];
	for (int i = 0; i < OIT_COEFF_COUNT; i++) coefficients[i] = 0.0f;
	float absorbance = oit_to_absorbance(transmittance);
	float originalDepth = oit_normalize_depth(fragCoord.z, fragCoord.xy, bounds, projection);
	float depth = originalDepth * float(OIT_NUMBER_OF_DEPTH_BINS - 1) / float(OIT_NUMBER_OF_DEPTH_BINS);
	float averageAbsorbance = absorbance * (1.0f - depth);
	coefficients[0] = averageAbsorbance;
	int depthBinIndex = int(floor(originalDepth * float(OIT_NUMBER_OF_DEPTH_BINS - 1)));
	int treeIndex = depthBinIndex + OIT_COEFF_COUNT;
	for (int waveletLevel = 0; waveletLevel <= OIT_WAVELET_RANK; waveletLevel++) {
		int power = OIT_WAVELET_RANK - waveletLevel;
		int parentIndex = treeIndex >> 1;
		float indexAtParentLevel = float(parentIndex & ((1 << power) - 1));
		int isRightHalf = treeIndex & 1;
		int waveletSign = 1 - (isRightHalf << 1);
		float waveletWidth = exp2(float(-power));
		float waveletHeightScale = exp2(float(power) * 0.5f);
		float depthOffsetRelativeToWavelet = depth - waveletWidth * indexAtParentLevel;
		float distanceFromWaveletEdge = float(isRightHalf) * waveletWidth + float(waveletSign) * depthOffsetRelativeToWavelet;
		coefficients[parentIndex] = distanceFromWaveletEdge * waveletHeightScale * absorbance;
		treeIndex = parentIndex;
	}
	out.coeff0 = float4(coefficients[0], coefficients[1], coefficients[2], coefficients[3]);
#if OIT_COEFF_ATTACHMENT_COUNT > 1
	out.coeff1 = float4(coefficients[4], coefficients[5], coefficients[6], coefficients[7]);
#endif
#if OIT_COEFF_ATTACHMENT_COUNT > 2
	out.coeff2 = float4(coefficients[8], coefficients[9], coefficients[10], coefficients[11]);
	out.coeff3 = float4(coefficients[12], coefficients[13], coefficients[14], coefficients[15]);
#endif
	return out;
#endif
}
#define NATIVE_OIT_ALPHA_ONLY(fragCoord, alpha) native_oit_alpha_only(fragCoord, alpha, oitProjection, DepthBoundsSampler)
#elif defined(OIT_ACCUMULATE)
#define NATIVE_OIT_FS_PARAMS , constant Projection &oitProjection [[buffer(NATIVE_SLOT_Projection)]], \
	texture2d<float> DepthBoundsSampler [[texture(NATIVE_SLOT_DepthBoundsSampler)]] NATIVE_OIT_COEFF_PARAMS
#define NATIVE_FS_OUT float4
// oit_sample.glsl's sampleColorForAccumulation
static inline float4 native_oit_accumulate(float4 color, float4 fragCoord, constant Projection &projection, texture2d<float> bounds
	NATIVE_OIT_COEFF_DECL) {
#ifdef OIT_ADDITIVE
	float transmittance = 1.0f;
	float accumAlpha = 0.0f;
#else
	float transmittance = 1.0f - color.a;
	float accumAlpha = color.a;
#endif
	float sampledTransmittance = oit_sample_transmittance(uint2(int2(fragCoord.xy)), oit_normalize_depth(fragCoord.z, fragCoord.xy, bounds, projection),
		transmittance NATIVE_OIT_COEFF_ARGS);
	return float4(color.rgb * color.a, accumAlpha) * sampledTransmittance;
}
#define NATIVE_OIT_ACCUMULATE(color, fragCoord) native_oit_accumulate(color, fragCoord, oitProjection, DepthBoundsSampler NATIVE_OIT_COEFF_ARGS)
#endif
#endif // OIT

#ifndef NATIVE_OIT_FS_PARAMS
#define NATIVE_OIT_FS_PARAMS
#endif
#ifndef NATIVE_FS_OUT
#define NATIVE_FS_OUT float4
#endif

// calculateFinalColor's fog: under OIT_ACCUMULATE the fog colour is premultiplied like the accumulated colour
static inline float4 apply_fog_final(float4 c, float sph, float cyl, constant Fog &fog) {
#ifdef OIT_ACCUMULATE
	float4 fogColor = float4(fog.FogColor.rgb * c.a, fog.FogColor.a);
#else
	float4 fogColor = fog.FogColor;
#endif
	return apply_fog(c, sph, cyl, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd, fogColor);
}
#ifdef OIT_ACCUMULATE
#define NATIVE_OIT_ACCUMULATE_STEP(color, fragCoord) color = NATIVE_OIT_ACCUMULATE(color, fragCoord)
#else
#define NATIVE_OIT_ACCUMULATE_STEP(color, fragCoord)
#endif
