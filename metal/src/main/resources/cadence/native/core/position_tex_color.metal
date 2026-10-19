// Cadence native: vanilla's core/position_tex_color.vsh + position_tex_color.fsh.
struct PtcIn { float3 Position [[attribute(0)]]; float2 UV0 [[attribute(1)]]; float4 Color [[attribute(2)]]; };
struct PtcVaryings { float4 position [[position]]; float2 texCoord0; float4 vertexColor; };

vertex PtcVaryings native_vs(PtcIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	PtcVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.texCoord0 = in.UV0;
	out.vertexColor = in.Color;
	return out;
}

fragment float4 native_fs(PtcVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0) * in.vertexColor;
	if (color.a == 0.0f) discard_fragment();
	return color * transforms.ColorModulator;
}
