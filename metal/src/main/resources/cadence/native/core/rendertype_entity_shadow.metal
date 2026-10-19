// Cadence native: vanilla's core/rendertype_entity_shadow.vsh + .fsh (not order-independent transparency).
struct ShadowIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float2 UV0 [[attribute(2)]]; };
struct ShadowVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float4 vertexColor; float2 texCoord0; };

vertex ShadowVaryings native_vs(ShadowIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	ShadowVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = in.Color;
	out.texCoord0 = in.UV0;
	return out;
}

fragment float4 native_fs(ShadowVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, clamp(in.texCoord0, 0.0f, 1.0f));
	color *= in.vertexColor * transforms.ColorModulator;
	return apply_fog(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
