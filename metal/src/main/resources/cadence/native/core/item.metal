// Cadence native: vanilla's core/item.vsh + item.fsh (every variant but order-independent transparency).
struct ItemIn {
	float3 Position [[attribute(0)]];
	float4 Color [[attribute(1)]];
	float2 UV0 [[attribute(2)]];
	int2 UV1 [[attribute(3)]];
	int2 UV2 [[attribute(4)]];
#ifdef GLINT_SPECIAL
	float2 UV3 [[attribute(5)]];
#endif
	float3 Normal [[attribute(6)]];
};

struct ItemVaryings {
	float4 position [[position]];
	float sphericalVertexDistance;
	float cylindricalVertexDistance;
	float4 vertexColor;
	float4 lightMapColor;
	float4 overlayColor;
	float2 texCoord0;
#ifdef GLINT
	float2 texCoordGlint;
#endif
};

vertex ItemVaryings native_vs(ItemIn in [[stage_in]],
	constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Lighting &lighting [[buffer(NATIVE_SLOT_Lighting)]],
	texture2d<float> Sampler1 [[texture(NATIVE_SLOT_Sampler1)]],
	texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]) {
	ItemVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = minecraft_mix_light(lighting.Light0_Direction, lighting.Light1_Direction, in.Normal, in.Color);
	out.lightMapColor = sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
	out.overlayColor = Sampler1.read(uint2(in.UV1), 0);
	out.texCoord0 = in.UV0;
#ifdef GLINT
#ifdef GLINT_SPECIAL
	out.texCoordGlint = (transforms.TextureMat * float4(in.UV3, 0.0f, 1.0f)).xy;
#else
	out.texCoordGlint = (transforms.TextureMat * float4(in.UV0, 0.0f, 1.0f)).xy;
#endif
#endif
	return out;
}

fragment float4 native_fs(ItemVaryings in [[stage_in]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]
#ifdef GLINT
	, constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]]
	, texture2d<float> GlintSampler [[texture(NATIVE_SLOT_GlintSampler)]], sampler GlintSamplerSmplr [[sampler(NATIVE_SLOT_GlintSampler)]]
#endif
	) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) discard_fragment();
#endif
	color *= in.vertexColor * transforms.ColorModulator;
#ifdef GLINT
	color.a = max(color.a, globals.GlintAlpha);
#endif
	color.rgb = mix(in.overlayColor.rgb, color.rgb, in.overlayColor.a);
	color *= in.lightMapColor;
#ifdef GLINT
	float4 glintColor = globals.GlintAlpha * GlintSampler.sample(GlintSamplerSmplr, in.texCoordGlint);
	color.rgb += glintColor.rgb * glintColor.rgb;
#endif
	return apply_fog(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
