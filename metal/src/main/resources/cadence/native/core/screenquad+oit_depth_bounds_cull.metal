// Cadence native: vanilla's core/screenquad.vsh + oit_depth_bounds_cull.fsh (order-independent transparency's depth bounds,
// clipped to the nearest opaque transparent surface).
// native: oit
struct QuadVaryings { float4 position [[position]]; float2 texCoord; };

vertex QuadVaryings native_vs(uint vid [[vertex_id]]) {
	QuadVaryings out = {};
	float2 uv = float2(int2((int(vid) << 1) & 2, int(vid) & 2));
	out.position = native_flip(float4(uv * float2(2.0f, 2.0f) + float2(-1.0f, -1.0f), 0.0f, 1.0f));
	out.texCoord = uv;
	return out;
}

struct CullOut { float4 color [[color(0)]]; float depth [[depth(any)]]; };
fragment CullOut native_fs(QuadVaryings in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	texture2d<float> DepthBoundsSampler [[texture(NATIVE_SLOT_DepthBoundsSampler)]]) {
	float4 depthBounds = DepthBoundsSampler.read(uint2(int2(in.position.xy)), 0);
	float closestBoundLinearDepthNegated = depthBounds.r;
	float furthestBoundLinearDepth = depthBounds.g;
	float closestBoundDeviceDepth = depthBounds.b;
	float opaqueOitDeviceDepth = depthBounds.a;
	float opaqueOitLinearDepth = oit_device_to_linear_depth(opaqueOitDeviceDepth, projection);
	CullOut out;
	out.color = float4(closestBoundLinearDepthNegated, min(furthestBoundLinearDepth, opaqueOitLinearDepth), closestBoundDeviceDepth, opaqueOitDeviceDepth);
	out.depth = opaqueOitDeviceDepth;
	return out;
}
