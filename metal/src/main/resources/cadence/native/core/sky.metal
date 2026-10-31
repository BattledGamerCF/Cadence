// Cadence native: vanilla's core/sky.vsh + sky.fsh.
struct SkyIn { float3 Position [[attribute(0)]]; };
struct SkyVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; };

vertex SkyVaryings native_vs(SkyIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	SkyVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	return out;
}

fragment float4 native_fs(SkyVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]]) {
	return apply_fog(transforms.ColorModulator, in.sphericalVertexDistance, in.cylindricalVertexDistance, 0.0f, fog.FogSkyEnd,
		fog.FogSkyEnd, fog.FogSkyEnd, fog.FogColor);
}
