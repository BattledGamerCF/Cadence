// Record Mode's frame without Minecraft: times record.metal's record_trace on a world dumped from the game (the self-test's
// "recorddump=name" step writes metal/run/screenshots/dump-name/), one command buffer a frame as the game sends them, up to 16
// in flight. For quick A/Bs of the shader: variants by -D macros, alternated in rounds so the Mac's heat drifts across both.
// Build: clang -fobjc-arc -O2 -framework Metal -framework Foundation -framework ImageIO -framework CoreGraphics tools/record_bench.m -o /tmp/record_bench
// Usage: record_bench <dump dir> <record.metal> [frames=4000] [rounds=3] [variant macros ...]
//   a variant is a comma list of NAME or NAME=VALUE ("" or "-": none), e.g.  record_bench dump src.metal 4000 3 - TG16
//   RB_W=width RB_H=height: another output size; RB_PNG=path: a frame by the first variant as a PNG; RB_TG=8x8: threadgroup.
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#import <ImageIO/ImageIO.h>
#import <Metal/Metal.h>

static id<MTLBuffer> load(id<MTLDevice> d, NSString *dir, NSString *name) {
	NSData *data = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:name]];
	if (!data) {
		fprintf(stderr, "missing %s\n", name.UTF8String);
		exit(1);
	}
	return [d newBufferWithBytes:data.bytes length:data.length options:MTLResourceStorageModeShared];
}

static id<MTLTexture> tex(id<MTLDevice> d, MTLPixelFormat f, int w, int h, MTLStorageMode mode) {
	MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:w height:h mipmapped:NO];
	if (getenv("RB_UNTRACKED")) t.hazardTrackingMode = MTLHazardTrackingModeUntracked;
	t.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
	t.storageMode = mode;
	return [d newTextureWithDescriptor:t];
}

static NSDictionary *macros(const char *spec) {
	NSMutableDictionary *m = [NSMutableDictionary dictionary];
	if (!spec || !*spec || !strcmp(spec, "-")) return m;
	for (NSString *part in [[NSString stringWithUTF8String:spec] componentsSeparatedByString:@","]) {
		NSArray *kv = [part componentsSeparatedByString:@"="];
		m[kv[0]] = kv.count > 1 ? @([kv[1] intValue]) : @1;
	}
	return m;
}

int main(int argc, char **argv) {
	@autoreleasepool {
		if (argc < 3) {
			fprintf(stderr, "usage: record_bench <dump dir> <record.metal> [frames] [rounds] [variants...]\n");
			return 2;
		}
		NSString *dir = [NSString stringWithUTF8String:argv[1]];
		NSString *src = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[2]] encoding:NSUTF8StringEncoding error:nil];
		int frames = argc > 3 ? atoi(argv[3]) : 4000, rounds = argc > 4 ? atoi(argv[4]) : 3;
		int nv = argc > 5 ? argc - 5 : 1;
		id<MTLDevice> d = MTLCreateSystemDefaultDevice();
		id<MTLCommandQueue> q = [d newCommandQueue];
		NSData *frameData = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"frame.bin"]];
		NSMutableData *frame = [frameData mutableCopy];
		if (frame.length < 512) {
			// (a dump from before the brightness table: vanilla's curve at Brightness 1.0)
			frame.length = 512;
			float *lut = (float *) ((char *) frame.mutableBytes + 448);
			for (int l = 0; l < 16; l++) {
				float lv = l / 15.0f, b = lv / (4 - 3 * lv), nb = 1 - b;
				lut[l] = (1 - nb * nb * nb * nb) * 0.96f + 0.04f;
			}
		}
		uint32_t *size = (uint32_t *) ((char *) frame.mutableBytes + 304);
		if (getenv("RB_W")) size[0] = atoi(getenv("RB_W"));
		if (getenv("RB_H")) size[1] = atoi(getenv("RB_H"));
		int w = size[0], h = size[1];
		if (getenv("RB_NOINTERP")) *(uint32_t *) ((char *) frame.mutableBytes + 336) = 0;
		if (getenv("RB_STILL")) *(uint32_t *) ((char *) frame.mutableBytes + 336) = 2;
		*(uint32_t *) ((char *) frame.mutableBytes + 348) = getenv("RB_TEMPORAL") ? 1 : 0;  // (the dump's frame may have it either way)
		int tgx = 8, tgy = 8, batch = getenv("RB_BATCH") ? atoi(getenv("RB_BATCH")) : 1;
		if (getenv("RB_TG")) sscanf(getenv("RB_TG"), "%dx%d", &tgx, &tgy);
		id<MTLBuffer> grid = load(d, dir, @"grid.bin"), pal = load(d, dir, @"palette.bin"),
			occ = load(d, dir, @"occ.bin"), light = load(d, dir, @"light.bin"), ents = load(d, dir, @"entities.bin");
		// the entity tile masks (Record.placeEntities), made again from the dump's rectangles (older dumps have none)
		int tilesX = (w + 7) >> 3, tilesY = (h + 7) >> 3, nEnt = *(uint32_t *) ((char *) frame.mutableBytes + 312);
		*(uint32_t *) ((char *) frame.mutableBytes + 436) = tilesX;
		id<MTLBuffer> tiles = [d newBufferWithLength:(NSUInteger) tilesX * tilesY * 4 options:MTLResourceStorageModeShared];
		memset(tiles.contents, 0, tiles.length);
		for (int i = 0; i < nEnt; i++) {
			int32_t *r = (int32_t *) ((char *) ents.contents + i * 48 + 32);
			for (int ty = r[1] >> 3; ty <= r[3] >> 3 && ty < tilesY; ty++)
				for (int tx = r[0] >> 3; tx <= r[2] >> 3 && tx < tilesX; tx++) ((uint32_t *) tiles.contents)[ty * tilesX + tx] |= 1u << i;
		}
		if (getenv("RB_TOLINEAR")) {
			// (the dump's brick-major buffers as x-fastest linear ones, for the RB_LINEAR layout)
			uint32_t *gi = (uint32_t *) ((char *) frame.mutableBytes + 288);
			int W0 = 1 << gi[0], H0 = gi[1], bw0 = W0 >> 3;
			NSMutableData *g2 = [NSMutableData dataWithLength:grid.length], *l2 = [NSMutableData dataWithLength:light.length], *o2 = [NSMutableData dataWithLength:occ.length];
			for (int iy = 0; iy < H0; iy++)
				for (int iz = 0; iz < W0; iz++)
					for (int ix = 0; ix < W0; ix++) {
						int bi = (((iy >> 3) * bw0 + (iz >> 3)) * bw0 + (ix >> 3)) * 512 + ((iy & 7) * 8 + (iz & 7)) * 8 + (ix & 7);
						int li = (iy * W0 + iz) * W0 + ix;
						((uint16_t *) g2.mutableBytes)[li] = ((uint16_t *) grid.contents)[bi];
						((uint8_t *) l2.mutableBytes)[li] = ((uint8_t *) light.contents)[bi];
						if ((((uint32_t *) occ.contents)[bi >> 5] >> (bi & 31)) & 1) ((uint32_t *) o2.mutableBytes)[li >> 5] |= 1u << (li & 31);
					}
			memcpy(grid.contents, g2.bytes, grid.length);
			memcpy(light.contents, l2.bytes, light.length);
			memcpy(occ.contents, o2.bytes, occ.length);
		}
		// the HUD overlay (RecordHud): the dump's when it has one, else none drawn
		NSData *hudData = [NSData dataWithContentsOfFile:[dir stringByAppendingPathComponent:@"hud.bin"]];
		id<MTLBuffer> text = [d newBufferWithLength:(NSUInteger) w * h * 4 options:MTLResourceStorageModeShared];
		memset(text.contents, 0, text.length);
		if (hudData.length == text.length) memcpy(text.contents, hudData.bytes, text.length);
		// RB_RING=4: four of each, the in-between frame made from frames N-2 and N-3, so frame N depends on no frame still
		// running (consecutive frames can overlap on the GPU); else three, from N-1 and N-2
		int ring = getenv("RB_RING") ? 4 : 3, lagA = ring == 4 ? 2 : 1, lagB = lagA + 1;
		id<MTLTexture> color[4], depth[4], mids[4];
		for (int k = 0; k < ring; k++) {
			mids[k] = tex(d, MTLPixelFormatRGBA8Unorm, w, h, MTLStorageModePrivate);
			color[k] = tex(d, MTLPixelFormatRGBA8Unorm, w, h, MTLStorageModePrivate);
			depth[k] = tex(d, getenv("RB_DEPTH32") ? MTLPixelFormatR32Float : MTLPixelFormatR16Float, w, h, MTLStorageModePrivate);
		}
		id<MTLTexture> mid = mids[0];
		id<MTLTexture> font = tex(d, MTLPixelFormatRGBA8Unorm, 128, 128, MTLStorageModeShared);
		id<MTLTexture> atlas = tex(d, MTLPixelFormatRGBA8Unorm, 16, 16, MTLStorageModeShared);
		id<MTLComputePipelineState> pipes[16];
		for (int v = 0; v < nv; v++) {
			MTLCompileOptions *o = [MTLCompileOptions new];
			o.mathMode = MTLMathModeFast;
			o.preprocessorMacros = macros(argc > 5 ? argv[5 + v] : NULL);
			NSError *e = nil;
			id<MTLLibrary> lib = [d newLibraryWithSource:src options:o error:&e];
			if (!lib) {
				fprintf(stderr, "compile (%s): %s\n", argc > 5 ? argv[5 + v] : "-", e.localizedDescription.UTF8String);
				return 1;
			}
			pipes[v] = [d newComputePipelineStateWithFunction:[lib newFunctionWithName:@"record_trace"] error:&e];
			printf("variant %-28s max threads/threadgroup %lu, simd width %lu\n", argc > 5 ? argv[5 + v] : "-",
				(unsigned long) pipes[v].maxTotalThreadsPerThreadgroup, (unsigned long) pipes[v].threadExecutionWidth);
		}
		printf("%dx%d, threadgroup %dx%d, %d frames x %d rounds\n", w, h, tgx, tgy, frames, rounds);
		double best[16] = {0}, sum[16] = {0}, gpuSum[16] = {0};
		int cur = 0;
		for (int r = 0; r < rounds; r++)
			for (int v = 0; v < nv; v++) {
				dispatch_semaphore_t inflight = dispatch_semaphore_create(16);
				__block double gpu = 0;
				__block int gpuN = 0;
				double t0 = NSProcessInfo.processInfo.systemUptime;
				id<MTLCommandBuffer> last = nil;
				for (int i = 0; i < frames; i += batch) {
					dispatch_semaphore_wait(inflight, DISPATCH_TIME_FOREVER);
					@autoreleasepool {
						id<MTLCommandBuffer> cb = [q commandBufferWithUnretainedReferences];
						// (RB_BATCH frames to a command buffer, each its own encoder and dispatch into its own image, as the game does)
						id<MTLComputeCommandEncoder> one = getenv("RB_CONC") ? [cb computeCommandEncoderWithDispatchType:MTLDispatchTypeConcurrent] : nil;
						for (int j = 0; j < batch; j++) {
							id<MTLComputeCommandEncoder> c = one ? one : [cb computeCommandEncoderWithDispatchType:MTLDispatchTypeSerial];
							[c setComputePipelineState:pipes[v]];
							[c setBuffer:grid offset:0 atIndex:1];
							[c setBuffer:pal offset:0 atIndex:3];
							[c setBuffer:ents offset:0 atIndex:4];
							[c setBuffer:text offset:0 atIndex:5];
							[c setBuffer:occ offset:0 atIndex:6];
							[c setBuffer:light offset:0 atIndex:7];
							[c setBuffer:tiles offset:0 atIndex:8];
							int a = (cur + ring - lagA) % ring, b = (cur + ring - lagB) % ring;
							[c setTexture:(ring == 4 ? mids[cur] : mid) atIndex:8];
							[c setTexture:color[cur] atIndex:0];
							[c setTexture:depth[cur] atIndex:1];
							[c setTexture:color[a] atIndex:4];
							[c setTexture:depth[a] atIndex:5];
							[c setTexture:color[b] atIndex:6];
							[c setTexture:depth[b] atIndex:7];
							[c setBytes:frame.bytes length:frame.length atIndex:0];
							[c dispatchThreads:MTLSizeMake(w, getenv("RB_HALFH") ? (h + 1) / 2 : h, 1) threadsPerThreadgroup:MTLSizeMake(tgx, tgy, 1)];
							if (!one) [c endEncoding];
							cur = (cur + 1) % ring;
						}
						if (one) [one endEncoding];
						bool sample = ((i / batch) & 15) == 0;
						[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
							if (sample) {
								gpu += (done.GPUEndTime - done.GPUStartTime) / batch;
								gpuN++;
							}
							dispatch_semaphore_signal(inflight);
						}];
						[cb commit];
						last = cb;
					}
				}
				[last waitUntilCompleted];
				for (int k = 0; k < 16; k++) dispatch_semaphore_wait(inflight, DISPATCH_TIME_FOREVER);
				for (int k = 0; k < 16; k++) dispatch_semaphore_signal(inflight);  // (back to its start: libdispatch traps otherwise)
				double fps = frames / (NSProcessInfo.processInfo.systemUptime - t0);
				double us = gpuN ? gpu / gpuN * 1e6 : 0;
				sum[v] += fps;
				gpuSum[v] += us;
				if (fps > best[v]) best[v] = fps;
				printf("round %d  %-28s %7.0f fps  gpu %6.1f us/frame\n", r, argc > 5 ? argv[5 + v] : "-", fps, us);
				fflush(stdout);
			}
		for (int v = 0; v < nv; v++)
			printf("== %-28s mean %7.0f fps  best %7.0f  gpu %6.1f us\n", argc > 5 ? argv[5 + v] : "-", sum[v] / rounds, best[v], gpuSum[v] / rounds);
		const char *png = getenv("RB_PNG");
		if (png) {
			// (one more frame by the first variant: the image is of it, whichever variant ran last)
			for (int i = 0; i < 3; i++) {
				id<MTLCommandBuffer> cb = [q commandBuffer];
				id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
				int a = (cur + ring - lagA) % ring, b = (cur + ring - lagB) % ring;
				[c setComputePipelineState:pipes[0]];
				id<MTLTexture> ts[9] = {color[cur], depth[cur], atlas, font, color[a], depth[a], color[b], depth[b], mid};
				for (int k = 0; k < 9; k++) [c setTexture:ts[k] atIndex:k];
				[c setBytes:frame.bytes length:frame.length atIndex:0];
				id<MTLBuffer> bs[9] = {nil, grid, nil, pal, ents, text, occ, light, tiles};
				for (int k = 1; k < 9; k++) if (bs[k]) [c setBuffer:bs[k] offset:0 atIndex:k];
				[c dispatchThreads:MTLSizeMake(w, getenv("RB_HALFH") ? (h + 1) / 2 : h, 1) threadsPerThreadgroup:MTLSizeMake(tgx, tgy, 1)];
				[c endEncoding];
				[cb commit];
				[cb waitUntilCompleted];
				cur = (cur + 1) % ring;
			}
			id<MTLBuffer> out = [d newBufferWithLength:(NSUInteger) w * h * 4 options:MTLResourceStorageModeShared];
			id<MTLCommandBuffer> cb = [q commandBuffer];
			id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
			[bl copyFromTexture:color[(cur + ring - 1) % ring] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
				toBuffer:out destinationOffset:0 destinationBytesPerRow:w * 4 destinationBytesPerImage:w * h * 4];
			[bl endEncoding];
			[cb commit];
			[cb waitUntilCompleted];
			// (the frame's rows are stored bottom first, as the game's targets: top first for the PNG)
			NSMutableData *rows = [NSMutableData dataWithLength:(NSUInteger) w * h * 4];
			for (int y = 0; y < h; y++) memcpy((char *) rows.mutableBytes + (size_t) y * w * 4, (char *) out.contents + (size_t) (h - 1 - y) * w * 4, (size_t) w * 4);
			memcpy(out.contents, rows.bytes, (size_t) w * h * 4);
			CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
			CGContextRef ctx = CGBitmapContextCreate(out.contents, w, h, 8, w * 4, cs, kCGImageAlphaNoneSkipLast);
			CGImageRef img = CGBitmapContextCreateImage(ctx);
			CGImageDestinationRef dst = CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:@(png)], CFSTR("public.png"), 1, NULL);
			CGImageDestinationAddImage(dst, img, NULL);
			CGImageDestinationFinalize(dst);
			CFRelease(dst);
			CGImageRelease(img);
			CGContextRelease(ctx);
			CGColorSpaceRelease(cs);
		}
	}
	return 0;
}
