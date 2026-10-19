// Cadence native: vanilla's core/particle.vsh + particle.fsh (every variant but order-independent transparency).
struct ParticleIn {
	float3 Position [[attribute(0)]];
	float2 UV0 [[attribute(1)]];
	float4 Color [[attribute(2)]];
	int2 UV2 [[attribute(3)]];
};

struct ParticleVaryings {
	float4 position [[position]];
	float sphericalVertexDistance;
	float cylindricalVertexDistance;
	float2 texCoord0;
	float4 vertexColor;
};

vertex ParticleVaryings native_vs(ParticleIn in [[stage_in]],
	constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	texture2d<float> Sampler2 [[texture(NATIVE_SLOT_Sampler2)]], sampler Sampler2Smplr [[sampler(NATIVE_SLOT_Sampler2)]]) {
	ParticleVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.texCoord0 = in.UV0;
	out.vertexColor = in.Color * sample_lightmap(Sampler2, Sampler2Smplr, in.UV2);
	return out;
}

fragment float4 native_fs(ParticleVaryings in [[stage_in]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0) * in.vertexColor * transforms.ColorModulator;
	if (color.a < 0.1f) discard_fragment();
	return apply_fog(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
