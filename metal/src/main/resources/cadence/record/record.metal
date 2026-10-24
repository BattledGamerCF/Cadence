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
	uint4 flags;              // x: make the in-between frame of the last two (frame interpolation) in this dispatch;
	                          // y: the sky's darkening (Level.getSkyDarken, 0..11 at night); z: the brightness option (float bits)
	uint4 hud[3];             // the HUD: 9 hotbar slot colours (0: empty); then the selected slot, health and food (0..20)
	int4 target;              // the block the crosshair is on (window-relative); w: 0 none, else 2 + its breaking stage (-1..9)
	uint4 status;             // x: air (0..20; 255: not shown), y: hurt (0..10), z: eye in water (1) or lava (2), w: XP progress (0..1000)
	uint4 chat;               // x: chat lines shown (text lines 8.., bottom left)
};

// A palette entry (RecordWorld.PALETTE_STRIDE words): top uv rect, side uv rect, then tints, average colours, flags, shape.
struct Pal {
	float4 top, side;
	uint topTint, sideTint, topAvg, sideAvg;
	uint flags;  // 1: crossed plant (two alpha-tested diagonal quads), 2: fluid, 4: shape box (not a full cube)
	uint box;    // shape box: min x,y,z, max x,y,z, 5 bits each (0..16)
	uint pad0, pad1;
};

struct Entity {
	float4 lo;    // xyz min (window-relative); w: colour bits
	float4 hi;    // xyz max
	int4 rect;    // screen rectangle the box covers (pixels, inclusive), from the CPU
};

constexpr sampler atlasSampler(filter::nearest, mip_filter::nearest, address::clamp_to_edge);

static inline float3 rgb(uint c) { return float3((c >> 16) & 255, (c >> 8) & 255, c & 255) / 255.0f; }

// Brick-major: each 8^3 brick's 512 voxels together (a ray inside a brick stays in a few cache lines), bricks ordered y, z, x.
static inline uint voxelAt(device const ushort *grid, int3 v, constant Frame &f) {
	uint w = 1u << f.grid.x, m = w - 1, bw = w >> 3;
	uint ix = (uint(v.x) + f.grid.z) & m, iz = (uint(v.z) + f.grid.w) & m, iy = uint(v.y);
	uint brick = ((iy >> 3) * bw + (iz >> 3)) * bw + (ix >> 3);
	return grid[brick * 512 + (((iy & 7) * 8 + (iz & 7)) * 8 + (ix & 7))];
}

// Per 8^3 brick: 512 occupancy bits (16 words, one cache line), so a ray crossing air inside a partly filled brick tests one
// bit a step and reads the block id only where there is a block.
static inline bool occupiedAt(device const uint *bits, int3 v, constant Frame &f) {
	uint w = 1u << f.grid.x, m = w - 1, bw = w >> 3;
	uint ix = (uint(v.x) + f.grid.z) & m, iz = (uint(v.z) + f.grid.w) & m, iy = uint(v.y);
	uint brick = ((iy >> 3) * bw + (iz >> 3)) * bw + (ix >> 3);
	uint local = ((iy & 7) * 8 + (iz & 7)) * 8 + (ix & 7);
	return (bits[brick * 16 + (local >> 5)] >> (local & 31)) & 1u;
}

// The light where a face looks out (vanilla's flat lighting, Smooth Lighting off): per voxel sky light << 4 | block light,
// laid out as the grid. Above the window's blocks it's open sky; below the world, dark.
static inline uint lightAt(device const uchar *light, int3 v, constant Frame &f) {
	if (v.y >= int(f.grid.y)) return 0xF0u;
	if (v.y < 0) return 0u;
	uint w = 1u << f.grid.x, m = w - 1, bw = w >> 3;
	uint ix = (uint(v.x) + f.grid.z) & m, iz = (uint(v.z) + f.grid.w) & m, iy = uint(v.y);
	uint brick = ((iy >> 3) * bw + (iz >> 3)) * bw + (ix >> 3);
	return light[brick * 512 + (((iy & 7) * 8 + (iz & 7)) * 8 + (ix & 7))];
}

// A light level as vanilla's lightmap makes it brightness: the sky's part darkened for the time of day, the brighter of sky
// and block light, vanilla's curve (no ambient light), the Brightness option, and the lightmap's floor.
static inline float brightness(uint l, constant Frame &f) {
	float lv = max(max(float(l >> 4) - float(f.flags.y), 0.0f), float(l & 15u)) / 15.0f;
	float b = lv / (4.0f - 3.0f * lv);
	float nb = 1.0f - b;
	b = mix(b, 1.0f - nb * nb * nb * nb, as_type<float>(f.flags.z));
	return b * 0.96f + 0.04f;
}

// Per 8^3 brick: 0 when it holds a block, else its distance (Chebyshev, in bricks, at most 8) to the nearest brick that does.
static inline uint brickAt(device const uchar *bricks, int3 b, constant Frame &f) {
	uint bw = (1u << f.grid.x) >> 3, m = bw - 1;
	uint ix = ((uint(b.x) + (f.grid.z >> 3)) & m), iz = ((uint(b.z) + (f.grid.w >> 3)) & m);
	return bricks[(uint(b.y) * bw + iz) * bw + ix];
}

static inline float3 rayDir(Camera c, float2 ndc) {
	return normalize(c.fwd.xyz + c.right.xyz * (ndc.x * c.fwd.w) + c.up.xyz * (ndc.y * c.right.w));
}

// The atlas texel of a face: sprite rect, face-local uv, level of detail from the distance.
static inline float4 sampleFace(texture2d<float> atlas, float4 rect, float2 uv, float lod) {
	float2 p = mix(rect.xy, rect.zw, clamp(uv, 0.001f, 0.999f));
	return atlas.sample(atlasSampler, p, level(lod));
}

// One pixel of a block of text lines (the game's font, 6-pixel advance, a dark box behind each line): `first` the block's
// first line in the text buffer, `origin` its top left.
static inline float3 textPixel(uint2 gid, int2 origin, uint first, uint count, uint scale, device const uchar *text, texture2d<float> font,
	float3 color) {
	int2 p = int2(gid) - origin;
	uint lineH = 10 * scale, adv = 6 * scale;
	if (p.x < 0 || p.y < 0) return color;
	uint line = uint(p.y) / lineH, col = uint(p.x) / adv;
	if (line >= count || col >= 64) return color;
	uint ch = text[(first + line) * 64 + col];
	if (ch == 0) return color;
	color *= 0.45f;
	uint lx = (uint(p.x) % adv) / scale, ly = (uint(p.y) % lineH) / scale;
	if (lx < 8 && ly >= 1 && ly < 9 && font.read(uint2((ch & 15) * 8 + lx, (ch >> 4) * 8 + (ly - 1))).a > 0.5f) color = float3(1);
	return color;
}

// (the in-between frame for one pixel, from the last two frames: see record_interp)
static float3 interpolated(uint2 gid, constant Frame &f, texture2d<float> colorA, texture2d<float> depthA, texture2d<float> colorB,
	texture2d<float> depthB);

kernel void record_trace(texture2d<float, access::write> outColor [[texture(0)]], texture2d<float, access::write> outDepth [[texture(1)]],
	texture2d<float> atlas [[texture(2)]], texture2d<float> font [[texture(3)]], texture2d<float> colorA [[texture(4)]],
	texture2d<float> depthA [[texture(5)]], texture2d<float> colorB [[texture(6)]], texture2d<float> depthB [[texture(7)]],
	texture2d<float, access::write> outMid [[texture(8)]], constant Frame &f [[buffer(0)]],
	device const ushort *grid [[buffer(1)]], device const uchar *bricks [[buffer(2)]], device const Pal *pal [[buffer(3)]],
	device const Entity *entities [[buffer(4)]], device const uchar *text [[buffer(5)]], device const uint *occ [[buffer(6)]],
	device const uchar *light [[buffer(7)]],
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
	if (tEnter < tExit) {
		float t = tEnter;
		int axis = tn.x >= tn.y && tn.x >= tn.z ? 0 : tn.y >= tn.z ? 1 : 2;
		bool done = false;
		for (int it = 0; it < 96 && !done && t < tExit; it++) {
			float3 p = ro + rd * (t + 1e-4f);
			int3 v = int3(floor(p));
			if (any(v < 0) || v.x >= W || v.y >= H || v.z >= W) break;
			int3 b = v >> 3;
			uint dist = brickAt(bricks, b, f);
			if (dist > 0) {
				// every brick within dist - 1 of this one is empty (the brick distance field): to the far side of that cube
				float3 bmin = float3((b - int(dist - 1)) * 8), bmax = float3((b + int(dist)) * 8);
				float3 tb = (select(bmin, bmax, rd > 0) - ro) * inv;
				float tn2 = min(min(tb.x, tb.y), tb.z);
				axis = tb.x <= tb.y && tb.x <= tb.z ? 0 : tb.y <= tb.z ? 1 : 2;
				t = max(tn2, t + 1e-4f);
				continue;
			}
			// voxels within the brick
			float3 tMax = (float3(v) + float3(rd > 0) - ro) * inv;
			float3 tDelta = abs(inv);
			float tIn = t;
			for (int k = 0; k < 28; k++) {
				uint id = occupiedAt(occ, v, f) ? voxelAt(grid, v, f) : 0;
				// (the eye in water or lava: fluid seen through, as the game draws it from inside)
				if (id != 0 && f.status.z != 0 && (pal[id].flags & 2)) id = 0;
				if (id != 0) {
					Pal e = pal[id];
					float3 hp = ro + rd * tIn;
					float3 n = float3(0);
					n[axis] = -float(stepv[axis]);
					float tHit = tIn;
					bool hit = true;
					float2 uv;
					if (e.flags & 1) {
						// crossed plant: the two diagonal planes, alpha-tested
						hit = false;
						float3 o = ro - float3(v);
						for (int q = 0; q < 2 && !hit; q++) {
							// plane x - z = 0 (q 0) or x + z = 1 (q 1)
							float3 pn = q == 0 ? float3(1, 0, -1) : float3(1, 0, 1);
							float pd = q == 0 ? 0.0f : 1.0f;
							float den = dot(pn, rd);
							if (abs(den) < 1e-6f) continue;
							float tq = (pd - dot(pn, o)) / den;
							float3 lp = o + rd * tq;
							if (tq < tIn - 1e-4f || any(lp < 0.0f) || any(lp > 1.0f)) continue;
							// (1x1 textures: the plant's average colour over the whole quad)
							hit = true;
							tHit = tq;
							color = rgb(e.sideAvg) * rgb(e.sideTint) * 0.85f * brightness(lightAt(light, v, f), f);
						}
						if (hit) {
							hitT = tHit;
							done = true;
							break;
						}
					} else {
						if (e.flags & 4) {
							// a shape smaller than the cube: its box within the voxel
							float3 bl = float3(e.box & 31, (e.box >> 5) & 31, (e.box >> 10) & 31) / 16.0f;
							float3 bh = float3((e.box >> 15) & 31, (e.box >> 20) & 31, (e.box >> 25) & 31) / 16.0f;
							float3 o = ro - float3(v);
							float3 a0 = (bl - o) * inv, a1 = (bh - o) * inv;
							float3 an = min(a0, a1), af = max(a0, a1);
							float te = max(max(an.x, an.y), an.z), tx = min(min(af.x, af.y), af.z);
							if (te > tx || tx < 0) hit = false;
							else {
								tHit = max(te, tIn);
								int ax = an.x >= an.y && an.x >= an.z ? 0 : an.y >= an.z ? 1 : 2;
								n = float3(0);
								n[ax] = -float(stepv[ax]);
								hp = ro + rd * tHit;
							}
						}
						if (hit) {
							float3 fr = fract(hp + n * 1e-3f);
							if (n.y != 0) uv = fr.xz;
							else if (n.x != 0) uv = float2(fr.z, 1.0f - fr.y);
							else uv = float2(fr.x, 1.0f - fr.y);
							bool topFace = n.y != 0;
							// 1x1 textures (the record's rules allow them): each face its texture's average colour, tinted
							float3 c = rgb(topFace ? e.topAvg : e.sideAvg) * rgb(topFace ? e.topTint : e.sideTint);
							float shade = n.y > 0 ? 1.0f : n.y < 0 ? 0.5f : n.x != 0 ? 0.6f : 0.8f;
							// (a full cube's face is lit by the block it faces; a smaller shape or a fluid by its own voxel)
							int3 lv = (e.flags & 6) ? v : v + int3(n);
							color = c * shade * brightness(lightAt(light, lv, f), f);
							if (f.target.w != 0 && all(v == f.target.xyz)) {
								// the targeted block: vanilla's dark outline along its edges, and darker as it breaks
								float2 e2 = min(uv, 1.0f - uv);
								if (min(e2.x, e2.y) < 0.035f) color *= 0.3f;
								color *= 1.0f - 0.055f * float(max(f.target.w - 1, 0));
							}
							hitT = tHit;
							done = true;
							break;
						}
					}
				}
				// next voxel
				if (tMax.x < tMax.y && tMax.x < tMax.z) { tIn = tMax.x; v.x += stepv.x; tMax.x += tDelta.x; axis = 0; }
				else if (tMax.y < tMax.z) { tIn = tMax.y; v.y += stepv.y; tMax.y += tDelta.y; axis = 1; }
				else { tIn = tMax.z; v.z += stepv.z; tMax.z += tDelta.z; axis = 2; }
				if (any(v < 0) || v.x >= W || v.y >= H || v.z >= W) { t = tExit; break; }
				if (any((v >> 3) != b)) { t = tIn; break; }
			}
		}
	}
	// entities: boxes, only those whose screen rectangle holds this pixel
	for (uint i = 0; i < f.size.z; i++) {
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
	// the HUD text: the F3 lines (top left) and the latest chat (bottom left)
	uint scale = uint(f.misc.y);
	color = textPixel(gid, int2(2 * scale), 0, f.size.w, scale, text, font, color);
	if (f.chat.x > 0)
		color = textPixel(gid, int2(2 * scale, int(f.size.y) - int((24 + 10 * f.chat.x) * scale)), 8, f.chat.x, scale, text, font, color);
	// the HUD, the most basic way: the hotbar's nine slots (each its item's colour, the selected one outlined), health and food
	{
		int slot = int(10 * scale), barW = slot * 9;
		int x0 = int(f.size.x / 2) - barW / 2, y0 = int(f.size.y) - slot - int(2 * scale);
		int px = int(gid.x) - x0, py = int(gid.y) - y0;
		if (px >= 0 && px < barW && py >= 0 && py < slot) {
			uint k = uint(px / slot);
			int lx = px % slot;
			uint item = f.hud[k >> 2][k & 3];
			bool edge = lx < int(scale) || lx >= slot - int(scale) || py < int(scale) || py >= slot - int(scale);
			color = edge ? (k == f.hud[2].y ? float3(1) : float3(0.25f)) : item != 0 ? rgb(item) : color * 0.5f;
		}
		// the XP bar just above the hotbar
		if (py == -int(scale) && px >= 0 && px < barW) color = px < barW * int(f.status.w) / 1000 ? float3(0.5f, 1.0f, 0.2f) : float3(0.15f);
		// air (underwater) above the food bar
		int ay = y0 - int(7 * scale), bh = int(2 * scale);
		if (f.status.x != 255 && int(gid.y) >= ay && int(gid.y) < ay + bh) {
			int halfW = barW / 2 - int(2 * scale), qx = px - (barW - halfW);
			if (qx >= 0 && qx < halfW) color = qx >= halfW - halfW * int(f.status.x) / 20 ? float3(0.6f, 0.8f, 1.0f) : float3(0.2f);
		}
		// health (left) and food (right) above the hotbar: a bar each, 0..20
		int by = y0 - int(4 * scale);
		if (int(gid.y) >= by && int(gid.y) < by + bh) {
			int halfW = barW / 2 - int(2 * scale);
			if (px >= 0 && px < halfW) color = px < halfW * int(f.hud[2].z) / 20 ? float3(0.85f, 0.1f, 0.1f) : float3(0.2f);
			int qx = px - (barW - halfW);
			if (qx >= 0 && qx < halfW) color = qx >= halfW - halfW * int(f.hud[2].w) / 20 ? float3(0.75f, 0.5f, 0.2f) : float3(0.2f);
		}
	}
	// the crosshair (the game stays playable: you can see what you aim at)
	int2 cc = int2(gid) - int2(f.size.xy / 2);
	int arm = int(4 * scale), thick = int(max(1u, scale / 2));
	if ((abs(cc.x) <= arm && abs(cc.y) < thick) || (abs(cc.y) <= arm && abs(cc.x) < thick)) color = 1.0f - color;
	outColor.write(float4(color, 1), gid);
	outDepth.write(float4(hitT), gid);
	// frame interpolation, in the same dispatch: the frame halfway between the last two (one frame behind this one)
	if (f.flags.x != 0) outMid.write(float4(interpolated(gid, f, colorA, depthA, colorB, depthB), 1), gid);
}

// Frame interpolation: the frame halfway between the last two (a the newer, b the older), by reprojection. Each pixel's ray
// from the in-between camera takes frame a's depth there as its distance; the point it reaches is looked up in both frames,
// and the frame whose own depth agrees with it (it saw that point, nothing in front) gives the colour (both agreeing: their
// mean).
static float3 interpolated(uint2 gid, constant Frame &f, texture2d<float> colorA, texture2d<float> depthA, texture2d<float> colorB,
	texture2d<float> depthB) {
	float2 size = float2(f.size.xy);
	float2 ndc = (float2(gid) + 0.5f) / size * 2.0f - 1.0f;
	ndc.y = -ndc.y;
	float d = depthA.read(gid).r;
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
		uint2 px = uint2(min(uv * size, size - 1.0f));  // (a 32-bit float texture isn't filterable everywhere: nearest)
		float dd = (k == 0 ? depthA : depthB).read(px).r;
		float dist = length(v);
		float w = d >= 1e8f || abs(dd - dist) < max(0.5f, dist * 0.02f) ? 1.0f : 0.05f;
		acc += (k == 0 ? colorA : colorB).read(px).rgb * w;
		wsum += w;
	}
	return wsum > 0 ? acc / wsum : colorA.read(gid).rgb;
}
