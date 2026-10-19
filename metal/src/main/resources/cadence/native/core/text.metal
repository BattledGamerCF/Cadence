// Cadence native: vanilla's core/text.vsh + text.fsh (world text with fog and light; IS_GUI, IS_SEE_THROUGH, IS_GRAYSCALE;
// order-independent transparency too).
// native: oit (its order-independent transparency variants too: common.metal)
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#define NATIVE_WORLD_TEXT 1
#endif

struct TextIn {
	float3 Position [[attribute(0)]];
	float4 Color [[attribute(1)]];
	float2 UV0 [[attribute(2)]];
#ifdef NATIVE_WORLD_TEXT
	int2 UV2 [[attribute(3)]];
#endif
};

struct TextVaryings {
	float4 position [[position]];
#ifdef NATIVE_WORLD_TEXT
	float sphericalVertexDistance;
	float cylindricalVertexDistance;
#endif
	float4 vertexColor;
	float2 texCoord0;
};

vertex TextVaryings native_vs(TextIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]
#ifdef NATIVE_WORLD_TEXT
	, texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]
#endif
	) {
	TextVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
#ifdef NATIVE_WORLD_TEXT
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
#else
	out.vertexColor = in.Color;
#endif
	out.texCoord0 = in.UV0;
	return out;
}

fragment NATIVE_FS_OUT native_fs(TextVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]
#ifdef NATIVE_WORLD_TEXT
	, constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]]
#endif
	, texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]] NATIVE_OIT_FS_PARAMS) {
#ifdef IS_GRAYSCALE
	float4 texColor = Sampler0.sample(Sampler0Smplr, in.texCoord0).rrrr;
#else
	float4 texColor = Sampler0.sample(Sampler0Smplr, in.texCoord0);
#endif
	float4 color = texColor * in.vertexColor * transforms.ColorModulator;
	if (color.a < 0.1f) discard_fragment();
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
#ifdef NATIVE_WORLD_TEXT
	color = apply_fog_final(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
#endif
	return color;
#endif
}
