// Cadence native: vanilla's core/world_border.vsh + .fsh.
// native: oit (its order-independent transparency variants too: common.metal)
struct BorderIn { float3 Position [[attribute(0)]]; float2 UV0 [[attribute(1)]]; };
struct BorderVaryings { float4 position [[position]]; float2 texCoord0; };

vertex BorderVaryings native_vs(BorderIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	BorderVaryings out = {};
	float3 pos = in.Position + transforms.ModelOffset;
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(pos, 1.0f));
	out.texCoord0 = (transforms.TextureMat * float4(in.UV0, 0.0f, 1.0f)).xy;
	return out;
}

fragment NATIVE_FS_OUT native_fs(BorderVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]] NATIVE_OIT_FS_PARAMS) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
	if (color.a == 0.0f) discard_fragment();
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	color *= transforms.ColorModulator;  // (in calculateFinalColor: after the alpha-only phases)
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return color;
#endif
}
