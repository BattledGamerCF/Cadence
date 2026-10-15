// Cadence native check: the backend extensions added for the shader loader, run on the real GPU without Minecraft.
//   clang -fobjc-arc -framework Metal -framework Foundation tools/cadence_native_test.m \
//     -I metal/src/main/native -L metal/build/natives/natives -lmcmetal -Wl,-rpath,metal/build/natives/natives -o /tmp/cnt && /tmp/cnt
// 1. mc_library_new_mode compiles a pack-like shader ([[invariant]] position, depth2d + sample_compare) in every mode.
// 2. mc_sampler_new_compare's sampler really compares (less-or-equal) on the GPU: a 4x1 depth texture with known depths is
//    sampled with sample_compare at a fixed reference, and each result is checked against the expected 0 or 1.
#import <Metal/Metal.h>
#include <stdio.h>
#include <string.h>
#include "mcmetal.h"

Ctx *mc_create(char *name, int nameCap, char *err, int errCap);
id<MTLLibrary> mc_library_new_mode(Ctx *ctx, const char *source, int flags, char *err, int errCap);
id<MTLSamplerState> mc_sampler_new_compare(Ctx *ctx, int u, int v, int minF, int magF, int mipF, int aniso, float maxLod, int compare);

static const char *SRC =
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct V { float4 pos [[position, invariant]]; };\n"
	"vertex V vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  V o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
	"}\n"
	"fragment float4 fs(V in [[stage_in]], depth2d<float> shadow [[texture(0)]], sampler cmp [[sampler(0)]],\n"
	"                   constant float &ref [[buffer(0)]]) {\n"
	"  float2 uv = float2((in.pos.x) / 4.0, 0.5);\n"
	"  float lit = shadow.sample_compare(cmp, uv, ref);\n"
	"  return float4(lit, 0, 0, 1);\n"
	"}\n";

int main(void) {
	int failures = 0;
	char name[256], err[4096];
	Ctx *ctx = mc_create(name, sizeof name, err, sizeof err);
	if (!ctx) { printf("FAIL mc_create: %s\n", err); return 1; }
	printf("device: %s\n", name);

	const char *modes[] = {"Safe", "Relaxed", "Fast"};
	id<MTLLibrary> lib = nil;
	for (int m = 0; m < 3; m++) {
		for (int inv = 0; inv < 2; inv++) {
			id<MTLLibrary> l = mc_library_new_mode(ctx, SRC, m | (inv ? 4 : 0), err, sizeof err);
			printf("%s compile %-7s invariance=%d\n", l ? "ok  " : "FAIL", modes[m], inv);
			if (!l) { printf("  %s\n", err); failures++; }
			if (l && m == 0 && inv) lib = l;
		}
	}
	if (!lib) { printf("no library to render with\n"); return 1; }

	// Depth texture 4x1: 0.2, 0.4, 0.6, 0.8. Reference 0.5 with LessEqual: lit = (ref <= depth) -> 0, 0, 1, 1.
	MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:4 height:1 mipmapped:NO];
	dd.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
	dd.storageMode = MTLStorageModePrivate;
	id<MTLTexture> depth = [ctx->device newTextureWithDescriptor:dd];
	// Fill it through a staging buffer + blit (depth textures can't be replaceRegion'd when private).
	float depths[4] = {0.2f, 0.4f, 0.6f, 0.8f};
	id<MTLBuffer> staging = [ctx->device newBufferWithBytes:depths length:sizeof depths options:MTLResourceStorageModeShared];
	MTLTextureDescriptor *cd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:4 height:1 mipmapped:NO];
	cd.usage = MTLTextureUsageRenderTarget;
	cd.storageMode = MTLStorageModeShared;
	id<MTLTexture> color = [ctx->device newTextureWithDescriptor:cd];

	// Nearest filtering so each pixel reads exactly its own texel; clamp to edge (0), compare LessEqual (3).
	id<MTLSamplerState> cmp = mc_sampler_new_compare(ctx, 0, 0, 0, 0, 0, 1, 1000.0f, 3);
	if (!cmp) { printf("FAIL mc_sampler_new_compare returned nil\n"); return 1; }

	MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
	pd.vertexFunction = [lib newFunctionWithName:@"vs"];
	pd.fragmentFunction = [lib newFunctionWithName:@"fs"];
	pd.colorAttachments[0].pixelFormat = MTLPixelFormatR32Float;
	NSError *e = nil;
	id<MTLRenderPipelineState> ps = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!ps) { printf("FAIL pipeline: %s\n", e.localizedDescription.UTF8String); return 1; }

	id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
	id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
	[blit copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:16 sourceBytesPerImage:16 sourceSize:MTLSizeMake(4, 1, 1)
		toTexture:depth destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
	[blit endEncoding];
	MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
	rp.colorAttachments[0].texture = color;
	rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
	rp.colorAttachments[0].storeAction = MTLStoreActionStore;
	id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
	float ref = 0.5f;
	[re setRenderPipelineState:ps];
	[re setFragmentTexture:depth atIndex:0];
	[re setFragmentSamplerState:cmp atIndex:0];
	[re setFragmentBytes:&ref length:sizeof ref atIndex:0];
	[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
	[re endEncoding];
	[cb commit];
	[cb waitUntilCompleted];
	if (cb.error) { printf("FAIL command buffer: %s\n", cb.error.localizedDescription.UTF8String); return 1; }

	float out[4];
	[color getBytes:out bytesPerRow:16 fromRegion:MTLRegionMake2D(0, 0, 4, 1) mipmapLevel:0];
	float expect[4] = {0, 0, 1, 1};
	for (int i = 0; i < 4; i++) {
		int ok = out[i] == expect[i];
		if (!ok) failures++;
		printf("%s compare depth %.1f vs ref 0.5 -> %.0f (expected %.0f)\n", ok ? "ok  " : "FAIL", depths[i], out[i], expect[i]);
	}
	printf(failures ? "\n%d FAILURE(S)\n" : "\nALL PASS\n", failures);
	return failures ? 1 : 0;
}
