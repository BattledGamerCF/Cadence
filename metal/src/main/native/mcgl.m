// Cadence OpenGL compatibility (dev.cadence.metal.compat.GlFallback): mods that draw with OpenGL directly draw into a target that
// is shared with Metal through an IOSurface (no copy), and each present lays that picture over the frame (straight alpha), so
// what they draw is seen. Only used while the compatibility context exists (an installed mod calls OpenGL). The overlay has no
// depth: what such a mod draws is on top of the world, as a HUD would be.
#define GL_SILENCE_DEPRECATION
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/CGLIOSurface.h>
#import <OpenGL/gl3.h>
#include "mcmetal.h"

static struct {
	IOSurfaceRef surface;
	id<MTLTexture> texture;
	GLuint glTexture, fbo, queries[2];
	int frame;      // which query this frame uses
	int drewLately; // the last frame whose count is in: something was drawn
	int dirty;      // something was drawn into the target since it was last cleared
	int queryOpen;  // the samples query of this frame is running
	int w, h;
	id<MTLRenderPipelineState> pipelines[2];  // RGBA8Unorm, RGBA16Float targets
	MTLPixelFormat formats[2];
} glo;

static NSString *overlaySource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V glo_vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	// OpenGL's rows start at the bottom
	"  V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = float2(p.x, p.y); return v;\n"
	"}\n"
	"fragment float4 glo_fs(V v [[stage_in]], texture2d<float> t [[texture(0)]]) {\n"
	"  constexpr sampler s(filter::nearest);\n"
	"  return t.sample(s, float2(v.uv.x, 1.0 - v.uv.y));\n"
	"}\n";

static void releaseTarget(void) {
	if (glo.fbo) glDeleteFramebuffers(1, &glo.fbo);
	if (glo.glTexture) glDeleteTextures(1, &glo.glTexture);
	if (glo.texture) [glo.texture release];
	if (glo.surface) CFRelease(glo.surface);
	glo.fbo = glo.glTexture = 0;
	glo.texture = nil;
	glo.surface = NULL;
	glo.w = glo.h = 0;
}

// Render thread, the compatibility context current: the shared target made (again, for a new size), bound as what OpenGL draws
// into, and cleared for the next frame. 0 if it can't be made (then OpenGL draws stay invisible, as before).
int mc_gl_overlay_begin(Ctx *ctx, int w, int h) {
	@autoreleasepool {
		CGLContextObj cgl = CGLGetCurrentContext();
		if (!cgl || w <= 0 || h <= 0) return 0;
		if (glo.w != w || glo.h != h || !glo.surface) {
			releaseTarget();
			NSDictionary *props = @{(id) kIOSurfaceWidth: @(w), (id) kIOSurfaceHeight: @(h), (id) kIOSurfaceBytesPerElement: @4,
				(id) kIOSurfacePixelFormat: @((uint32_t) 'BGRA')};
			glo.surface = IOSurfaceCreate((CFDictionaryRef) props);
			if (!glo.surface) return 0;
			glGenTextures(1, &glo.glTexture);
			glBindTexture(GL_TEXTURE_RECTANGLE, glo.glTexture);
			if (CGLTexImageIOSurface2D(cgl, GL_TEXTURE_RECTANGLE, GL_RGBA, (GLsizei) w, (GLsizei) h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
					glo.surface, 0) != kCGLNoError) {
				releaseTarget();
				return 0;
			}
			glBindTexture(GL_TEXTURE_RECTANGLE, 0);
			glGenFramebuffers(1, &glo.fbo);
			glBindFramebuffer(GL_FRAMEBUFFER, glo.fbo);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, glo.glTexture, 0);
			if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
				releaseTarget();
				return 0;
			}
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
				width:(NSUInteger) w height:(NSUInteger) h mipmapped:NO];
			d.usage = MTLTextureUsageShaderRead;
			glo.texture = [ctx->device newTextureWithDescriptor:d iosurface:glo.surface plane:0];
			if (!glo.texture) {
				releaseTarget();
				return 0;
			}
			glo.w = w;
			glo.h = h;
			glo.dirty = 1;  // (a new surface's contents are undefined)
		}
		glBindFramebuffer(GL_FRAMEBUFFER, glo.fbo);
		glViewport(0, 0, w, h);
		// (cleared only after a frame that drew: most frames of most mods draw nothing, and then this costs nothing)
		if (glo.dirty) {
			glClearColor(0, 0, 0, 0);
			glClear(GL_COLOR_BUFFER_BIT);
			glo.dirty = 0;
		}
		// counts what OpenGL draws this frame (the present reads it: nothing drawn, nothing to wait for or lay over)
		if (!glo.queries[0]) glGenQueries(2, glo.queries);
		glBeginQuery(GL_SAMPLES_PASSED, glo.queries[glo.frame & 1]);
		glo.queryOpen = 1;
		return 1;
	}
}

static id<MTLRenderPipelineState> pipelineFor(Ctx *ctx, MTLPixelFormat format) {
	for (int i = 0; i < 2; i++) if (glo.pipelines[i] && glo.formats[i] == format) return glo.pipelines[i];
	NSError *e = nil;
	id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:overlaySource options:nil error:&e] autorelease];
	if (!lib) return nil;
	MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
	pd.vertexFunction = [[lib newFunctionWithName:@"glo_vs"] autorelease];
	pd.fragmentFunction = [[lib newFunctionWithName:@"glo_fs"] autorelease];
	pd.colorAttachments[0].pixelFormat = format;
	pd.colorAttachments[0].blendingEnabled = YES;
	pd.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
	pd.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
	pd.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorZero;
	pd.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOne;
	id<MTLRenderPipelineState> p = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
	if (!p) return nil;
	int slot = glo.pipelines[0] ? 1 : 0;
	if (glo.pipelines[slot]) [glo.pipelines[slot] release];
	glo.pipelines[slot] = p;
	glo.formats[slot] = format;
	return p;
}

// The frame's present: OpenGL's work for this frame finished, then its picture laid over the frame's texture.
void mc_gl_overlay_end(Enc *enc, id<MTLTexture> target) {
	@autoreleasepool {
		if (!glo.texture || !target || !glo.queryOpen) return;
		glEndQuery(GL_SAMPLES_PASSED);
		glo.queryOpen = 0;
		glFlush();  // (OpenGL's work goes to the GPU before this frame's Metal work: the IOSurface is read after it's drawn)
		// The previous frame's count, read without waiting (waiting would stall the CPU on OpenGL every frame): a mod that
		// draws keeps drawing, so its picture is laid over while it does; one that stops costs one more (empty) pass.
		GLuint prev = glo.queries[(glo.frame + 1) & 1], ready = 0, samples = 0;
		if (glo.frame > 0) {
			glGetQueryObjectuiv(prev, GL_QUERY_RESULT_AVAILABLE, &ready);
			if (ready) {
				glGetQueryObjectuiv(prev, GL_QUERY_RESULT, &samples);
				glo.drewLately = samples > 0;
			}
		}
		glo.frame++;
		if (!glo.drewLately) {
			// (the first frame of a mod's drawing: shown from the next one, when its count is in; not cleared meanwhile)
			if (glo.frame == 1) glo.drewLately = 1;
			return;
		}
		glo.dirty = 1;
		id<MTLRenderPipelineState> p = pipelineFor(enc->ctx, target.pixelFormat);
		if (!p) return;
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		[r setRenderPipelineState:p];
		[r setFragmentTexture:glo.texture atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
}
