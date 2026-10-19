// Cadence native: vanilla's core/rendertype_lightning.vsh + .fsh (not order-independent transparency).
struct BoltIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; };
struct BoltVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float4 vertexColor; };

vertex BoltVaryings native_vs(BoltIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	BoltVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = in.Color;
	return out;
}

fragment float4 native_fs(BoltVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]]) {
	return in.vertexColor * transforms.ColorModulator * (1.0f - total_fog_value(in.sphericalVertexDistance, in.cylindricalVertexDistance,
		fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
}
