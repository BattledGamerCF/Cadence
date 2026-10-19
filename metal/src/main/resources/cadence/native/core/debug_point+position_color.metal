// Cadence native: vanilla's core/debug_point.vsh + position_color.fsh (debug points, LineWidth pixels wide).
// native: oit (its order-independent transparency variants too: common.metal)
struct DbgIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float LineWidth [[attribute(2)]]; };
struct DbgVaryings { float4 position [[position]]; float4 vertexColor; float pointSize [[point_size]]; };

vertex DbgVaryings native_vs(DbgIn in [[stage_in]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]) {
	DbgVaryings out = {};
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(in.Position, 1.0f));
	out.vertexColor = in.Color;
	out.pointSize = in.LineWidth;
	return out;
}

fragment NATIVE_FS_OUT native_fs(DbgVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]]
	NATIVE_OIT_FS_PARAMS) {
	float4 color = in.vertexColor;
	if (color.a == 0.0f) discard_fragment();
	color *= transforms.ColorModulator;
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return color;
#endif
}
