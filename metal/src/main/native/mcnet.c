// Cadence: the network path for remote servers (dev.cadence.metal.net): zlib decompression with the system's libz and
// AES-128-CFB8 decryption with the ARMv8 cryptography instructions. One handle per connection, used by one thread at a time.
#include <arm_neon.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

// ---- decompression: the zlib format Minecraft's packets use (Java's Inflater, nowrap false) ----

void *mc_net_inflater_new(void) {
	z_stream *z = calloc(1, sizeof(z_stream));
	if (z && inflateInit(z) != Z_OK) { free(z); return NULL; }
	return z;
}

/**
 * Inflates one packet's payload into dst (cap bytes, the size the packet declares). Returns the bytes produced (as Java's
 * Inflater, a payload that would decompress to more than cap stops at cap), or -1 for malformed data.
 */
long mc_net_inflate(void *h, const void *src, long n, void *dst, long cap) {
	z_stream *z = h;
	inflateReset(z);
	z->next_in = (Bytef *) src;
	z->avail_in = (uInt) n;
	z->next_out = dst;
	z->avail_out = (uInt) cap;
	int r = inflate(z, Z_FINISH);
	if (r == Z_DATA_ERROR || r == Z_STREAM_ERROR || r == Z_MEM_ERROR || r == Z_NEED_DICT) return -1;
	return (long) (cap - z->avail_out);
}

void mc_net_inflater_free(void *h) {
	if (!h) return;
	inflateEnd(h);
	free(h);
}

// ---- decryption: AES-128-CFB8 with the key as IV (Minecraft's Crypt.getCipher) ----
// Each plaintext byte is a ciphertext byte XOR the first byte of AES(the 16 ciphertext bytes before it). Decrypting, all of
// those inputs are already known, so 8 bytes' AES run interleaved (encrypting can't: each input needs the byte just made).

static const uint8_t sbox[256] = {
0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16};

typedef struct {
	uint8x16_t rk[11];
	uint8_t sr[16];  // the last 16 ciphertext bytes (the IV before any)
} Cfb8;

void *mc_net_cfb8_new(const uint8_t *key) {
	Cfb8 *c = calloc(1, sizeof(Cfb8));
	if (!c) return NULL;
	uint8_t w[176];
	memcpy(w, key, 16);
	uint8_t rcon = 1;
	for (int i = 16; i < 176; i += 4) {
		uint8_t t[4] = {w[i - 4], w[i - 3], w[i - 2], w[i - 1]};
		if (i % 16 == 0) {
			uint8_t x = t[0];
			t[0] = sbox[t[1]] ^ rcon; t[1] = sbox[t[2]]; t[2] = sbox[t[3]]; t[3] = sbox[x];
			rcon = (uint8_t) ((rcon << 1) ^ ((rcon & 0x80) ? 0x1b : 0));
		}
		for (int j = 0; j < 4; j++) w[i + j] = w[i - 16 + j] ^ t[j];
	}
	for (int r = 0; r < 11; r++) c->rk[r] = vld1q_u8(w + 16 * r);
	memcpy(c->sr, key, 16);
	return c;
}

static inline uint8_t aesFirst(const Cfb8 *c, uint8x16_t b) {
	for (int r = 0; r < 9; r++) b = vaesmcq_u8(vaeseq_u8(b, c->rk[r]));
	return vgetq_lane_u8(veorq_u8(vaeseq_u8(b, c->rk[9]), c->rk[10]), 0);
}

/** Decrypts n bytes from in to out (which must not overlap), continuing the stream. */
void mc_net_cfb8_decrypt(void *h, const uint8_t *in, uint8_t *out, long n) {
	Cfb8 *c = h;
	if (n <= 0) return;
	// the first 16 bytes' inputs straddle the register and the new ciphertext
	uint8_t head[32];
	long pre = n < 16 ? n : 16;
	memcpy(head, c->sr, 16);
	memcpy(head + 16, in, (size_t) pre);
	for (long k = 0; k < pre; k++) out[k] = in[k] ^ aesFirst(c, vld1q_u8(head + k));
	long i = 16;
	uint8_t ks[8];
	for (; i + 8 <= n; i += 8) {
		const uint8_t *p = in + i - 16;
		uint8x16_t b0 = vld1q_u8(p), b1 = vld1q_u8(p + 1), b2 = vld1q_u8(p + 2), b3 = vld1q_u8(p + 3),
			b4 = vld1q_u8(p + 4), b5 = vld1q_u8(p + 5), b6 = vld1q_u8(p + 6), b7 = vld1q_u8(p + 7);
		for (int r = 0; r < 9; r++) {
			uint8x16_t k = c->rk[r];
			b0 = vaesmcq_u8(vaeseq_u8(b0, k)); b1 = vaesmcq_u8(vaeseq_u8(b1, k));
			b2 = vaesmcq_u8(vaeseq_u8(b2, k)); b3 = vaesmcq_u8(vaeseq_u8(b3, k));
			b4 = vaesmcq_u8(vaeseq_u8(b4, k)); b5 = vaesmcq_u8(vaeseq_u8(b5, k));
			b6 = vaesmcq_u8(vaeseq_u8(b6, k)); b7 = vaesmcq_u8(vaeseq_u8(b7, k));
		}
		uint8x16_t k9 = c->rk[9], k10 = c->rk[10];
		ks[0] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b0, k9), k10), 0);
		ks[1] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b1, k9), k10), 0);
		ks[2] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b2, k9), k10), 0);
		ks[3] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b3, k9), k10), 0);
		ks[4] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b4, k9), k10), 0);
		ks[5] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b5, k9), k10), 0);
		ks[6] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b6, k9), k10), 0);
		ks[7] = vgetq_lane_u8(veorq_u8(vaeseq_u8(b7, k9), k10), 0);
		vst1_u8(out + i, veor_u8(vld1_u8(in + i), vld1_u8(ks)));
	}
	for (; i < n; i++) out[i] = in[i] ^ aesFirst(c, vld1q_u8(in + i - 16));
	// the register for the next call: the last 16 ciphertext bytes
	if (n >= 16) memcpy(c->sr, in + n - 16, 16);
	else {
		memmove(c->sr, c->sr + n, (size_t) (16 - n));
		memcpy(c->sr + 16 - n, in, (size_t) n);
	}
}

void mc_net_cfb8_free(void *h) { free(h); }
