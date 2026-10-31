// Cadence native: vanilla's core/rendertype_water_mask.vsh + .fsh.
struct MaskIn { float3 Position [[attribute(0)]]; };
struct MaskVaryings { float4 position [[position]]; };

vertex MaskVaryings native_vs(MaskIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	MaskVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	return out;
}

fragment float4 native_fs(MaskVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	return transforms.ColorModulator;
}
