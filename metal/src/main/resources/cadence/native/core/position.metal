// Cadence native: vanilla's core/position.vsh + position.fsh.
struct PIn { float3 Position [[attribute(0)]]; };
struct PVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; };

vertex PVaryings native_vs(PIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	PVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	return out;
}

fragment float4 native_fs(PVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]]) {
	return apply_fog(transforms.ColorModulator, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
