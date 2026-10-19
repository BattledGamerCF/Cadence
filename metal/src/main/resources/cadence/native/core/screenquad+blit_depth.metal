// Cadence native: vanilla's core/screenquad.vsh + blit_depth.fsh.
struct QuadVaryings { float4 position [[position]]; float2 texCoord; };

vertex QuadVaryings native_vs(uint vid [[vertex_id]]) {
	QuadVaryings out = {};
	float2 uv = float2(int2((int(vid) << 1) & 2, int(vid) & 2));
	out.position = native_flip(float4(uv * float2(2.0f, 2.0f) + float2(-1.0f, -1.0f), 0.0f, 1.0f));
	out.texCoord = uv;
	return out;
}

struct DepthOut { float depth [[depth(any)]]; };
fragment DepthOut native_fs(QuadVaryings in [[stage_in]], texture2d<float> InSampler [[texture(NATIVE_SLOT_InSampler)]],
	sampler InSamplerSmplr [[sampler(NATIVE_SLOT_InSampler)]]) {
	DepthOut out;
	out.depth = InSampler.sample(InSamplerSmplr, in.texCoord).r;
	return out;
}
