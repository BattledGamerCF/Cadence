// Cadence native: vanilla's core/position_color.vsh + position_color.fsh (not order-independent transparency).
struct PcIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; };
struct PcVaryings { float4 position [[position]]; float4 vertexColor; };

vertex PcVaryings native_vs(PcIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	PcVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.vertexColor = in.Color;
	return out;
}

fragment float4 native_fs(PcVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	float4 color = in.vertexColor;
	if (color.a == 0.0f) discard_fragment();
	return color * transforms.ColorModulator;
}
