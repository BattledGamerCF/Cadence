// Cadence native: vanilla's core/clouds.vsh + clouds.fsh. The faces come from the
// native: oit (its order-independent transparency variants too: common.metal)
// CloudFaces texel buffer (three ints per face: cell x, cell z, direction and flags), four vertices per face.
struct CloudInfo { float4 CloudColor; float3 CloudOffset; float3 CellSize; };
struct CloudVaryings { float4 position [[position]]; float vertexDistance; float4 vertexColor; };

constant int FLAG_MASK_DIR = 7;
constant int FLAG_INSIDE_FACE = 1 << 4;
constant int FLAG_USE_TOP_COLOR = 1 << 5;
constant int FLAG_EXTRA_Z = 1 << 6;
constant int FLAG_EXTRA_X = 1 << 7;
constant int packedX = 0xF03CC3;
constant int packedY = 0x6666F0;
constant int packedZ = 0xC3F066;
constant float4 faceColors[6] = {
	float4(0.7f, 0.7f, 0.7f, 1.0f), float4(1.0f, 1.0f, 1.0f, 1.0f), float4(0.8f, 0.8f, 0.8f, 1.0f),
	float4(0.8f, 0.8f, 0.8f, 1.0f), float4(0.9f, 0.9f, 0.9f, 1.0f), float4(0.9f, 0.9f, 0.9f, 1.0f)
};

static inline float3 cloud_vertex(int index) {
	return float3(float((packedX >> index) & 1), float((packedY >> index) & 1), float((packedZ >> index) & 1));
}

vertex CloudVaryings native_vs(uint vid [[vertex_id]], constant Projection &projection [[buffer(NATIVE_SLOT_Projection)]],
	constant DynamicTransforms &transforms [[buffer(NATIVE_SLOT_DynamicTransforms)]],
	constant CloudInfo &info [[buffer(NATIVE_SLOT_CloudInfo)]],
	texture_buffer<int> CloudFaces [[texture(NATIVE_SLOT_CloudFaces)]]) {
	CloudVaryings out = {};
	int vertexIndex = int(vid);
	int quadVertex = vertexIndex % 4;
	int index = (vertexIndex / 4) * 3;
	int cellX = CloudFaces.read(uint(index)).r;
	int cellZ = CloudFaces.read(uint(index + 1)).r;
	int dirAndFlags = CloudFaces.read(uint(index + 2)).r;
	int direction = dirAndFlags & FLAG_MASK_DIR;
	bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
	bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;
	cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
	cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
	float3 faceVertex = cloud_vertex((direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex));
	float3 pos = (faceVertex * info.CellSize) + (float3(float(cellX), 0.0f, float(cellZ)) * info.CellSize) + info.CloudOffset;
	out.position = native_flip((projection.ProjMat * transforms.ModelViewMat) * float4(pos, 1.0f));
	out.vertexDistance = fog_spherical_distance(pos);
	out.vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * info.CloudColor;
	return out;
}

fragment NATIVE_FS_OUT native_fs(CloudVaryings in [[stage_in]], constant Fog &fog [[buffer(NATIVE_SLOT_Fog)]] NATIVE_OIT_FS_PARAMS) {
	float4 color = in.vertexColor;
#ifndef OIT_DEPTH_BOUNDS
	color.a *= 1.0f - linear_fog_value(in.vertexDistance, 0.0f, fog.FogCloudsEnd);
#endif
#ifdef OIT_ALPHA_ONLY
	return NATIVE_OIT_ALPHA_ONLY(in.position, color.a);
#else
	NATIVE_OIT_ACCUMULATE_STEP(color, in.position);
	return color;
#endif
}
