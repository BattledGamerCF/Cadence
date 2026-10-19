// Cadence native: vanilla's core/entity.vsh + entity.fsh (every variant).
// native: oit (its order-independent transparency variants too: common.metal)
// Same inputs (layout locations), same math, same order of operations.
#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#define NATIVE_LIT 1
#endif

struct EntityIn {
	float3 Position [[attribute(0)]];
	float4 Color [[attribute(1)]];
	float2 UV0 [[attribute(2)]];
#ifndef NO_OVERLAY
	int2 UV1 [[attribute(3)]];
#endif
#ifndef EMISSIVE
	int2 UV2 [[attribute(4)]];
#endif
#ifdef NATIVE_LIT
	float3 Normal [[attribute(5)]];
#endif
};

struct EntityVaryings {
	float4 position [[position]];
	float sphericalVertexDistance;
	float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
	float4 vertexPerFaceColorBack;
	float4 vertexPerFaceColorFront;
#else
	float4 vertexColor;
#endif
#ifndef EMISSIVE
	float4 lightMapColor;
#endif
#ifndef NO_OVERLAY
	float4 overlayColor;
#endif
	float2 texCoord0;
#ifdef GLINT
	float2 texCoordGlint;
#endif
};

vertex EntityVaryings native_vs(EntityIn in [[stage_in]],
	constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]
#ifdef NATIVE_LIT
	, constant Lighting &lighting [[buffer(NATIVE_SLOT_Lighting)]]
#endif
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
	, texture2d<float> Sampler1 [[texture(NATIVE_SLOT_Sampler1)]]
#endif
#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
	, texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]
#endif
	) {
	EntityVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
#ifdef PER_FACE_LIGHTING
	float2 light = minecraft_compute_light(lighting.Light0_Direction, lighting.Light1_Direction, in.Normal);
	out.vertexPerFaceColorBack = minecraft_mix_light_separate(-light, in.Color);
	out.vertexPerFaceColorFront = minecraft_mix_light_separate(light, in.Color);
#elif defined(NO_CARDINAL_LIGHTING)
	out.vertexColor = in.Color;
#else
	out.vertexColor = minecraft_mix_light(lighting.Light0_Direction, lighting.Light1_Direction, in.Normal, in.Color);
#endif
#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
	out.lightMapColor = sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
#endif
#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
	out.overlayColor = Sampler1.read(uint2(in.UV1), 0);
#endif
	out.texCoord0 = in.UV0;
#ifdef APPLY_TEXTURE_MATRIX
	out.texCoord0 = (transforms.TextureMat * float4(in.UV0, 0.0f, 1.0f)).xy;
#endif
#ifdef GLINT
	out.texCoordGlint = (transforms.TextureMat * float4(in.UV0, 0.0f, 1.0f)).xy;
#endif
	return out;
}

fragment NATIVE_FS_OUT native_fs(EntityVaryings in [[stage_in]], bool frontFacing [[front_facing]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]
#ifdef DISSOLVE
	, texture2d<float> DissolveMaskSampler [[texture(NATIVE_SLOT_DissolveMaskSampler)]], sampler DissolveMaskSamplerSmplr [[sampler(NATIVE_SLOT_DissolveMaskSampler)]]
#endif
#ifdef GLINT
	, constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]]
	, texture2d<float> GlintSampler [[texture(NATIVE_SLOT_GlintSampler)]], sampler GlintSamplerSmplr [[sampler(NATIVE_SLOT_GlintSampler)]]
#endif
	NATIVE_OIT_FS_PARAMS) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
#ifdef OIT_ADDITIVE
	color.a = min(0.99f, color.a);
#endif
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) discard_fragment();
#endif
#ifdef PER_FACE_LIGHTING
	float4 faceVertexColor = frontFacing ? in.vertexPerFaceColorFront : in.vertexPerFaceColorBack;
#else
	float4 faceVertexColor = in.vertexColor;
#endif
#ifdef DISSOLVE
	if (faceVertexColor.a < DissolveMaskSampler.sample(DissolveMaskSamplerSmplr, in.texCoord0).a) discard_fragment();
	faceVertexColor.a = 1.0f;
#endif
	color *= faceVertexColor * transforms.ColorModulator;
#ifdef GLINT
	color.a = max(color.a, globals.GlintAlpha);
#endif
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	// calculateFinalColor
#ifndef NO_OVERLAY
	color.rgb = mix(in.overlayColor.rgb, color.rgb, in.overlayColor.a);
#endif
#ifndef EMISSIVE
	color *= in.lightMapColor;
#endif
#ifdef GLINT
	float4 glintColor = globals.GlintAlpha * GlintSampler.sample(GlintSamplerSmplr, in.texCoordGlint);
	color.rgb += glintColor.rgb * glintColor.rgb;
#endif
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return apply_fog_final(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
#endif
}
