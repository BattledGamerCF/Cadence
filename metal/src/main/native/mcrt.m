// Cadence ray tracing (dev.cadence.metal.rt, rt.metal): the shadow trace and its application to the game's frame, and for
// hardware ray tracing the acceleration structure of the voxel window (a primitive structure of 64 brick boxes per 16^3
// section, an instance per non-empty section, rebuilt for the sections and window moves Java reports). Render thread only.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import "mcmetal.h"
#include <string.h>

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

#define RT_FRAME_BYTES 256  // RtFrame (rt.metal)
#define RT_RING 4

typedef struct {
	Ctx *ctx;
	id<MTLComputePipelineState> shadow, shadowHw;
	id<MTLFunction> applyVs, applyFs, debugFs;
	id<MTLRenderPipelineState> apply[2], debug[2];  // [0] an 8-bit frame, [1] a float one (HDR)
	MTLPixelFormat applyFormat[2];
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
	id<MTLIntersectionFunctionTable> table;
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
		if (!r->shadow || !r->applyVs || !r->applyFs || !r->debugFs) {
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
				r->table = [r->shadowHw newIntersectionFunctionTableWithDescriptor:td];
				[r->table setFunction:[r->shadowHw functionHandleWithFunction:brick] atIndex:0];
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

static id<MTLRenderPipelineState> applyPipeline(Rt *r, MTLPixelFormat format, int debug) {
	int k = format == MTLPixelFormatRGBA16Float ? 1 : 0;
	id<MTLRenderPipelineState> *slot = debug ? &r->debug[k] : &r->apply[k];
	if (*slot && r->applyFormat[k] == format) return *slot;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
		d.vertexFunction = r->applyVs;
		d.fragmentFunction = debug ? r->debugFs : r->applyFs;
		d.colorAttachments[0].pixelFormat = format;
		if (!debug) {
			// the frame times the factor: src * 0 + dst * src colour; the frame's alpha kept
			d.colorAttachments[0].blendingEnabled = YES;
			d.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorZero;
			d.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorSourceColor;
			d.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
			d.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
		}
		NSError *e = nil;
		[*slot release];
		*slot = [r->ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
		r->applyFormat[k] = format;
		if (!*slot) NSLog(@"cadence-rt: apply pipeline: %@", e);
		return *slot;
	}
}

// One frame's shadows: traced into the visibility texture (the frame's size), then applied to color. frame: RtFrame (rt.metal).
// sox, soz: the window's origin in sections (hardware path). flags: bit 0 hardware ray tracing, bit 1 debug view.
// 0, or -1 when nothing could be done.
int mcrt_frame(void *rt, Enc *enc, id<MTLTexture> color, id<MTLTexture> depth, id<MTLTexture> lightmap, id<MTLBuffer> vox,
	id<MTLBuffer> bricks, id<MTLBuffer> sections, const void *frame, int len, int sox, int soz, int flags) {
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
		r->ring = (r->ring + 1) % RT_RING;
		memcpy(fb.contents, frame, (size_t) len);
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
			[r->table setBuffer:bricks offset:0 atIndex:1];
			[r->table setBuffer:fb offset:0 atIndex:2];
			[c setAccelerationStructure:r->tlas atBufferIndex:3];
			[c setIntersectionFunctionTable:r->table atBufferIndex:4];
			// (the instances' structures, reached through the top one)
			for (int s = 0; s < r->slots; s++) if (r->blas[s] && r->masks[s]) [c useResource:r->blas[s] usage:MTLResourceUsageRead];
		}
		[c dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
		id<MTLRenderPipelineState> p = applyPipeline(r, color.pixelFormat, (flags & 2) != 0);
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
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
	}
	return useHw ? 1 : 0;
}

void mcrt_free(void *rt) {
	Rt *r = rt;
	if (!r) return;
	[r->shadow release];
	[r->shadowHw release];
	[r->applyVs release];
	[r->applyFs release];
	[r->debugFs release];
	for (int k = 0; k < 2; k++) {
		[r->apply[k] release];
		[r->debug[k] release];
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
	[r->table release];
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
