// Cadence ray tracing (dev.cadence.metal.rt, rt.metal): the shadow trace and its application to the game's frame, and for
// hardware ray tracing the acceleration structure of the voxel window (a primitive structure of 64 brick boxes per 16^3
// section, an instance per non-empty section, rebuilt for the sections and window moves Java reports). Render thread only.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import "mcmetal.h"
#include <string.h>

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

#define RT_FRAME_BYTES 512  // RtFrame (rt.metal)
#define RT_CACHE_ENTRIES (1 << 19)  // the lighting cache (rt.metal CacheEntry, 32 bytes each: 16 MB)
#define RT_RING 4

typedef struct {
	Ctx *ctx;
	id<MTLComputePipelineState> shadow, shadowHw, cacheUpdate, cacheResolve, gather;
	id<MTLTexture> irr;  // the cached light per traced sample (rt_gather)
	id<MTLFunction> applyVs, applyFs, debugFs, lightFs;
	id<MTLRenderPipelineState> apply[2], debug[2], light[2];  // [0] an 8-bit frame, [1] a float one (HDR)
	id<MTLBuffer> cache;  // the lighting cache (Ray Traced Lighting), made at its first use
	id<MTLBuffer> touched;  // the entries the frame's samples touched: a count, then their slots
	id<MTLComputePipelineState> path;
	id<MTLComputePipelineState> torch;  // Torch Shadows (rt_torch)
	id<MTLComputePipelineState> nrcTrain, nrcAdam, nrcFinish, nrcGather, nrcFeat;  // Neural Lighting (rt_nrc_*)
	id<MTLTexture> torchTex;            // its visibility and distance per traced sample (RGBA16Float, kept between frames)
	id<MTLFunction> pathOutFs;
	id<MTLRenderPipelineState> pathOut[2];
	id<MTLTexture> accum;  // path traced photos: the sum of the samples so far (RGBA32Float; a = the count)
	// denoised photos: the average so far and the denoiser's other inputs (rt_path writes them), its output, the denoiser
	id<MTLTexture> noisy, albedo, normal, rough, dcopy, motion, spec, denoised;
	id denoiser;
	id<MTLFunction> pathDenFs;
	id<MTLRenderPipelineState> pathDen[2];
	int lastDenoised;  // (the last sample was denoised: the denoiser's history goes on; else it starts again)
	id<MTLTexture> out;
	id<MTLBuffer> frames[RT_RING];  // the frame's constants, for the intersection function too (it can't take setBytes)
	int ring;
	// hardware: the sections' boxes and structures (slot = physical section index), the instances, the table
	int hw, slotsX, slotsY, slots;
	id<MTLBuffer> boxes, instances[RT_RING], scratch;  // (instances: one per rebuild in flight)
	int instRing;
	id<MTLAccelerationStructure> *blas;
	uint64_t *masks;       // per slot: which of its 64 bricks hold anything (0: no instance)
	uint8_t *dirtySlot;
	int dirtyCount, tlasDirty, tlasBuilt, ox, oz;
	id<MTLAccelerationStructure> tlas;
	NSUInteger blasSize, scratchSize;
	id<MTLIntersectionFunctionTable> table[RT_RING];  // (one per frame in flight: a table's bindings aren't versioned by Metal)
	NSArray<id<MTLAccelerationStructure>> *blasArray;
} Rt;

static void rtErr(NSError *e, const char *what, char *err, int cap) {
	snprintf(err, cap, "%s: %s", what, e ? e.localizedDescription.UTF8String : "unknown error");
}

// hw: 1 to set up hardware ray tracing (the acceleration structure path) for a window of w x h blocks.
void *mcrt_new(Ctx *ctx, const char *source, int hw, int w, int h, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_1;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e] autorelease];
		if (!lib) { rtErr(e, "rt.metal", err, errCap); return NULL; }
		Rt *r = calloc(1, sizeof(Rt));
		r->ctx = ctx;
		id<MTLFunction> sh = [[lib newFunctionWithName:@"rt_shadow"] autorelease];
		r->shadow = sh ? [ctx->device newComputePipelineStateWithFunction:sh error:&e] : nil;
		r->applyVs = [lib newFunctionWithName:@"rt_apply_vs"];
		r->applyFs = [lib newFunctionWithName:@"rt_apply_fs"];
		r->debugFs = [lib newFunctionWithName:@"rt_debug_fs"];
		r->lightFs = [lib newFunctionWithName:@"rt_light_fs"];
		r->pathOutFs = [lib newFunctionWithName:@"rt_path_out_fs"];
		r->pathDenFs = [lib newFunctionWithName:@"rt_path_den_fs"];
		id<MTLFunction> pf = [[lib newFunctionWithName:@"rt_path"] autorelease];
		r->path = pf ? [ctx->device newComputePipelineStateWithFunction:pf error:&e] : nil;
		id<MTLFunction> cu = [[lib newFunctionWithName:@"rt_cache_update"] autorelease], cr = [[lib newFunctionWithName:@"rt_cache_resolve"] autorelease];
		r->cacheUpdate = cu ? [ctx->device newComputePipelineStateWithFunction:cu error:&e] : nil;
		r->cacheResolve = cr ? [ctx->device newComputePipelineStateWithFunction:cr error:&e] : nil;
		const char *nrcNames[5] = {"rt_nrc_train", "rt_nrc_adam", "rt_nrc_finish", "rt_nrc_gather", "rt_nrc_feat"};
		id<MTLComputePipelineState> *nrcSlots[5] = {&r->nrcTrain, &r->nrcAdam, &r->nrcFinish, &r->nrcGather, &r->nrcFeat};
		for (int i = 0; i < 5; i++) {
			// (optional: no Neural Lighting without them)
			id<MTLFunction> nf = [[lib newFunctionWithName:[NSString stringWithUTF8String:nrcNames[i]]] autorelease];
			*nrcSlots[i] = nf ? [ctx->device newComputePipelineStateWithFunction:nf error:&e] : nil;
			if (!*nrcSlots[i]) NSLog(@"cadence-rt: %s: %@", nrcNames[i], e);
		}
		id<MTLFunction> tf = [[lib newFunctionWithName:@"rt_torch"] autorelease];
		r->torch = tf ? [ctx->device newComputePipelineStateWithFunction:tf error:&e] : nil;  // (optional: no Torch Shadows without it)
		if (!r->torch) NSLog(@"cadence-rt: rt_torch: %@", e);
		id<MTLFunction> gf = [[lib newFunctionWithName:@"rt_gather"] autorelease];
		r->gather = gf ? [ctx->device newComputePipelineStateWithFunction:gf error:&e] : nil;
		if (!r->shadow || !r->applyVs || !r->applyFs || !r->debugFs || !r->lightFs || !r->cacheUpdate || !r->cacheResolve || !r->gather || !r->path || !r->pathOutFs) {
			rtErr(e, "rt pipelines", err, errCap);
			free(r);
			return NULL;
		}
		for (int i = 0; i < RT_RING; i++) r->frames[i] = [ctx->device newBufferWithLength:RT_FRAME_BYTES options:MTLResourceStorageModeShared];
		if (hw && ctx->device.supportsRaytracing) {
			MTLComputePipelineDescriptor *cd = [[MTLComputePipelineDescriptor new] autorelease];
			cd.computeFunction = [[lib newFunctionWithName:@"rt_shadow_hw"] autorelease];
			id<MTLFunction> brick = [[lib newFunctionWithName:@"rt_brick"] autorelease];
			MTLLinkedFunctions *lf = [[MTLLinkedFunctions new] autorelease];
			lf.functions = brick ? @[brick] : @[];
			cd.linkedFunctions = lf;
			r->shadowHw = brick ? [ctx->device newComputePipelineStateWithDescriptor:cd options:MTLPipelineOptionNone reflection:nil error:&e] : nil;
			if (r->shadowHw) {
				MTLIntersectionFunctionTableDescriptor *td = [[MTLIntersectionFunctionTableDescriptor new] autorelease];
				td.functionCount = 1;
				for (int k = 0; k < RT_RING; k++) {
					r->table[k] = [r->shadowHw newIntersectionFunctionTableWithDescriptor:td];
					[r->table[k] setFunction:[r->shadowHw functionHandleWithFunction:brick] atIndex:0];
				}
				r->slotsX = w >> 4;
				r->slotsY = h >> 4;
				r->slots = r->slotsX * r->slotsX * r->slotsY;
				r->boxes = [ctx->device newBufferWithLength:(NSUInteger) r->slots * 64 * sizeof(MTLAxisAlignedBoundingBox) options:MTLResourceStorageModeShared];
				for (int i = 0; i < RT_RING; i++)
					r->instances[i] = [ctx->device newBufferWithLength:(NSUInteger) r->slots * sizeof(MTLAccelerationStructureUserIDInstanceDescriptor)
						options:MTLResourceStorageModeShared];
				r->blas = calloc((size_t) r->slots, sizeof(id));
				r->masks = calloc((size_t) r->slots, sizeof(uint64_t));
				r->dirtySlot = calloc((size_t) r->slots, 1);
				r->hw = 1;
			} else {
				NSLog(@"cadence-rt: no hardware ray tracing pipeline (%@): voxel tracing instead", e);
			}
		}
		return r;
	}
}

int mcrt_hw(void *rt) {
	return ((Rt *) rt)->hw;
}

// A section's bricks changed (slot: its physical index, (y * slotsX + z) * slotsX + x; mask: which of its 64 bricks, x + 4 y + 16 z,
// hold anything): its boxes are written now, its structure rebuilt at the next frame.
void mcrt_section(void *rt, int slot, uint64_t mask) {
	Rt *r = rt;
	if (!r->hw || slot < 0 || slot >= r->slots) return;
	if (r->masks[slot] == mask && r->blas[slot]) return;
	r->masks[slot] = mask;
	MTLAxisAlignedBoundingBox *b = (MTLAxisAlignedBoundingBox *) r->boxes.contents + (size_t) slot * 64;
	for (int k = 0; k < 64; k++) {
		if ((mask >> k) & 1) {
			float x = (float) ((k & 3) * 4), y = (float) (((k >> 2) & 3) * 4), z = (float) (((k >> 4) & 3) * 4);
			b[k].min = MTLPackedFloat3Make(x, y, z);
			b[k].max = MTLPackedFloat3Make(x + 4, y + 4, z + 4);
		} else {
			// (an empty brick: a point far outside anything a ray reaches; the primitive numbers stay the bricks' numbers)
			b[k].min = b[k].max = MTLPackedFloat3Make(-1e6f, -1e6f, -1e6f);
		}
	}
	if (!r->dirtySlot[slot]) {
		r->dirtySlot[slot] = 1;
		r->dirtyCount++;
	}
	r->tlasDirty = 1;
}

// Sizes once, for the most the window can hold: every section's structure, the top structure for an instance per section, and
// one scratch buffer for the largest build (nothing is reallocated while frames that use it may be on the GPU).
static int sizeAccel(Rt *r) {
	if (r->blasSize) return 1;
	id<MTLDevice> dev = r->ctx->device;
	MTLAccelerationStructureBoundingBoxGeometryDescriptor *g = [MTLAccelerationStructureBoundingBoxGeometryDescriptor descriptor];
	g.boundingBoxBuffer = r->boxes;
	g.boundingBoxCount = 64;
	g.boundingBoxStride = sizeof(MTLAxisAlignedBoundingBox);
	MTLPrimitiveAccelerationStructureDescriptor *pd = [MTLPrimitiveAccelerationStructureDescriptor descriptor];
	pd.geometryDescriptors = @[g];
	MTLAccelerationStructureSizes bs = [dev accelerationStructureSizesWithDescriptor:pd];
	MTLInstanceAccelerationStructureDescriptor *td = [MTLInstanceAccelerationStructureDescriptor descriptor];
	td.instanceCount = (NSUInteger) r->slots;
	td.instanceDescriptorBuffer = r->instances[0];
	td.instanceDescriptorType = MTLAccelerationStructureInstanceDescriptorTypeUserID;
	MTLAccelerationStructureSizes ts = [dev accelerationStructureSizesWithDescriptor:td];
	r->blasSize = bs.accelerationStructureSize;
	r->scratchSize = MAX(bs.buildScratchBufferSize, ts.buildScratchBufferSize);
	r->scratch = [dev newBufferWithLength:MAX(r->scratchSize, 4096) options:MTLResourceStorageModePrivate];
	r->tlas = [dev newAccelerationStructureWithSize:ts.accelerationStructureSize];
	return r->scratch && r->tlas;
}

// Builds what changed: the dirty sections' structures, then the instances (the window's origin in sections, ox / 16, oz / 16).
static void buildAccel(Rt *r, id<MTLCommandBuffer> cb, int sox, int soz) {
	if (r->ox != sox || r->oz != soz) {
		r->ox = sox;
		r->oz = soz;
		r->tlasDirty = 1;
	}
	if (!r->dirtyCount && !r->tlasDirty) return;
	if (!sizeAccel(r)) return;
	id<MTLDevice> dev = r->ctx->device;
	id<MTLAccelerationStructureCommandEncoder> ae = [cb accelerationStructureCommandEncoder];
	ae.label = @"Cadence ray tracing structures";
	if (r->dirtyCount) {
		for (int s = 0; s < r->slots; s++) {
			if (!r->dirtySlot[s]) continue;
			r->dirtySlot[s] = 0;
			MTLAccelerationStructureBoundingBoxGeometryDescriptor *g = [MTLAccelerationStructureBoundingBoxGeometryDescriptor descriptor];
			g.boundingBoxBuffer = r->boxes;
			g.boundingBoxBufferOffset = (NSUInteger) s * 64 * sizeof(MTLAxisAlignedBoundingBox);
			g.boundingBoxCount = 64;
			g.boundingBoxStride = sizeof(MTLAxisAlignedBoundingBox);
			g.intersectionFunctionTableOffset = 0;
			g.opaque = NO;
			MTLPrimitiveAccelerationStructureDescriptor *pd = [MTLPrimitiveAccelerationStructureDescriptor descriptor];
			pd.geometryDescriptors = @[g];
			if (!r->blas[s]) {
				r->blas[s] = [dev newAccelerationStructureWithSize:r->blasSize];
				[r->blasArray release];
				r->blasArray = nil;
			}
			// (one scratch buffer for every build: the encoder runs them one after another)
			[ae buildAccelerationStructure:r->blas[s] descriptor:pd scratchBuffer:r->scratch scratchBufferOffset:0];
		}
		r->dirtyCount = 0;
	}
	if (!r->blasArray) {
		NSMutableArray *a = [NSMutableArray arrayWithCapacity:(NSUInteger) r->slots];
		id<MTLAccelerationStructure> filler = nil;
		for (int s = 0; s < r->slots && !filler; s++) filler = r->blas[s];
		for (int s = 0; s < r->slots && filler; s++) [a addObject:r->blas[s] ?: filler];
		r->blasArray = filler ? [a retain] : nil;
	}
	if (!r->blasArray) {
		[ae endEncoding];
		return;
	}
	// the instances: every section with anything in it, placed at its logical position in the window
	id<MTLBuffer> ib = r->instances[r->instRing];
	r->instRing = (r->instRing + 1) % RT_RING;
	MTLAccelerationStructureUserIDInstanceDescriptor *inst = ib.contents;
	int n = 0, sx = r->slotsX;
	for (int y = 0; y < r->slotsY; y++)
		for (int z = 0; z < sx; z++)
			for (int x = 0; x < sx; x++) {
				int s = (y * sx + z) * sx + x;
				if (!r->masks[s] || !r->blas[s]) continue;
				int lx = (x - sox) & (sx - 1), lz = (z - soz) & (sx - 1);
				MTLAccelerationStructureUserIDInstanceDescriptor *d = &inst[n++];
				memset(d, 0, sizeof *d);
				d->transformationMatrix.columns[0] = MTLPackedFloat3Make(1, 0, 0);
				d->transformationMatrix.columns[1] = MTLPackedFloat3Make(0, 1, 0);
				d->transformationMatrix.columns[2] = MTLPackedFloat3Make(0, 0, 1);
				d->transformationMatrix.columns[3] = MTLPackedFloat3Make((float) (lx * 16), (float) (y * 16), (float) (lz * 16));
				d->options = MTLAccelerationStructureInstanceOptionNonOpaque;
				d->mask = 0xFF;
				d->intersectionFunctionTableOffset = 0;
				d->accelerationStructureIndex = (uint32_t) s;
				d->userID = (uint32_t) (lx | y << 8 | lz << 16);
			}
	MTLInstanceAccelerationStructureDescriptor *td = [MTLInstanceAccelerationStructureDescriptor descriptor];
	td.instancedAccelerationStructures = r->blasArray;
	td.instanceCount = (NSUInteger) n;
	td.instanceDescriptorBuffer = ib;
	td.instanceDescriptorType = MTLAccelerationStructureInstanceDescriptorTypeUserID;
	[ae buildAccelerationStructure:r->tlas descriptor:td scratchBuffer:r->scratch scratchBufferOffset:0];
	[ae endEncoding];
	r->tlasDirty = 0;
	r->tlasBuilt = 1;
}

// kind: 0 the shadows' factor, 1 the debug view, 2 Ray Traced Lighting's factor
static id<MTLRenderPipelineState> applyPipeline(Rt *r, MTLPixelFormat format, int kind) {
	int k = format == MTLPixelFormatRGBA16Float ? 1 : 0;
	id<MTLRenderPipelineState> *slot = kind == 1 ? &r->debug[k] : kind == 2 ? &r->light[k] : &r->apply[k];
	if (*slot) return *slot;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
		d.vertexFunction = r->applyVs;
		d.fragmentFunction = kind == 1 ? r->debugFs : kind == 2 ? r->lightFs : r->applyFs;
		d.colorAttachments[0].pixelFormat = format;
		if (kind != 1) {
			// the frame times the factor: src * 0 + dst * src colour; the frame's alpha kept
			d.colorAttachments[0].blendingEnabled = YES;
			d.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorZero;
			d.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorSourceColor;
			d.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
			d.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
		}
		NSError *e = nil;
		*slot = [r->ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
		if (!*slot) NSLog(@"cadence-rt: apply pipeline: %@", e);
		return *slot;
	}
}

// One frame's shadows: traced into the visibility texture (the frame's size), then applied to color. frame: RtFrame (rt.metal).
// sox, soz: the window's origin in sections (hardware path). flags: bit 0 hardware ray tracing, bit 1 debug view, bit 2 Ray Traced
// Lighting (the lighting cache updated and the frame relit instead of only shadowed), bit 3 the cache cleared first.
// 0, or -1 when nothing could be done.
int mcrt_frame(void *rt, Enc *enc, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> lightmap, id<MTLBuffer> vox,
	id<MTLBuffer> bricks, id<MTLBuffer> sections, id<MTLBuffer> palette, const void *frame, int len, int sox, int soz, int flags, id<MTLBuffer> lights,
	id<MTLBuffer> nrc) {
	Rt *r = rt;
	if (!color || !depth || !vox || !bricks || !sections || len > RT_FRAME_BYTES) return -1;
	int useHw = (flags & 1) && r->hw;
	@autoreleasepool {
		// flags bits 8..11: the trace's scale (1 or 2: a ray per 2 x 2 pixels)
		NSUInteger scale = (NSUInteger) MAX(1, (flags >> 8) & 15);
		NSUInteger w = (depth.width + scale - 1) / scale, h = (depth.height + scale - 1) / scale;
		if (!r->out || r->out.width != w || r->out.height != h) {
			[r->out release];
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
			d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
			d.storageMode = MTLStorageModePrivate;
			r->out = [r->ctx->device newTextureWithDescriptor:d];
		}
		id<MTLBuffer> fb = r->frames[r->ring];
		id<MTLIntersectionFunctionTable> table = r->table[r->ring];
		r->ring = (r->ring + 1) % RT_RING;
		memcpy(fb.contents, frame, (size_t) len);
		// (RtFrame screen.w: whether rt_torch runs this frame, decided here where its pipeline is known, BH8-10)
		int torch = (flags & 0x10000) && lights && r->torch;
		if (len >= 208) ((uint32_t *) fb.contents)[51] = torch ? 1u : 0u;
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		if (useHw) buildAccel(r, cb, sox, soz);
		if (useHw && !r->tlasBuilt) useHw = 0;
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"Cadence ray traced shadows";
		[c setComputePipelineState:useHw ? r->shadowHw : r->shadow];
		[c setTexture:depth atIndex:0];
		[c setTexture:r->out atIndex:1];
		[c setBuffer:vox offset:0 atIndex:0];
		[c setBuffer:bricks offset:0 atIndex:1];
		[c setBuffer:fb offset:0 atIndex:2];
		[c setBuffer:sections offset:0 atIndex:5];
		if (useHw) {
			[table setBuffer:bricks offset:0 atIndex:1];
			[table setBuffer:fb offset:0 atIndex:2];
			[c setAccelerationStructure:r->tlas atBufferIndex:3];
			[c setIntersectionFunctionTable:table atBufferIndex:4];
			// (the instances' structures, reached through the top one)
			for (int s = 0; s < r->slots; s++) if (r->blas[s] && r->masks[s]) [c useResource:r->blas[s] usage:MTLResourceUsageRead];
		}
		[c dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		// Torch Shadows (flags bit 16, with the light grid): the share of each sample's block light that reaches it
		if (torch) {
			if (!r->torchTex || r->torchTex.width != w || r->torchTex.height != h) {
				[r->torchTex release];
				MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
				d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
				d.storageMode = MTLStorageModePrivate;
				r->torchTex = [r->ctx->device newTextureWithDescriptor:d];
			}
			id<MTLComputeCommandEncoder> t = [cb computeCommandEncoder];
			t.label = @"Cadence torch shadows";
			[t setComputePipelineState:r->torch];
			[t setTexture:depth atIndex:0];
			[t setTexture:r->torchTex atIndex:1];
			[t setBuffer:vox offset:0 atIndex:0];
			[t setBuffer:bricks offset:0 atIndex:1];
			[t setBuffer:fb offset:0 atIndex:2];
			[t setBuffer:sections offset:0 atIndex:5];
			[t setBuffer:lights offset:0 atIndex:8];
			[t dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
			[t endEncoding];
		} else if (r->torchTex) {
			[r->torchTex release];
			r->torchTex = nil;
		}
		int lighting = (flags & 4) != 0 && palette;
		// Neural Lighting (flags bit 17, with the network's buffer): trained, stepped, then asked, in place of the table
		int neural = lighting && (flags & 0x20000) && nrc && r->nrcTrain && r->nrcAdam && r->nrcFinish && r->nrcGather && r->nrcFeat
			&& r->nrcTrain.maxTotalThreadsPerThreadgroup >= 256;  // (its 16 x 16 groups; else the table cache)
		if (neural) {
			if (!r->irr || r->irr.width != w || r->irr.height != h) {
				[r->irr release];
				MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
				d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
				d.storageMode = MTLStorageModePrivate;
				r->irr = [r->ctx->device newTextureWithDescriptor:d];
			}
			id<MTLComputeCommandEncoder> u = [cb computeCommandEncoder];
			u.label = @"Cadence neural lighting (train, step, ask)";
			[u setTexture:depth atIndex:0];
			[u setTexture:r->out atIndex:1];
			[u setBuffer:vox offset:0 atIndex:0];
			[u setBuffer:bricks offset:0 atIndex:1];
			[u setBuffer:fb offset:0 atIndex:2];
			[u setBuffer:palette offset:0 atIndex:4];
			[u setBuffer:sections offset:0 atIndex:5];
			[u setBuffer:nrc offset:0 atIndex:9];
			[u setBuffer:nrc offset:0 atIndex:10];
			[u setBuffer:nrc offset:0 atIndex:11];
			[u setBuffer:nrc offset:(NSUInteger) (4 * 32768 * 2) * 4 atIndex:12];  // (the MLP's weights, as a constant buffer: rt.metal NRC_W)
			const char *skip = getenv("CADENCE_NRC_SKIP");  // (profiling: "train" or "gather" left out)
			[u setComputePipelineState:r->nrcTrain];
			NSUInteger tw = (w + 15) / 16, th = (h + 15) / 16;  // (rt.metal NRC_STRIDE)
			// (whole 16 x 16 groups: the kernel sums 8 simdgroups' rows and writes all of its row; threads past the edge add zero.
			// Partial edge groups summed rows nobody wrote, BH8-05)
			NSUInteger gx = (tw + 15) / 16, gy = (th + 15) / 16;
			if (!skip || strcmp(skip, "train") != 0) [u dispatchThreadgroups:MTLSizeMake(gx, gy, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
			uint32_t groups = (uint32_t) (gx * gy);
			[u memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[u setComputePipelineState:r->nrcAdam];
			[u setBytes:&groups length:4 atIndex:13];
			[u dispatchThreads:MTLSizeMake(520, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[u memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[u setComputePipelineState:r->nrcFeat];
			[u dispatchThreads:MTLSizeMake(4 * 32768, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];  // (NRC_FEAT / 2 entries)
			[u setComputePipelineState:r->nrcFinish];
			[u dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[u memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[u setTexture:r->irr atIndex:2];
			[u setComputePipelineState:r->nrcGather];
			if (!skip || strcmp(skip, "gather") != 0) [u dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
			[u endEncoding];
		} else if (lighting) {
			if (!r->cache) {
				r->cache = [r->ctx->device newBufferWithLength:(NSUInteger) RT_CACHE_ENTRIES * 32 options:MTLResourceStorageModePrivate];
				flags |= 8;
			}
			if (!r->touched) r->touched = [r->ctx->device newBufferWithLength:(NSUInteger) (RT_CACHE_ENTRIES + 1) * 4 options:MTLResourceStorageModePrivate];
			id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
			if (flags & 8) [b fillBuffer:r->cache range:NSMakeRange(0, r->cache.length) value:0];
			[b fillBuffer:r->touched range:NSMakeRange(0, 4) value:0];
			[b endEncoding];
			id<MTLComputeCommandEncoder> u = [cb computeCommandEncoder];
			u.label = @"Cadence ray traced lighting (cache)";
			[u setComputePipelineState:r->cacheUpdate];
			[u setTexture:depth atIndex:0];
			[u setTexture:r->out atIndex:1];
			[u setBuffer:vox offset:0 atIndex:0];
			[u setBuffer:bricks offset:0 atIndex:1];
			[u setBuffer:fb offset:0 atIndex:2];
			[u setBuffer:r->cache offset:0 atIndex:3];
			[u setBuffer:palette offset:0 atIndex:4];
			[u setBuffer:sections offset:0 atIndex:5];
			[u setBuffer:r->touched offset:0 atIndex:6];
			// (one update thread per stride x stride traced samples: RtFrame extra2.w, the bytes at 252)
			float strideF = ((const float *) frame)[63];
			NSUInteger stride = strideF >= 1 ? (NSUInteger) strideF : 2;
			NSUInteger uw = (w + stride - 1) / stride, uh = (h + stride - 1) / stride;
			[u dispatchThreads:MTLSizeMake(uw, uh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
			[u memoryBarrierWithScope:MTLBarrierScopeBuffers];
			// (at most one entry per update thread: the list is never longer than that)
			[u setComputePipelineState:r->cacheResolve];
			[u dispatchThreads:MTLSizeMake(MIN(uw * uh, (NSUInteger) RT_CACHE_ENTRIES), 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			// the cached light per traced sample, for the relight pass to upsample
			if (!r->irr || r->irr.width != w || r->irr.height != h) {
				[r->irr release];
				MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:w height:h mipmapped:NO];
				d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
				d.storageMode = MTLStorageModePrivate;
				r->irr = [r->ctx->device newTextureWithDescriptor:d];
			}
			[u memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[u setComputePipelineState:r->gather];
			[u setTexture:depth atIndex:0];
			[u setTexture:r->out atIndex:1];
			[u setTexture:r->irr atIndex:2];
			[u dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
			[u endEncoding];
		}
		id<MTLRenderPipelineState> p = applyPipeline(r, color.pixelFormat, (flags & 2) ? 1 : lighting ? 2 : 0);
		if (!p || !lightmap) return -1;
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = color;
		rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
		re.label = @"Cadence ray traced shadows (apply)";
		[re setRenderPipelineState:p];
		[re setFragmentTexture:r->out atIndex:0];
		[re setFragmentTexture:lightmap atIndex:1];
		[re setFragmentTexture:depth atIndex:2];
		[re setFragmentBuffer:fb offset:0 atIndex:2];
		if (lighting) [re setFragmentTexture:r->irr atIndex:3];
		[re setFragmentTexture:torch ? r->torchTex : r->out atIndex:4];  // (read only when RtFrame screen.w says it ran)
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
	}
	return useHw ? 1 : 0;
}

static id<MTLTexture> photoTexture(Ctx *ctx, MTLPixelFormat f, NSUInteger w, NSUInteger h, MTLTextureUsage usage) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	d.usage = usage;
	d.storageMode = MTLStorageModePrivate;
	return [ctx->device newTextureWithDescriptor:d];
}

static void releaseDenoise(Rt *r) {
	[r->noisy release];
	[r->albedo release];
	[r->normal release];
	[r->rough release];
	[r->dcopy release];
	[r->motion release];
	[r->spec release];
	[r->denoised release];
	[r->denoiser release];
	r->noisy = r->albedo = r->normal = r->rough = r->dcopy = r->motion = r->spec = r->denoised = nil;
	r->denoiser = nil;
}

// The denoiser and its textures for a w x h photo (made once per size); 0 if MetalFX can't denoise here (then the photo is the
// plain average).
static int makeDenoise(Rt *r, NSUInteger w, NSUInteger h) {
	if (r->denoiser && r->denoised.width == w && r->denoised.height == h) return 1;
	releaseDenoise(r);
	if (@available(macOS 26.0, *)) {
	} else {
		return 0;
	}
	if (mc4On || ![MTLFXTemporalDenoisedScalerDescriptor supportsDevice:r->ctx->device]) return 0;
	MTLFXTemporalDenoisedScalerDescriptor *d = [[MTLFXTemporalDenoisedScalerDescriptor new] autorelease];
	d.colorTextureFormat = MTLPixelFormatRGBA16Float;
	d.depthTextureFormat = MTLPixelFormatR32Float;
	d.motionTextureFormat = MTLPixelFormatRG16Float;
	d.diffuseAlbedoTextureFormat = MTLPixelFormatRGBA8Unorm;
	d.specularAlbedoTextureFormat = MTLPixelFormatRGBA8Unorm;
	d.normalTextureFormat = MTLPixelFormatRGBA16Float;
	d.roughnessTextureFormat = MTLPixelFormatR8Unorm;
	d.outputTextureFormat = MTLPixelFormatRGBA16Float;
	d.inputWidth = w;
	d.inputHeight = h;
	d.outputWidth = w;
	d.outputHeight = h;
	id<MTLFXTemporalDenoisedScaler> den = [d newTemporalDenoisedScalerWithDevice:r->ctx->device];
	if (!den) return 0;
	MTLTextureUsage in = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	r->denoiser = den;
	r->noisy = photoTexture(r->ctx, MTLPixelFormatRGBA16Float, w, h, in | den.colorTextureUsage);
	r->albedo = photoTexture(r->ctx, MTLPixelFormatRGBA8Unorm, w, h, in | den.diffuseAlbedoTextureUsage);
	r->normal = photoTexture(r->ctx, MTLPixelFormatRGBA16Float, w, h, in | den.normalTextureUsage);
	r->rough = photoTexture(r->ctx, MTLPixelFormatR8Unorm, w, h, in | den.roughnessTextureUsage);
	r->dcopy = photoTexture(r->ctx, MTLPixelFormatR32Float, w, h, in | den.depthTextureUsage);
	r->motion = photoTexture(r->ctx, MTLPixelFormatRG16Float, w, h, in | den.motionTextureUsage);
	r->spec = photoTexture(r->ctx, MTLPixelFormatRGBA8Unorm, w, h, in | den.specularAlbedoTextureUsage);
	r->denoised = photoTexture(r->ctx, MTLPixelFormatRGBA16Float, w, h, MTLTextureUsageShaderRead | den.outputTextureUsage);
	return 1;
}

// A path traced photo's sample: the view path traced once more and added to the sum (reset: start a new sum), the average drawn
// into color. denoise: the average goes through MetalFX's denoiser first (with the surfaces' colours, normals and depth the
// kernel writes, and viewProj: the camera-relative view-projection, 16 floats), and the denoised photo is drawn. The caller makes
// sure the camera hasn't moved. 0 (1: denoised), or -1.
int mcrt_path(void *rt, Enc *enc, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> lightmap, id<MTLBuffer> vox, id<MTLBuffer> bricks,
	id<MTLBuffer> sections, id<MTLBuffer> palette, const void *frame, int len, int reset, int denoise, const float *viewProj) {
	Rt *r = rt;
	if (!color || !depth || !lightmap || len > RT_FRAME_BYTES) return -1;
	@autoreleasepool {
		NSUInteger w = color.width, h = color.height;
		if (!r->accum || r->accum.width != w || r->accum.height != h) {
			[r->accum release];
			r->accum = photoTexture(r->ctx, MTLPixelFormatRGBA32Float, w, h, MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite);
			reset = 1;
		}
		if (denoise && !makeDenoise(r, w, h)) denoise = 0;
		int k = color.pixelFormat == MTLPixelFormatRGBA16Float ? 1 : 0;
		id<MTLRenderPipelineState> *pso = denoise ? &r->pathDen[k] : &r->pathOut[k];
		if (!*pso) {
			MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
			d.vertexFunction = r->applyVs;
			d.fragmentFunction = denoise ? r->pathDenFs : r->pathOutFs;
			d.colorAttachments[0].pixelFormat = color.pixelFormat;
			d.colorAttachments[0].writeMask = MTLColorWriteMaskRed | MTLColorWriteMaskGreen | MTLColorWriteMaskBlue;
			NSError *e = nil;
			*pso = [r->ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
			if (!*pso) { NSLog(@"cadence-rt: path out: %@", e); return -1; }
		}
		id<MTLBuffer> fb = r->frames[r->ring];
		r->ring = (r->ring + 1) % RT_RING;
		memcpy(fb.contents, frame, (size_t) len);
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"Cadence path traced photo";
		[c setComputePipelineState:r->path];
		[c setTexture:depth atIndex:0];
		[c setTexture:color atIndex:1];
		[c setTexture:r->accum atIndex:2];
		[c setTexture:lightmap atIndex:3];
		id<MTLTexture> aux[7] = {r->noisy, r->albedo, r->normal, r->rough, r->dcopy, r->motion, r->spec};
		for (int i = 0; i < 7; i++) [c setTexture:denoise ? aux[i] : r->accum atIndex:(NSUInteger) (4 + i)];  // (unused when not denoising)
		[c setBuffer:vox offset:0 atIndex:0];
		[c setBuffer:bricks offset:0 atIndex:1];
		[c setBuffer:fb offset:0 atIndex:2];
		[c setBuffer:palette offset:0 atIndex:4];
		[c setBuffer:sections offset:0 atIndex:5];
		uint32_t rs = (uint32_t) (reset != 0), dn = (uint32_t) (denoise != 0);
		[c setBytes:&rs length:4 atIndex:6];
		[c setBytes:&dn length:4 atIndex:7];
		[c dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		if (@available(macOS 26.0, *)) if (denoise) {
			id<MTLFXTemporalDenoisedScaler> den = r->denoiser;
			den.colorTexture = r->noisy;
			den.depthTexture = r->dcopy;
			den.motionTexture = r->motion;
			den.diffuseAlbedoTexture = r->albedo;
			den.specularAlbedoTexture = r->spec;
			den.normalTexture = r->normal;
			den.roughnessTexture = r->rough;
			den.outputTexture = r->denoised;
			den.depthReversed = YES;
			den.shouldResetHistory = reset != 0 || !r->lastDenoised;
			den.jitterOffsetX = 0;
			den.jitterOffsetY = 0;
			den.motionVectorScaleX = 1;
			den.motionVectorScaleY = 1;
			// (the frame's points are camera-relative world space: that is the "view" here, the normals are in it too)
			den.worldToViewMatrix = matrix_identity_float4x4;
			simd_float4x4 vp;
			memcpy(&vp, viewProj, sizeof(vp));
			den.viewToClipMatrix = vp;
			[den encodeToCommandBuffer:cb];
		}
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = color;
		rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
		re.label = @"Cadence path traced photo (out)";
		[re setRenderPipelineState:*pso];
		[re setFragmentTexture:denoise ? r->denoised : r->accum atIndex:0];
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
		r->lastDenoised = denoise;
		return denoise ? 1 : 0;
	}
}

// Lets go of a path traced photo's sum (33 MB at 1080p).
void mcrt_path_done(void *rt) {
	Rt *r = rt;
	[r->accum release];
	r->accum = nil;
	releaseDenoise(r);
}

void mcrt_free(void *rt) {
	Rt *r = rt;
	if (!r) return;
	[r->shadow release];
	[r->shadowHw release];
	[r->applyVs release];
	[r->applyFs release];
	[r->debugFs release];
	[r->lightFs release];
	[r->cacheUpdate release];
	[r->cacheResolve release];
	[r->cache release];
	[r->touched release];
	[r->gather release];
	[r->irr release];
	[r->path release];
	[r->pathOutFs release];
	releaseDenoise(r);
	[r->pathDenFs release];
	[r->torch release];
	[r->nrcTrain release];
	[r->nrcAdam release];
	[r->nrcFinish release];
	[r->nrcGather release];
	[r->nrcFeat release];
	[r->torchTex release];
	[r->pathDen[0] release];
	[r->pathDen[1] release];
	[r->accum release];
	for (int k = 0; k < 2; k++) [r->pathOut[k] release];
	for (int k = 0; k < 2; k++) {
		[r->apply[k] release];
		[r->debug[k] release];
		[r->light[k] release];
	}
	[r->out release];
	for (int i = 0; i < RT_RING; i++) [r->frames[i] release];
	if (r->blas) for (int s = 0; s < r->slots; s++) [r->blas[s] release];
	free(r->blas);
	free(r->masks);
	free(r->dirtySlot);
	[r->blasArray release];
	[r->tlas release];
	[r->boxes release];
	for (int i = 0; i < RT_RING; i++) [r->instances[i] release];
	[r->scratch release];
	for (int i = 0; i < RT_RING; i++) [r->table[i] release];
	free(r);
}

// A shared buffer of `bytes` (+1 retained, contents via mcrt_contents), zeroed.
id<MTLBuffer> mcrt_buffer(Ctx *ctx, uint64_t bytes) {
	id<MTLBuffer> b = [ctx->device newBufferWithLength:(NSUInteger) bytes options:MTLResourceStorageModeShared];
	if (b) memset(b.contents, 0, (size_t) bytes);
	return b;
}

void *mcrt_contents(id<MTLBuffer> b) {
	return b.contents;
}

void mcrt_release(id o) {
	[o release];
}

// Hardware ray tracing on this Mac: 2 in hardware (Apple GPU family 9: M3 and newer), 1 through Metal's software traversal, 0 none.
int mcrt_support(Ctx *ctx) {
	if (!ctx->device.supportsRaytracing) return 0;
	return [ctx->device supportsFamily:MTLGPUFamilyApple9] ? 2 : 1;
}
