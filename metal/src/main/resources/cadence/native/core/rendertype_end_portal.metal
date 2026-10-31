// Cadence native: vanilla's core/rendertype_end_portal.vsh + .fsh. GLSL's mat constructors fill columns, as Metal's do;
// `texProj0 * M` multiplies a row vector, as Metal's `v * M`; textureProj divides by w.
struct PortalIn { float3 Position [[attribute(0)]]; };
struct PortalVaryings { float4 position [[position]]; float4 texProj0; float sphericalVertexDistance; float cylindricalVertexDistance; };

vertex PortalVaryings native_vs(PortalIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	PortalVaryings out = {};
	float4 p = (projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f);
	// projection_from_position (projection.glsl), from the position before the backend's flip, as the translation does
	float4 q = p * 0.5f;
	q.xy = float2(q.x + q.w, q.y + q.w);
	q.zw = p.zw;
	out.texProj0 = q;
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.position = native_flip(p);
	return out;
}

constant float3 COLORS[16] = {
	float3(0.022087f, 0.098399f, 0.110818f), float3(0.011892f, 0.095924f, 0.089485f), float3(0.027636f, 0.101689f, 0.100326f),
	float3(0.046564f, 0.109883f, 0.114838f), float3(0.064901f, 0.117696f, 0.097189f), float3(0.063761f, 0.086895f, 0.123646f),
	float3(0.084817f, 0.111994f, 0.166380f), float3(0.097489f, 0.154120f, 0.091064f), float3(0.106152f, 0.131144f, 0.195191f),
	float3(0.097721f, 0.110188f, 0.187229f), float3(0.133516f, 0.138278f, 0.148582f), float3(0.070006f, 0.243332f, 0.235792f),
	float3(0.196766f, 0.142899f, 0.214696f), float3(0.047281f, 0.315338f, 0.321970f), float3(0.204675f, 0.390010f, 0.302066f),
	float3(0.080955f, 0.314821f, 0.661491f)
};

static inline float2x2 mat2_rotate_z(float r) { return float2x2(float2(cos(r), -sin(r)), float2(sin(r), cos(r))); }

static inline float4x4 end_portal_layer(float layer, float gameTime) {
	float4x4 translate = float4x4(float4(1.0f, 0.0f, 0.0f, 17.0f / layer), float4(0.0f, 1.0f, 0.0f, (2.0f + layer / 1.5f) * (gameTime * 1.5f)),
		float4(0.0f, 0.0f, 1.0f, 0.0f), float4(0.0f, 0.0f, 0.0f, 1.0f));
	float4x4 scaleTranslate = float4x4(float4(0.5f, 0.0f, 0.0f, 0.25f), float4(0.0f, 0.5f, 0.0f, 0.25f), float4(0.0f, 0.0f, 1.0f, 0.0f),
		float4(0.0f, 0.0f, 0.0f, 1.0f));
	float2x2 rotate = mat2_rotate_z((layer * layer * 4321.0f + layer * 9.0f) * 2.0f * (M_PI_F / 180.0f));
	float s = (4.5f - layer / 4.0f) * 2.0f;
	float2x2 sr = float2x2(float2(s, 0.0f), float2(0.0f, s)) * rotate;
	// mat4(mat2): the mat2 in the upper left, the rest identity
	float4x4 m = float4x4(float4(sr[0], 0.0f, 0.0f), float4(sr[1], 0.0f, 0.0f), float4(0.0f, 0.0f, 1.0f, 0.0f), float4(0.0f, 0.0f, 0.0f, 1.0f));
	return m * translate * scaleTranslate;
}

static inline float4 texture_proj(texture2d<float> t, sampler s, float4 p) { return t.sample(s, p.xy / p.w); }

fragment float4 native_fs(PortalVaryings in [[stage_in]], constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]],
	constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]],
	texture2d<float> Sampler0 [[texture(NATIVE_SLOT_Sampler0)]], sampler Sampler0Smplr [[sampler(NATIVE_SLOT_Sampler0)]],
	texture2d<float> Sampler1 [[texture(NATIVE_SLOT_Sampler1)]], sampler Sampler1Smplr [[sampler(NATIVE_SLOT_Sampler1)]]) {
	float3 color = texture_proj(Sampler0, Sampler0Smplr, in.texProj0).rgb * COLORS[0];
	for (int i = 0; i < PORTAL_LAYERS; i++)
		color += texture_proj(Sampler1, Sampler1Smplr, in.texProj0 * end_portal_layer(float(i + 1), globals.GameTime)).rgb * COLORS[i];
	return apply_fog(float4(color, 1.0f), in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
}
