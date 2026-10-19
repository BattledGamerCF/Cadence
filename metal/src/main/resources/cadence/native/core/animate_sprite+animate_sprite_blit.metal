// Cadence native: vanilla's core/animate_sprite.vsh + animate_sprite_blit.fsh (a sprite frame into the atlas).
struct SpriteAnimationInfo { float4x4 ProjectionMatrix; float4x4 SpriteMatrix; float UPadding; float VPadding; int MipMapLevel; };
struct AnimVaryings { float4 position [[position]]; float fAnimationProgress; float2 texCoord0; };
constant float2 positions[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

vertex AnimVaryings native_vs(uint vid [[vertex_id]], constant SpriteAnimationInfo &info [[buffer(NATIVE_SLOT_SpriteAnimationInfo)]]) {
	AnimVaryings out = {};
	int index = int(vid) & 7;
	float frameProgress = float(int(vid) >> 3) / 1000.0f;
	float2 padding = float2(info.UPadding, info.VPadding);
	out.position = native_flip(info.ProjectionMatrix * info.SpriteMatrix * float4(positions[index], 0.0f, 1.0f));
	float2 uv = positions[index];
	float2 direction = uv * 2.0f - 1.0f;
	out.texCoord0 = uv + (padding * direction);
	out.fAnimationProgress = frameProgress;
	return out;
}

fragment float4 native_fs(AnimVaryings in [[stage_in]], constant SpriteAnimationInfo &info [[buffer(NATIVE_SLOT_SpriteAnimationInfo)]],
	texture2d<float> Sprite [[texture(NATIVE_SLOT_Sprite)]], sampler SpriteSmplr [[sampler(NATIVE_SLOT_Sprite)]]) {
	return Sprite.sample(SpriteSmplr, in.texCoord0, level(float(info.MipMapLevel)));
}
