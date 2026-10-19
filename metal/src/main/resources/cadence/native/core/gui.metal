// Cadence native: vanilla's core/gui.vsh + gui.fsh.
struct GuiIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; };
struct GuiVaryings { float4 position [[position]]; float4 vertexColor; };

vertex GuiVaryings native_vs(GuiIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	GuiVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.vertexColor = in.Color;
	return out;
}

fragment float4 native_fs(GuiVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	float4 color = in.vertexColor;
	if (color.a == 0.0f) discard_fragment();
	return color * transforms.ColorModulator;
}
