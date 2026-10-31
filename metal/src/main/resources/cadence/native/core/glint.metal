// Cadence native: vanilla's core/glint.vsh + glint.fsh.
struct GlintIn { float3 Position [[attribute(0)]]; float2 UV0 [[attribute(1)]]; };
struct GlintVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float2 texCoord0; };

vertex GlintVaryings native_vs(GlintIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	GlintVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.texCoord0 = (transforms.TextureMat * float4(in.UV0, 0.0f, 1.0f)).xy;
	return out;
}

fragment float4 native_fs(GlintVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]], constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]]) {
	float4 color = Sampler0.sample(Sampler0Smplr, in.texCoord0) * transforms.ColorModulator;
	if (color.a < 0.1f) discard_fragment();
	float fade = (1.0f - total_fog_value(in.sphericalVertexDistance, in.cylindricalVertexDistance, fog.FogEnvironmentalStart,
		fog.FogEnvironmentalEnd, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd)) * globals.GlintAlpha;
	return float4(color.rgb * fade, color.a);
}
