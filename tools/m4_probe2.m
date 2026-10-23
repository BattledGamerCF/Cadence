// Metal 4 probe 2 (no Minecraft): can the backend's Metal 3 objects be driven through Metal 4?
// 1. Does a residency set keep its allocations alive (retain)?  2. A render pipeline made by the Metal 3 device API, drawing
// through an MTL4 render encoder with an argument table (vertex buffer by address, fragment texture + sampler by resource id).
// 3. A compute pipeline made by the Metal 3 API, dispatched through an MTL4 compute encoder.
// Build: clang -fobjc-arc -O2 -framework Metal -framework Foundation tools/m4_probe2.m -o /tmp/m4_probe2
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static NSString *src = @
	"#include <metal_stdlib>\nusing namespace metal;\n"
	"struct V { float4 p [[position]]; float2 uv; };\n"
	"vertex V vs(uint vid [[vertex_id]], const device float2 *pos [[buffer(0)]], constant float &scale [[buffer(1)]]) {\n"
	"  V o; o.p = float4(pos[vid] * scale, 0, 1); o.uv = pos[vid] * 0.5 + 0.5; return o; }\n"
	"fragment float4 fs(V in [[stage_in]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]]) { return t.sample(s, in.uv); }\n"
	"kernel void k(device uint *out [[buffer(0)]], constant uint &add [[buffer(1)]], uint i [[thread_position_in_grid]]) { out[i] = i + add; }\n";

int main(void) {
	@autoreleasepool {
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		NSError *err = nil;
		// ---- 1. residency set retain ----
		id<MTLResidencySet> rs = [d newResidencySetWithDescriptor:[MTLResidencySetDescriptor new] error:&err];
		__weak id<MTLBuffer> weakBuf;
		@autoreleasepool {
			id<MTLBuffer> b = [d newBufferWithLength:4096 options:MTLResourceStorageModeShared];
			weakBuf = b;
			[rs addAllocation:b];
			[rs commit];
		}
		printf("1. residency set keeps a released buffer alive: %s (allocations %lu)\n", weakBuf ? "YES (retains)" : "no", (unsigned long) rs.allocationCount);
		// ---- shared objects ----
		id<MTLLibrary> lib = [d newLibraryWithSource:src options:nil error:&err];
		if (!lib) { printf("library: %s\n", err.localizedDescription.UTF8String); return 1; }
		MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
		pd.vertexFunction = [lib newFunctionWithName:@"vs"];
		pd.fragmentFunction = [lib newFunctionWithName:@"fs"];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
		id<MTLRenderPipelineState> rpso = [d newRenderPipelineStateWithDescriptor:pd error:&err];
		id<MTLComputePipelineState> cpso = [d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"k"] error:&err];
		float tri[6] = {-1, -1, 3, -1, -1, 3};
		id<MTLBuffer> vb = [d newBufferWithBytes:tri length:sizeof tri options:MTLResourceStorageModeShared];
		id<MTLBuffer> bytes = [d newBufferWithLength:4096 options:MTLResourceStorageModeShared];  // the transient ring (setBytes)
		*(float *) bytes.contents = 1.0f;
		((uint32_t *) bytes.contents)[64] = 1000;
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4 height:4 mipmapped:NO];
		td.storageMode = MTLStorageModeShared;
		id<MTLTexture> src4 = [d newTextureWithDescriptor:td];
		uint32_t px[16];
		for (int i = 0; i < 16; i++) px[i] = 0xFF0080FF;  // ABGR in memory: R=FF G=80 B=00 A=FF
		[src4 replaceRegion:MTLRegionMake2D(0, 0, 4, 4) mipmapLevel:0 withBytes:px bytesPerRow:16];
		td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
		id<MTLTexture> target = [d newTextureWithDescriptor:td];
		MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
		sd.supportArgumentBuffers = YES;
		id<MTLSamplerState> samp = [d newSamplerStateWithDescriptor:sd];
		id<MTLBuffer> out = [d newBufferWithLength:256 * 4 options:MTLResourceStorageModeShared];
		// ---- Metal 4 queue, residency ----
		id<MTL4CommandQueue> q = [d newMTL4CommandQueue];
		id<MTLResidencySet> set = [d newResidencySetWithDescriptor:[MTLResidencySetDescriptor new] error:&err];
		for (id<MTLAllocation> a in @[vb, bytes, src4, target, out]) [set addAllocation:a];
		[set commit];
		[q addResidencySet:set];
		id<MTL4CommandAllocator> al = [d newCommandAllocator];
		id<MTL4CommandBuffer> cb = [d newCommandBuffer];
		MTL4ArgumentTableDescriptor *ad = [MTL4ArgumentTableDescriptor new];
		ad.maxBufferBindCount = 31;
		ad.maxTextureBindCount = 31;
		ad.maxSamplerStateBindCount = 16;
		id<MTL4ArgumentTable> vt = [d newArgumentTableWithDescriptor:ad error:&err];
		id<MTL4ArgumentTable> ft = [d newArgumentTableWithDescriptor:ad error:&err];
		id<MTL4ArgumentTable> ct = [d newArgumentTableWithDescriptor:ad error:&err];
		if (!vt || !ft || !ct) { printf("argument table: %s\n", err.localizedDescription.UTF8String); return 1; }
		[cb beginCommandBufferWithAllocator:al];
		// ---- 2. draw ----
		MTL4RenderPassDescriptor *rp = [MTL4RenderPassDescriptor new];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionClear;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTL4RenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
		[re setRenderPipelineState:rpso];
		[vt setAddress:vb.gpuAddress atIndex:0];
		[vt setAddress:bytes.gpuAddress atIndex:1];
		[ft setTexture:src4.gpuResourceID atIndex:0];
		[ft setSamplerState:samp.gpuResourceID atIndex:0];
		[re setArgumentTable:vt atStages:MTLRenderStageVertex];
		[re setArgumentTable:ft atStages:MTLRenderStageFragment];
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
		// ---- 3. dispatch ----
		id<MTL4ComputeCommandEncoder> ce = [cb computeCommandEncoder];
		[ce setComputePipelineState:cpso];
		[ct setAddress:out.gpuAddress atIndex:0];
		[ct setAddress:bytes.gpuAddress + 256 atIndex:1];
		[ce setArgumentTable:ct];
		[ce dispatchThreads:MTLSizeMake(256, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[ce endEncoding];
		[cb endCommandBuffer];
		id<MTLSharedEvent> ev = [d newSharedEvent];
		id<MTL4CommandBuffer> list[1] = {cb};
		[q commit:list count:1];
		[q signalEvent:ev value:1];
		BOOL done = [ev waitUntilSignaledValue:1 timeoutMS:5000];
		uint32_t got;
		[target getBytes:&got bytesPerRow:16 fromRegion:MTLRegionMake2D(2, 2, 1, 1) mipmapLevel:0];
		printf("2. Metal 3 render pipeline through an MTL4 encoder + argument tables: pixel %08X (want FF0080FF) %s\n", got, got == 0xFF0080FF ? "OK" : "WRONG");
		uint32_t *o = out.contents;
		printf("3. Metal 3 compute pipeline through an MTL4 compute encoder: out[5]=%u (want 1005) %s; finished %s\n", o[5], o[5] == 1005 ? "OK" : "WRONG", done ? "yes" : "TIMEOUT");
	}
	return 0;
}
