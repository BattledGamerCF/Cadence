// Cadence: Biome Colours (dev.cadence.metal.grade.BiomeGrade): the world's picture colour graded for where you are, after the
// world is drawn and before the hand and the HUD. One full-screen triangle over the frame that reads each pixel where it is
// (Apple GPUs' programmable blending: [[color(0)]]) and writes it back graded: white balance, exposure, saturation, contrast
// and a tint in the shadows. Values above 1 (HDR highlights in the float frame) keep their excess; the curve is on 0..1.
#import <Metal/Metal.h>
#import "mcmetal.h"

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);

static NSString *const gradeSource = @
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct V { float4 p [[position]]; };\n"
	"vertex V gr_vs(uint id [[vertex_id]]) {\n"
	"  float2 uv = float2((id << 1) & 2, id & 2);\n"
	"  V o; o.p = float4(uv * 2 - 1, 0, 1); return o;\n"
	"}\n"
	// gain.rgb: white balance, gain.w: exposure; shadow.rgb: added in the shadows, shadow.w: saturation;
	// misc.x: contrast, misc.y: strength (0: as drawn)
	"struct P { float4 gain; float4 shadow; float4 misc; };\n"
	"fragment float4 gr_fs(V in [[stage_in]], float4 dst [[color(0)]], constant P &p [[buffer(0)]]) {\n"
	"  float3 c = dst.rgb;\n"
	"  float3 g = c * p.gain.rgb * p.gain.w;\n"
	"  float l = dot(g, float3(0.2126, 0.7152, 0.0722));\n"
	"  g = max(mix(float3(l), g, p.shadow.w), 0.0);\n"
	"  float3 base = min(g, 1.0), over = g - base;\n"
	"  base = clamp((base - 0.5) * p.misc.x + 0.5, 0.0, 1.0);\n"
	"  base = clamp(base + p.shadow.rgb * (1.0 - smoothstep(0.0, 0.45, l)), 0.0, 1.0);\n"
	"  return float4(mix(c, base + over, p.misc.y), dst.a);\n"
	"}\n";

static id<MTLFunction> gVs, gFs;
static id<MTLRenderPipelineState> gPso[4];
static MTLPixelFormat gPsoFormat[4];

static id<MTLRenderPipelineState> pipeline(Ctx *ctx, MTLPixelFormat format) {
	for (int i = 0; i < 4; i++)
		if (gPso[i] && gPsoFormat[i] == format) return gPso[i];
	if (!gVs) {
		NSError *e = nil;
		id<MTLLibrary> lib = [[ctx->device newLibraryWithSource:gradeSource options:[[MTLCompileOptions new] autorelease] error:&e] autorelease];
		if (!lib) {
			NSLog(@"cadence-grade: %@", e);
			return nil;
		}
		gVs = [lib newFunctionWithName:@"gr_vs"];
		gFs = [lib newFunctionWithName:@"gr_fs"];
	}
	MTLRenderPipelineDescriptor *d = [[MTLRenderPipelineDescriptor new] autorelease];
	d.vertexFunction = gVs;
	d.fragmentFunction = gFs;
	d.colorAttachments[0].pixelFormat = format;
	NSError *e = nil;
	id<MTLRenderPipelineState> p = [ctx->device newRenderPipelineStateWithDescriptor:d error:&e];
	if (!p) {
		NSLog(@"cadence-grade: %@", e);
		return nil;
	}
	for (int i = 0; i < 4; i++)
		if (!gPso[i]) {
			gPso[i] = p;
			gPsoFormat[i] = format;
			return p;
		}
	return p;  // (more than 4 formats: not kept; never happens with the game's targets)
}

// Render thread: the grade (12 floats, the shader's P) over color. Returns 1 if it was encoded.
int mcgr_frame(Ctx *ctx, Enc *enc, id<MTLTexture> color, const float *params) {
	if (!color) return 0;
	@autoreleasepool {
		id<MTLRenderPipelineState> p = pipeline(ctx, color.pixelFormat);
		if (!p) return 0;
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = color;
		rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
		re.label = @"Cadence biome colours";
		[re setRenderPipelineState:p];
		[re setFragmentBytes:params length:12 * sizeof(float) atIndex:0];
		[re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[re endEncoding];
		return 1;
	}
}
