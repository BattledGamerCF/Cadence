// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
// The in-engine recorder (-Dcadence.rec, dev.cadence.metal.rec.Recorder, native/rec.m): one dispatch per recorded frame turns the
// finished frame (GUI included) into a downscaled 4:2:0 picture (NV12: a luma plane, then interleaved Cb Cr; BT.709, limited
// range, the hardware H.264 encoder's own format) in a shared buffer, which a writer thread pipes into the encoder. One
// thread per 2 x 2 output block: four pixels of luma and one Cb Cr pair.
#include <metal_stdlib>
using namespace metal;

struct RecParams {
	uint outW, outH;        // output size (even)
	uint rx, ry, rw, rh;    // where the picture sits in the output (even); outside it is black
	uint tapsX, tapsY;      // bilinear taps per output pixel and axis, about one per source pixel it covers
	uint uvOffset, pad2;    // byte offset of the interleaved Cb Cr plane
	uint pad0, pad1;
	float4 crop;            // the source window, normalized top-down: x0, y0, w, h (0, 0, 1, 1: the whole frame; -Dcadence.rec.crop)
};

// The source is stored bottom-up (GL's row order: row 0 is the picture's bottom row), the output top-down.
static float3 pixel(texture2d<float, access::sample> src, constant RecParams &p, uint x, uint y) {
	constexpr sampler s(coord::normalized, address::clamp_to_edge, filter::linear);
	float du = p.crop.z / float(p.rw), dv = p.crop.w / float(p.rh);
	float u0 = p.crop.x + (float(x - p.rx) + 0.5) * du, v0 = 1.0 - (p.crop.y + (float(y - p.ry) + 0.5) * dv);
	float3 sum = 0.0;
	for (uint j = 0; j < p.tapsY; j++) {
		float v = v0 + ((float(j) + 0.5) / float(p.tapsY) - 0.5) * dv;
		for (uint i = 0; i < p.tapsX; i++) {
			float u = u0 + ((float(i) + 0.5) / float(p.tapsX) - 0.5) * du;
			sum += src.sample(s, float2(u, v)).rgb;
		}
	}
	return saturate(sum / float(p.tapsX * p.tapsY));
}

static uchar q(float v) {
	return uchar(clamp(v + 0.5, 0.0, 255.0));
}

kernel void rec_yuv(texture2d<float, access::sample> src [[texture(0)]], device uchar *out [[buffer(0)]], constant RecParams &p [[buffer(1)]],
	uint2 gid [[thread_position_in_grid]]) {
	uint bx = gid.x * 2, by = gid.y * 2;
	if (bx >= p.outW || by >= p.outH) return;
	bool inside = bx >= p.rx && bx < p.rx + p.rw && by >= p.ry && by < p.ry + p.rh;
	float3 c[4];
	for (uint k = 0; k < 4; k++) c[k] = inside ? pixel(src, p, bx + (k & 1), by + (k >> 1)) : float3(0.0);
	// BT.709: Y' = 0.2126 R' + 0.7152 G' + 0.0722 B', limited range (Y 16-235, chroma 16-240)
	const float3 kY = float3(0.2126, 0.7152, 0.0722);
	for (uint k = 0; k < 4; k++) out[(by + (k >> 1)) * p.outW + bx + (k & 1)] = q(16.0 + 219.0 * dot(c[k], kY));
	float3 m = (c[0] + c[1] + c[2] + c[3]) * 0.25;
	float y = dot(m, kY);
	uint ci = p.uvOffset + (by >> 1) * p.outW + bx;
	out[ci] = q(128.0 + 224.0 * (m.b - y) / 1.8556);
	out[ci + 1] = q(128.0 + 224.0 * (m.r - y) / 1.5748);
}

// The same picture written into an IOSurface-backed NV12 pixel buffer's two planes (Y: R8, CbCr: RG8), which VideoToolbox
// encodes in place (-Dcadence.rec.codec=vt: no copies).
kernel void rec_nv12_tex(texture2d<float, access::sample> src [[texture(0)]], texture2d<float, access::write> yTex [[texture(1)]],
	texture2d<float, access::write> uvTex [[texture(2)]], constant RecParams &p [[buffer(1)]], uint2 gid [[thread_position_in_grid]]) {
	uint bx = gid.x * 2, by = gid.y * 2;
	if (bx >= p.outW || by >= p.outH) return;
	bool inside = bx >= p.rx && bx < p.rx + p.rw && by >= p.ry && by < p.ry + p.rh;
	float3 c[4];
	for (uint k = 0; k < 4; k++) c[k] = inside ? pixel(src, p, bx + (k & 1), by + (k >> 1)) : float3(0.0);
	const float3 kY = float3(0.2126, 0.7152, 0.0722);
	for (uint k = 0; k < 4; k++) yTex.write(float4(float(q(16.0 + 219.0 * dot(c[k], kY))) / 255.0), uint2(bx + (k & 1), by + (k >> 1)));
	float3 m = (c[0] + c[1] + c[2] + c[3]) * 0.25;
	float y = dot(m, kY);
	uvTex.write(float4(float(q(128.0 + 224.0 * (m.b - y) / 1.8556)) / 255.0, float(q(128.0 + 224.0 * (m.r - y) / 1.5748)) / 255.0, 0.0, 0.0), gid);
}
