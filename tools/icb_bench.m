// Cadence bench: the CPU cost of vanilla-style chunk draws on Metal — N indirect indexed draws issued one by one
// (Cadence's mc_r_draw_indexed_indirect loop) vs one indirect command buffer encoded on the GPU (compute, in an earlier
// command buffer) or on the CPU, executed with a single call. Standalone, no Minecraft.
//   clang -fobjc-arc -O2 -framework Metal -framework Foundation -framework QuartzCore tools/icb_bench.m -o /tmp/icbb && /tmp/icbb [N]
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include <stdio.h>
#include <stdlib.h>
#include <mach/mach_time.h>

static NSString *const SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Draw { uint indexCount; uint instanceCount; uint firstIndex; int baseVertex; uint baseInstance; };\n"
"struct VIn { float3 pos [[attribute(0)]]; float4 info [[attribute(1)]]; };\n"
"struct VOut { float4 pos [[position]]; float4 c; };\n"
"vertex VOut vs(VIn in [[stage_in]], constant float4x4 &mvp [[buffer(2)]]) {\n"
"  VOut o; o.pos = mvp * float4(in.pos + in.info.xyz, 1.0); o.c = float4(fract(in.pos), 1.0); return o;\n"
"}\n"
"fragment float4 fs(VOut in [[stage_in]]) { return in.c; }\n"
"struct Icb { command_buffer cmds [[id(0)]]; };\n"
"kernel void enc16(const device Draw *d [[buffer(0)]], device Icb &icb [[buffer(1)]], constant uint *p [[buffer(2)]],\n"
"    const device ushort *idx [[buffer(3)]], uint i [[thread_position_in_grid]]) {\n"
"  if (i >= p[0]) return;\n"
"  render_command c(icb.cmds, p[1] + i);\n"
"  Draw a = d[i];\n"
"  if (a.indexCount == 0 || a.instanceCount == 0) { c.reset(); return; }\n"
"  c.draw_indexed_primitives(primitive_type::triangle, a.indexCount, idx + a.firstIndex, a.instanceCount, a.baseVertex, a.baseInstance);\n"
"}\n";

static double ms(uint64_t t) {
	static mach_timebase_info_data_t tb;
	if (!tb.denom) mach_timebase_info(&tb);
	return t * (double) tb.numer / tb.denom / 1e6;
}

int main(int argc, char **argv) {
	@autoreleasepool {
		int N = argc > 1 ? atoi(argv[1]) : 3000;
		int frames = 300;
		id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [dev newCommandQueue];
		NSError *err = nil;
		id<MTLLibrary> lib = [dev newLibraryWithSource:SRC options:nil error:&err];
		if (!lib) { printf("compile: %s\n", err.description.UTF8String); return 1; }
		MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
		vd.attributes[0].format = MTLVertexFormatFloat3; vd.attributes[0].bufferIndex = 0; vd.attributes[0].offset = 0;
		vd.layouts[0].stride = 12;
		vd.attributes[1].format = MTLVertexFormatFloat4; vd.attributes[1].bufferIndex = 1; vd.attributes[1].offset = 0;
		vd.layouts[1].stride = 16; vd.layouts[1].stepFunction = MTLVertexStepFunctionPerInstance;
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"fs"];
		pd.vertexDescriptor = vd;
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
		id<MTLRenderPipelineState> plain = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
		pd.supportIndirectCommandBuffers = YES;
		id<MTLRenderPipelineState> icbPso = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
		id<MTLFunction> encFn = [lib newFunctionWithName:@"enc16"];
		id<MTLComputePipelineState> enc = [dev newComputePipelineStateWithFunction:encFn error:&err];
		// geometry: one heap of quads; each draw is a run of ~100 quads (600 indices) with its own base vertex / instance
		int quadsPer = 100, verts = N * quadsPer * 4;
		id<MTLBuffer> vb = [dev newBufferWithLength:(NSUInteger) verts * 12 options:MTLResourceStorageModeShared];
		float *v = vb.contents;
		for (int i = 0; i < verts; i++) { v[i * 3] = (i % 64) * 0.01f; v[i * 3 + 1] = ((i / 4) % 16) * 0.01f; v[i * 3 + 2] = -0.5f; }
		id<MTLBuffer> ib = [dev newBufferWithLength:quadsPer * 6 * 2 options:MTLResourceStorageModeShared];
		uint16_t *ix = ib.contents;
		for (int qd = 0; qd < quadsPer; qd++) { int b = qd * 4; uint16_t o[6] = {b, b + 1, b + 2, b + 2, b + 3, b}; memcpy(ix + qd * 6, o, 12); }
		id<MTLBuffer> info = [dev newBufferWithLength:(NSUInteger) N * 16 options:MTLResourceStorageModeShared];
		float *in = info.contents;
		for (int i = 0; i < N; i++) { in[i * 4] = (i % 50) * 0.02f - 0.5f; in[i * 4 + 1] = (i / 50 % 50) * 0.02f - 0.5f; in[i * 4 + 2] = 0; in[i * 4 + 3] = 0; }
		id<MTLBuffer> args = [dev newBufferWithLength:(NSUInteger) N * 20 options:MTLResourceStorageModeShared];
		uint32_t *a = args.contents;
		for (int i = 0; i < N; i++) {
			a[i * 5] = (i % 7 == 0) ? 0 : 6 * (20 + i % 80); a[i * 5 + 1] = 1; a[i * 5 + 2] = 0; a[i * 5 + 3] = i * quadsPer * 4; a[i * 5 + 4] = i;
		}
		float mvp[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0.5f, 1};
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1470 height:919 mipmapped:NO];
		td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModePrivate;
		id<MTLTexture> color = [dev newTextureWithDescriptor:td];
		td.pixelFormat = MTLPixelFormatDepth32Float;
		id<MTLTexture> depth = [dev newTextureWithDescriptor:td];
		MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
		dd.depthCompareFunction = MTLCompareFunctionLessEqual; dd.depthWriteEnabled = YES;
		id<MTLDepthStencilState> ds = [dev newDepthStencilStateWithDescriptor:dd];
		MTLIndirectCommandBufferDescriptor *icd = [MTLIndirectCommandBufferDescriptor new];
		icd.commandTypes = MTLIndirectCommandTypeDrawIndexed;
		icd.inheritPipelineState = YES;
		icd.inheritBuffers = YES;
		icd.maxVertexBufferBindCount = 0;
		icd.maxFragmentBufferBindCount = 0;
		id<MTLIndirectCommandBuffer> icbs[3];
		for (int k = 0; k < 3; k++) icbs[k] = [dev newIndirectCommandBufferWithDescriptor:icd maxCommandCount:N options:MTLResourceStorageModePrivate];
		printf("device %s, %d draws/frame, ICB %llu bytes\n", dev.name.UTF8String, N, (unsigned long long) icbs[0].size);
		id<MTLArgumentEncoder> ae = [encFn newArgumentEncoderWithBufferIndex:1];
		id<MTLBuffer> argBufs[3];
		for (int k = 0; k < 3; k++) {
			argBufs[k] = [dev newBufferWithLength:ae.encodedLength options:MTLResourceStorageModeShared];
			[ae setArgumentBuffer:argBufs[k] offset:0];
			[ae setIndirectCommandBuffer:icbs[k] atIndex:0];
		}
		const char *names[] = {"loop (today)", "ICB, GPU-encoded", "ICB, CPU-encoded"};
		for (int round = 0; round < 2; round++)
		for (int mode = 0; mode < 3; mode++) {
			uint64_t cpu = 0;
			double gpu = 0;
			int gpuN = 0;
			__block double gsum = 0; __block int gn = 0;
			id<MTLCommandBuffer> last = nil;
			for (int f = 0; f < frames; f++) {
				@autoreleasepool {
					int k = f % 3;
					uint64_t t0 = mach_absolute_time();
					id<MTLCommandBuffer> pre = nil;
					if (mode == 1) {
						pre = [q commandBuffer];
						id<MTLComputeCommandEncoder> c = [pre computeCommandEncoder];
						[c setComputePipelineState:enc];
						[c setBuffer:args offset:0 atIndex:0];
						[c setBuffer:argBufs[k] offset:0 atIndex:1];
						uint32_t p[2] = {(uint32_t) N, 0};
						[c setBytes:p length:8 atIndex:2];
						[c setBuffer:ib offset:0 atIndex:3];
						[c useResource:icbs[k] usage:MTLResourceUsageWrite];
						[c dispatchThreads:MTLSizeMake(N, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
						[c endEncoding];
						[pre commit];
					} else if (mode == 2) {
						for (int i = 0; i < N; i++) {
							id<MTLIndirectRenderCommand> rc = [icbs[k] indirectRenderCommandAtIndex:i];
							if (a[i * 5] == 0) { [rc reset]; continue; }
							[rc drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:a[i * 5] indexType:MTLIndexTypeUInt16 indexBuffer:ib
								indexBufferOffset:a[i * 5 + 2] * 2 instanceCount:a[i * 5 + 1] baseVertex:(int) a[i * 5 + 3] baseInstance:a[i * 5 + 4]];
						}
					}
					id<MTLCommandBuffer> cb = [q commandBuffer];
					MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
					rp.colorAttachments[0].texture = color; rp.colorAttachments[0].loadAction = MTLLoadActionClear;
					rp.colorAttachments[0].storeAction = MTLStoreActionStore;
					rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear;
					rp.depthAttachment.storeAction = MTLStoreActionDontCare; rp.depthAttachment.clearDepth = 1.0;
					id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
					[r setRenderPipelineState:mode == 0 ? plain : icbPso];
					[r setDepthStencilState:ds];
					[r setVertexBuffer:vb offset:0 atIndex:0];
					[r setVertexBuffer:info offset:0 atIndex:1];
					[r setVertexBytes:mvp length:64 atIndex:2];
					if (mode == 0) {
						for (int i = 0; i < N; i++)
							[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:ib indexBufferOffset:0
								indirectBuffer:args indirectBufferOffset:(NSUInteger) i * 20];
					} else {
						[r useResource:ib usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
						[r executeCommandsInBuffer:icbs[k] withRange:NSMakeRange(0, N)];
					}
					[r endEncoding];
					[cb addCompletedHandler:^(id<MTLCommandBuffer> c) { gsum += (c.GPUEndTime - c.GPUStartTime) * 1e3; gn++; }];
					[cb commit];
					cpu += mach_absolute_time() - t0;
					if (last) [last waitUntilCompleted];
					last = cb;
				}
			}
			[last waitUntilCompleted];
			if (round == 1) printf("%-18s CPU %.3f ms/frame   GPU (render cmd) %.3f ms/frame\n", names[mode], ms(cpu) / frames, gsum / (gn ? gn : 1));
		}
	}
	return 0;
}
