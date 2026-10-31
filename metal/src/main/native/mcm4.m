// Cadence: Metal 4 under the Metal 3 interface the rest of Cadence speaks (-Dcadence.metal4=true; Metal 3 otherwise).
//
// Every native module encodes through id<MTLCommandBuffer> / id<MTLRenderCommandEncoder> / id<MTLComputeCommandEncoder> /
// id<MTLBlitCommandEncoder>. With Metal 4 on, the context's queue is an McM4Queue and those objects are the facades below:
// they encode into an MTL4CommandBuffer (from a pooled MTL4CommandAllocator) on one MTL4CommandQueue, and supply what Metal 3
// did implicitly:
// - bindings: set*Buffer/Texture/SamplerState write per-stage MTL4ArgumentTables (each draw keeps the bindings it was
//   encoded with: a standalone probe); set*Bytes copies into a per-command-buffer chunk of shared memory, bound by address;
// - residency: every resource bound, attached, copied or used indirectly joins one MTLResidencySet (it retains them), and
//   leaves it a few seconds after its last use, when no GPU work can still reference it;
// - hazards: Metal 4 doesn't track them. Each encoder starts with a barrier after all earlier work on the queue; a serial
//   compute encoder and a blit encoder put a barrier after each dispatch / copy; memoryBarrier and fences map directly;
// - completion: commit feedback gives the GPU times and error, runs the completed handlers and recycles the allocator;
// - presentation: waitForDrawable / signalDrawable on the queue around the commit, then the drawable presents.
// Pipelines, textures, buffers, samplers made with the Metal 3 device API work unchanged in Metal 4 encoders.
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/QuartzCore.h>
#import <os/lock.h>
#include <stdatomic.h>
#import "mcmetal.h"

#pragma clang diagnostic ignored "-Wprotocol"
#pragma clang diagnostic ignored "-Wobjc-protocol-property-synthesis"
#pragma clang diagnostic ignored "-Wincomplete-implementation"
#pragma clang diagnostic ignored "-Wunguarded-availability-new"  // (only reached after mc4_queue_new found Metal 4)

int mc4On;
void mc_metal4(int on) { mc4On = on; }
int mc_metal4_on(void) { return mc4On; }

static id<MTLDevice> m4dev;
static id<MTL4CommandQueue> m4q;
static id<MTL4Compiler> m4compiler;
static id<MTLCommandQueue> m4facade;  // the McM4Queue (commandBuffer.commandQueue)

// ---- residency ----

static id<MTLResidencySet> rset;
static CFMutableDictionaryRef tracked;  // resource -> last commit serial that used it (PINNED: until unpinned)
static os_unfair_lock rlock = OS_UNFAIR_LOCK_INIT;
static int rdirty;
static uint64_t submitted;            // commits so far (render threads, under rlock)
static _Atomic uint64_t completed;    // highest commit serial the GPU finished
#define PINNED UINT64_MAX
static uint64_t ageSerial[4];         // the submission count at each of the last ageing scans (one a second)
static double ageNext;

static inline void track(id a) {
	if (!a) return;
	os_unfair_lock_lock(&rlock);
	const void *v;
	if (!CFDictionaryGetValueIfPresent(tracked, (const void *) a, &v)) {
		[rset addAllocation:(id<MTLAllocation>) a];
		rdirty = 1;
		v = 0;
	}
	if ((uint64_t) (uintptr_t) v != PINNED) CFDictionarySetValue(tracked, (const void *) a, (const void *) (uintptr_t) (submitted + 1));
	os_unfair_lock_unlock(&rlock);
}

// Resources reached by GPU address only (written into buffers): resident until unpinned.
void mc4_pin(id a) {
	if (!mc4On || !a) return;
	os_unfair_lock_lock(&rlock);
	if (!CFDictionaryContainsKey(tracked, (const void *) a)) {
		[rset addAllocation:(id<MTLAllocation>) a];
		rdirty = 1;
	}
	CFDictionarySetValue(tracked, (const void *) a, (const void *) (uintptr_t) PINNED);
	os_unfair_lock_unlock(&rlock);
}

// The owner let it go: it leaves the set (which keeps it alive meanwhile) as soon as the GPU has finished every commit
// that could use it. (Ageing alone held per-frame objects for seconds: 3 GB at 11,000 fps in Render Max FPS.)
static CFMutableArrayRef dying;      // released resources, in release order
static uint64_t *dyingSerial;        // the commit each must finish first
static CFIndex dyingCap;
void mc4_unpin(id a) {
	if (!mc4On || !a) return;
	os_unfair_lock_lock(&rlock);
	if (CFDictionaryContainsKey(tracked, (const void *) a)) {
		CFDictionarySetValue(tracked, (const void *) a, (const void *) (uintptr_t) (submitted + 1));
		CFIndex n = CFArrayGetCount(dying);
		if (n == dyingCap) {
			dyingCap = dyingCap ? dyingCap * 2 : 256;
			dyingSerial = realloc(dyingSerial, sizeof(uint64_t) * dyingCap);
		}
		CFArrayAppendValue(dying, (const void *) a);
		dyingSerial[n] = submitted + 1;
	}
	os_unfair_lock_unlock(&rlock);
}

// (rlock held) released resources the GPU is done with leave the set; one used again since (a pooled buffer handed out
// anew) stays
static void releaseDying(void) {
	CFIndex n = CFArrayGetCount(dying), k = 0;
	if (n == 0) return;
	uint64_t done = atomic_load(&completed);
	while (k < n && dyingSerial[k] <= done) {
		const void *a = CFArrayGetValueAtIndex(dying, k);
		const void *v;
		if (CFDictionaryGetValueIfPresent(tracked, a, &v) && (uint64_t) (uintptr_t) v <= dyingSerial[k]) {
			CFDictionaryRemoveValue(tracked, a);
			[rset removeAllocation:(id<MTLAllocation>) a];
			rdirty = 1;
		}
		k++;
	}
	if (k > 0) {
		CFArrayReplaceValues(dying, CFRangeMake(0, k), NULL, 0);
		memmove(dyingSerial, dyingSerial + k, sizeof(uint64_t) * (n - k));
	}
}

typedef struct { uint64_t limit; CFMutableArrayRef drop; } AgeCtx;

static void ageOne(const void *key, const void *value, void *c) {
	AgeCtx *a = c;
	uint64_t v = (uint64_t) (uintptr_t) value;
	if (v != PINNED && v <= a->limit) CFArrayAppendValue(a->drop, key);
}

// Before a commit (rlock held): the set's changes committed; once a second, resources unused for 3 s that the GPU is done
// with leave it.
static int m4log = -1;
static void residencyBeforeCommit(void) {
	releaseDying();
	double t = CACurrentMediaTime();
	if (t >= ageNext) {
		// (every quarter second: resources unused for about a second leave)
		ageNext = t + 0.25;
		if (m4log < 0) m4log = getenv("CADENCE_M4LOG") != NULL;
		static double logNext;
		if (m4log && t >= logNext) {
			logNext = t + 5;
			NSLog(@"cadence-metal4: resident %ld tracked, %ld released waiting, set %.0f MB, %llu commits (%llu done)", (long) CFDictionaryGetCount(tracked),
				(long) CFArrayGetCount(dying), rset.allocatedSize / 1048576.0, submitted, atomic_load(&completed));
		}
		uint64_t limit = ageSerial[0];
		uint64_t done = atomic_load(&completed);
		if (limit > done) limit = done;
		memmove(ageSerial, ageSerial + 1, sizeof(uint64_t) * 3);
		ageSerial[3] = submitted;
		if (limit > 0) {
			AgeCtx a = {limit, CFArrayCreateMutable(NULL, 0, NULL)};
			CFDictionaryApplyFunction(tracked, ageOne, &a);
			CFIndex n = CFArrayGetCount(a.drop);
			for (CFIndex i = 0; i < n; i++) {
				const void *k = CFArrayGetValueAtIndex(a.drop, i);
				CFDictionaryRemoveValue(tracked, k);
				[rset removeAllocation:(id<MTLAllocation>) k];  // (the set's reference: the resource may be freed now)
			}
			if (n > 0) rdirty = 1;
			CFRelease(a.drop);
		}
	}
	if (rdirty) {
		[rset commit];
		rdirty = 0;
	}
}

// ---- pools: allocators + command buffers, argument tables, byte chunks ----

// (an argument table's high-water marks, kept by the encoder that binds it: what is cleared before the table is reused)
typedef struct { int buf, tex, samp; } Marks;

static os_unfair_lock plock = OS_UNFAIR_LOCK_INIT;
static NSMutableArray *freeAllocs, *freeCbs, *freeTables, *freeChunks;
#define CHUNK (256 * 1024)

static id<MTL4ArgumentTable> takeTable(void) {
	os_unfair_lock_lock(&plock);
	id<MTL4ArgumentTable> t = [[freeTables lastObject] retain];
	if (t) [freeTables removeLastObject];
	os_unfair_lock_unlock(&plock);
	if (t) return t;
	MTL4ArgumentTableDescriptor *d = [[MTL4ArgumentTableDescriptor new] autorelease];
	d.maxBufferBindCount = 31;
	d.maxTextureBindCount = 128;
	d.maxSamplerStateBindCount = 16;
	NSError *e = nil;
	t = [m4dev newArgumentTableWithDescriptor:d error:&e];
	if (!t) NSLog(@"cadence-metal4: argument table: %@", e);
	return t;
}


static void clearTable(id<MTL4ArgumentTable> t, Marks m) {
	for (int i = 0; i < m.buf; i++) [t setAddress:0 atIndex:i];
	for (int i = 0; i < m.tex; i++) [t setTexture:(MTLResourceID) {0} atIndex:i];
	for (int i = 0; i < m.samp; i++) [t setSamplerState:(MTLResourceID) {0} atIndex:i];
}

static inline void tA(id<MTL4ArgumentTable> t, Marks *m, uint64_t a, NSUInteger i) {
	if ((int) i >= m->buf) m->buf = (int) i + 1;
	[t setAddress:a atIndex:i];
}
static inline void tT(id<MTL4ArgumentTable> t, Marks *m, MTLResourceID r, NSUInteger i) {
	if ((int) i >= m->tex) m->tex = (int) i + 1;
	[t setTexture:r atIndex:i];
}
static inline void tS(id<MTL4ArgumentTable> t, Marks *m, MTLResourceID r, NSUInteger i) {
	if ((int) i >= m->samp) m->samp = (int) i + 1;
	[t setSamplerState:r atIndex:i];
}

static void giveTable(id<MTL4ArgumentTable> t) {
	if (!t) return;
	os_unfair_lock_lock(&plock);
	[freeTables addObject:t];
	os_unfair_lock_unlock(&plock);
	[t release];
}

static id<MTLBuffer> takeChunk(void) {
	os_unfair_lock_lock(&plock);
	id<MTLBuffer> b = [[freeChunks lastObject] retain];
	if (b) [freeChunks removeLastObject];
	os_unfair_lock_unlock(&plock);
	if (!b) {
		b = [m4dev newBufferWithLength:CHUNK options:MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined];
		mc4_pin(b);
	}
	return b;
}

// ---- the facades ----

@class McM4CB;

@interface McM4Queue : NSObject <MTLCommandQueue>
@end

@interface McM4CB : NSObject <MTLCommandBuffer> {
@public
	id<MTL4CommandBuffer> cb;
	id<MTL4CommandAllocator> alloc;
	NSMutableArray *chunks;       // byte chunks used (the last one is current)
	NSUInteger chunkUsed;
	NSMutableArray *completedHandlers, *scheduledHandlers;
	NSMutableArray *drawables;    // to present after the commit
	CFTimeInterval minDuration;   // presentDrawable:afterMinimumDuration:
	NSMutableArray *waitEvents, *signalEvents;  // {event, value} pairs: before / after the commit
	dispatch_group_t done;
	MTLCommandBufferStatus status;
	CFTimeInterval gpuStart, gpuEnd;
	NSError *err;
	NSString *label;
	uint64_t serial;
	id open;                      // the encoder open on it
	CFMutableArrayRef keep;       // what an MTL4 command buffer doesn't retain (pipeline and depth states, scalers): until done
	const void *lastKept;
}
@end

// Kept alive until the GPU has run the command buffer (Metal 3 command buffers retained what they used; an evicted cached
// pipeline was freed while a commit still used it).
static inline void keepFor(McM4CB *c, id o) {
	if (!o || (const void *) o == c->lastKept) return;
	c->lastKept = (const void *) o;
	if (!c->keep) c->keep = CFArrayCreateMutable(NULL, 0, &kCFTypeArrayCallBacks);
	CFArrayAppendValue(c->keep, (const void *) o);
}

static uint64_t bytesAddr(McM4CB *c, const void *bytes, NSUInteger length) {
	NSUInteger aligned = (length + 15) & ~(NSUInteger) 15;
	if (aligned > CHUNK) {
		// (bigger than a chunk: its own buffer, kept with the chunks and released after the GPU is done)
		id<MTLBuffer> b = [m4dev newBufferWithBytes:bytes length:length options:MTLResourceStorageModeShared];
		track(b);
		keepFor(c, b);
		mc4_unpin(b);  // (leaves the set once this commit is done)
		uint64_t a = b.gpuAddress;
		[b release];
		return a;
	}
	if (c->chunks.count == 0 || c->chunkUsed + aligned > CHUNK) {
		id<MTLBuffer> b = takeChunk();
		[c->chunks addObject:b];
		[b release];
		c->chunkUsed = 0;
	}
	id<MTLBuffer> b = [c->chunks lastObject];
	memcpy((char *) b.contents + c->chunkUsed, bytes, length);
	uint64_t a = b.gpuAddress + c->chunkUsed;
	c->chunkUsed += aligned;
	return a;
}

// Every encoder: after everything earlier on the queue (Metal 3 ordered dependent work for us; Metal 4 doesn't).
static void barrierAll(id<MTL4CommandEncoder> e) {
	[e barrierAfterQueueStages:MTLStageAll beforeStages:MTLStageAll visibilityOptions:MTL4VisibilityOptionDevice];
}

static MTLStages fenceStages(MTLRenderStages s) {
	MTLStages out = 0;
	if (s & MTLRenderStageVertex) out |= MTLStageVertex;
	if (s & MTLRenderStageFragment) out |= MTLStageFragment;
	if (s & MTLRenderStageTile) out |= MTLStageTile;
	if (s & MTLRenderStageObject) out |= MTLStageObject;
	if (s & MTLRenderStageMesh) out |= MTLStageMesh;
	return out ? out : MTLStageVertex | MTLStageFragment;
}

// ---- render ----

@interface McM4Render : NSObject <MTLRenderCommandEncoder> {
@public
	McM4CB *owner;
	id<MTL4RenderCommandEncoder> r;
	id<MTL4ArgumentTable> vt, ft, mt, tt;
	Marks vtm, ftm, mtm, ttm;
	uint64_t vbase[31], fbase[31];  // the buffers' addresses at each index (set*BufferOffset)
	NSUInteger tileW, tileH;
}
@end

// (the mesh / object and tile stages' tables: made at their first binding, few passes have those stages)
#define MT (mt ? mt : (mt = [self stageTable:MTLRenderStageMesh | MTLRenderStageObject]))
#define TT (tt ? tt : (tt = [self stageTable:MTLRenderStageTile]))

@implementation McM4Render
- (id<MTL4ArgumentTable>)stageTable:(MTLRenderStages)stages {
	id<MTL4ArgumentTable> t = takeTable();
	[r setArgumentTable:t atStages:stages];
	return t;
}
- (id<MTLDevice>)device { return m4dev; }
- (NSString *)label { return nil; }
- (void)setLabel:(NSString *)l { r.label = l; }
- (void)setRenderPipelineState:(id<MTLRenderPipelineState>)p { keepFor(owner, p); [r setRenderPipelineState:p]; }
- (void)setDepthStencilState:(id<MTLDepthStencilState>)s { keepFor(owner, s); [r setDepthStencilState:s]; }
- (NSUInteger)tileWidth { return tileW; }
- (NSUInteger)tileHeight { return tileH; }
- (void)setCullMode:(MTLCullMode)m { [r setCullMode:m]; }
- (void)setFrontFacingWinding:(MTLWinding)w { [r setFrontFacingWinding:w]; }
- (void)setTriangleFillMode:(MTLTriangleFillMode)m { [r setTriangleFillMode:m]; }
- (void)setDepthBias:(float)b slopeScale:(float)s clamp:(float)c { [r setDepthBias:b slopeScale:s clamp:c]; }
- (void)setViewport:(MTLViewport)v { [r setViewport:v]; }
- (void)setScissorRect:(MTLScissorRect)s { [r setScissorRect:s]; }
- (void)setStencilReferenceValue:(uint32_t)v { [r setStencilReferenceValue:v]; }
- (void)setBlendColorRed:(float)red green:(float)g blue:(float)b alpha:(float)a { [r setBlendColorRed:red green:g blue:b alpha:a]; }
- (void)setVisibilityResultMode:(MTLVisibilityResultMode)m offset:(NSUInteger)o { [r setVisibilityResultMode:m offset:o]; }
- (void)setColorStoreAction:(MTLStoreAction)a atIndex:(NSUInteger)i { [r setColorStoreAction:a atIndex:i]; }
- (void)setDepthStoreAction:(MTLStoreAction)a { [r setDepthStoreAction:a]; }
- (void)setStencilStoreAction:(MTLStoreAction)a { [r setStencilStoreAction:a]; }
// bindings
- (void)setVertexBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i {
	track(b);
	if (i < 31) vbase[i] = b ? b.gpuAddress : 0;
	tA(vt, &vtm, b ? b.gpuAddress + o : 0, i);
}
- (void)setFragmentBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i {
	track(b);
	if (i < 31) fbase[i] = b ? b.gpuAddress : 0;
	tA(ft, &ftm, b ? b.gpuAddress + o : 0, i);
}
// (the rest of the interface an addon reaching the encoder through Cadence's API may call)
- (void)setVertexBufferOffset:(NSUInteger)o atIndex:(NSUInteger)i { if (i < 31 && vbase[i]) tA(vt, &vtm, vbase[i] + o, i); }
- (void)setFragmentBufferOffset:(NSUInteger)o atIndex:(NSUInteger)i { if (i < 31 && fbase[i]) tA(ft, &ftm, fbase[i] + o, i); }
- (void)setVertexBuffers:(const id<MTLBuffer> *)b offsets:(const NSUInteger *)o withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setVertexBuffer:b[k] offset:o[k] atIndex:r_.location + k];
}
- (void)setFragmentBuffers:(const id<MTLBuffer> *)b offsets:(const NSUInteger *)o withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setFragmentBuffer:b[k] offset:o[k] atIndex:r_.location + k];
}
- (void)setVertexTextures:(const id<MTLTexture> *)t withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setVertexTexture:t[k] atIndex:r_.location + k];
}
- (void)setFragmentTextures:(const id<MTLTexture> *)t withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setFragmentTexture:t[k] atIndex:r_.location + k];
}
- (void)setFragmentSamplerStates:(const id<MTLSamplerState> *)s withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setFragmentSamplerState:s[k] atIndex:r_.location + k];
}
- (void)setVertexSamplerStates:(const id<MTLSamplerState> *)s withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setVertexSamplerState:s[k] atIndex:r_.location + k];
}
- (void)setViewports:(const MTLViewport *)v count:(NSUInteger)n { [r setViewports:v count:n]; }
- (void)setScissorRects:(const MTLScissorRect *)s count:(NSUInteger)n { [r setScissorRects:s count:n]; }
- (void)setDepthClipMode:(MTLDepthClipMode)m { [r setDepthClipMode:m]; }
- (void)setStencilFrontReferenceValue:(uint32_t)f backReferenceValue:(uint32_t)b { [r setStencilFrontReferenceValue:f backReferenceValue:b]; }
- (void)setMeshBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i { track(b); tA(MT, &mtm, b ? b.gpuAddress + o : 0, i); }
- (void)setObjectBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i { track(b); tA(MT, &mtm, b ? b.gpuAddress + o : 0, i); }
- (void)setTileBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i { track(b); tA(TT, &ttm, b ? b.gpuAddress + o : 0, i); }
- (void)setVertexBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(vt, &vtm, bytesAddr(owner, p, n), i); }
- (void)setFragmentBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(ft, &ftm, bytesAddr(owner, p, n), i); }
- (void)setMeshBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(MT, &mtm, bytesAddr(owner, p, n), i); }
- (void)setObjectBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(MT, &mtm, bytesAddr(owner, p, n), i); }
- (void)setTileBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(TT, &ttm, bytesAddr(owner, p, n), i); }
- (void)setVertexTexture:(id<MTLTexture>)t atIndex:(NSUInteger)i { track(t); tT(vt, &vtm, t ? t.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setFragmentTexture:(id<MTLTexture>)t atIndex:(NSUInteger)i { track(t); tT(ft, &ftm, t ? t.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setMeshTexture:(id<MTLTexture>)t atIndex:(NSUInteger)i { track(t); tT(MT, &mtm, t ? t.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setTileTexture:(id<MTLTexture>)t atIndex:(NSUInteger)i { track(t); tT(TT, &ttm, t ? t.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setVertexSamplerState:(id<MTLSamplerState>)s atIndex:(NSUInteger)i { tS(vt, &vtm, s ? s.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setFragmentSamplerState:(id<MTLSamplerState>)s atIndex:(NSUInteger)i { tS(ft, &ftm, s ? s.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setMeshSamplerState:(id<MTLSamplerState>)s atIndex:(NSUInteger)i { tS(MT, &mtm, s ? s.gpuResourceID : (MTLResourceID) {0}, i); }
// draws
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n {
	[r drawPrimitives:t vertexStart:s vertexCount:n];
}
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n instanceCount:(NSUInteger)k {
	[r drawPrimitives:t vertexStart:s vertexCount:n instanceCount:k];
}
- (void)drawPrimitives:(MTLPrimitiveType)t vertexStart:(NSUInteger)s vertexCount:(NSUInteger)n instanceCount:(NSUInteger)k baseInstance:(NSUInteger)b {
	[r drawPrimitives:t vertexStart:s vertexCount:n instanceCount:k baseInstance:b];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)o {
	track(ib);
	[r drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib.gpuAddress + o indexBufferLength:ib.length - o];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)o
	instanceCount:(NSUInteger)k {
	track(ib);
	[r drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib.gpuAddress + o indexBufferLength:ib.length - o instanceCount:k];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexCount:(NSUInteger)n indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)o
	instanceCount:(NSUInteger)k baseVertex:(NSInteger)bv baseInstance:(NSUInteger)bi {
	track(ib);
	[r drawIndexedPrimitives:t indexCount:n indexType:it indexBuffer:ib.gpuAddress + o indexBufferLength:ib.length - o instanceCount:k baseVertex:bv baseInstance:bi];
}
- (void)drawPrimitives:(MTLPrimitiveType)t indirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)o {
	track(b);
	[r drawPrimitives:t indirectBuffer:b.gpuAddress + o];
}
- (void)drawIndexedPrimitives:(MTLPrimitiveType)t indexType:(MTLIndexType)it indexBuffer:(id<MTLBuffer>)ib indexBufferOffset:(NSUInteger)o
	indirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)io {
	track(ib);
	track(b);
	[r drawIndexedPrimitives:t indexType:it indexBuffer:ib.gpuAddress + o indexBufferLength:ib.length - o indirectBuffer:b.gpuAddress + io];
}
- (void)drawMeshThreadgroupsWithIndirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)o threadsPerObjectThreadgroup:(MTLSize)ot
	threadsPerMeshThreadgroup:(MTLSize)mt_ {
	track(b);
	[r drawMeshThreadgroupsWithIndirectBuffer:b.gpuAddress + o threadsPerObjectThreadgroup:ot threadsPerMeshThreadgroup:mt_];
}
- (void)drawMeshThreadgroups:(MTLSize)g threadsPerObjectThreadgroup:(MTLSize)ot threadsPerMeshThreadgroup:(MTLSize)mt_ {
	[r drawMeshThreadgroups:g threadsPerObjectThreadgroup:ot threadsPerMeshThreadgroup:mt_];
}
- (void)executeCommandsInBuffer:(id<MTLIndirectCommandBuffer>)icb withRange:(NSRange)range {
	track(icb);
	[r executeCommandsInBuffer:icb withRange:range];
}
- (void)dispatchThreadsPerTile:(MTLSize)s {
	// (the tile stage reads what the draws before it wrote: Metal 3 ordered that within the pass)
	[r barrierAfterEncoderStages:MTLStageFragment beforeEncoderStages:MTLStageTile visibilityOptions:MTL4VisibilityOptionDevice];
	[r dispatchThreadsPerTile:s];
}
- (void)setThreadgroupMemoryLength:(NSUInteger)l offset:(NSUInteger)o atIndex:(NSUInteger)i { [r setThreadgroupMemoryLength:l offset:o atIndex:i]; }
// synchronisation
- (void)memoryBarrierWithScope:(MTLBarrierScope)s afterStages:(MTLRenderStages)a beforeStages:(MTLRenderStages)b {
	[r barrierAfterEncoderStages:fenceStages(a) beforeEncoderStages:fenceStages(b) visibilityOptions:MTL4VisibilityOptionDevice];
}
- (void)memoryBarrierWithResources:(const id<MTLResource> *)res count:(NSUInteger)n afterStages:(MTLRenderStages)a beforeStages:(MTLRenderStages)b {
	[r barrierAfterEncoderStages:fenceStages(a) beforeEncoderStages:fenceStages(b) visibilityOptions:MTL4VisibilityOptionDevice];
}
- (void)updateFence:(id<MTLFence>)f afterStages:(MTLRenderStages)s { [r updateFence:f afterEncoderStages:fenceStages(s)]; }
- (void)waitForFence:(id<MTLFence>)f beforeStages:(MTLRenderStages)s { [r waitForFence:f beforeEncoderStages:fenceStages(s)]; }
- (void)useResource:(id<MTLResource>)res usage:(MTLResourceUsage)u stages:(MTLRenderStages)s { track(res); }
- (void)useResource:(id<MTLResource>)res usage:(MTLResourceUsage)u { track(res); }
- (void)useResources:(const id<MTLResource> *)res count:(NSUInteger)n usage:(MTLResourceUsage)u stages:(MTLRenderStages)s {
	for (NSUInteger i = 0; i < n; i++) track(res[i]);
}
- (void)pushDebugGroup:(NSString *)s { [r pushDebugGroup:s]; }
- (void)popDebugGroup { [r popDebugGroup]; }
- (void)insertDebugSignpost:(NSString *)s { [r insertDebugSignpost:s]; }
- (void)endEncoding {
	[r endEncoding];
	[r release];
	r = nil;
	if (vt) clearTable(vt, vtm);
	if (ft) clearTable(ft, ftm);
	if (mt) clearTable(mt, mtm);
	if (tt) clearTable(tt, ttm);
	giveTable(vt);
	giveTable(ft);
	giveTable(mt);
	giveTable(tt);
	vt = ft = mt = tt = nil;
	if (owner->open == self) owner->open = nil;
}
@end

// ---- compute (and blit: Metal 4 does both in one kind of encoder) ----

@interface McM4Compute : NSObject <MTLComputeCommandEncoder, MTLBlitCommandEncoder> {
@public
	McM4CB *owner;
	id<MTL4ComputeCommandEncoder> c;
	id<MTL4ArgumentTable> t;
	Marks tm;
	uint64_t base[31];
	int serial;  // Metal 3's serial dispatch (and every blit): each command after the previous one
}
@end

#define AFTER_EACH() \
	do { \
		if (serial) [c barrierAfterEncoderStages:MTLStageDispatch | MTLStageBlit beforeEncoderStages:MTLStageDispatch | MTLStageBlit \
			visibilityOptions:MTL4VisibilityOptionDevice]; \
	} while (0)

@implementation McM4Compute
- (id<MTLDevice>)device { return m4dev; }
- (NSString *)label { return nil; }
- (void)setLabel:(NSString *)l { c.label = l; }
- (MTLDispatchType)dispatchType { return serial ? MTLDispatchTypeSerial : MTLDispatchTypeConcurrent; }
- (void)setComputePipelineState:(id<MTLComputePipelineState>)p { keepFor(owner, p); [c setComputePipelineState:p]; }
- (void)setBuffer:(id<MTLBuffer>)b offset:(NSUInteger)o atIndex:(NSUInteger)i {
	track(b);
	if (i < 31) base[i] = b ? b.gpuAddress : 0;
	tA(t, &tm, b ? b.gpuAddress + o : 0, i);
}
- (void)setBufferOffset:(NSUInteger)o atIndex:(NSUInteger)i { if (i < 31 && base[i]) tA(t, &tm, base[i] + o, i); }
- (void)setBuffers:(const id<MTLBuffer> *)b offsets:(const NSUInteger *)o withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setBuffer:b[k] offset:o[k] atIndex:r_.location + k];
}
- (void)setSamplerStates:(const id<MTLSamplerState> *)s withRange:(NSRange)r_ {
	for (NSUInteger k = 0; k < r_.length; k++) [self setSamplerState:s[k] atIndex:r_.location + k];
}
- (void)setBytes:(const void *)p length:(NSUInteger)n atIndex:(NSUInteger)i { tA(t, &tm, bytesAddr(owner, p, n), i); }
- (void)setTexture:(id<MTLTexture>)tx atIndex:(NSUInteger)i { track(tx); tT(t, &tm, tx ? tx.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setTextures:(const id<MTLTexture> *)txs withRange:(NSRange)range {
	for (NSUInteger k = 0; k < range.length; k++) [self setTexture:txs[k] atIndex:range.location + k];
}
- (void)setSamplerState:(id<MTLSamplerState>)s atIndex:(NSUInteger)i { tS(t, &tm, s ? s.gpuResourceID : (MTLResourceID) {0}, i); }
- (void)setThreadgroupMemoryLength:(NSUInteger)l atIndex:(NSUInteger)i { [c setThreadgroupMemoryLength:l atIndex:i]; }
- (void)dispatchThreads:(MTLSize)g threadsPerThreadgroup:(MTLSize)tg { [c dispatchThreads:g threadsPerThreadgroup:tg]; AFTER_EACH(); }
- (void)dispatchThreadgroups:(MTLSize)g threadsPerThreadgroup:(MTLSize)tg { [c dispatchThreadgroups:g threadsPerThreadgroup:tg]; AFTER_EACH(); }
- (void)dispatchThreadgroupsWithIndirectBuffer:(id<MTLBuffer>)b indirectBufferOffset:(NSUInteger)o threadsPerThreadgroup:(MTLSize)tg {
	track(b);
	[c dispatchThreadgroupsWithIndirectBuffer:b.gpuAddress + o threadsPerThreadgroup:tg];
	AFTER_EACH();
}
- (void)executeCommandsInBuffer:(id<MTLIndirectCommandBuffer>)icb withRange:(NSRange)range {
	track(icb);
	[c executeCommandsInBuffer:icb withRange:range];
	AFTER_EACH();
}
- (void)memoryBarrierWithScope:(MTLBarrierScope)s {
	[c barrierAfterEncoderStages:MTLStageDispatch | MTLStageBlit beforeEncoderStages:MTLStageDispatch | MTLStageBlit visibilityOptions:MTL4VisibilityOptionDevice];
}
- (void)memoryBarrierWithResources:(const id<MTLResource> *)res count:(NSUInteger)n { [self memoryBarrierWithScope:MTLBarrierScopeBuffers]; }
- (void)updateFence:(id<MTLFence>)f { [c updateFence:f afterEncoderStages:MTLStageDispatch | MTLStageBlit]; }
- (void)waitForFence:(id<MTLFence>)f { [c waitForFence:f beforeEncoderStages:MTLStageDispatch | MTLStageBlit]; }
- (void)useResource:(id<MTLResource>)res usage:(MTLResourceUsage)u { track(res); }
- (void)useResources:(const id<MTLResource> *)res count:(NSUInteger)n usage:(MTLResourceUsage)u { for (NSUInteger i = 0; i < n; i++) track(res[i]); }
// blits
- (void)copyFromBuffer:(id<MTLBuffer>)s sourceOffset:(NSUInteger)so toBuffer:(id<MTLBuffer>)d destinationOffset:(NSUInteger)dof size:(NSUInteger)n {
	track(s);
	track(d);
	[c copyFromBuffer:s sourceOffset:so toBuffer:d destinationOffset:dof size:n];
	AFTER_EACH();
}
- (void)copyFromBuffer:(id<MTLBuffer>)s sourceOffset:(NSUInteger)so sourceBytesPerRow:(NSUInteger)bpr sourceBytesPerImage:(NSUInteger)bpi sourceSize:(MTLSize)sz
	toTexture:(id<MTLTexture>)d destinationSlice:(NSUInteger)ds destinationLevel:(NSUInteger)dl destinationOrigin:(MTLOrigin)o {
	track(s);
	track(d);
	[c copyFromBuffer:s sourceOffset:so sourceBytesPerRow:bpr sourceBytesPerImage:bpi sourceSize:sz toTexture:d destinationSlice:ds destinationLevel:dl destinationOrigin:o];
	AFTER_EACH();
}
- (void)copyFromTexture:(id<MTLTexture>)s sourceSlice:(NSUInteger)ss sourceLevel:(NSUInteger)sl sourceOrigin:(MTLOrigin)so sourceSize:(MTLSize)sz
	toBuffer:(id<MTLBuffer>)d destinationOffset:(NSUInteger)dof destinationBytesPerRow:(NSUInteger)bpr destinationBytesPerImage:(NSUInteger)bpi {
	track(s);
	track(d);
	[c copyFromTexture:s sourceSlice:ss sourceLevel:sl sourceOrigin:so sourceSize:sz toBuffer:d destinationOffset:dof destinationBytesPerRow:bpr destinationBytesPerImage:bpi];
	AFTER_EACH();
}
- (void)copyFromTexture:(id<MTLTexture>)s sourceSlice:(NSUInteger)ss sourceLevel:(NSUInteger)sl sourceOrigin:(MTLOrigin)so sourceSize:(MTLSize)sz
	toBuffer:(id<MTLBuffer>)d destinationOffset:(NSUInteger)dof destinationBytesPerRow:(NSUInteger)bpr destinationBytesPerImage:(NSUInteger)bpi options:(MTLBlitOption)op {
	track(s);
	track(d);
	[c copyFromTexture:s sourceSlice:ss sourceLevel:sl sourceOrigin:so sourceSize:sz toBuffer:d destinationOffset:dof destinationBytesPerRow:bpr
		destinationBytesPerImage:bpi options:op];
	AFTER_EACH();
}
- (void)copyFromTexture:(id<MTLTexture>)s sourceSlice:(NSUInteger)ss sourceLevel:(NSUInteger)sl sourceOrigin:(MTLOrigin)so sourceSize:(MTLSize)sz
	toTexture:(id<MTLTexture>)d destinationSlice:(NSUInteger)ds destinationLevel:(NSUInteger)dl destinationOrigin:(MTLOrigin)o {
	track(s);
	track(d);
	[c copyFromTexture:s sourceSlice:ss sourceLevel:sl sourceOrigin:so sourceSize:sz toTexture:d destinationSlice:ds destinationLevel:dl destinationOrigin:o];
	AFTER_EACH();
}
- (void)copyFromTexture:(id<MTLTexture>)s toTexture:(id<MTLTexture>)d {
	track(s);
	track(d);
	[c copyFromTexture:s toTexture:d];
	AFTER_EACH();
}
- (void)fillBuffer:(id<MTLBuffer>)b range:(NSRange)range value:(uint8_t)v {
	track(b);
	[c fillBuffer:b range:range value:v];
	AFTER_EACH();
}
- (void)generateMipmapsForTexture:(id<MTLTexture>)tx {
	track(tx);
	[c generateMipmapsForTexture:tx];
	AFTER_EACH();
}
- (void)optimizeContentsForGPUAccess:(id<MTLTexture>)tx { track(tx); [c optimizeContentsForGPUAccess:tx]; AFTER_EACH(); }
- (void)synchronizeResource:(id<MTLResource>)res {}  // (shared storage only on Apple Silicon: nothing to synchronise)
- (void)pushDebugGroup:(NSString *)s { [c pushDebugGroup:s]; }
- (void)popDebugGroup { [c popDebugGroup]; }
- (void)insertDebugSignpost:(NSString *)s { [c insertDebugSignpost:s]; }
- (void)endEncoding {
	[c endEncoding];
	[c release];
	c = nil;
	if (t) clearTable(t, tm);
	giveTable(t);
	t = nil;
	if (owner->open == self) owner->open = nil;
}
@end

// ---- command buffer ----

@implementation McM4CB
- (id<MTLDevice>)device { return m4dev; }
- (id<MTLCommandQueue>)commandQueue { return m4facade; }
- (BOOL)retainedReferences { return NO; }
- (MTLCommandBufferErrorOption)errorOptions { return MTLCommandBufferErrorOptionNone; }
- (NSString *)label { return label; }
- (void)setLabel:(NSString *)l { [label release]; label = [l copy]; }
- (MTLCommandBufferStatus)status { return status; }
- (NSError *)error { return err; }
- (CFTimeInterval)GPUStartTime { return gpuStart; }
- (CFTimeInterval)GPUEndTime { return gpuEnd; }
- (CFTimeInterval)kernelStartTime { return gpuStart; }
- (CFTimeInterval)kernelEndTime { return gpuEnd; }
- (id<MTLLogContainer>)logs { return nil; }

- (id<MTLRenderCommandEncoder>)renderCommandEncoderWithDescriptor:(MTLRenderPassDescriptor *)d {
	MTL4RenderPassDescriptor *p = [[MTL4RenderPassDescriptor new] autorelease];
	for (int i = 0; i < 8; i++) {
		MTLRenderPassColorAttachmentDescriptor *a = d.colorAttachments[i];
		if (!a.texture) continue;
		track(a.texture);
		if (a.resolveTexture) track(a.resolveTexture);
		p.colorAttachments[i] = a;
	}
	if (d.depthAttachment.texture) {
		track(d.depthAttachment.texture);
		p.depthAttachment = d.depthAttachment;
	}
	if (d.stencilAttachment.texture) {
		track(d.stencilAttachment.texture);
		p.stencilAttachment = d.stencilAttachment;
	}
	p.renderTargetArrayLength = d.renderTargetArrayLength;
	p.renderTargetWidth = d.renderTargetWidth;
	p.renderTargetHeight = d.renderTargetHeight;
	p.defaultRasterSampleCount = d.defaultRasterSampleCount;
	p.imageblockSampleLength = d.imageblockSampleLength;
	p.threadgroupMemoryLength = d.threadgroupMemoryLength;
	p.tileWidth = d.tileWidth;
	p.tileHeight = d.tileHeight;
	if (d.visibilityResultBuffer) {
		track(d.visibilityResultBuffer);
		p.visibilityResultBuffer = d.visibilityResultBuffer;
	}
	McM4Render *e = [McM4Render new];
	e->owner = self;
	e->r = [[cb renderCommandEncoderWithDescriptor:p] retain];
	e->tileW = d.tileWidth ? d.tileWidth : 32;
	e->tileH = d.tileHeight ? d.tileHeight : 32;
	if (!e->r) {
		[e release];
		return nil;
	}
	barrierAll(e->r);
	e->vt = takeTable();
	e->ft = takeTable();
	[e->r setArgumentTable:e->vt atStages:MTLRenderStageVertex];
	[e->r setArgumentTable:e->ft atStages:MTLRenderStageFragment];
	open = e;
	return [e autorelease];
}

- (McM4Compute *)compute:(int)serial {
	McM4Compute *e = [McM4Compute new];
	e->owner = self;
	e->c = [[cb computeCommandEncoder] retain];
	e->serial = serial;
	barrierAll(e->c);
	e->t = takeTable();
	[e->c setArgumentTable:e->t];
	open = e;
	return [e autorelease];
}
- (id<MTLComputeCommandEncoder>)computeCommandEncoder { return [self compute:1]; }
- (id<MTLComputeCommandEncoder>)computeCommandEncoderWithDispatchType:(MTLDispatchType)t { return [self compute:t == MTLDispatchTypeSerial]; }
- (id<MTLComputeCommandEncoder>)computeCommandEncoderWithDescriptor:(MTLComputePassDescriptor *)d { return [self compute:d.dispatchType == MTLDispatchTypeSerial]; }
- (id<MTLBlitCommandEncoder>)blitCommandEncoder { return [self compute:1]; }
- (id<MTLBlitCommandEncoder>)blitCommandEncoderWithDescriptor:(MTLBlitPassDescriptor *)d { return [self compute:1]; }

- (void)addCompletedHandler:(MTLCommandBufferHandler)h {
	MTLCommandBufferHandler copy = [h copy];
	[completedHandlers addObject:copy];
	[copy release];
}
- (void)addScheduledHandler:(MTLCommandBufferHandler)h {
	if (!scheduledHandlers) scheduledHandlers = [NSMutableArray new];
	MTLCommandBufferHandler copy = [h copy];
	[scheduledHandlers addObject:copy];
	[copy release];
}
- (void)presentDrawable:(id<MTLDrawable>)d { [self presentDrawable:d afterMinimumDuration:0]; }
- (void)presentDrawable:(id<MTLDrawable>)d afterMinimumDuration:(CFTimeInterval)m {
	if (!drawables) drawables = [NSMutableArray new];
	[drawables addObject:d];
	minDuration = m;
}
- (void)encodeWaitForEvent:(id<MTLEvent>)e value:(uint64_t)v {
	if (!waitEvents) waitEvents = [NSMutableArray new];
	[waitEvents addObject:@[e, @(v)]];
}
- (void)encodeSignalEvent:(id<MTLEvent>)e value:(uint64_t)v {
	if (!signalEvents) signalEvents = [NSMutableArray new];
	[signalEvents addObject:@[e, @(v)]];
}
- (void)pushDebugGroup:(NSString *)s { [cb pushDebugGroup:s]; }
- (void)popDebugGroup { [cb popDebugGroup]; }
- (void)enqueue {}
- (void)waitUntilScheduled {}
- (void)waitUntilCompleted { if (status >= MTLCommandBufferStatusCommitted) dispatch_group_wait(done, DISPATCH_TIME_FOREVER); }

- (void)finished:(id<MTL4CommitFeedback>)fb {
	gpuStart = fb.GPUStartTime;
	gpuEnd = fb.GPUEndTime;
	if (fb.error) err = [fb.error retain];
	status = err ? MTLCommandBufferStatusError : MTLCommandBufferStatusCompleted;
	uint64_t s = serial, c = atomic_load(&completed);
	while (c < s && !atomic_compare_exchange_weak(&completed, &c, s)) {}
	for (MTLCommandBufferHandler h in completedHandlers) h(self);
	[completedHandlers removeAllObjects];
	// the allocator and the byte chunks are free again
	[alloc reset];
	os_unfair_lock_lock(&plock);
	[freeAllocs addObject:alloc];
	[freeCbs addObject:cb];
	for (id<MTLBuffer> b in chunks)
		if (b.length == CHUNK) [freeChunks addObject:b];
	os_unfair_lock_unlock(&plock);
	[chunks removeAllObjects];
	if (keep) {
		CFRelease(keep);
		keep = NULL;
	}
	dispatch_group_leave(done);
}

- (void)commit {
	[cb endCommandBuffer];
	for (NSArray *w in waitEvents) [m4q waitForEvent:w[0] value:[w[1] unsignedLongLongValue]];
	for (id<MTLDrawable> d in drawables) [m4q waitForDrawable:d];
	os_unfair_lock_lock(&rlock);
	residencyBeforeCommit();
	serial = ++submitted;
	os_unfair_lock_unlock(&rlock);
	MTL4CommitOptions *o = [[MTL4CommitOptions new] autorelease];
	[self retain];  // until its feedback
	[o addFeedbackHandler:^(id<MTL4CommitFeedback> fb) {
		[self finished:fb];
		[self release];
	}];
	status = MTLCommandBufferStatusCommitted;
	id<MTL4CommandBuffer> list[1] = {cb};
	[m4q commit:list count:1 options:o];
	for (NSArray *s in signalEvents) [m4q signalEvent:s[0] value:[s[1] unsignedLongLongValue]];
	for (id<MTLDrawable> d in drawables) {
		[m4q signalDrawable:d];
		if (minDuration > 0) [d presentAfterMinimumDuration:minDuration];
		else [d present];
	}
	for (MTLCommandBufferHandler h in scheduledHandlers) h(self);
	[scheduledHandlers removeAllObjects];
}

- (void)dealloc {
	[chunks release];
	[completedHandlers release];
	[scheduledHandlers release];
	[drawables release];
	[waitEvents release];
	[signalEvents release];
	[err release];
	[label release];
	if (keep) CFRelease(keep);
	[alloc release];
	[cb release];
	dispatch_release(done);
	[super dealloc];
}
@end

static McM4CB *newCB(void) {
	McM4CB *c = [McM4CB new];
	os_unfair_lock_lock(&plock);
	c->alloc = [[freeAllocs lastObject] retain];
	if (c->alloc) [freeAllocs removeLastObject];
	c->cb = [[freeCbs lastObject] retain];
	if (c->cb) [freeCbs removeLastObject];
	os_unfair_lock_unlock(&plock);
	if (!c->alloc) c->alloc = [m4dev newCommandAllocator];
	if (!c->cb) c->cb = [m4dev newCommandBuffer];
	[c->cb beginCommandBufferWithAllocator:c->alloc];
	c->chunks = [NSMutableArray new];
	c->completedHandlers = [NSMutableArray new];
	c->done = dispatch_group_create();
	dispatch_group_enter(c->done);
	c->status = MTLCommandBufferStatusNotEnqueued;
	return [c autorelease];
}

@implementation McM4Queue
- (id<MTLDevice>)device { return m4dev; }
- (NSString *)label { return nil; }
- (void)setLabel:(NSString *)l {}
- (id<MTLCommandBuffer>)commandBuffer { return newCB(); }
- (id<MTLCommandBuffer>)commandBufferWithUnretainedReferences { return newCB(); }
- (id<MTLCommandBuffer>)commandBufferWithDescriptor:(MTLCommandBufferDescriptor *)d { return newCB(); }
- (void)insertDebugCaptureBoundary {}
- (void)addResidencySet:(id<MTLResidencySet>)s { [m4q addResidencySet:s]; }
- (void)removeResidencySet:(id<MTLResidencySet>)s { [m4q removeResidencySet:s]; }
@end

// The context's queue under Metal 4 (mc_create, when mc_metal4(1) was called first); nil when Metal 4 isn't available.
id<MTLCommandQueue> mc4_queue_new(id<MTLDevice> device) {
	if (![device respondsToSelector:@selector(newMTL4CommandQueue)]) return nil;
	m4dev = device;
	m4q = [device newMTL4CommandQueue];
	NSError *e = nil;
	rset = [device newResidencySetWithDescriptor:[[MTLResidencySetDescriptor new] autorelease] error:&e];
	if (!m4q || !rset) {
		NSLog(@"cadence-metal4: no Metal 4 queue (%@): Metal 3", e);
		return nil;
	}
	[m4q addResidencySet:rset];
	tracked = CFDictionaryCreateMutable(NULL, 0, NULL, NULL);
	dying = CFArrayCreateMutable(NULL, 0, NULL);
	freeAllocs = [NSMutableArray new];
	freeCbs = [NSMutableArray new];
	freeTables = [NSMutableArray new];
	freeChunks = [NSMutableArray new];
	m4compiler = [device newCompilerWithDescriptor:[[MTL4CompilerDescriptor new] autorelease] error:&e];
	m4facade = [McM4Queue new];
	return [m4facade retain];
}

// MetalFX under Metal 4: the scaler made with the compiler, encoded into the facade's own MTL4CommandBuffer.
void *mc4_fx_spatial_new(MTLFXSpatialScalerDescriptor *d) { return m4compiler ? (void *) [d newSpatialScalerWithDevice:m4dev compiler:m4compiler] : NULL; }
void mc4_fx_spatial_encode(void *scaler, id<MTLCommandBuffer> facade, id<MTLTexture> in, id<MTLTexture> out) {
	McM4CB *c = (McM4CB *) facade;
	track(in);
	track(out);
	// (MetalFX's own encoders wait on its fence, which an encoder updates after everything before it is done; the next facade
	// encoder's barrier orders what follows)
	static id<MTLFence> fence;
	if (!fence) fence = [m4dev newFence];
	id<MTL4ComputeCommandEncoder> b = [c->cb computeCommandEncoder];
	barrierAll(b);
	[b updateFence:fence afterEncoderStages:MTLStageAll];
	[b endEncoding];
	id<MTL4FXSpatialScaler> s = (id<MTL4FXSpatialScaler>) scaler;
	keepFor(c, s);  // (released on a resize while a frame in flight still used it)
	s.fence = fence;
	s.colorTexture = in;
	s.outputTexture = out;
	s.inputContentWidth = in.width;
	s.inputContentHeight = in.height;
	[s encodeToCommandBuffer:c->cb];
}
