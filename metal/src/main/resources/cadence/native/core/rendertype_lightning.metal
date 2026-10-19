// Cadence native: vanilla's core/rendertype_lightning.vsh + .fsh.
// native: oit (its order-independent transparency variants too: common.metal)
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

fragment NATIVE_FS_OUT native_fs(BoltVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]] NATIVE_OIT_FS_PARAMS) {
	float4 color = in.vertexColor * transforms.ColorModulator * (1.0f - total_fog_value(in.sphericalVertexDistance, in.cylindricalVertexDistance,
		fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return color;
#endif
}
