// Cadence native: vanilla's core/screenquad.vsh + oit_composite.fsh (the order-independent transparency composite: OIT with
// no phase, so oit.glsl's oit_sample.glsl, in common.metal).
// native: oit
struct QuadVaryings { float4 position [[position]]; float2 texCoord; };

vertex QuadVaryings native_vs(uint vid [[vertex_id]]) {
	QuadVaryings out = {};
	float2 uv = float2(int2((int(vid) << 1) & 2, int(vid) & 2));
	out.position = native_flip(float4(uv * float2(2.0f, 2.0f) + float2(-1.0f, -1.0f), 0.0f, 1.0f));
	out.texCoord = uv;
	return out;
}

struct CompositeOut { float4 color [[color(0)]]; float depth [[depth(any)]]; };
fragment CompositeOut native_fs(QuadVaryings in [[stage_in]], texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]],
	texture2d<float> DepthBoundsSampler [[texture(NATIVE_SLOT_DepthBoundsSampler)]] NATIVE_OIT_COEFF_PARAMS) {
	uint2 pixelCoords = uint2(int2(in.position.xy));
	float4 accumulatedColor = Sampler0.read(pixelCoords, 0);
	float sampledTransmittance = oit_sample_transmittance(pixelCoords, 100000.0f, 1.0f NATIVE_OIT_COEFF_ARGS);
	float coverage = 1.0f - (sampledTransmittance < OIT_FULLY_OPAQUE_TOTAL_TRANSMITTANCE ? 0.0f : sampledTransmittance);
	if (coverage < 0.00001f && dot(accumulatedColor.rgb, float3(1.0f)) < 0.00001f) discard_fragment();
	float normalization = accumulatedColor.a > 0.00001f ? coverage / accumulatedColor.a : 1.0f;
	CompositeOut out;
	out.color = float4(accumulatedColor.rgb * normalization, coverage);
	out.depth = DepthBoundsSampler.read(pixelCoords, 0).b;
	return out;
}
