// Cadence ray tracing (dev.cadence.metal.rt.RayTracing): the world near the camera as voxels (RtWorld: a ushort per block in a
// toroidal window W x H x W, and a 64-bit occupancy mask per 4 x 4 x 4 brick), traced per pixel of the game's frame.
//
// Shadows: rt_shadow reads the frame's depth, rebuilds each pixel's point and surface normal, and traces one ray from it towards
// the sun (or the moon) through the voxels (rt_trace: brick by brick, empty bricks crossed in one step). The result (visible or
// not, and the light levels of the air in front of the surface) goes to an RG8 texture; rt_apply then darkens the frame there
// by what the game's own lightmap gives for less sky light at that spot: a torch-lit wall in shadow stays torch-lit.
// rt_shadow_hw does the same trace through a Metal acceleration structure (RtAccel: a bounding box per non-empty brick, the
// brick's own mask tested by an intersection function): hardware ray tracing on M3 and newer (Apple GPU family 9), Metal's
// software traversal on earlier chips.
//
// Rows are stored bottom first, as the game's targets are (uv.y = 0 is the bottom of the screen); the game's projection is
// reversed-Z in Metal's 0..1 (the far plane at 0).
#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace raytracing;

struct RtFrame {
	float4x4 invViewProj;  // clip -> camera-relative world
	float4x4 viewProj;     // camera-relative world -> clip (the previous frame's, for the temporal filter)
	float4 origin;         // xyz: the window's cell (0, 0, 0) relative to the camera (blocks); w: the window's half width
	float4 sun;            // xyz: towards the light (the sun, or the moon at night); w: its strength (0: no direct light)
	uint4 dims;            // x: W (a power of two), y: H, z: the window's origin x & (W - 1), w: its origin z & (W - 1)
	float4 params;         // x: longest shadow ray (blocks), y: frame number, z: sky light levels a full shadow removes, w: soft shadow radius
	uint4 screen;          // xy: the frame's size; z: flags (bit 0: debug view of the visibility)
	float4 sky;            // xyz: the sky's colour (linear), w: sky brightness 0..1 (path tracing)
	float4 extra;          // x: one above the highest solid cell in the window (window y): a ray climbing past it is in the open;
	                       // y: the trace's scale (1: a ray per pixel, 2: per 2 x 2 pixels, rt_apply_fs upsamples);
	                       // zw: the window's origin x, z minus its wrap (world x = window x + dims.z + extra.z)
	float4 extra2;         // xyz: the sun's colour (linear, its strength folded in later by sun.w); w: the lighting cache's update stride
	float4 cache;          // x: lighting cache entries (a power of two), y: bounce ray length, z: least blend per frame, w: exposure
};

// ---- the voxels ----

// A cell of the window (logical coordinates: 0..W-1, 0..H-1, 0..W-1) at its place in the toroidal buffers.
static inline uint voxelIndex(int3 c, constant RtFrame &f) {
	uint m = f.dims.x - 1;
	uint px = (uint(c.x) + f.dims.z) & m, pz = (uint(c.z) + f.dims.w) & m;
	return (uint(c.y) * f.dims.x + pz) * f.dims.x + px;
}

static inline uint brickIndex(int3 c, constant RtFrame &f) {
	uint m = f.dims.x - 1, bw = f.dims.x >> 2;
	uint px = (uint(c.x) + f.dims.z) & m, pz = (uint(c.z) + f.dims.w) & m;
	return ((uint(c.y) >> 2) * bw + (pz >> 2)) * bw + (px >> 2);
}

static inline uint sectionIndex(int3 c, constant RtFrame &f) {
	uint m = f.dims.x - 1, sw = f.dims.x >> 4;
	uint px = (uint(c.x) + f.dims.z) & m, pz = (uint(c.z) + f.dims.w) & m;
	return ((uint(c.y) >> 4) * sw + (pz >> 4)) * sw + (px >> 4);
}

static inline bool inWindow(int3 c, constant RtFrame &f) {
	return c.x >= 0 && c.z >= 0 && c.y >= 0 && c.x < int(f.dims.x) && c.z < int(f.dims.x) && c.y < int(f.dims.y);
}

static inline bool brickBit(uint2 mask, int3 c) {
	uint bit = uint(((c.y & 3) << 4) | ((c.z & 3) << 2) | (c.x & 3));
	return ((bit < 32 ? mask.x >> bit : mask.y >> (bit - 32)) & 1u) != 0;
}

// Where a ray leaves the cube of size `size` (a power of two) holding cell c: its t.
static inline float cubeExit(int3 c, int size, float3 o, float3 d, float3 inv) {
	float3 b0 = float3(c & ~(size - 1)), b1 = b0 + float(size);
	float3 exitT = (select(b0, b1, d > 0.0f) - o) * inv;
	return min(min(select(1e30f, exitT.x, abs(d.x) >= 1e-9f), select(1e30f, exitT.y, abs(d.y) >= 1e-9f)), select(1e30f, exitT.z, abs(d.z) >= 1e-9f));
}

// The first solid cell along o + d t (window coordinates), t in [tMin, tMax]; skip: a cell never counted as a hit (the surface's
// own, for partial blocks). Empty 16^3 sections and 4^3 bricks are crossed in one step each; a ray climbing above the window's
// highest block is in the open. Returns t, or -1.
static float rt_trace(float3 o, float3 d, float tMin, float tMax, int3 skip, device const uint2 *bricks, device const uchar *sections,
	constant RtFrame &f) {
	float3 inv = 1.0f / select(d, float3(1e-9f), abs(d) < 1e-9f);
	float3 lo = (float3(0.0f) - o) * inv, hi = (float3(float(f.dims.x), float(f.dims.y), float(f.dims.x)) - o) * inv;
	float t0 = max(max(min(lo.x, hi.x), min(lo.y, hi.y)), min(lo.z, hi.z));
	float t1 = min(min(max(lo.x, hi.x), max(lo.y, hi.y)), max(lo.z, hi.z));
	float t = max(t0, tMin);
	float tEnd = min(t1, tMax);
	// (rising rays: nothing past the highest block)
	if (d.y > 1e-6f) tEnd = min(tEnd, (f.extra.x - o.y) / d.y);
	if (t >= tEnd) return -1.0f;
	int3 step = int3(sign(d));
	float3 delta = abs(inv);
	int3 c = int3(floor(o + d * (t + 1e-4f)));
	float3 next = (float3(c) + max(float3(step), 0.0f) - o) * inv;
	for (int i = 0; i < 256; i++) {
		if (!inWindow(c, f)) return -1.0f;
		int size = 0;
		uint2 mask = uint2(0);
		if (sections[sectionIndex(c, f)] == 0) size = 16;
		else {
			mask = bricks[brickIndex(c, f)];
			if ((mask.x | mask.y) == 0) size = 4;
		}
		if (size != 0) {
			// empty: on to where the ray leaves it
			t = cubeExit(c, size, o, d, inv);
			if (t >= tEnd) return -1.0f;
			c = int3(floor(o + d * (t + 1e-4f)));
			next = (float3(c) + max(float3(step), 0.0f) - o) * inv;
			continue;
		}
		// a brick with something in it: its cells walked against the mask (no more loads until the ray leaves it)
		int3 b0 = c & ~3;
		for (int k = 0; k < 10; k++) {
			if (brickBit(mask, c) && any(c != skip)) return t;
			if (next.x < next.y && next.x < next.z) {
				t = next.x; c.x += step.x; next.x += delta.x;
			} else if (next.y < next.z) {
				t = next.y; c.y += step.y; next.y += delta.y;
			} else {
				t = next.z; c.z += step.z; next.z += delta.z;
			}
			if (t >= tEnd) return -1.0f;
			if (any((c & ~3) != b0)) break;
		}
	}
	return -1.0f;
}

// ---- the frame's points ----

static inline float3 pointAt(uint2 gid, float d, constant RtFrame &f) {
	float2 uv = (float2(gid) + 0.5f) / float2(f.screen.xy);
	float4 p = f.invViewProj * float4(uv * 2.0f - 1.0f, d, 1.0f);
	return p.xyz / p.w;
}

// The surface normal from the depth of the pixel and its neighbours (the side whose depth is nearer, so edges don't bend it),
// facing the camera.
static float3 normalAt(depth2d<float, access::read> depth, uint2 gid, float3 P, constant RtFrame &f) {
	uint2 sz = f.screen.xy;
	uint2 gx0 = uint2(gid.x > 0 ? gid.x - 1 : gid.x, gid.y), gx1 = uint2(min(gid.x + 1, sz.x - 1), gid.y);
	uint2 gy0 = uint2(gid.x, gid.y > 0 ? gid.y - 1 : gid.y), gy1 = uint2(gid.x, min(gid.y + 1, sz.y - 1));
	float3 ax0 = pointAt(gx0, depth.read(gx0), f), ax1 = pointAt(gx1, depth.read(gx1), f);
	float3 ay0 = pointAt(gy0, depth.read(gy0), f), ay1 = pointAt(gy1, depth.read(gy1), f);
	float3 dx = length(ax1 - P) < length(P - ax0) ? ax1 - P : P - ax0;
	float3 dy = length(ay1 - P) < length(P - ay0) ? ay1 - P : P - ay0;
	float3 n = cross(dx, dy);
	float l = length(n);
	n = l > 1e-8f ? n / l : float3(0.0f, 1.0f, 0.0f);
	// (blocks: a normal within a few degrees of an axis is that axis, so flat faces get exact normals despite depth precision)
	float3 a = abs(n);
	if (a.x > 0.97f) n = float3(sign(n.x), 0.0f, 0.0f);
	else if (a.y > 0.97f) n = float3(0.0f, sign(n.y), 0.0f);
	else if (a.z > 0.97f) n = float3(0.0f, 0.0f, sign(n.z));
	return dot(n, P) > 0.0f ? -n : n;
}

// A surface normal's face (0..5: -x +x -y +y -z +z)
static inline uint faceIndex(float3 n) {
	float3 a = abs(n);
	if (a.x >= a.y && a.x >= a.z) return n.x < 0.0f ? 0u : 1u;
	if (a.y >= a.z) return n.y < 0.0f ? 2u : 3u;
	return n.z < 0.0f ? 4u : 5u;
}

// The light levels (sky << 4 | block) of the air in front of a surface point, 0xF0 outside the window.
static uint lightAt(float3 g, float3 n, device const ushort *vox, constant RtFrame &f) {
	for (int k = 1; k <= 2; k++) {
		int3 c = int3(floor(g + n * (0.5f * float(k))));
		if (!inWindow(c, f)) return 0xF0u;
		ushort v = vox[voxelIndex(c, f)];
		if ((v & 0x8000u) == 0) return v & 0xFFu;
	}
	return 0u;
}

// 0 at the window's middle, 1 at its edge: shadows fade out before the voxels end.
static inline float edgeFade(float3 P, constant RtFrame &f) {
	float r = max(abs(P.x - f.origin.x - f.origin.w), abs(P.z - f.origin.z - f.origin.w));
	return smoothstep(f.origin.w - 12.0f, f.origin.w - 2.0f, r);
}

// A blue-noise-like value per pixel and frame (interleaved gradient noise), for soft shadows.
static inline float ign(uint2 p, uint frame) {
	float2 q = float2(p) + 5.588238f * float(frame & 63u);
	return fract(52.9829189f * fract(dot(q, float2(0.06711056f, 0.00583715f))));
}

static float3 jitterTowards(float3 l, float radius, uint2 gid, uint frame) {
	if (radius <= 0.0f) return l;
	float3 t = normalize(abs(l.y) < 0.9f ? cross(l, float3(0, 1, 0)) : cross(l, float3(1, 0, 0)));
	float3 b = cross(l, t);
	float a = ign(gid, frame) * 6.2831853f, r = sqrt(ign(gid.yx + 17u, frame + 7u)) * radius;
	return normalize(l + (t * cos(a) + b * sin(a)) * r);
}

kernel void rt_shadow(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	device const ushort *vox [[buffer(0)]], device const uint2 *bricks [[buffer(1)]], constant RtFrame &f [[buffer(2)]],
	device const uchar *sections [[buffer(5)]], uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
	uint2 px = min(gid * uint(f.extra.y), f.screen.xy - 1);  // (the frame's pixel this ray stands for)
	float d = depth.read(px);
	if (d <= 1e-7f) {
		out.write(float4(1.0f, 240.0f, 60000.0f, 3.0f), gid);  // the sky
		return;
	}
	float3 P = pointAt(px, d, f);
	float3 N = normalAt(depth, px, P, f);
	float3 g = P - f.origin.xyz;  // window coordinates
	uint light = lightAt(g, N, vox, f);
	float vis = 1.0f, fade = edgeFade(P, f);
	if (f.sun.w > 0.0f && fade < 0.999f) {  // (past the window's edge the shadows have faded out: no ray)
		float3 L = jitterTowards(f.sun.xyz, f.params.w, px, uint(f.params.y));
		if ((light >> 4) == 0u) vis = 1.0f;  // (no sky light here: nothing for a sun shadow to take away, no ray)
		else if (dot(N, L) <= 0.0f) vis = 0.0f;
		else {
			int3 own = int3(floor(g - N * 0.01f));
			vis = rt_trace(g + N * 0.02f, L, 0.0f, f.params.x, own, bricks, sections, f) >= 0.0f ? 0.0f : 1.0f;
		}
		vis = mix(vis, 1.0f, fade);
	}
	out.write(float4(vis, float(light), length(P), float(faceIndex(N))), gid);
}

// ---- hardware ray tracing (RtAccel): the same trace through an acceleration structure of the non-empty bricks ----

struct BrickHit {
	bool accept [[accept_intersection]];
	float distance [[distance]];
};

// A brick's box was entered: march its 4 x 4 x 4 cells (the instance's section, brick number primitive_id) against its mask.
// The instance's user id is its section's place in the window (logical, sx | sy << 8 | sz << 16); the boxes are in the
// section's own coordinates (0..16).
[[intersection(bounding_box, instancing)]]
BrickHit rt_brick(float3 o [[origin]], float3 d [[direction]], float tMin [[min_distance]], float tMax [[max_distance]],
	uint prim [[primitive_id]], uint user [[user_instance_id]], device const uint2 *bricks [[buffer(1)]],
	constant RtFrame &f [[buffer(2)]], ray_data int3 &skip [[payload]]) {
	BrickHit h = {false, 0.0f};
	int3 sec = int3(user & 0xFF, (user >> 8) & 0xFF, (user >> 16) & 0xFF) * 16;
	int3 b = int3(prim & 3, (prim >> 2) & 3, (prim >> 4) & 3) * 4;  // brick within the section
	int3 base = sec + b;
	uint2 mask = bricks[brickIndex(base, f)];
	// o, d are in the instance's space (section-local); cells are tested in window coordinates
	float3 inv = 1.0f / select(d, float3(1e-9f), abs(d) < 1e-9f);
	float3 lo = (float3(b) - o) * inv, hi = (float3(b) + 4.0f - o) * inv;
	float t = max(max(max(min(lo.x, hi.x), min(lo.y, hi.y)), min(lo.z, hi.z)), tMin);
	float tEnd = min(min(min(max(lo.x, hi.x), max(lo.y, hi.y)), max(lo.z, hi.z)), tMax);
	if (t >= tEnd) return h;
	int3 step = int3(sign(d));
	float3 delta = abs(inv);
	int3 c = clamp(int3(floor(o + d * (t + 1e-4f))), b, b + 3);
	float3 next = (float3(c) + max(float3(step), 0.0f) - o) * inv;
	for (int i = 0; i < 12; i++) {
		int3 w = sec + c;
		if (brickBit(mask, w) && any(w != skip)) {
			h.accept = true;
			h.distance = max(t, tMin);
			return h;
		}
		if (next.x < next.y && next.x < next.z) { t = next.x; c.x += step.x; next.x += delta.x; }
		else if (next.y < next.z) { t = next.y; c.y += step.y; next.y += delta.y; }
		else { t = next.z; c.z += step.z; next.z += delta.z; }
		if (t >= tEnd || any(c < b) || any(c > b + 3)) return h;
	}
	return h;
}

kernel void rt_shadow_hw(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],
	device const ushort *vox [[buffer(0)]], device const uint2 *bricks [[buffer(1)]], constant RtFrame &f [[buffer(2)]],
	instance_acceleration_structure accel [[buffer(3)]], intersection_function_table<instancing> table [[buffer(4)]],
	uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;
	uint2 px = min(gid * uint(f.extra.y), f.screen.xy - 1);
	float d = depth.read(px);
	if (d <= 1e-7f) {
		out.write(float4(1.0f, 240.0f, 60000.0f, 3.0f), gid);
		return;
	}
	float3 P = pointAt(px, d, f);
	float3 N = normalAt(depth, px, P, f);
	float3 g = P - f.origin.xyz;
	uint light = lightAt(g, N, vox, f);
	float vis = 1.0f, fade = edgeFade(P, f);
	if (f.sun.w > 0.0f && fade < 0.999f) {  // (past the window's edge the shadows have faded out: no ray)
		float3 L = jitterTowards(f.sun.xyz, f.params.w, px, uint(f.params.y));
		if ((light >> 4) == 0u) vis = 1.0f;  // (no sky light here: nothing for a sun shadow to take away, no ray)
		else if (dot(N, L) <= 0.0f) vis = 0.0f;
		else {
			ray r;
			r.origin = g + N * 0.02f;
			r.direction = L;
			r.min_distance = 0.0f;
			r.max_distance = f.params.x;
			intersector<instancing> it;
			it.accept_any_intersection(true);
			int3 skip = int3(floor(g - N * 0.01f));
			auto hit = it.intersect(r, accel, 0xFF, table, skip);
			vis = hit.type == intersection_type::none ? 1.0f : 0.0f;
		}
		vis = mix(vis, 1.0f, fade);
	}
	out.write(float4(vis, float(light), length(P), float(faceIndex(N))), gid);
}

// ---- applying the shadows to the frame: a full-screen pass, the frame multiplied by the factor ----

struct VOut {
	float4 pos [[position]];
};

vertex VOut rt_apply_vs(uint id [[vertex_id]]) {
	float2 p = float2((id << 1) & 2, id & 2);
	VOut o;
	o.pos = float4(p * 2.0f - 1.0f, 0.0f, 1.0f);
	return o;
}

// The trace at this pixel: itself (scale 1), or the four nearest traced samples weighted by distance from it and by how close
// their depth is to this pixel's (scale 2: a shadow edge stays on the edge of the geometry). Visibility, light code, distance, face.
static float4 traced(texture2d<float, access::read> rt, depth2d<float, access::read> depth, uint2 p, constant RtFrame &f) {
	if (f.extra.y < 1.5f) return rt.read(p);
	float d = depth.read(p);
	if (d <= 1e-7f) return float4(1.0f, 240.0f, 60000.0f, 3.0f);
	float dist = length(pointAt(p, d, f));
	float2 q = (float2(p) + 0.5f) / f.extra.y - 0.5f;
	int2 q0 = int2(floor(q));
	float2 fr = q - float2(q0);
	int2 lim = int2(rt.get_width(), rt.get_height()) - 1;
	float3 sum = float3(0.0f);
	float wsum = 0.0f, bestW = -1.0f;
	float4 best = float4(1.0f, 240.0f, dist, 3.0f);
	for (int k = 0; k < 4; k++) {
		int2 o = int2(k & 1, k >> 1);
		float4 v = rt.read(uint2(clamp(q0 + o, int2(0), lim)));
		float wb = (o.x ? fr.x : 1.0f - fr.x) * (o.y ? fr.y : 1.0f - fr.y);
		float wd = exp(-abs(v.b - dist) / (0.03f * dist + 0.15f));
		float w = wb * wd + 1e-5f;
		sum += v.rgb * w;
		wsum += w;
		if (wd > bestW) {
			bestW = wd;
			best = v;
		}
	}
	// (the light code and the face aren't blended: the nearest-depth sample's)
	return float4((sum / wsum).r, best.g, dist, best.a);
}

// The factor at a pixel: 1 where the light reaches it; in shadow, the game's lightmap at fewer sky light levels over the
// lightmap at the levels it has (per channel), so the darkening is the one the game itself shows for less sky light.
fragment float4 rt_apply_fs(VOut in [[stage_in]], texture2d<float, access::read> rt [[texture(0)]],
	texture2d<float, access::read> lightmap [[texture(1)]], depth2d<float, access::read> depth [[texture(2)]], constant RtFrame &f [[buffer(2)]]) {
	uint2 p = uint2(in.pos.xy);
	float4 s = traced(rt, depth, p, f);
	if (s.r >= 0.999f || f.sun.w <= 0.0f) return float4(1.0f);
	uint code = uint(s.g + 0.5f), sky = (code >> 4) & 15u, block = code & 15u;
	float drop = f.params.z * f.sun.w;
	float lower = max(0.0f, float(sky) - drop);
	uint s0 = uint(floor(lower)), s1 = min(s0 + 1u, 15u);
	float3 lit = lightmap.read(uint2(block, sky)).rgb;
	float3 dark = mix(lightmap.read(uint2(block, s0)).rgb, lightmap.read(uint2(block, s1)).rgb, fract(lower));
	float3 ratio = clamp(dark / max(lit, float3(1e-3f)), 0.0f, 1.0f);
	return float4(mix(ratio, float3(1.0f), s.r), 1.0f);
}

// Debug view (-Dcadence.rt.debug): the visibility itself, white lit, black shadowed (blend off: replaces the frame).
fragment float4 rt_debug_fs(VOut in [[stage_in]], texture2d<float, access::read> rt [[texture(0)]],
	depth2d<float, access::read> depth [[texture(2)]], constant RtFrame &f [[buffer(2)]]) {
	float v = traced(rt, depth, uint2(in.pos.xy), f).r;
	return float4(v, v, v, 1.0f);
}

// ---- Ray Traced Lighting (experimental): the sky's and the sun's light from the voxels, through a lighting cache ----
//
// The lighting cache: a hash table in world space, an entry per (air cell, face direction) that a pixel sees: the light arriving
// at the block faces facing that way in that cell (the sky, the sun and what other blocks bounce), averaged over time. Each frame
// rt_cache_update traces one ray from a quarter of the traced pixels' surfaces into the hemisphere above them (cosine-weighted):
// out to the sky it brings the sky's light; onto a block, that block's colour times the sun on it (one shadow ray) and the
// cache's own light there (so light bounces on, frame after frame). rt_cache_resolve folds each entry's new samples into its
// average. rt_light_fs then relights the frame: the game's own sky lighting replaced by the sun (traced shadow) and the cached
// sky and bounce light, the block light (torches) kept as the game gives it.

struct CacheEntry {
	atomic_uint key;    // 0: free; else the cell and face (cacheKey)
	atomic_uint sum[3]; // this frame's samples, RGB, fixed point (x 1024)
	atomic_uint count;  // this frame's sample count
	uint age;           // updates folded in so far (the average's weight)
	half4 irr;          // the light arriving (linear RGB), the running average
};

#define CACHE_PROBES 8

// A world cell (window y, world x and z) and face (0..5: -x +x -y +y -z +z) as a key; never 0.
static inline uint cacheKey(int3 world, uint face) {
	return (uint(world.x) & 0x3FFu) | (uint(world.z) & 0x3FFu) << 10 | (uint(world.y) & 0x1FFu) << 20 | (face + 1u) << 29;
}

static inline uint hashKey(uint k) {
	k ^= k >> 16; k *= 0x7feb352du; k ^= k >> 15; k *= 0x846ca68bu; k ^= k >> 16;
	return k;
}

// The entry for a key: found, or claimed (insert) when free; -1 if the probes are all taken by others.
static int cacheFind(device CacheEntry *cache, uint mask, uint key, bool insert) {
	uint h = hashKey(key);
	for (uint i = 0; i < CACHE_PROBES; i++) {
		uint slot = (h + i) & mask;
		uint k = atomic_load_explicit(&cache[slot].key, memory_order_relaxed);
		if (k == key) return int(slot);
		if (k == 0u) {
			if (!insert) return -1;
			uint expected = 0u;
			if (atomic_compare_exchange_weak_explicit(&cache[slot].key, &expected, key, memory_order_relaxed, memory_order_relaxed)) return int(slot);
			if (expected == key) return int(slot);
		}
	}
	return -1;
}

// The entry for a key, claimed if it has none; when all its probes are taken, one whose cell is outside the window now is taken
// over (the player moved on: without this the table fills while exploring and new surfaces get no bounce light, BH7 suspicion
// 3). Its average restarts (age 0: the first samples count fully).
static int cacheClaim(device CacheEntry *cache, uint mask, uint key, constant RtFrame &f) {
	int s = cacheFind(cache, mask, key, true);
	if (s >= 0) return s;
	uint ox = (f.dims.z + uint(int(f.extra.z))) & 0x3FFu, oz = (f.dims.w + uint(int(f.extra.w))) & 0x3FFu, w = f.dims.x;
	uint h = hashKey(key);
	for (uint i = 0; i < CACHE_PROBES; i++) {
		uint slot = (h + i) & mask;
		uint k = atomic_load_explicit(&cache[slot].key, memory_order_relaxed);
		if (((k - ox) & 0x3FFu) < w && (((k >> 10) - oz) & 0x3FFu) < w) continue;  // (inside the window: in use)
		uint expected = k;
		if (atomic_compare_exchange_weak_explicit(&cache[slot].key, &expected, key, memory_order_relaxed, memory_order_relaxed)) {
			cache[slot].age = 0u;
			return int(slot);
		}
		if (expected == key) return int(slot);
	}
	return -1;
}

static inline uint faceOf(float3 n) {
	return faceIndex(n);
}

static inline float3 faceNormal(uint face) {
	float3 n = float3(0.0f);
	n[face >> 1] = (face & 1u) ? 1.0f : -1.0f;
	return n;
}

// The window cell (logical) to world x, z (the key's coordinates: the cache stays valid as the window moves).
static inline int3 worldCell(int3 c, constant RtFrame &f) {
	return int3(c.x + int(f.dims.z) + int(f.extra.z), c.y, c.z + int(f.dims.w) + int(f.extra.w));
}

static inline float3 srgbToLinear(float3 c) {
	return select(pow((c + 0.055f) / 1.055f, 2.4f), c / 12.92f, c <= 0.04045f);
}

// The first solid cell along the ray, and the face it was entered through (the hit's normal axis, signed). -1 if none.
static float rt_hit(float3 o, float3 d, float tMax, device const uint2 *bricks, device const uchar *sections, constant RtFrame &f,
	thread int3 &hit, thread float3 &normal) {
	float3 inv = 1.0f / select(d, float3(1e-9f), abs(d) < 1e-9f);
	float3 lo = (float3(0.0f) - o) * inv, hi = (float3(float(f.dims.x), float(f.dims.y), float(f.dims.x)) - o) * inv;
	float t0 = max(max(min(lo.x, hi.x), min(lo.y, hi.y)), min(lo.z, hi.z));
	float t1 = min(min(max(lo.x, hi.x), max(lo.y, hi.y)), max(lo.z, hi.z));
	float t = max(t0, 0.0f);
	float tEnd = min(t1, tMax);
	if (d.y > 1e-6f) tEnd = min(tEnd, (f.extra.x - o.y) / d.y);
	if (t >= tEnd) return -1.0f;
	int3 step = int3(sign(d));
	float3 delta = abs(inv);
	int3 c = int3(floor(o + d * (t + 1e-4f)));
	float3 next = (float3(c) + max(float3(step), 0.0f) - o) * inv;
	int axis = 1;
	for (int i = 0; i < 192; i++) {
		if (!inWindow(c, f)) return -1.0f;
		int size = 0;
		uint2 mask = uint2(0);
		if (sections[sectionIndex(c, f)] == 0) size = 16;
		else {
			mask = bricks[brickIndex(c, f)];
			if ((mask.x | mask.y) == 0) size = 4;
		}
		if (size != 0) {
			float3 b0 = float3(c & ~(size - 1)), b1 = b0 + float(size);
			float3 exitT = (select(b0, b1, d > 0.0f) - o) * inv;
			exitT = select(float3(1e30f), exitT, abs(d) >= 1e-9f);
			axis = exitT.x < exitT.y && exitT.x < exitT.z ? 0 : exitT.y < exitT.z ? 1 : 2;
			t = exitT[axis];
			if (t >= tEnd) return -1.0f;
			c = int3(floor(o + d * (t + 1e-4f)));
			next = (float3(c) + max(float3(step), 0.0f) - o) * inv;
			continue;
		}
		int3 b0 = c & ~3;
		for (int k = 0; k < 10; k++) {
			if (brickBit(mask, c) && i + k > 0) {
				hit = c;
				normal = float3(0.0f);
				normal[axis] = -float(step[axis]);
				return t;
			}
			if (next.x < next.y && next.x < next.z) { t = next.x; c.x += step.x; next.x += delta.x; axis = 0; }
			else if (next.y < next.z) { t = next.y; c.y += step.y; next.y += delta.y; axis = 1; }
			else { t = next.z; c.z += step.z; next.z += delta.z; axis = 2; }
			if (t >= tEnd) return -1.0f;
			if (any((c & ~3) != b0)) break;
		}
	}
	return -1.0f;
}

// A cosine-weighted direction about n (u: two numbers in 0..1).
static float3 cosineDir(float3 n, float2 u) {
	float3 t = normalize(abs(n.y) < 0.9f ? cross(n, float3(0, 1, 0)) : cross(n, float3(1, 0, 0)));
	float3 b = cross(n, t);
	float r = sqrt(u.x), a = 6.2831853f * u.y;
	return normalize(t * (r * cos(a)) + b * (r * sin(a)) + n * sqrt(max(0.0f, 1.0f - u.x)));
}

static inline float2 rand2(uint2 p, uint frame) {
	uint h = hashKey(p.x * 73856093u ^ p.y * 19349663u ^ frame * 83492791u);
	return float2(float(h & 0xFFFFu), float(h >> 16)) / 65536.0f;
}

// The sun's (or moon's) light on a surface: its colour times strength times the cosine, if a ray reaches it.
static float3 sunOn(float3 g, float3 n, device const uint2 *bricks, device const uchar *sections, constant RtFrame &f) {
	float c = dot(n, f.sun.xyz);
	if (c <= 0.0f || f.sun.w <= 0.0f) return float3(0.0f);
	if (rt_trace(g + n * 0.02f, f.sun.xyz, 0.0f, f.params.x, int3(-100000), bricks, sections, f) >= 0.0f) return float3(0.0f);
	return f.extra2.xyz * (c * f.sun.w);
}

// The cached light at a world cell and face (0 if none yet).
static float3 cacheAt(device CacheEntry *cache, uint mask, int3 wcell, uint face) {
	int s = cacheFind(cache, mask, cacheKey(wcell, face), false);
	return s < 0 ? float3(0.0f) : float3(cache[s].irr.rgb);
}

kernel void rt_cache_update(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::read> traced [[texture(1)]],
	device const ushort *vox [[buffer(0)]], device const uint2 *bricks [[buffer(1)]], constant RtFrame &f [[buffer(2)]],
	device CacheEntry *cache [[buffer(3)]], device const uint2 *palette [[buffer(4)]], device const uchar *sections [[buffer(5)]],
	device atomic_uint *touched [[buffer(6)]], uint2 gid [[thread_position_in_grid]]) {
	// one traced pixel in stride x stride each frame (extra2.w), in a pattern that moves every frame
	uint frame = uint(f.params.y), stride = max(1u, uint(f.extra2.w));
	uint2 q = gid * stride + uint2(frame % stride, (frame / stride) % stride);
	if (q.x >= traced.get_width() || q.y >= traced.get_height()) return;
	uint2 px = min(q * uint(f.extra.y), f.screen.xy - 1);
	float d = depth.read(px);
	if (d <= 1e-7f) return;
	float3 P = pointAt(px, d, f);
	uint face = min(uint(traced.read(q).a + 0.5f), 5u);  // (the shadow pass's normal, as a face)
	float3 N = faceNormal(face);
	float3 g = P - f.origin.xyz;
	int3 air = int3(floor(g + N * 0.5f));
	if (!inWindow(air, f)) return;
	uint mask = uint(f.cache.x) - 1u;
	float3 dir = cosineDir(N, rand2(q, frame));
	int3 hit;
	float3 hn;
	float3 L;
	float t = rt_hit(g + N * 0.02f, dir, f.cache.y, bricks, sections, f, hit, hn);
	if (t < 0.0f) {
		// the sky (the brighter the higher it is seen; below the horizon it's the ground's colour at the window's edge)
		L = f.sky.xyz * f.sky.w * (dir.y > 0.0f ? 1.0f : 0.25f);
	} else {
		ushort v = vox[voxelIndex(hit, f)];
		float3 albedo = srgbToLinear(float3((uint3(palette[v & 0x7FFFu].x) >> uint3(16, 8, 0)) & 0xFFu) / 255.0f);
		float3 hp = g + dir * t;
		float3 inLight = sunOn(hp, hn, bricks, sections, f) + cacheAt(cache, mask, worldCell(hit + int3(hn), f), faceOf(hn));
		L = albedo * inLight;
	}
	int s = cacheClaim(cache, mask, cacheKey(worldCell(air, f), face), f);
	if (s < 0) return;
	uint3 fx = uint3(clamp(L, 0.0f, 64.0f) * 1024.0f);
	atomic_fetch_add_explicit(&cache[s].sum[0], fx.x, memory_order_relaxed);
	atomic_fetch_add_explicit(&cache[s].sum[1], fx.y, memory_order_relaxed);
	atomic_fetch_add_explicit(&cache[s].sum[2], fx.z, memory_order_relaxed);
	// (the frame's first sample for this entry: it goes on the list rt_cache_resolve walks, instead of every entry)
	if (atomic_fetch_add_explicit(&cache[s].count, 1u, memory_order_relaxed) == 0u) {
		uint k = atomic_fetch_add_explicit(&touched[0], 1u, memory_order_relaxed);
		if (k < uint(f.cache.x)) atomic_store_explicit(&touched[1 + k], uint(s), memory_order_relaxed);
	}
}

// Each entry's new samples folded into its average (the first ones count fully, then each frame a tenth), then cleared.
kernel void rt_cache_resolve(device CacheEntry *cache [[buffer(3)]], constant RtFrame &f [[buffer(2)]], device const uint *touched [[buffer(6)]],
	uint t [[thread_position_in_grid]]) {
	// (the entries this frame's samples touched: rt_cache_update's list)
	if (t >= min(touched[0], uint(f.cache.x))) return;
	uint i = touched[1 + t];
	uint n = atomic_load_explicit(&cache[i].count, memory_order_relaxed);
	if (n == 0u) return;
	float3 s = float3(atomic_load_explicit(&cache[i].sum[0], memory_order_relaxed), atomic_load_explicit(&cache[i].sum[1], memory_order_relaxed),
		atomic_load_explicit(&cache[i].sum[2], memory_order_relaxed)) / (1024.0f * float(n));
	uint age = cache[i].age;
	float a = max(1.0f / float(age + 1u), f.cache.z);
	cache[i].irr = half4(half3(mix(float3(cache[i].irr.rgb), s, a)), 1.0h);
	cache[i].age = min(age + n, 1000u);
	atomic_store_explicit(&cache[i].sum[0], 0u, memory_order_relaxed);
	atomic_store_explicit(&cache[i].sum[1], 0u, memory_order_relaxed);
	atomic_store_explicit(&cache[i].sum[2], 0u, memory_order_relaxed);
	atomic_store_explicit(&cache[i].count, 0u, memory_order_relaxed);
}

// The cached light at a surface point, blended between the four nearest cells across its face (smooth, not one value per block).
static float3 cacheSmooth(device CacheEntry *cache, uint mask, float3 g, uint face, constant RtFrame &f) {
	float3 n = faceNormal(face);
	uint ax = face >> 1, a1 = (ax + 1u) % 3u, a2 = (ax + 2u) % 3u;
	float3 c = g + n * 0.5f;
	float u = c[a1] - 0.5f, v = c[a2] - 0.5f;
	float fu = fract(u), fv = fract(v);
	float3 sum = float3(0.0f);
	float wsum = 0.0f;
	for (int k = 0; k < 4; k++) {
		float3 p = c;
		p[a1] = floor(u) + float(k & 1) + 0.5f;
		p[a2] = floor(v) + float(k >> 1) + 0.5f;
		int3 cell = int3(floor(p));
		if (!inWindow(cell, f)) continue;
		int s = cacheFind(cache, mask, cacheKey(worldCell(cell, f), face), false);
		if (s < 0) continue;
		float w = ((k & 1) ? fu : 1.0f - fu) * ((k >> 1) ? fv : 1.0f - fv) + 1e-4f;
		sum += float3(cache[s].irr.rgb) * w;
		wsum += w;
	}
	return wsum > 0.0f ? sum / wsum : float3(-1.0f);
}

// The cached light per traced sample (half resolution: a quarter of the cache lookups the frame's pixels would make), for
// rt_light_fs to upsample by depth as the shadows are. a: 1 where the cache had light there.
kernel void rt_gather(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::read> traced [[texture(1)]],
	texture2d<float, access::write> irr [[texture(2)]], constant RtFrame &f [[buffer(2)]], device CacheEntry *cache [[buffer(3)]],
	uint2 q [[thread_position_in_grid]]) {
	if (q.x >= irr.get_width() || q.y >= irr.get_height()) return;
	uint2 px = min(q * uint(f.extra.y), f.screen.xy - 1);
	float d = depth.read(px);
	if (d <= 1e-7f) {
		irr.write(float4(0.0f), q);
		return;
	}
	float3 g = pointAt(px, d, f) - f.origin.xyz;
	uint face = min(uint(traced.read(q).a + 0.5f), 5u);
	float3 c = cacheSmooth(cache, uint(f.cache.x) - 1u, g, face, f);
	irr.write(c.x < 0.0f ? float4(0.0f) : float4(c, 1.0f), q);
}

// The gathered light at a pixel, upsampled as traced() upsamples the shadows (weights by distance and by depth).
static float4 gathered(texture2d<float, access::read> rt, texture2d<float, access::read> irr, depth2d<float, access::read> depth, uint2 p,
	constant RtFrame &f) {
	if (f.extra.y < 1.5f) return irr.read(p);
	float dist = length(pointAt(p, depth.read(p), f));
	float2 q = (float2(p) + 0.5f) / f.extra.y - 0.5f;
	int2 q0 = int2(floor(q));
	float2 fr = q - float2(q0);
	int2 lim = int2(rt.get_width(), rt.get_height()) - 1;
	float4 sum = float4(0.0f);
	float wsum = 0.0f;
	for (int k = 0; k < 4; k++) {
		int2 o = int2(k & 1, k >> 1);
		uint2 at = uint2(clamp(q0 + o, int2(0), lim));
		float4 v = irr.read(at);
		float wb = (o.x ? fr.x : 1.0f - fr.x) * (o.y ? fr.y : 1.0f - fr.y);
		float w = (wb * exp(-abs(rt.read(at).b - dist) / (0.03f * dist + 0.15f)) + 1e-5f) * v.a;
		sum += v * w;
		wsum += w;
	}
	return wsum > 1e-4f ? sum / wsum : float4(0.0f);
}

// The game's face shading (vanilla: up 1.0, down 0.5, north/south 0.8, east/west 0.6), taken out where the new light replaces it.
static inline float vanillaShade(uint face) {
	return face == 3u ? 1.0f : face == 2u ? 0.5f : face >= 4u ? 0.8f : 0.6f;
}

// The frame relit: the factor, per channel (sRGB-encoded, as the frame is), that turns the game's lighting at this pixel into
// the game's block light plus the traced sun and the cached sky and bounce light.
fragment float4 rt_light_fs(VOut in [[stage_in]], texture2d<float, access::read> rt [[texture(0)]],
	texture2d<float, access::read> lightmap [[texture(1)]], depth2d<float, access::read> depth [[texture(2)]], constant RtFrame &f [[buffer(2)]],
	texture2d<float, access::read> irr [[texture(3)]]) {
	uint2 p = uint2(in.pos.xy);
	float d = depth.read(p);
	if (d <= 1e-7f) return float4(1.0f);
	float4 s = traced(rt, depth, p, f);
	uint code = uint(s.g + 0.5f), sky = (code >> 4) & 15u, block = code & 15u;
	float3 P = pointAt(p, d, f);
	uint face = min(uint(s.a + 0.5f), 5u);  // (the shadow pass's normal, as a face: not rebuilt here per pixel)
	float3 g = P - f.origin.xyz;
	float fade = edgeFade(P, f);
	if (fade >= 0.999f) return float4(1.0f);
	float4 gl = gathered(rt, irr, depth, p, f);
	if (gl.a < 0.5f) return float4(1.0f);  // (not lit yet: as the game draws it)
	float3 cached = gl.rgb;
	float3 vanilla = srgbToLinear(lightmap.read(uint2(block, sky)).rgb) * vanillaShade(face);
	float3 torch = srgbToLinear(lightmap.read(uint2(block, 0u)).rgb);
	float vis = sky == 0u ? 0.0f : s.r;
	float3 sun = f.extra2.xyz * (f.sun.w * vis * max(dot(faceNormal(face), f.sun.xyz), 0.0f));
	float3 relit = torch * vanillaShade(face) + (sun + cached) * f.cache.w;
	float3 k = clamp(relit / max(vanilla, float3(0.03f)), 0.0f, 6.0f);
	k = mix(k, float3(1.0f), fade);
	return float4(pow(k, 1.0f / 2.2f), 1.0f);
}

// ---- Path traced photos (Photo Mode with Ray Tracing on): the view path traced, a sample per pixel per frame, added up ----
//
// The first hit is the game's own frame (its depth gives the point and the face; its colour divided by the game's lighting there
// gives the surface's colour, texture detail included). From there the path goes on through the voxels: at every surface the sun
// or moon (a shadow ray, its disc's size jittered: soft shadows), then a bounce in a cosine-weighted direction; out to the sky, the
// sky's light; onto a block, its colour (its texture's average) and, for blocks that give light, their glow. Torch light at the
// first hit is the game's block light. accum keeps the sum; rt_path_out_fs draws the average into the frame.

static float3 skyRadiance(float3 dir, constant RtFrame &f) {
	return f.sky.xyz * f.sky.w * (dir.y > 0.0f ? 0.6f + 0.4f * dir.y : 0.2f);
}

static float3 sunSoft(float3 g, float3 n, uint2 p, uint frame, device const uint2 *bricks, device const uchar *sections, constant RtFrame &f) {
	if (f.sun.w <= 0.0f) return float3(0.0f);
	float2 u = rand2(p + 101u, frame);
	float3 l = f.sun.xyz;
	float3 t = normalize(abs(l.y) < 0.9f ? cross(l, float3(0, 1, 0)) : cross(l, float3(1, 0, 0))), b = cross(l, t);
	float r = sqrt(u.x) * 0.03f, a = 6.2831853f * u.y;
	l = normalize(l + (t * cos(a) + b * sin(a)) * r);
	float c = dot(n, l);
	if (c <= 0.0f) return float3(0.0f);
	if (rt_trace(g + n * 0.02f, l, 0.0f, f.params.x, int3(-100000), bricks, sections, f) >= 0.0f) return float3(0.0f);
	return f.extra2.xyz * (c * f.sun.w);
}

kernel void rt_path(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::read> color [[texture(1)]],
	texture2d<float, access::read_write> accum [[texture(2)]], texture2d<float, access::read> lightmap [[texture(3)]],
	device const ushort *vox [[buffer(0)]], device const uint2 *bricks [[buffer(1)]], constant RtFrame &f [[buffer(2)]],
	device const uint2 *palette [[buffer(4)]], device const uchar *sections [[buffer(5)]], constant uint &reset [[buffer(6)]],
	uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= f.screen.x || gid.y >= f.screen.y) return;
	float4 sum = reset != 0u ? float4(0.0f) : accum.read(gid);
	float3 c0 = srgbToLinear(clamp(color.read(gid).rgb, 0.0f, 64.0f));
	float d = depth.read(gid);
	float3 result = c0;
	uint frame = uint(f.params.y);
	if (d > 1e-7f) {
		float3 P = pointAt(gid, d, f);
		uint face = faceOf(normalAt(depth, gid, P, f));
		float3 N = faceNormal(face);
		float3 g = P - f.origin.xyz;
		float fade = edgeFade(P, f);
		if (fade < 0.999f) {
			uint code = lightAt(g, N, vox, f), sky = (code >> 4) & 15u, block = code & 15u;
			float shade = vanillaShade(face);
			float3 vanilla = srgbToLinear(lightmap.read(uint2(block, sky)).rgb) * shade;
			float3 albedo = clamp(c0 / max(vanilla, float3(0.02f)), 0.0f, 1.0f);
			float3 L = srgbToLinear(lightmap.read(uint2(block, 0u)).rgb) * shade / max(f.cache.w, 1e-3f);
			L += sunSoft(g, N, gid, frame, bricks, sections, f);
			float3 thr = float3(1.0f), o = g + N * 0.02f, n = N;
			for (uint bounce = 0; bounce < 3u; bounce++) {
				float3 dir = cosineDir(n, rand2(gid + uint2(bounce * 7919u, 0u), frame));
				int3 hit;
				float3 hn;
				float t = rt_hit(o, dir, 64.0f, bricks, sections, f, hit, hn);
				if (t < 0.0f) {
					L += thr * skyRadiance(dir, f);
					break;
				}
				uint e = palette[vox[voxelIndex(hit, f)] & 0x7FFFu].x;
				float3 a = srgbToLinear(float3((uint3(e) >> uint3(16, 8, 0)) & 0xFFu) / 255.0f);
				float glow = float(e >> 24) / 15.0f;
				if (glow > 0.0f) L += thr * a * glow * 4.0f;
				thr *= a;
				o = o + dir * t + hn * 0.02f;
				n = hn;
				L += thr * sunSoft(o, n, gid + uint2(0u, bounce * 104729u + 7u), frame, bricks, sections, f);
				if (max(max(thr.r, thr.g), thr.b) < 0.02f) break;
			}
			result = mix(albedo * L * f.cache.w, c0, fade);
		}
	}
	accum.write(sum + float4(result, 1.0f), gid);
}

static float3 linearToSrgb(float3 l) {
	l = max(l, 0.0f);
	return select(1.055f * pow(l, 1.0f / 2.4f) - 0.055f, l * 12.92f, l <= 0.0031308f);
}

// The average so far, into the frame (its alpha kept).
fragment float4 rt_path_out_fs(VOut in [[stage_in]], texture2d<float, access::read> accum [[texture(0)]]) {
	float4 s = accum.read(uint2(in.pos.xy));
	return float4(linearToSrgb(s.rgb / max(s.a, 1.0f)), 1.0f);
}
