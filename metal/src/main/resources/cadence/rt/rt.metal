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
	                       // y: the trace's scale (1: a ray per pixel, 2: per 2 x 2 pixels, rt_apply_fs upsamples)
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
		out.write(float4(1.0f, 240.0f, 60000.0f, 0.0f), gid);  // the sky
		return;
	}
	float3 P = pointAt(px, d, f);
	float3 N = normalAt(depth, px, P, f);
	float3 g = P - f.origin.xyz;  // window coordinates
	uint light = lightAt(g, N, vox, f);
	float vis = 1.0f;
	if (f.sun.w > 0.0f) {
		float3 L = jitterTowards(f.sun.xyz, f.params.w, px, uint(f.params.y));
		if ((light >> 4) == 0u) vis = 1.0f;  // (no sky light here: nothing for a sun shadow to take away, no ray)
		else if (dot(N, L) <= 0.0f) vis = 0.0f;
		else {
			int3 own = int3(floor(g - N * 0.01f));
			vis = rt_trace(g + N * 0.02f, L, 0.0f, f.params.x, own, bricks, sections, f) >= 0.0f ? 0.0f : 1.0f;
		}
		vis = mix(vis, 1.0f, edgeFade(P, f));
	}
	out.write(float4(vis, float(light), length(P), 0.0f), gid);
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
		out.write(float4(1.0f, 240.0f, 60000.0f, 0.0f), gid);
		return;
	}
	float3 P = pointAt(px, d, f);
	float3 N = normalAt(depth, px, P, f);
	float3 g = P - f.origin.xyz;
	uint light = lightAt(g, N, vox, f);
	float vis = 1.0f;
	if (f.sun.w > 0.0f) {
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
		vis = mix(vis, 1.0f, edgeFade(P, f));
	}
	out.write(float4(vis, float(light), length(P), 0.0f), gid);
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

// The factor at a pixel: 1 where the light reaches it; in shadow, the game's lightmap at fewer sky light levels over the
// lightmap at the levels it has (per channel), so the darkening is the one the game itself shows for less sky light.
// The trace at this pixel: itself (scale 1), or the four nearest traced samples weighted by distance from it and by how close
// their depth is to this pixel's (scale 2: a shadow edge stays on the edge of the geometry). rgb: visibility, light code, distance.
static float3 traced(texture2d<float, access::read> rt, depth2d<float, access::read> depth, uint2 p, constant RtFrame &f) {
	if (f.extra.y < 1.5f) return rt.read(p).rgb;
	float d = depth.read(p);
	if (d <= 1e-7f) return float3(1.0f, 240.0f, 60000.0f);
	float dist = length(pointAt(p, d, f));
	float2 q = (float2(p) + 0.5f) / f.extra.y - 0.5f;
	int2 q0 = int2(floor(q));
	float2 fr = q - float2(q0);
	int2 lim = int2(rt.get_width(), rt.get_height()) - 1;
	float3 sum = float3(0.0f);
	float wsum = 0.0f, bestW = -1.0f;
	float3 best = float3(1.0f, 240.0f, dist);
	for (int k = 0; k < 4; k++) {
		int2 o = int2(k & 1, k >> 1);
		float3 v = rt.read(uint2(clamp(q0 + o, int2(0), lim))).rgb;
		float wb = (o.x ? fr.x : 1.0f - fr.x) * (o.y ? fr.y : 1.0f - fr.y);
		float wd = exp(-abs(v.b - dist) / (0.03f * dist + 0.15f));
		float w = wb * wd + 1e-5f;
		sum += v * w;
		wsum += w;
		if (wd > bestW) {
			bestW = wd;
			best = v;
		}
	}
	float3 r = sum / wsum;
	r.g = best.g;  // (the light code isn't blended: the nearest-depth sample's)
	return r;
}

// The factor at a pixel: 1 where the light reaches it; in shadow, the game's lightmap at fewer sky light levels over the
// lightmap at the levels it has (per channel), so the darkening is the one the game itself shows for less sky light.
fragment float4 rt_apply_fs(VOut in [[stage_in]], texture2d<float, access::read> rt [[texture(0)]],
	texture2d<float, access::read> lightmap [[texture(1)]], depth2d<float, access::read> depth [[texture(2)]], constant RtFrame &f [[buffer(2)]]) {
	uint2 p = uint2(in.pos.xy);
	float3 s = traced(rt, depth, p, f);
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
