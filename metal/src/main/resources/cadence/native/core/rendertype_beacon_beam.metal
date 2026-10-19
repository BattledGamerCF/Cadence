// Cadence native: vanilla's core/rendertype_beacon_beam.vsh + .fsh.
// native: oit (its order-independent transparency variants too: common.metal)
struct BeamIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float2 UV0 [[attribute(2)]]; };
struct BeamVaryings { float4 position [[position]]; float4 vertexColor; float2 texCoord0; };

vertex BeamVaryings native_vs(BeamIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	BeamVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.vertexColor = in.Color;
	out.texCoord0 = in.UV0;
	return out;
}

fragment NATIVE_FS_OUT native_fs(BeamVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]] NATIVE_OIT_FS_PARAMS) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
	color *= in.vertexColor * transforms.ColorModulator;
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	float fragmentDistance = 1.0f / in.position.w;  // (gl_FragCoord.w, as the translation reads it)
	return apply_fog_final(color, fragmentDistance, fragmentDistance, fog);
#endif
}
