// Cadence native: vanilla's core/stars.vsh + stars.fsh.
struct StarsIn { float3 Position [[attribute(0)]]; };
struct StarsVaryings { float4 position [[position]]; };

vertex StarsVaryings native_vs(StarsIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	StarsVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	return out;
}

fragment float4 native_fs(StarsVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	return transforms.ColorModulator;
}
