// Cadence: Record Mode's frame (dev.cadence.metal.record). No render pass: the world is ray-marched through a voxel window
// around the camera (the client's loaded blocks, textured from the block atlas), entities are boxes, and the HUD text is
// drawn from the game's font, all in one compute dispatch; a second one makes the in-between frame (frame interpolation) by
// reprojecting the two latest frames.
#include <metal_stdlib>
using namespace metal;

struct Camera {
	float4 pos;    // xyz: eye, relative to the window's origin
	float4 fwd;    // xyz: forward; w: tan(half horizontal fov)
	float4 right;  // xyz: right; w: tan(half vertical fov)
	float4 up;     // xyz: up
};

struct Frame {
	Camera cam, a, b, mid;    // this frame's camera; the last two frames' (a the newer); the in-between frame's (between b and a)
	float4 sky, fog;          // sky colour (zenith), fog/horizon colour; fog.w: where fog is full (blocks)
	uint4 grid;               // x: window width log2 (x and z), y: height, z: x offset & mask (toroidal), w: z offset
	uint4 size;               // x, y: output size; z: entity count; w: text lines
	float4 misc;              // x: atlas max mip; y: text scale; z: fog start; w: the window's top (above every block)
	uint4 flags;              // x: make the in-between frame of the last two (frame interpolation) in this dispatch (2: the
	                          // camera didn't move between them, so no reprojection: the two frames' blend);
	                          // y: the sky's darkening (Level.getSkyDarken, 0..11 at night); z: the brightness option (float bits)
	uint4 hud[3];             // the HUD: 9 hotbar slot colours (0: empty); then the selected slot, health and food (0..20)
	int4 target;              // the block the crosshair is on (window-relative); w: 0 none, else 2 + its breaking stage (-1..9)
	uint4 status;             // x: air (0..20; 255: not shown), y: hurt (0..10), z: eye in water (1) or lava (2), w: XP progress (0..1000)
	uint4 chat;               // x: chat lines shown (text lines 8.., bottom left); y: tiles across (the entity tile masks)
};

// A palette entry (RecordWorld.PALETTE_STRIDE bytes), a uint4: x the top's colour, y the sides' (each its texture's average,
// tinted: 1x1 textures); z flags (1: crossed plant, 2: fluid, 4: shape box, not a full cube); w the shape box (min x,y,z, max
// x,y,z, 5 bits each, 0..16).

struct Entity {
	float4 lo;    // xyz min (window-relative); w: colour bits
	float4 hi;    // xyz max
	int4 rect;    // screen rectangle the box covers (pixels, inclusive), from the CPU
};


static inline float3 rgb(uint c) { return float3((c >> 16) & 255, (c >> 8) & 255, c & 255) / 255.0f; }

// A voxel's place in the window's buffers: x fastest, then z, then y (toroidal in x and z). (Linear beat 8^3 bricks by 5%:
// the occupancy bits are 196 KB, cached whole, and the address is shifts and ors.)
static inline uint cellIndex(int3 v, constant Frame &f) {
	uint m = (1u << f.grid.x) - 1;
	return (uint(v.y) << (2 * f.grid.x)) | (((uint(v.z) + f.grid.w) & m) << f.grid.x) | ((uint(v.x) + f.grid.z) & m);
}

static inline uint voxelAt(device const ushort *grid, int3 v, constant Frame &f) {
	return grid[cellIndex(v, f)];
}

// An occupancy bit per voxel, so a ray crossing air tests one bit a step and reads the block id only where there is a block.
static inline bool occupiedAt(device const uint *bits, int3 v, constant Frame &f) {
	uint i = cellIndex(v, f);
	return (bits[i >> 5] >> (i & 31)) & 1u;
}

// The light where a face looks out (vanilla's flat lighting, Smooth Lighting off): per voxel sky light << 4 | block light,
// laid out as the grid. Above the window's blocks it's open sky; below the world, dark.
static inline uint lightAt(device const uchar *light, int3 v, constant Frame &f) {
	if (v.y >= int(f.grid.y)) return 0xF0u;
	if (v.y < 0) return 0u;
	return light[cellIndex(v, f)];
}

// A light level (sky << 4 | block) as vanilla's lightmap makes it brightness: the sky's part darkened for the time of day,
// the brighter of the two, vanilla's curve (as Record.curve).
static inline float brightness(uint l, constant Frame &f) {
#ifdef RB_NO_LIGHT
	return 1.0f;
#endif
	uint lv = max(uint(max(int(l >> 4) - int(f.flags.y), 0)), l & 15u);
	// (vanilla's curve, no ambient light; the Brightness option; the lightmap's floor. Computed: a table in constant memory,
	// indexed per pixel, was slower)
	float x = float(lv) * (1.0f / 15.0f), b = x / (4.0f - 3.0f * x), nb = 1.0f - b;
	nb *= nb;
	return mix(b, 1.0f - nb * nb, as_type<float>(f.flags.z)) * 0.96f + 0.04f;
}


static inline float3 rayDir(Camera c, float2 ndc) {
	return normalize(c.fwd.xyz + c.right.xyz * (ndc.x * c.fwd.w) + c.up.xyz * (ndc.y * c.right.w));
}



// (the in-between frame for one pixel, from the last two frames: see record_interp)
static float3 interpolated(uint2 gid, constant Frame &f, texture2d<float> colorA, texture2d<float> depthA, texture2d<float> colorB,
	texture2d<float> depthB);

kernel void record_trace(texture2d<float, access::write> outColor [[texture(0)]], texture2d<float, access::write> outDepth [[texture(1)]],
	texture2d<float> colorA [[texture(4)]],
	texture2d<float> depthA [[texture(5)]], texture2d<float> colorB [[texture(6)]], texture2d<float> depthB [[texture(7)]],
	texture2d<float, access::write> outMid [[texture(8)]], constant Frame &f [[buffer(0)]],
	device const ushort *grid [[buffer(1)]], device const uint4 *pal [[buffer(3)]],
	constant Entity *entities [[buffer(4)]], device const uint *hud [[buffer(5)]], device const uint *occ [[buffer(6)]],
	device const uchar *light [[buffer(7)]], device const uint *tiles [[buffer(8)]],
	uint2 gid [[thread_position_in_grid]]) {
	if (gid.x >= f.size.x || gid.y >= f.size.y) return;
	float2 ndc = (float2(gid) + 0.5f) / float2(f.size.xy) * 2.0f - 1.0f;
	ndc.y = -ndc.y;
	float3 ro = f.cam.pos.xyz, rd = rayDir(f.cam, ndc);
	int W = 1 << f.grid.x, H = int(min(float(f.grid.y), f.misc.w));  // (nothing above the window's top: rays there are sky)
	float3 inv = 1.0f / select(rd, float3(1e-20f), abs(rd) < 1e-20f);
	// the window's box
	float3 t0 = (float3(0) - ro) * inv, t1 = (float3(W, H, W) - ro) * inv;
	float3 tn = min(t0, t1), tf = max(t0, t1);
	float tEnter = max(max(tn.x, tn.y), max(tn.z, 0.0f)), tExit = min(min(tf.x, tf.y), tf.z);
	float hitT = 1e9f;
	float3 color = float3(0);
	int3 stepv = int3(sign(rd));
#ifdef RB_NO_TRACE
	tExit = -1;
#endif
	if (tEnter < tExit) {
		float t = tEnter;
		int axis = tn.x >= tn.y && tn.x >= tn.z ? 0 : tn.y >= tn.z ? 1 : 2;
		bool done = false;
		uint4 hitE = uint4(0);  // the block hit: its palette entry, voxel, face axis (3: a crossed plant)
		int3 hitV = int3(0);
		int hitAx = 0;
#ifdef RB_STEPS
		uint innerSteps = 0;
#endif
		// The voxels along the ray one by one (Amanatides & Woo), as far as the fog is total: beyond it every block is the fog's
		// colour. (Within a window of 64 blocks, fog total at 28, a ray crosses at most ~50 voxels: no empty-space skipping.)
		{
			float tEnd = min(tExit, f.fog.w);
			int3 v = int3(floor(ro + rd * (t + 1e-4f)));
			float3 tMax = (float3(v) + float3(rd > 0) - ro) * inv, tDelta = abs(inv);
			float tIn = t;
			for (int k = 0; k < 96 && tIn < tEnd; k++) {
				// (x and z can't leave the window before tEnd, and wrap if rounding says otherwise; y is checked: it indexes memory)
				if (uint(v.y) >= uint(H)) break;
#ifdef RB_STEPS
				innerSteps++;
#endif
				uint id = occupiedAt(occ, v, f) ? voxelAt(grid, v, f) : 0;
				if (id != 0) {
					uint4 e = pal[id];
#ifdef RB_SIMPLE_HIT
					hitE = e; hitV = v; hitAx = axis; hitT = tIn; done = true; break;
#endif
					if (f.status.z != 0 && (e.z & 2)) {
						// (the eye in water or lava: fluid seen through, as the game draws it from inside)
					} else if (e.z & 1) {
						// crossed plant: the two diagonal planes x - z = 0 and x + z = 1, the plant's colour over the whole quad
						float3 o = ro - float3(v);
						for (int q = 0; q < 2; q++) {  // (both: the nearer one hit counts)
							float3 pn = q == 0 ? float3(1, 0, -1) : float3(1, 0, 1);
							float den = dot(pn, rd);
							if (abs(den) < 1e-6f) continue;
							float tq = (float(q) - dot(pn, o)) / den;
							float3 lp = o + rd * tq;
							if (tq < tIn - 1e-4f || any(lp < 0.0f) || any(lp > 1.0f) || (done && tq >= hitT)) continue;
							hitE = e; hitV = v; hitAx = 3; hitT = tq; done = true;
						}
						if (done) break;
					} else if (e.z & 4) {
						// a shape smaller than the cube: its box within the voxel
						float3 bl = float3(e.w & 31, (e.w >> 5) & 31, (e.w >> 10) & 31) / 16.0f;
						float3 bh = float3((e.w >> 15) & 31, (e.w >> 20) & 31, (e.w >> 25) & 31) / 16.0f;
						float3 o = ro - float3(v);
						float3 a0 = (bl - o) * inv, a1 = (bh - o) * inv;
						float3 an = min(a0, a1), af = max(a0, a1);
						float te = max(max(an.x, an.y), an.z), tx = min(min(af.x, af.y), af.z);
						if (te <= tx && tx >= 0) {
							hitE = e; hitV = v; hitAx = an.x >= an.y && an.x >= an.z ? 0 : an.y >= an.z ? 1 : 2; hitT = max(te, tIn); done = true;
							break;
						}
					} else {
						hitE = e; hitV = v; hitAx = axis; hitT = tIn; done = true;
						break;
					}
				}
#ifdef RB_BRANCHY
				if (tMax.x < tMax.y && tMax.x < tMax.z) { tIn = tMax.x; v.x += stepv.x; tMax.x += tDelta.x; axis = 0; }
				else if (tMax.y < tMax.z) { tIn = tMax.y; v.y += stepv.y; tMax.y += tDelta.y; axis = 1; }
				else { tIn = tMax.z; v.z += stepv.z; tMax.z += tDelta.z; axis = 2; }
#else
				// the next voxel, branch-free: along the axis whose boundary comes first
				bool3 m = tMax <= min(tMax.yzx, tMax.zxy);
				axis = m.x ? 0 : m.y ? 1 : 2;
				tIn = min(min(tMax.x, tMax.y), tMax.z);
				v += select(int3(0), stepv, m);
				tMax += select(float3(0), tDelta, m);
#endif
			}
		}
		if (done) {
			// the hit's colour: its face's 1x1 texture (the average colour, tinted), shaded as vanilla's flat lighting does
			if (hitAx == 3) color = rgb(hitE.y) * 0.85f * brightness(lightAt(light, hitV, f), f);
			else {
				float3 n = float3(0);
				n[hitAx] = -float(stepv[hitAx]);
				float shade = n.y > 0 ? 1.0f : n.y < 0 ? 0.5f : n.x != 0 ? 0.6f : 0.8f;
				// (a full cube's face is lit by the block it faces; a smaller shape or a fluid by its own voxel)
				int3 lv = (hitE.z & 6) ? hitV : hitV + int3(n);
				color = rgb(n.y != 0 ? hitE.x : hitE.y) * shade * brightness(lightAt(light, lv, f), f);
				if (f.target.w != 0 && all(hitV == f.target.xyz)) {
					// the targeted block: vanilla's dark outline along its edges, and darker as it breaks
					float3 fr = fract(ro + rd * hitT + n * 1e-3f);
					float2 uv = n.y != 0 ? fr.xz : n.x != 0 ? fr.zy : fr.xy;
					float2 e2 = min(uv, 1.0f - uv);
					if (min(e2.x, e2.y) < 0.035f) color *= 0.3f;
					color *= 1.0f - 0.055f * float(max(f.target.w - 1, 0));
				}
			}
		}
#ifdef RB_STEPS
		outColor.write(float4(0, float(innerSteps) / 255.0f, 0, 1), gid);
		return;
#endif
	}
	// entities: boxes, only those whose screen rectangle touches this pixel's 8 x 8 tile (a mask per tile from the CPU)
#ifdef RB_NO_ENT
	uint emask = 0;
#else
	uint emask = tiles[(gid.y >> 3) * f.chat.y + (gid.x >> 3)];
#endif
	while (emask != 0) {
		uint i = ctz(emask);
		emask &= emask - 1;
		Entity e = entities[i];
		if (int(gid.x) < e.rect.x || int(gid.x) > e.rect.z || int(gid.y) < e.rect.y || int(gid.y) > e.rect.w) continue;
		float3 a0 = (e.lo.xyz - ro) * inv, a1 = (e.hi.xyz - ro) * inv;
		float3 an = min(a0, a1), af = max(a0, a1);
		float te = max(max(an.x, an.y), max(an.z, 0.0f)), tx = min(min(af.x, af.y), af.z);
		if (te <= tx && te < hitT) {
			hitT = te;
			int ax = an.x >= an.y && an.x >= an.z ? 0 : an.y >= an.z ? 1 : 2;
			float shade = ax == 1 ? (rd.y < 0 ? 1.0f : 0.5f) : ax == 0 ? 0.6f : 0.8f;
			color = rgb(as_type<uint>(e.lo.w)) * shade;
		}
	}
	// sky and fog
	float3 sky = mix(f.fog.rgb, f.sky.rgb, saturate(rd.y * 1.6f + 0.1f));
	if (hitT >= 1e8f) color = sky;
	else color = mix(color, f.fog.rgb, smoothstep(f.misc.z, f.fog.w, hitT));
	// in water or lava: the view tinted as the game's fog tints it; hurt: a red flash
	if (f.status.z == 1) color = mix(color * float3(0.45f, 0.6f, 1.0f), float3(0.02f, 0.06f, 0.25f), smoothstep(2.0f, 20.0f, hitT));
	else if (f.status.z == 2) color = mix(color, float3(0.9f, 0.3f, 0.05f), 0.85f);
	if (f.status.y > 0) color = mix(color, float3(0.8f, 0.0f, 0.0f), float(f.status.y) * 0.035f);
#ifndef RB_NO_HUD
	// the HUD (RecordHud: text, hotbar, bars), drawn by the CPU when it changes, blended over the frame
	uint o = hud[gid.y * f.size.x + gid.x];
	if (o != 0) {
		float4 hc = unpack_unorm4x8_to_float(o);
		color = mix(color, hc.rgb, hc.a);
	}
	uint scale = uint(f.misc.y);
	// the crosshair (the game stays playable: you can see what you aim at)
	int2 cc = int2(gid) - int2(f.size.xy / 2);
	int arm = int(4 * scale), thick = int(max(1u, scale / 2));
	if ((abs(cc.x) <= arm && abs(cc.y) < thick) || (abs(cc.y) <= arm && abs(cc.x) < thick)) color = 1.0f - color;
#endif
	// (stored bottom row first, as the game's targets are: the present flips them; the image above is top row first)
	uint2 st = uint2(gid.x, f.size.y - 1 - gid.y);
	outColor.write(float4(color, 1), st);
	outDepth.write(float4(hitT), st);
	// frame interpolation, in the same dispatch: the frame halfway between the last two (one frame behind this one)
	if (f.flags.x != 0) outMid.write(float4(interpolated(gid, f, colorA, depthA, colorB, depthB), 1), st);
}

// Frame interpolation: the frame halfway between the last two (a the newer, b the older), by reprojection. Each pixel's ray
// from the in-between camera takes frame a's depth there as its distance; the point it reaches is looked up in both frames,
// and the frame whose own depth agrees with it (it saw that point, nothing in front) gives the colour (both agreeing: their
// mean).
static float3 interpolated(uint2 gid, constant Frame &f, texture2d<float> colorA, texture2d<float> depthA, texture2d<float> colorB,
	texture2d<float> depthB) {
	uint2 st = uint2(gid.x, f.size.y - 1 - gid.y);  // (the textures' rows are bottom first: see record_trace)
	if (f.flags.x == 2) return (colorA.read(st).rgb + colorB.read(st).rgb) * 0.5f;
	float2 size = float2(f.size.xy);
	float2 ndc = (float2(gid) + 0.5f) / size * 2.0f - 1.0f;
	ndc.y = -ndc.y;
	float d = depthA.read(st).r;
	float3 rd = rayDir(f.mid, ndc);
	float3 P = f.mid.pos.xyz + rd * min(d, 4096.0f);
	float3 acc = float3(0);
	float wsum = 0;
	for (int k = 0; k < 2; k++) {
		Camera c = k == 0 ? f.a : f.b;
		float3 v = P - c.pos.xyz;
		float z = dot(v, c.fwd.xyz);
		if (z <= 1e-3f) continue;
		float2 q = float2(dot(v, c.right.xyz) / (z * c.fwd.w), dot(v, c.up.xyz) / (z * c.right.w));
		if (any(abs(q) > 1.0f)) continue;
		float2 uv = float2(q.x * 0.5f + 0.5f, 0.5f - q.y * 0.5f);
		uint2 px = uint2(min(uv * size, size - 1.0f));  // (nearest)
		px.y = f.size.y - 1 - px.y;
		float dd = (k == 0 ? depthA : depthB).read(px).r;
		float dist = length(v);
		float w = d >= 1e8f || abs(dd - dist) < max(0.5f, dist * 0.02f) ? 1.0f : 0.05f;
		acc += (k == 0 ? colorA : colorB).read(px).rgb * w;
		wsum += w;
	}
	return wsum > 0 ? acc / wsum : colorA.read(st).rgb;
}
