// Cadence native: vanilla's core/panorama.vsh + panorama.fsh (the title screen's cube map).
struct PanoIn { float3 Position [[attribute(0)]]; };
struct PanoVaryings { float4 position [[position]]; float3 texCoord0; };

vertex PanoVaryings native_vs(PanoIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	PanoVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.texCoord0 = in.Position;
	return out;
}

fragment float4 native_fs(PanoVaryings in [[stage_in]], texturecube<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]],
	sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	return Sampler0.sample(Sampler0Smplr, in.texCoord0);
}
