// Cadence native: vanilla's core/block.vsh + block.fsh (blocks drawn outside chunks; not order-independent transparency).
struct BlockIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float2 UV0 [[attribute(2)]]; int2 UV2 [[attribute(3)]]; };
struct BlockVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float4 vertexColor; float2 texCoord0; };

vertex BlockVaryings native_vs(BlockIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]) {
	BlockVaryings out = {};
	float3 pos = in.Position + transforms.ModelOffset;
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(pos, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(pos);
	out.cylindricalVertexDistance = fog_cylindrical_distance(pos);
	out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
	out.texCoord0 = in.UV0;
	return out;
}

fragment float4 native_fs(BlockVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0) * in.vertexColor * transforms.ColorModulator;
#ifdef ALPHA_CUTOUT
	if (color.a < ALPHA_CUTOUT) discard_fragment();
#endif
	return apply_fog(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
