// Cadence: Frame Generation (dev.cadence.metal.framegen.FrameGen). Each real frame leaves a snapshot of the world (its colour
// and each pixel's distance from the camera, before the hand and the GUI are drawn); a generated frame is the world seen from
// another camera, rebuilt from the last two snapshots by reprojection (no motion vectors: Minecraft and shader packs have
// none), with whatever was drawn after the snapshot (the hand, the HUD, chat, screens) taken from the newest real frame as it
// is, so text and the hand never smear.
//
// Smooth: the camera halfway between the last two frames, shown before the newest one (one frame of added latency).
// Responsive: the newest frame seen from the camera the mouse has turned to since, shown halfway to the next frame (no added
// latency; what the newest frame couldn't see comes from the frame before it, else its nearest edge).
//
// Rows are stored bottom first, as the game's targets are (uv.y = 0 is the bottom of the screen). The game's projection is
// reversed-Z in Metal's 0..1 (near 1, far 0: z_ndc = depth); a shader pack's depth is 1 - that (its gbufferProjection is the
// game's turned to OpenGL's -1..1, then mapped back to 0..1 by the translation).
#include <metal_stdlib>
using namespace metal;

struct Cam {
	float4x4 viewProj;     // camera-relative world -> clip (projection x view rotation)
	float4x4 invViewProj;  // clip -> camera-relative world
	float4 pos;            // xyz: the camera, relative to the newest frame's camera (blocks)
};

struct Frame {
	Cam a, b, m;      // a: the newest real frame; b: the one before it; m: the camera of the frame made here
	float4 params;    // x: Responsive (1) or Smooth (0); y: whether frame b is usable; z: hand distance (blocks: nearer pixels
	                  // stay where they are on screen); w: unused
	float4 size;      // xy: the output size; zw: 1 / it
};

#define SKY 60000.0f

// ---- the snapshot: one compute dispatch per real frame ----

struct SnapParams {
	float4x4 invProj;  // clip -> view (the game's projection, inverted)
	float4 info;       // x: the depth's kind: 0 the game's own (z_ndc = d), 1 a shader pack's (z_ndc = 1 - d)
};

static inline float distanceOf(float d, float2 uv, constant SnapParams &p) {
	float z = p.info.x > 0.5f ? 1.0f - d : d;
	if (z <= 1e-7f) return SKY;  // (reversed-Z: the far plane, the sky)
	float4 v = p.invProj * float4(uv * 2.0f - 1.0f, z, 1.0f);
	float dist = length(v.xyz / v.w);
	return isfinite(dist) ? min(dist, SKY) : SKY;
}

kernel void fg_snapshot(texture2d<float> color [[texture(0)]], depth2d<float> depth [[texture(1)]],
	texture2d<float, access::write> outColor [[texture(2)]], texture2d<float, access::write> outDist [[texture(3)]],
	constant SnapParams &p [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {
	uint w = outColor.get_width(), h = outColor.get_height();
	if (gid.x >= w || gid.y >= h) return;
	constexpr sampler near(coord::normalized, filter::nearest, address::clamp_to_edge);
	float2 uv = (float2(gid) + 0.5f) / float2(w, h);
	outColor.write(color.get_width() == w && color.get_height() == h ? color.read(gid) : color.sample(near, uv), gid);
	float d = depth.get_width() == w && depth.get_height() == h ? depth.read(gid) : depth.sample(near, uv);
	outDist.write(float4(distanceOf(d, uv, p)), gid);
}

// (the same with a colour-format depth: a shader pack's depth copy)
kernel void fg_snapshot_f(texture2d<float> color [[texture(0)]], texture2d<float> depth [[texture(1)]],
	texture2d<float, access::write> outColor [[texture(2)]], texture2d<float, access::write> outDist [[texture(3)]],
	constant SnapParams &p [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {
	uint w = outColor.get_width(), h = outColor.get_height();
	if (gid.x >= w || gid.y >= h) return;
	constexpr sampler near(coord::normalized, filter::nearest, address::clamp_to_edge);
	float2 uv = (float2(gid) + 0.5f) / float2(w, h);
	outColor.write(color.get_width() == w && color.get_height() == h ? color.read(gid) : color.sample(near, uv), gid);
	float d = depth.sample(near, uv).r;
	outDist.write(float4(distanceOf(d, uv, p)), gid);
}

// ---- the generated frame: a full-screen triangle into the drawable ----

struct VOut {
	float4 pos [[position]];
	float2 uv;  // game-texture space: (0, 0) bottom left
};

vertex VOut fg_vs(uint id [[vertex_id]]) {
	float2 p = float2((id << 1) & 2, id & 2);
	VOut o;
	o.pos = float4(p * 2.0f - 1.0f, 0.0f, 1.0f);
	o.uv = float2(p.x, p.y);  // (the drawable's top row is the game texture's last: clip y up = uv.y up)
	return o;
}

static inline float3 rayOf(constant Cam &c, float2 uv) {
	float4 p = c.invViewProj * float4(uv * 2.0f - 1.0f, 0.0f, 1.0f);
	return normalize(p.xyz / p.w);
}

// A point (relative to the newest camera) in camera c: its uv and its distance from c; false behind the camera.
static inline bool project(constant Cam &c, float3 P, thread float2 &uv, thread float &dist) {
	float3 r = P - c.pos.xyz;
	float4 q = c.viewProj * float4(r, 1.0f);
	if (q.w <= 1e-4f) return false;
	uv = q.xy / q.w * 0.5f + 0.5f;
	dist = length(r);
	return true;
}

static inline bool projectDir(constant Cam &c, float3 dir, thread float2 &uv) {
	float4 q = c.viewProj * float4(dir, 0.0f);
	if (q.w <= 1e-6f) return false;
	uv = q.xy / q.w * 0.5f + 0.5f;
	return true;
}

static inline bool inside(float2 uv) {
	return all(uv >= 0.0f) && all(uv <= 1.0f);
}

fragment float4 fg_fs(VOut in [[stage_in]], constant Frame &f [[buffer(0)]],
	texture2d<float> colorA [[texture(0)]], texture2d<float> distA [[texture(1)]],
	texture2d<float> colorB [[texture(2)]], texture2d<float> distB [[texture(3)]],
	texture2d<float> latest [[texture(4)]]) {
	constexpr sampler near(coord::normalized, filter::nearest, address::clamp_to_edge);
	constexpr sampler lin(coord::normalized, filter::linear, address::clamp_to_edge);
	float2 uv = in.uv;
	// drawn after the snapshot (the hand, the HUD, chat, a screen): as the newest frame has it
	float4 fin = latest.sample(near, uv), snap = colorA.sample(near, uv);
	if (any(abs(fin.rgb - snap.rgb) > 1.5f / 255.0f)) return float4(fin.rgb, 1.0f);
	float here = distA.sample(near, uv).r;
	if (here < f.params.z) return float4(snap.rgb, 1.0f);  // the hand (a shader pack's frame has it in its depth): on screen as it was
	bool smooth = f.params.x < 0.5f, haveB = f.params.y > 0.5f;
	float3 dir = rayOf(f.m, uv);
	// the point this pixel sees: from the newest frame's depth, refined twice where that frame sees it
	float t = here;
	bool sky = t >= SKY;
	for (int i = 0; i < 2 && !sky; i++) {
		float2 ua;
		float ea;
		if (!project(f.a, f.m.pos.xyz + dir * t, ua, ea) || !inside(ua)) break;
		float ta = distA.sample(near, ua).r;
		if (ta >= SKY) { sky = true; break; }
		float3 pa = f.a.pos.xyz + rayOf(f.a, ua) * ta;
		t = max(dot(pa - f.m.pos.xyz, dir), 0.05f);
	}
	float2 ua, ub;
	bool okA, okB;
	if (sky) {
		// open sky: only the direction matters (the sky has no parallax)
		okA = projectDir(f.a, dir, ua) && inside(ua) && distA.sample(near, ua).r >= SKY;
		okB = haveB && projectDir(f.b, dir, ub) && inside(ub) && distB.sample(near, ub).r >= SKY;
		if (!okA && !okB) {
			// (sky in no frame there: the newest frame's nearest)
			if (!projectDir(f.a, dir, ua)) ua = uv;
		}
	} else {
		float3 P = f.m.pos.xyz + dir * t;
		float ea = 0, eb = 0;
		bool inA = project(f.a, P, ua, ea) && inside(ua), inB = haveB && project(f.b, P, ub, eb) && inside(ub);
		// a frame saw this point where its own depth there agrees (else something in front of it hid it from that frame)
		okA = inA && abs(distA.sample(near, ua).r - ea) < 0.03f * ea + 0.1f;
		okB = inB && abs(distB.sample(near, ub).r - eb) < 0.03f * eb + 0.1f;
		if (!inA) ua = clamp(ua, 0.0f, 1.0f);
	}
	float3 ca = colorA.sample(lin, ua).rgb;
	if (okA && okB) return float4(smooth ? mix(ca, colorB.sample(lin, ub).rgb, 0.5f) : ca, 1.0f);
	if (okB && !okA) return float4(colorB.sample(lin, ub).rgb, 1.0f);
	return float4(ca, 1.0f);
}
