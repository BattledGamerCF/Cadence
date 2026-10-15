// Cadence: shader pack compute and storage resources (Iris's compute programs, custom images, buffer objects).
// Compute work goes into the frame's command buffer between the game's render passes, like the native shading
// pipeline's: whatever encoder is open ends first, and Metal's hazard tracking orders the accesses across encoders.
#import "mcmetal.h"

static void copyErr(NSError *e, char *err, int errCap) {
	const char *m = e ? e.localizedDescription.UTF8String : "unknown error";
	snprintf(err, errCap, "%s", m ? m : "unknown error");
}

// MSL 3.1 (texture atomics, which Iris packs use on images), the loader's Safe arithmetic.
id<MTLLibrary> mc_pc_library_new(Ctx *ctx, const char *source, int relaxed, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_1;
		o.mathMode = relaxed ? MTLMathModeRelaxed : MTLMathModeSafe;
		o.mathFloatingPointFunctions = MTLMathFloatingPointFunctionsFast;
		o.preserveInvariance = YES;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) copyErr(e, err, errCap);
		return lib;
	}
}

id<MTLComputePipelineState> mc_pc_pipeline_new(Ctx *ctx, id<MTLLibrary> lib, const char *entry, char *err, int errCap) {
	@autoreleasepool {
		id<MTLFunction> f = [[lib newFunctionWithName:[NSString stringWithUTF8String:entry]] autorelease];
		if (!f) {
			snprintf(err, errCap, "no function %s", entry);
			return nil;
		}
		NSError *e = nil;
		id<MTLComputePipelineState> p = [ctx->device newComputePipelineStateWithFunction:f error:&e];
		if (!p) copyErr(e, err, errCap);
		return p;
	}
}

int mc_pc_max_threads(id<MTLComputePipelineState> p) { return (int) p.maxTotalThreadsPerThreadgroup; }

// A texture compute kernels and shaders may write (ShaderRead | ShaderWrite, and RenderTarget when asked): dims 2 or 3.
id<MTLTexture> mc_pc_texture_new(Ctx *ctx, int format, int dims, int w, int h, int d, int renderTarget) {
	@autoreleasepool {
		MTLTextureDescriptor *t = [[MTLTextureDescriptor new] autorelease];
		t.textureType = dims == 3 ? MTLTextureType3D : MTLTextureType2D;
		t.pixelFormat = (MTLPixelFormat) format;
		t.width = w;
		t.height = h;
		t.depth = dims == 3 ? d : 1;
		t.mipmapLevelCount = 1;
		t.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | (renderTarget ? MTLTextureUsageRenderTarget : 0);
		t.storageMode = MTLStorageModePrivate;
		return [ctx->device newTextureWithDescriptor:t];
	}
}

// A GPU-only buffer of zeros (buffer objects).
id<MTLBuffer> mc_pc_buffer_new(Ctx *ctx, uint64_t size) {
	@autoreleasepool {
		id<MTLBuffer> b = [ctx->device newBufferWithLength:(size + 15) & ~(uint64_t) 15 options:MTLResourceStorageModePrivate];
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		id<MTLBlitCommandEncoder> e = [cb blitCommandEncoder];
		[e fillBuffer:b range:NSMakeRange(0, b.length) value:0];
		[e endEncoding];
		[cb commit];
		return b;
	}
}

// A compute encoder on the frame's command buffer (anything open ends first). Retained: mc_pc_end releases it.
id<MTLComputeCommandEncoder> mc_pc_begin(Enc *enc) {
	return mc_profiled_compute(enc, mc_frame_cmd(enc), MTLDispatchTypeSerial);
}

void mc_pc_end(id<MTLComputeCommandEncoder> ce) {
	[ce endEncoding];
	[ce release];
}

void mc_pc_pipeline(id<MTLComputeCommandEncoder> ce, id<MTLComputePipelineState> p) { [ce setComputePipelineState:p]; }
void mc_pc_texture(id<MTLComputeCommandEncoder> ce, int i, id<MTLTexture> t) { [ce setTexture:t atIndex:i]; }
void mc_pc_sampler(id<MTLComputeCommandEncoder> ce, int i, id<MTLSamplerState> s) { [ce setSamplerState:s atIndex:i]; }
void mc_pc_buffer(id<MTLComputeCommandEncoder> ce, int i, id<MTLBuffer> b, uint64_t off) { [ce setBuffer:b offset:off atIndex:i]; }
void mc_pc_bytes(id<MTLComputeCommandEncoder> ce, int i, const void *p, int len) { [ce setBytes:p length:len atIndex:i]; }

void mc_pc_dispatch(id<MTLComputeCommandEncoder> ce, int gx, int gy, int gz, int tx, int ty, int tz) {
	[ce dispatchThreadgroups:MTLSizeMake(gx, gy, gz) threadsPerThreadgroup:MTLSizeMake(tx, ty, tz)];
}

// Zeros every texel of texture's level 0 (all slices) in the frame's blit encoder: a slice-sized zero buffer copied in.
static id<MTLBuffer> zeros;
void mc_pc_clear_texture(Enc *enc, id<MTLTexture> t, int bytesPerPixel) {
	uint64_t row = (uint64_t) t.width * bytesPerPixel, slice = row * t.height;
	if (!zeros || zeros.length < slice) {
		[zeros release];
		zeros = [enc->ctx->device newBufferWithLength:slice options:MTLResourceStorageModeShared];
		memset(zeros.contents, 0, slice);
	}
	id<MTLBlitCommandEncoder> b = mc_blit(enc);
	NSUInteger depth = t.textureType == MTLTextureType3D ? t.depth : 1;
	for (NSUInteger z = 0; z < depth; z++) {
		[b copyFromBuffer:zeros sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:slice
			sourceSize:MTLSizeMake(t.width, t.height, 1) toTexture:t destinationSlice:0 destinationLevel:0
			destinationOrigin:MTLOriginMake(0, 0, z)];
	}
}

void mc_pc_clear_buffer(Enc *enc, id<MTLBuffer> b) {
	[mc_blit(enc) fillBuffer:b range:NSMakeRange(0, b.length) value:0];
}

// A render pass's extra resource, outside the game's binding model (a pack's images, buffer objects, 3D textures):
// both stages, as the backend binds everything else.
void mc_pc_r_texture(Enc *enc, int i, id<MTLTexture> t) {
	[enc->render setVertexTexture:t atIndex:i];
	[enc->render setFragmentTexture:t atIndex:i];
}

void mc_pc_r_buffer(Enc *enc, int i, id<MTLBuffer> b) {
	[enc->render setVertexBuffer:b offset:0 atIndex:i];
	[enc->render setFragmentBuffer:b offset:0 atIndex:i];
}

// Cadence: a texture's whole mip chain from its base level in one blit command (the shader loader made it with one render
// pass per level).
void mc_pc_generate_mips(Enc *enc, id<MTLTexture> t) {
	[mc_blit(enc) generateMipmapsForTexture:t];
}
