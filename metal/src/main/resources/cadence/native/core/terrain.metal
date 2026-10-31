// Cadence native: vanilla's core/terrain.vsh + terrain.fsh with texture_sampling.glsl (used when Cadence's own terrain
// renderer is off; order-independent transparency too). NATIVE_RGSS_SKIP: Cadence's shortcut (VanillaRgss) where RGSS's
// blend is 0 and the result is the nearest sample exactly.
// native: oit (its order-independent transparency variants too: common.metal)
struct TerrainUniform { float4x4 ModelViewMat; int2 TextureSize; };
#ifndef MULTIDRAW_TERRAIN
struct ChunkSection { packed_int3 ChunkPosition; float ChunkVisibility; };
#endif

struct TerrainIn {
	float3 Position [[attribute(0)]];
	float4 Color [[attribute(1)]];
	float2 UV0 [[attribute(2)]];
	int2 UV2 [[attribute(3)]];
#ifdef MULTIDRAW_TERRAIN
	int3 ChunkPosition [[attribute(4)]];
	float ChunkVisibility [[attribute(5)]];
#endif
};

struct TerrainVaryings {
	float4 position [[position]];
	float sphericalVertexDistance;
	float cylindricalVertexDistance;
	float4 vertexColor;
	float2 texCoord0;
	float chunkVisibility;
};

vertex TerrainVaryings native_vs(TerrainIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]], constant TerrainUniform &terrain [[buffer(NATIVE_SLOT_TerrainUniform)]]
#ifndef MULTIDRAW_TERRAIN
	, constant ChunkSection &section [[buffer(NATIVE_SLOT_ChunkSection)]]
#endif
#ifndef OIT_ALPHA_ONLY
	, texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]
#endif
	) {
	TerrainVaryings out = {};
#ifdef MULTIDRAW_TERRAIN
	int3 chunkPosition = in.ChunkPosition;
	float chunkVis = in.ChunkVisibility;
#else
	int3 chunkPosition = int3(section.ChunkPosition);
	float chunkVis = section.ChunkVisibility;
#endif
	float3 pos = in.Position + float3(chunkPosition - int3(globals.CameraBlockPos)) + float3(globals.CameraOffset);
	out.position = native_flip((projection.ProjMat * terrain.ModelViewMat) * float4(pos, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(pos);
	out.cylindricalVertexDistance = fog_cylindrical_distance(pos);
#ifndef OIT_ALPHA_ONLY
	out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
#else
	out.vertexColor = in.Color;
#endif
	out.texCoord0 = in.UV0;
	const float chunkFullyVisibleRange = 16.0f;
	float dist = length(pos);
	out.chunkVisibility = mix(1.0f, chunkVis, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0f, 1.0f));
	return out;
}

// texture_sampling.glsl
static inline float4 sampleNearest(texture2d<float> source, sampler s, float2 uv, float2 pixelSize, float2 du, float2 dv, float2 texelScreenSize) {
	float2 uvTexelCoords = uv / pixelSize;
	float2 texelCenter = round(uvTexelCoords) - 0.5f;
	float2 texelOffset = uvTexelCoords - texelCenter;
	texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
	texelOffset = clamp(texelOffset, 0.0f, 1.0f);
	uv = (texelCenter + texelOffset) * pixelSize;
	return source.sample(s, uv, gradient2d(du, dv));
}
static inline float4 sampleNearest(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv), dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	return sampleNearest(source, s, uv, pixelSize, du, dv, texelScreenSize);
}
constant float2 RGSS_OFFSETS[4] = { float2(0.125f, 0.375f), float2(-0.125f, -0.375f), float2(0.375f, -0.125f), float2(-0.375f, 0.125f) };
static inline float4 sampleRGSS(texture2d<float> source, sampler s, float2 uv, float2 pixelSize) {
	float2 du = dfdx(uv), dv = dfdy(uv);
	float2 texelScreenSize = sqrt(du * du + dv * dv);
	float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
	float minPixelSize = min(pixelSize.x, pixelSize.y);
	float blendFactor = smoothstep(minPixelSize * 1.0f, minPixelSize * 2.0f, maxTexelSize);
#ifdef NATIVE_RGSS_SKIP
	if (blendFactor == 0.0f) return sampleNearest(source, s, uv, pixelSize, du, dv, texelScreenSize);
#endif
	float minDerivative = min(length(du), length(dv));
	float maxDerivative = max(length(du), length(dv));
	float effectiveDerivative = sqrt(minDerivative * maxDerivative);
	float mipLevelExact = max(0.0f, log2(effectiveDerivative / minPixelSize));
	float mipLevelLow = floor(mipLevelExact);
	float mipLevelHigh = mipLevelLow + 1.0f;
	float mipBlend = fract(mipLevelExact);
	float4 low = float4(0.0f), high = float4(0.0f);
	for (int i = 0; i < 4; ++i) {
		float2 sampleUV = uv + RGSS_OFFSETS[i] * pixelSize;
		low += source.sample(s, sampleUV, level(mipLevelLow));
		high += source.sample(s, sampleUV, level(mipLevelHigh));
	}
	low *= 0.25f;
	high *= 0.25f;
	float4 rgssColor = mix(low, high, mipBlend);
	float4 nearestColor = sampleNearest(source, s, uv, pixelSize, du, dv, texelScreenSize);
	return mix(nearestColor, rgssColor, blendFactor);
}

fragment NATIVE_FS_OUT native_fs(TerrainVaryings in [[stage_in]], constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]],
	constant TerrainUniform &terrain [[buffer(NATIVE_SLOT_TerrainUniform)]], constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]] NATIVE_OIT_FS_PARAMS) {
	float2 pixelSize = 1.0f / float2(terrain.TextureSize);
	float4 color = (globals.UseRgss == 1 ? sampleRGSS(Sampler0, Sampler0Smplr, in.texCoord0, pixelSize)
		: sampleNearest(Sampler0, Sampler0Smplr, in.texCoord0, pixelSize)) * in.vertexColor;
#ifndef OIT_ALPHA_ONLY
	color = mix(fog.FogColor * float4(1.0f, 1.0f, 1.0f, color.a), color, in.chunkVisibility);
#endif
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) discard_fragment();
#endif
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return apply_fog_final(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
#endif
}
