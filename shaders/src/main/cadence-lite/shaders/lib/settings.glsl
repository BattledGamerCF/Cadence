// Cadence Lite settings. Profiles (LOW..ULTRA) are in shaders.properties.

#define SHADOWS                 // Sun and moon shadows
#define SHADOW_FILTER 0         // [0 1 2] 0 = one hardware tap, 1 = 4 taps, 2 = 12 soft taps
#define SHADOW_STRENGTH 0.55    // [0.35 0.45 0.55 0.65 0.75] How dark shadows get
#define WAVING                  // Leaves, grass, flowers and crops move in the wind
#define SUN_LIGHT 1             // [0 1 2] A little warm sunlight (and soft moonlight) on top of vanilla light: off, subtle, rich
#define WATER 1                 // [0 1] 0 = vanilla, 1 = sky reflection, waves and sun glint
//#define WATER_SSR             // Water also reflects the world (screen-space)
//#define BLOOM                 // Glow around bright light
#define BLOOM_STRENGTH 0.12     // [0.06 0.09 0.12 0.16 0.20]
//#define GODRAYS               // Light shafts from the sun and moon
#define GODRAY_SAMPLES 12       // [8 12 16 24]
#define VIBRANCE 1.08           // [1.00 1.04 1.08 1.14 1.20 1.28] Colour saturation
#define CONTRAST 1.02           // [1.00 1.02 1.05 1.08]
//#define TONEMAP               // Filmic highlight roll-off (keeps bright skies and bloom from clipping)

const int shadowMapResolution = 1024; // [512 1024 1536 2048 3072 4096]
const float shadowDistance = 64.0;    // [48.0 64.0 96.0 128.0 160.0 192.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -30.0;  // [-40.0 -30.0 -20.0 0.0 20.0 30.0]
const bool shadowHardwareFiltering = true;
const float ambientOcclusionLevel = 1.0;

// Block ids from block.properties
#define ID_PLANT 10001
#define ID_LEAVES 10002
#define ID_TALL_LOWER 10003
#define ID_TALL_UPPER 10004
#define ID_WATER 10005
#define ID_EMISSIVE 10006
