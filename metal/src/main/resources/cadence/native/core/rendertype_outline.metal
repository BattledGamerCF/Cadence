// Cadence native: vanilla's core/rendertype_outline.vsh + .fsh (the glowing outline's silhouettes).
struct OutlineIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float2 UV0 [[attribute(2)]]; };
struct OutlineVaryings { float4 position [[position]]; float4 vertexColor; float2 texCoord0; };

vertex OutlineVaryings native_vs(OutlineIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	OutlineVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.vertexColor = in.Color;
	out.texCoord0 = in.UV0;
	return out;
}

fragment float4 native_fs(OutlineVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0);
	if (color.a == 0.0f) discard_fragment();
	return float4(transforms.ColorModulator.rgb * in.vertexColor.rgb, transforms.ColorModulator.a);
}
