// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
// The far terrain's transition probe (-Dcadence.lod.seam=DIR, LodSeam): measures how the frame changes from one frame to
// the next, in the far terrain's region, once per frame at the end of the level (before the GUI). Opt-in instrumentation:
// nothing here runs unless asked for.
//
// Per pixel of frame t: its color and depth become a ring entry (RGB8 + the pixel's zone: near terrain, the hand-off band
// at the render distance, far terrain, sky). The same world point is looked up in frames t-1, t-2 and t-3 (reprojected
// with each frame's camera: depth for terrain, the direction for sky), each compared over a 3 x 3 neighborhood so a
// sub-pixel move of an edge is not a change. A flash is a pixel whose value at t-2 is not near anything frame t-3 had
// there, while t-1 or t is back to what t-3 had: it changed and changed back within 3 frames. A pop (a snap) changes at
// t-2 and stays changed. The frame-to-frame change (t-1 -> t) is counted by size. Counters per frame go to `stats`.
//
// Loops: the 3 x 3 neighborhood and the 3 history frames only (fixed bounds).
#include <metal_stdlib>
using namespace metal;

#define SEAM_STATS 32
#define S_REGION 0
#define S_FLASH 1
#define S_FLASH_SKY 2
#define S_FLASH_HANDOFF 3
#define S_FLASH_FAR 4
#define S_FLASH_NEAR 5
#define S_CHANGE16 6
#define S_CHANGE32 7
#define S_CHANGE64 8
#define S_CHANGE128 9
#define S_MAXCHANGE 10
#define S_POP 11
#define S_FLASH_SX 12
#define S_FLASH_SY 13
#define S_VALID 14
#define S_POP_SKY 15        // pops from sky (a hole filled) at t-2
#define S_POP_TO_SKY 16     // pops to sky (terrain gone, a hole opened)
#define S_FLASH2 17         // flashes that lasted 2 frames (t-2 and t-1 both off)
#define S_FAR_PX 18         // far + hand-off pixels this frame
#define S_SKY_BAND 19       // sky pixels this frame whose t-1 was terrain beyond the near zone (terrain lost)
#define S_SOLID_FLASH 20      // flash pixels whose whole 3 x 3 neighborhood flashed too (seam_blob): areas, not aliasing
#define S_SOLID_FLASH_SKY 21
#define S_SOLID_FLASH_HANDOFF 22
#define S_SOLID_FLASH_FAR 23
#define S_SOLID_POP 24
#define S_SOLID_CHANGE64 25    // pixels whose whole 3 x 3 neighborhood changed by more than 64 (t-1 -> t)
#define S_SOLID_MAXCHANGE 26   // the largest change that covers a whole 3 x 3 block
#define S_SOLID_POP_SKY 27
#define S_FOG 28              // terrain nearer than the hand-off zone's end drawn in the fog's own color (a section fading in)
#define S_FOG_HANDOFF 29      // of those, in the hand-off zone
#define S_N 30

struct SeamFrame {
    float4x4 invVP;          // frame t: clip -> camera-relative world
    float4x4 vp[3];          // frames t-1, t-2, t-3: camera-relative world -> clip
    float4 camDelta[3];      // camera(t) - camera(t-k), blocks
    uint4 dims;              // width, height, ring slot of t, ring size
    uint4 slots;             // ring slots of t-1, t-2, t-3, history frames available (0..3)
    float4 zones;            // x: hand-off zone starts (blocks, cylindrical), y: far zone starts, z: change threshold, w: back threshold
    float4 fog;              // the frame's fog color (rgb)
};

static inline uint seamPack(float4 c, uint zone) {
    uint3 v = uint3(clamp(c.rgb, 0.0, 1.0) * 255.0 + 0.5);
    return v.r | v.g << 8 | v.b << 16 | zone << 24;
}

static inline int seamDiff(uint a, uint b) {
    int3 x = int3(a & 255u, a >> 8 & 255u, a >> 16 & 255u), y = int3(b & 255u, b >> 8 & 255u, b >> 16 & 255u);
    int3 d = abs(x - y);
    return max(d.x, max(d.y, d.z));
}

// The pixel of frame k (1..3) showing what pixel p of frame t shows, or (-1, -1) off screen.
static inline int2 seamReproject(constant SeamFrame& f, int k, float3 rel, bool sky) {
    float4 p = sky ? float4(rel, 0.0) : float4(rel + f.camDelta[k - 1].xyz, 1.0);
    float4 c = f.vp[k - 1] * p;
    if (c.w <= 1e-6) return int2(-1);
    float2 ndc = c.xy / c.w;
    // (the game's textures are stored in GL's orientation: row 0 is the bottom)
    float2 px = float2((ndc.x + 1.0) * 0.5 * float(f.dims.x), (ndc.y + 1.0) * 0.5 * float(f.dims.y));
    if (!(px.x >= 0.0 && px.y >= 0.0 && px.x < float(f.dims.x) && px.y < float(f.dims.y))) return int2(-1);
    return int2(px);
}

static inline uint seamAt(constant SeamFrame& f, device const uint* ring, uint slot, int2 q) {
    q = clamp(q, int2(0), int2(f.dims.xy) - 1);
    return ring[ulong(slot) * f.dims.x * f.dims.y + uint(q.y) * f.dims.x + uint(q.x)];
}

// Smallest difference between v and frame `slot`'s 3 x 3 neighborhood around q.
static inline int seamNear(constant SeamFrame& f, device const uint* ring, uint slot, int2 q, uint v) {
    int best = 255;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) best = min(best, seamDiff(v, seamAt(f, ring, slot, q + int2(dx, dy))));
    }
    return best;
}

kernel void seam_probe(constant SeamFrame& f [[buffer(0)]], device uint* ring [[buffer(1)]], device uchar* marks [[buffer(2)]],
                       device atomic_uint* stats [[buffer(3)]], device uchar* changes [[buffer(4)]], texture2d<float, access::read> color [[texture(0)]],
                       depth2d<float, access::read> depth [[texture(1)]], uint2 gid [[thread_position_in_grid]],
                       uint tid [[thread_index_in_threadgroup]]) {
    threadgroup atomic_uint local[S_N];
    if (tid < S_N) atomic_store_explicit(&local[tid], 0u, memory_order_relaxed);
    threadgroup_barrier(mem_flags::mem_threadgroup);

    bool inside = gid.x < f.dims.x && gid.y < f.dims.y;
    if (inside) {
        int2 p = int2(gid);
        ulong plane = ulong(f.dims.x) * f.dims.y;
        ulong at = uint(p.y) * f.dims.x + uint(p.x);
        float d = depth.read(gid);
        float4 c = color.read(gid);
        // camera-relative position: the game's projection is reversed Z, depth 0 is the far plane (sky)
        float2 ndc = float2((float(p.x) + 0.5) / float(f.dims.x) * 2.0 - 1.0, (float(p.y) + 0.5) / float(f.dims.y) * 2.0 - 1.0);
        bool sky = !(d > 0.0);
        float3 rel;
        if (sky) {
            float4 a = f.invVP * float4(ndc, 1.0, 1.0), b = f.invVP * float4(ndc, 0.5, 1.0);
            rel = normalize(b.xyz / b.w - a.xyz / a.w);
        } else {
            float4 w = f.invVP * float4(ndc, d, 1.0);
            rel = w.xyz / w.w;
        }
        float dist = length(rel.xz);
        uint zone = sky ? 3u : dist < f.zones.x ? 0u : dist < f.zones.y ? 1u : 2u;
        uint v3 = seamPack(c, zone);
        ring[ulong(f.dims.z) * plane + at] = v3;
        marks[ulong(f.dims.z) * plane + at] = 0;
        changes[at] = 0;
        if (zone == 1u || zone == 2u) atomic_fetch_add_explicit(&local[S_FAR_PX], 1u, memory_order_relaxed);
        if (zone <= 1u && seamDiff(v3, seamPack(f.fog, 0u)) <= 12) {
            atomic_fetch_add_explicit(&local[S_FOG], 1u, memory_order_relaxed);
            if (zone == 1u) atomic_fetch_add_explicit(&local[S_FOG_HANDOFF], 1u, memory_order_relaxed);
        }
        if (f.slots.w >= 1u) {
            int2 q1 = seamReproject(f, 1, rel, sky);
            if (q1.x >= 0) {
                uint v2 = seamAt(f, ring, f.slots.x, q1);
                uint z2 = v2 >> 24;
                bool region = zone == 1u || zone == 2u || z2 == 1u || z2 == 2u;
                if (region) {
                    int change = seamNear(f, ring, f.slots.x, q1, v3);
                    changes[at] = uchar(min(change, 255));
                    if (change > 16) atomic_fetch_add_explicit(&local[S_CHANGE16], 1u, memory_order_relaxed);
                    if (change > 32) atomic_fetch_add_explicit(&local[S_CHANGE32], 1u, memory_order_relaxed);
                    if (change > 64) atomic_fetch_add_explicit(&local[S_CHANGE64], 1u, memory_order_relaxed);
                    if (change > 128) atomic_fetch_add_explicit(&local[S_CHANGE128], 1u, memory_order_relaxed);
                    atomic_fetch_max_explicit(&local[S_MAXCHANGE], uint(change), memory_order_relaxed);
                    if (zone == 3u && (z2 == 1u || z2 == 2u) && change > int(f.zones.z)) atomic_fetch_add_explicit(&local[S_SKY_BAND], 1u, memory_order_relaxed);
                }
                if (f.slots.w >= 3u) {
                    int2 q2 = seamReproject(f, 2, rel, sky), q3 = seamReproject(f, 3, rel, sky);
                    if (q2.x >= 0 && q3.x >= 0) {
                        uint v1 = seamAt(f, ring, f.slots.y, q2), v0 = seamAt(f, ring, f.slots.z, q3);
                        uint z1 = v1 >> 24, z0 = v0 >> 24;
                        bool region3 = region || z1 == 1u || z1 == 2u || z0 == 1u || z0 == 2u;
                        if (region3) {
                            atomic_fetch_add_explicit(&local[S_REGION], 1u, memory_order_relaxed);
                            int T = int(f.zones.z), B = int(f.zones.w);
                            bool changed = seamNear(f, ring, f.slots.z, q3, v1) > T;
                            if (changed) {
                                bool back2 = seamNear(f, ring, f.slots.z, q3, v2) <= B;
                                bool back3 = seamNear(f, ring, f.slots.z, q3, v3) <= B;
                                if (back2 || back3) {
                                    atomic_fetch_add_explicit(&local[S_FLASH], 1u, memory_order_relaxed);
                                    atomic_fetch_add_explicit(&local[z1 == 3u ? S_FLASH_SKY : z1 == 1u ? S_FLASH_HANDOFF : z1 == 2u ? S_FLASH_FAR : S_FLASH_NEAR], 1u,
                                                              memory_order_relaxed);
                                    if (!back2) atomic_fetch_add_explicit(&local[S_FLASH2], 1u, memory_order_relaxed);
                                    atomic_fetch_add_explicit(&local[S_FLASH_SX], uint(q2.x) >> 4, memory_order_relaxed);
                                    atomic_fetch_add_explicit(&local[S_FLASH_SY], uint(q2.y) >> 4, memory_order_relaxed);
                                    marks[ulong(f.slots.y) * plane + uint(q2.y) * f.dims.x + uint(q2.x)] = 1;
                                } else if (seamNear(f, ring, f.slots.x, q1, v3) <= B && seamNear(f, ring, f.slots.y, q2, v2) <= B) {
                                    // changed at t-2 and held through t: a snap
                                    atomic_fetch_add_explicit(&local[S_POP], 1u, memory_order_relaxed);
                                    if (z0 == 3u) atomic_fetch_add_explicit(&local[S_POP_SKY], 1u, memory_order_relaxed);
                                    if (z1 == 3u && z0 != 3u) atomic_fetch_add_explicit(&local[S_POP_TO_SKY], 1u, memory_order_relaxed);
                                    marks[ulong(f.slots.y) * plane + uint(q2.y) * f.dims.x + uint(q2.x)] = 2;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid < S_N) {
        uint v = atomic_load_explicit(&local[tid], memory_order_relaxed);
        if (tid == S_MAXCHANGE) atomic_fetch_max_explicit(&stats[f.dims.z * SEAM_STATS + tid], v, memory_order_relaxed);
        else if (v != 0u) atomic_fetch_add_explicit(&stats[f.dims.z * SEAM_STATS + tid], v, memory_order_relaxed);
    }
}

// After seam_probe (same frame): the flash and pop marks it left in frame t-2, and frame t's changes, counted only where a
// whole 3 x 3 block has them. Moving thin features (a pixel-wide edge sliding under the pixel grid) alias into single-pixel
// lines of "flashes"; what streaming does (a cell, a tile, a chunk) is areas.
kernel void seam_blob(constant SeamFrame& f [[buffer(0)]], device const uint* ring [[buffer(1)]], device const uchar* marks [[buffer(2)]],
                      device atomic_uint* stats [[buffer(3)]], device const uchar* changes [[buffer(4)]], uint2 gid [[thread_position_in_grid]],
                      uint tid [[thread_index_in_threadgroup]]) {
    threadgroup atomic_uint local[8];
    if (tid < 8u) atomic_store_explicit(&local[tid], 0u, memory_order_relaxed);
    threadgroup_barrier(mem_flags::mem_threadgroup);
    int W = int(f.dims.x), H = int(f.dims.y);
    if (f.slots.w >= 3u && int(gid.x) >= 1 && int(gid.y) >= 1 && int(gid.x) < W - 1 && int(gid.y) < H - 1) {
        ulong plane = ulong(W) * ulong(H);
        ulong base = ulong(f.slots.y) * plane;
        int x = int(gid.x), y = int(gid.y);
        uchar m = marks[base + ulong(y) * ulong(W) + ulong(x)];
        if (m != 0) {
            bool solid = true;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) solid = solid && marks[base + ulong(y + dy) * ulong(W) + ulong(x + dx)] == m;
            }
            if (solid) {
                uint zone = ring[base + ulong(y) * ulong(W) + ulong(x)] >> 24;
                if (m == 1) {
                    atomic_fetch_add_explicit(&local[0], 1u, memory_order_relaxed);
                    if (zone == 3u) atomic_fetch_add_explicit(&local[1], 1u, memory_order_relaxed);
                    if (zone == 1u) atomic_fetch_add_explicit(&local[2], 1u, memory_order_relaxed);
                    if (zone == 2u) atomic_fetch_add_explicit(&local[3], 1u, memory_order_relaxed);
                } else {
                    atomic_fetch_add_explicit(&local[4], 1u, memory_order_relaxed);
                    // (a pop from sky: frame t-3 was sky there; t-2's zone is what it became)
                    if (zone != 3u && (ring[ulong(f.slots.z) * plane + ulong(y) * ulong(W) + ulong(x)] >> 24) == 3u)
                        atomic_fetch_add_explicit(&local[7], 1u, memory_order_relaxed);
                }
            }
        }
        uint c = 255u;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) c = min(c, uint(changes[ulong(y + dy) * ulong(W) + ulong(x + dx)]));
        }
        if (c > 64u) atomic_fetch_add_explicit(&local[5], 1u, memory_order_relaxed);
        atomic_fetch_max_explicit(&local[6], c, memory_order_relaxed);
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid < 8u) {
        uint v = atomic_load_explicit(&local[tid], memory_order_relaxed);
        const uint slot[8] = {S_SOLID_FLASH, S_SOLID_FLASH_SKY, S_SOLID_FLASH_HANDOFF, S_SOLID_FLASH_FAR, S_SOLID_POP, S_SOLID_CHANGE64, S_SOLID_MAXCHANGE,
                              S_SOLID_POP_SKY};
        if (tid == 6u) atomic_fetch_max_explicit(&stats[f.dims.z * SEAM_STATS + slot[tid]], v, memory_order_relaxed);
        else if (v != 0u) atomic_fetch_add_explicit(&stats[f.dims.z * SEAM_STATS + slot[tid]], v, memory_order_relaxed);
    }
}

// -Dcadence.lod.publish=gpu: staged tile words written into the clipmap by the GPU, in the pre command buffer right before
// the frame's cull. The clipmap's word buffers are hazard-tracked then, so Metal orders this after every earlier frame's
// reads and before this frame's: frames in flight keep the words their meshes were built from. One thread per cell.
struct PubFrame {
    uint logN, levelWords, runsOffset, count;
};

struct PubTile {
    int level, tx, tz;
    uint flags;              // 1: crowns (crown + runs words), 2: texture words, 4: plant words
    uint src;                // the tile's first word in staging: g, c, then cr, runs (1), tw (2), plant A, plant B (4), 4096 each
    uint pad0, pad1, pad2;
};

kernel void seam_publish(constant PubFrame& f [[buffer(0)]], device const PubTile* tiles [[buffer(1)]], device const uint* staging [[buffer(2)]],
                         device uint* geom [[buffer(3)]], device uint* color [[buffer(4)]], device uint* crown [[buffer(5)]], device uint* tex [[buffer(6)]],
                         uint2 gid [[thread_position_in_grid]]) {
    if (gid.x >= 4096u || gid.y >= f.count) return;
    PubTile t = tiles[gid.y];
    uint m = (1u << f.logN) - 1u;
    uint cx = uint(t.tx * 64 + int(gid.x & 63u)) & m, cz = uint(t.tz * 64 + int(gid.x >> 6)) & m;
    uint off = uint(t.level) * f.levelWords + (cz << f.logN | cx);
    uint s = t.src + gid.x, k = 2u;
    geom[off] = staging[s];
    color[off] = staging[s + 4096u];
    if ((t.flags & 1u) != 0u) {
        crown[off] = staging[s + 4096u * k];
        crown[f.runsOffset + off] = staging[s + 4096u * (k + 1u)];
        k += 2u;
    }
    if ((t.flags & 2u) != 0u) {
        tex[off] = staging[s + 4096u * k];
        k += 1u;
    }
    if ((t.flags & 4u) != 0u) {
        tex[f.levelWords + off] = staging[s + 4096u * k];
        tex[2u * f.levelWords + off] = staging[s + 4096u * (k + 1u)];
    }
}

// -Dcadence.lod.taa=ALPHA (LodTaa): a temporal filter for the far terrain's pixels only, against shimmer (thin far features
// blinking as they slide under the pixel grid). Per pixel of the far band's rows: real terrain and sky pass through; a far
// pixel (depth past the hand-off zone) is reprojected into the previous frame with its depth, the previous result there
// (history) is clamped to this frame's 3 x 3 neighbourhood and blended in (weight 1 - ALPHA). The result goes to `out`,
// (full size, written over the band's rows) which is copied into the frame and is the next frame's history. Loops: the 3 x 3 neighbourhood only.
struct TaaFrame {
    float4x4 invVP;          // this frame: clip -> camera-relative world
    float4x4 prevVP;         // the previous frame: camera-relative world (its camera) -> clip
    float4 camDelta;         // camera(now) - camera(previous), blocks; w: 1 when the history is valid
    uint4 dims;              // width, height, first row, rows
    float4 params;           // x: far zone start (blocks, cylindrical), y: alpha (this frame's weight)
};

kernel void seam_taa(constant TaaFrame& f [[buffer(0)]], texture2d<float, access::read> color [[texture(0)]],
                     depth2d<float, access::read> depth [[texture(1)]], texture2d<float> hist [[texture(2)]],
                     texture2d<float, access::write> out [[texture(3)]], uint2 gid [[thread_position_in_grid]]) {
    uint x = gid.x, y = gid.y + f.dims.z;
    if (x >= f.dims.x || gid.y >= f.dims.w || y >= f.dims.y) return;
    float4 c = color.read(uint2(x, y));
    float d = depth.read(uint2(x, y));
    bool far = false;
    float2 uvPrev = float2(-1.0);
    if (d > 0.0 && f.camDelta.w > 0.0) {
        float2 ndc = float2((float(x) + 0.5) / float(f.dims.x) * 2.0 - 1.0, (float(y) + 0.5) / float(f.dims.y) * 2.0 - 1.0);
        float4 w = f.invVP * float4(ndc, d, 1.0);
        float3 rel = w.xyz / w.w;
        if (length(rel.xz) >= f.params.x) {
            float4 pc = f.prevVP * float4(rel + f.camDelta.xyz, 1.0);
            if (pc.w > 1e-6) {
                float2 pn = pc.xy / pc.w;
                uvPrev = float2((pn.x + 1.0) * 0.5, (pn.y + 1.0) * 0.5);
                far = all(uvPrev > 0.0) && all(uvPrev < 1.0);
            }
        }
    }
    if (!far) {
        out.write(c, uint2(x, y));
        return;
    }
    float4 lo = c, hi = c;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int2 q = clamp(int2(int(x) + dx, int(y) + dy), int2(0), int2(int(f.dims.x) - 1, int(f.dims.y) - 1));
            float4 n = color.read(uint2(q));
            lo = min(lo, n);
            hi = max(hi, n);
        }
    }
    constexpr sampler lin(filter::linear, address::clamp_to_edge);
    // (history: the previous frame's result, full size; rows outside last frame's band hold older results, which the clamp bounds)
    float4 h = hist.sample(lin, uvPrev);
    // (the box grown by params.z of its own size: a sliver missing from this frame's 3 x 3 for a frame stays in reach)
    float4 grow = (hi - lo) * f.params.z;
    h = clamp(h, lo - grow, hi + grow);
    out.write(mix(h, c, f.params.y), uint2(x, y));
}

// ---- -Dcadence.lod.taaTile=ALPHA: the far filter as a tile function inside the open level encoder (no store/reload) ----
//
// The far terrain's mesh fragments (columns.metal under SEAM_TAA_TILE) leave a code for their distance in the colour's alpha
// (1..254: log2 of distance/256 in steps of 8/253, 256 to 65536 blocks; 255: not far terrain). Per pixel of the tile: a far
// pixel's ray direction times its decoded distance is reprojected into the previous frame, the history there is clamped to
// the pixel's 3 x 3 neighbourhood within the tile (grown by params.y of its size) and blended at weight params.x; the result
// goes back into the tile (alpha 1) and into the next frame's history. Loops: the 3 x 3 neighbourhood only.
struct SeamTileFrag {
    half4 color [[color(0)]];
};

struct SeamTileFrame {
    // The reprojection, affine in the pixel (gid): this frame's view ray d = d0 + x dx + y dy (camera-relative, unnormalised),
    // the previous frame's clip of that ray k = k0 + x kx + y ky, folded to (u w, v w, w) of its history texture, and c the
    // previous clip of the camera's own move (same fold). A far pixel at distance D: q = D / |d| k + c, uv = q.xy / q.z.
    float4 d0, dx, dy;
    float4 k0, kx, ky;
    float4 c;                // w: 1 when the history is valid
    float4 params;           // x: alpha, y: box growth, z: mode (1: empty dispatch, 2: filter), w: frame number mod 64 (dither)
    uint4 dims;              // width, height, z: debug bits (1: no history sample, 2: no history write, 4: no neighbourhood,
                             // 8: no reprojection, 16: return right after reading the pixel, 64: no flat
                             // skip, 128: no dither)
};

kernel void seam_tile(imageblock<SeamTileFrag, imageblock_layout_implicit> img, constant SeamTileFrame& f [[buffer(0)]],
                      texture2d<half> hist [[texture(0)]], texture2d<float, access::write> next [[texture(1)]],
                      ushort2 lid [[thread_position_in_threadgroup]], ushort2 tsize [[threads_per_threadgroup]],
                      uint2 gid [[thread_position_in_grid]]) {
    if (f.params.z < 1.5) return;
    half4 c = img.read(lid).color;
    uint dbg = f.dims.z;
    if ((dbg & 16u) != 0u) return;
    half4 lo = c, hi = c;
    bool far = c.a < 0.999h;
    if (far && (dbg & 4u) == 0u) {
        // (a separable box over simd lanes measured no cheaper on the mini: the nine reads stay)
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int2 q = clamp(int2(lid) + int2(dx, dy), int2(0), int2(tsize) - 1);
                half4 n = img.read(ushort2(q)).color;
                lo = min(lo, n);
                hi = max(hi, n);
            }
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup_imageblock);
    if (!far || gid.x >= f.dims.x || gid.y >= f.dims.y) return;
    half3 res = c.rgb;
    // a flat box clamps the history to this pixel's own colour: the result is the colour itself, no need to fetch
    bool flat = all(hi.rgb == lo.rgb) && (dbg & 64u) == 0u && (dbg & 4u) == 0u;
    if (f.c.w > 0.0 && (dbg & 8u) == 0u && !flat) {
        float code = float(c.a) * 255.0;
        float dist = 256.0 * exp2((max(code, 1.0) - 1.0) * 8.0 / 253.0);
        float2 g = float2(gid);
        float3 d = f.d0.xyz + g.x * f.dx.xyz + g.y * f.dy.xyz;
        float3 k = f.k0.xyz + g.x * f.kx.xyz + g.y * f.ky.xyz;
        float3 q = (dist * rsqrt(dot(d, d))) * k + f.c.xyz;
        if (q.z > 1e-6) {
            float2 uv = q.xy / q.z;
            if (all(uv > 0.0) && all(uv < 1.0) && (dbg & 1u) == 0u) {
                constexpr sampler lin(filter::linear, address::clamp_to_edge);
                half3 h = hist.sample(lin, uv).rgb;
                half3 grow = (hi.rgb - lo.rgb) * half(f.params.y);
                h = clamp(h, lo.rgb - grow, hi.rgb + grow);
                res = mix(h, c.rgb, half(f.params.x));
            }
        }
    }
    SeamTileFrag o;
    o.color = half4(res, 1.0h);
    img.write(o, lid);
    if ((dbg & 2u) == 0u) {
        // the history in 8 bits with a dithered rounding (interleaved gradient noise, moving each frame): at alpha 0.1 a
        // plain rounding stops converging 5 steps short (0.9 h + 0.1 c rounds back to h), the dither keeps the mean moving
        float3 hv = float3(res);
        if ((dbg & 128u) == 0u) {
            float2 pg = float2(gid) + 5.588238 * f.params.w;
            hv += (fract(52.9829189 * fract(dot(pg, float2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
        }
        next.write(float4(hv, 1.0), gid);
    }
}
