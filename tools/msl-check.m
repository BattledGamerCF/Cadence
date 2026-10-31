// Compiles a Metal Shading Language file through the Metal API (no Xcode needed) and prints the compiler's errors.
// usage: msl-check FILE.metal [NAME=VALUE ...]   (each pair a preprocessor macro; NAME alone means NAME=1)
// build: clang -fobjc-arc -framework Metal -framework Foundation tools/msl-check.m -o build/msl-check
#import <Metal/Metal.h>
#import <Foundation/Foundation.h>

int main(int argc, const char **argv) {
	@autoreleasepool {
		if (argc < 2) { fprintf(stderr, "usage: msl-check FILE.metal [NAME=VALUE ...]\n"); return 2; }
		NSString *src = [NSString stringWithContentsOfFile:@(argv[1]) encoding:NSUTF8StringEncoding error:nil];
		if (!src) { fprintf(stderr, "can't read %s\n", argv[1]); return 2; }
		NSMutableDictionary *macros = [NSMutableDictionary dictionary];
		for (int i = 2; i < argc; i++) {
			NSString *a = @(argv[i]);
			NSRange eq = [a rangeOfString:@"="];
			if (eq.location == NSNotFound) macros[a] = @1;
			else macros[[a substringToIndex:eq.location]] = [a substringFromIndex:eq.location + 1];
		}
		MTLCompileOptions *o = [MTLCompileOptions new];
		o.preprocessorMacros = macros;
		NSError *err = nil;
		id<MTLLibrary> lib = [MTLCreateSystemDefaultDevice() newLibraryWithSource:src options:o error:&err];
		if (err) fprintf(lib ? stdout : stderr, "%s\n", err.localizedDescription.UTF8String);
		if (!lib) return 1;
		printf("ok: %s\n", [[lib functionNames] componentsJoinedByString:@" "].UTF8String);
		return 0;
	}
}
