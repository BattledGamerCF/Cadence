// Cadence native: vanilla's core/rendertype_lines.vsh + .fsh: each line segment widened
// native: oit (its order-independent transparency variants too: common.metal)
// on screen by LineWidth pixels.
struct LinesIn { float3 Position [[attribute(0)]]; float4 Color [[attribute(1)]]; float3 Normal [[attribute(2)]]; float LineWidth [[attribute(3)]]; };
struct LinesVaryings { float4 position [[position]]; float sphericalVertexDistance; float cylindricalVertexDistance; float4 vertexColor; };

constant float VIEW_SHRINK = 1.0f - (1.0f / 256.0f);

vertex LinesVaryings native_vs(LinesIn in [[stage_in]], uint vid [[vertex_id]],
	constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Globals &globals [[buffer(NATIVE_SLOT_Globals)]]) {
	LinesVaryings out = {};
	float4x4 viewScale = float4x4(float4(VIEW_SHRINK, 0, 0, 0), float4(0, VIEW_SHRINK, 0, 0), float4(0, 0, VIEW_SHRINK, 0), float4(0, 0, 0, 1));
	float4 linePosStart = projection.ProjMat * viewScale * transforms.ModelViewMat * float4(in.Position, 1.0f);
	float4 linePosEnd = projection.ProjMat * viewScale * transforms.ModelViewMat * float4(in.Position + in.Normal, 1.0f);
	float3 ndc1 = linePosStart.xyz / linePosStart.w;
	float3 ndc2 = linePosEnd.xyz / linePosEnd.w;
	float2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * globals.ScreenSize);
	float2 lineOffset = float2(-lineScreenDirection.y, lineScreenDirection.x) * in.LineWidth / globals.ScreenSize;
	if (lineOffset.x < 0.0f) lineOffset *= -1.0f;
	float4 p = (int(vid) % 2 == 0) ? float4((ndc1 + float3(lineOffset, 0.0f)) * linePosStart.w, linePosStart.w)
		: float4((ndc1 - float3(lineOffset, 0.0f)) * linePosStart.w, linePosStart.w);
	out.position = native_flip(p);
	out.sphericalVertexDistance = fog_spherical_distance(in.Position);
	out.cylindricalVertexDistance = fog_cylindrical_distance(in.Position);
	out.vertexColor = in.Color;
	return out;
}

fragment NATIVE_FS_OUT native_fs(LinesVaryings in [[stage_in]], constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]] NATIVE_OIT_FS_PARAMS) {
	float4 color = in.vertexColor * transforms.ColorModulator;
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return apply_fog_final(color, in.sphericalVertexDistance, in.cylindricalVertexDistance, fog);
#endif
}
