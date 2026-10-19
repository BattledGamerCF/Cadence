// Cadence native: vanilla's core/rendertype_leash.vsh + .fsh.
struct LeashIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; int2 UV2 [[attribute(2)]]; };
struct LeashVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float4 vertexColor [[flat]]; };

vertex LeashVaryings native_vs(LeashIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]) {
	LeashVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = in.Color * transforms.ColorModulator * sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
	return out;
}

fragment float4 native_fs(LeashVaryings in [[stage_in]], constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]]) {
	return apply_fog(in.vertexColor, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
