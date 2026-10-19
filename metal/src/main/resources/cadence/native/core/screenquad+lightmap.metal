// Cadence native: vanilla's core/screenquad.vsh + lightmap.fsh (the lightmap texture, redrawn as the light changes).
struct LightmapInfo {
	float SkyFactor, BlockFactor, NightVisionFactor, DarknessScale, BossOverlayWorldDarkeningFactor, BrightnessFactor;
	float3 BlockLightTint, SkyLightColor, AmbientColor, NightVisionColor;
};
struct QuadVaryings { float4 position [[position]]; float2 texCoord; };

vertex QuadVaryings native_vs(uint vid [[vertex_id]]) {
	QuadVaryings out = {};
	float2 uv = float2(int2((int(vid) << 1) & 2, int(vid) & 2));
	out.position = native_flip(float4(uv * float2(2.0f, 2.0f) + float2(-1.0f, -1.0f), 0.0f, 1.0f));
	out.texCoord = uv;
	return out;
}

static inline float get_brightness(float level) { return level / (4.0f - 3.0f * level); }
static inline float3 notGamma(float3 color) {
	float maxComponent = max(max(color.x, color.y), color.z);
	float maxInverted = 1.0f - maxComponent;
	float maxScaled = 1.0f - maxInverted * maxInverted * maxInverted * maxInverted;
	return color * (maxScaled / maxComponent);
}
static inline float parabolicMixFactor(float level) { return (2.0f * level - 1.0f) * (2.0f * level - 1.0f); }

fragment float4 native_fs(QuadVaryings in [[stage_in]], constant LightmapInfo &info [[buffer(NATIVE_SLOT_LightmapInfo)]]) {
	float block_level = floor(in.texCoord.x * 16.0f) / 15.0f;
	float sky_level = floor(in.texCoord.y * 16.0f) / 15.0f;
	float block_brightness = get_brightness(block_level) * info.BlockFactor;
	float sky_brightness = get_brightness(sky_level) * info.SkyFactor;
	float3 nightVisionColor = info.NightVisionColor * info.NightVisionFactor;
	float3 color = max(info.AmbientColor, nightVisionColor);
	color += info.SkyLightColor * sky_brightness;
	float3 blockLightColor = mix(info.BlockLightTint, float3(1.0f), 0.9f * parabolicMixFactor(block_level));
	color += blockLightColor * block_brightness;
	color = mix(color, color * float3(0.7f, 0.6f, 0.6f), info.BossOverlayWorldDarkeningFactor);
	color = color - float3(info.DarknessScale);
	color = clamp(color, 0.0f, 1.0f);
	float3 ng = notGamma(color);
	color = mix(color, ng, info.BrightnessFactor);
	return float4(color, 1.0f);
}
