// Cadence: macOS system signals for the heat and memory governors and thread priorities (Foundation, AppKit, libdispatch).
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#include <dispatch/dispatch.h>
#include <pthread.h>
#include <pthread/qos.h>
#include <stdatomic.h>
#include <IOKit/ps/IOPowerSources.h>
#include <IOKit/ps/IOPSKeys.h>
#include <IOKit/IOKitLib.h>

// NSProcessInfoThermalState: 0 nominal, 1 fair, 2 serious, 3 critical.
int mc_thermal_state(void) {
	@autoreleasepool {
		return (int) [[NSProcessInfo processInfo] thermalState];
	}
}

// App Nap: while busy (a world hosted with the window in the background) an activity keeps macOS from napping the game, which
// slows its timers and lowers its priority; the integrated server's ticks (and LAN players) would slow with it.
static id appNapActivity;
void mc_app_nap_busy(int busy) {
	@autoreleasepool {
		if (busy && !appNapActivity) {
			appNapActivity = [[[NSProcessInfo processInfo] beginActivityWithOptions:NSActivityUserInitiatedAllowingIdleSystemSleep
				reason:@"Running a Minecraft world"] retain];
		} else if (!busy && appNapActivity) {
			[[NSProcessInfo processInfo] endActivity:appNapActivity];
			[appNapActivity release];
			appNapActivity = nil;
		}
	}
}

int mc_low_power(void) {
	@autoreleasepool {
		NSProcessInfo *p = [NSProcessInfo processInfo];
		if ([p respondsToSelector:@selector(isLowPowerModeEnabled)]) return p.isLowPowerModeEnabled ? 1 : 0;
		return 0;
	}
}

// Memory pressure as macOS reports it to apps: 0 normal, 1 warning, 2 critical (a dispatch source, set up on first call).
static _Atomic int pressureLevel;
static _Atomic long pressureEvents;
static dispatch_source_t pressureSource;

int mc_memory_pressure(void) {
	static dispatch_once_t once;
	dispatch_once(&once, ^{
		pressureSource = dispatch_source_create(DISPATCH_SOURCE_TYPE_MEMORYPRESSURE, 0,
			DISPATCH_MEMORYPRESSURE_NORMAL | DISPATCH_MEMORYPRESSURE_WARN | DISPATCH_MEMORYPRESSURE_CRITICAL,
			dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));
		dispatch_source_set_event_handler(pressureSource, ^{
			unsigned long d = dispatch_source_get_data(pressureSource);
			int level = (d & DISPATCH_MEMORYPRESSURE_CRITICAL) ? 2 : (d & DISPATCH_MEMORYPRESSURE_WARN) ? 1 : 0;
			atomic_store(&pressureLevel, level);
			atomic_fetch_add(&pressureEvents, 1);
		});
		dispatch_resume(pressureSource);
	});
	return atomic_load(&pressureLevel);
}

long mc_memory_pressure_events(void) {
	return atomic_load(&pressureEvents);
}

// The calling thread's QoS class (0x21 user-interactive, 0x19 user-initiated, 0x15 default, 0x11 utility, 0x09 background).
int mc_set_qos(int qos) {
	return pthread_set_qos_class_self_np((qos_class_t) qos, 0);
}

int mc_qos_self(void) {
	return (int) qos_class_self();
}

// 1 while the Mac runs on its battery (IOKit's providing power source), 0 on AC power or without a battery.
int mc_on_battery(void) {
	CFTypeRef info = IOPSCopyPowerSourcesInfo();
	if (!info) return 0;
	CFStringRef type = IOPSGetProvidingPowerSourceType(info);
	int battery = type && CFStringCompare(type, CFSTR(kIOPMBatteryPowerKey), 0) == kCFCompareEqualTo;
	CFRelease(info);
	return battery;
}

// The energy left in the battery, watt-hours (its remaining charge times its voltage, from the AppleSmartBattery IORegistry
// entry), or -1 without a battery or if it can't be read.
double mc_battery_wh(void) {
	io_service_t svc = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleSmartBattery"));
	if (!svc) return -1;
	double wh = -1;
	CFNumberRef mah = IORegistryEntryCreateCFProperty(svc, CFSTR("AppleRawCurrentCapacity"), kCFAllocatorDefault, 0);
	if (!mah) {
		// (newer macOS: the charge left is in BatteryData's RemainingCapacity, mAh)
		CFDictionaryRef data = IORegistryEntryCreateCFProperty(svc, CFSTR("BatteryData"), kCFAllocatorDefault, 0);
		if (data && CFGetTypeID(data) == CFDictionaryGetTypeID()) {
			CFNumberRef r = CFDictionaryGetValue(data, CFSTR("RemainingCapacity"));
			if (r) mah = CFRetain(r);
		}
		if (data) CFRelease(data);
	}
	CFNumberRef mv = IORegistryEntryCreateCFProperty(svc, CFSTR("Voltage"), kCFAllocatorDefault, 0);
	long a = 0, v = 0;
	if (mah && mv && CFGetTypeID(mah) == CFNumberGetTypeID() && CFGetTypeID(mv) == CFNumberGetTypeID()
		&& CFNumberGetValue(mah, kCFNumberLongType, &a) && CFNumberGetValue(mv, kCFNumberLongType, &v) && a > 0 && v > 0)
		wh = a * (double) v / 1e6;
	if (mah) CFRelease(mah);
	if (mv) CFRelease(mv);
	IOObjectRelease(svc);
	return wh;
}

// The screen the game's window is on (its only window), whether or not the game is the active app; mainScreen (the screen
// with the active app's window) only without one. On a laptop with an external display the two differ whenever the player
// clicks into another app, and a game on a 60 Hz monitor would be paced for the laptop's 120 Hz.
static NSScreen *mc_game_screen(void) {
	NSWindow *w = NSApp.mainWindow;
	if (!w) for (NSWindow *x in NSApp.windows) if (x.isVisible) { w = x; break; }
	return w.screen ?: [NSScreen mainScreen];
}

// The last values read on the main thread (AppKit's windows belong to it); other threads get these. 0: not read yet.
static _Atomic int gameScreenHz, gameScreenScale;

static void mc_read_game_screen(void) {
	NSScreen *s = mc_game_screen();
	int hz = 60, scale = 100;
	if (s) {
		if ([s respondsToSelector:@selector(maximumFramesPerSecond)] && s.maximumFramesPerSecond > 0) hz = (int) s.maximumFramesPerSecond;
		if (s.backingScaleFactor > 0) scale = (int) lround(s.backingScaleFactor * 100);
	}
	gameScreenHz = hz;
	gameScreenScale = scale;
}

// From another thread (the heat governor's poll): the cached values, and one fresh read queued for the main thread (run when
// the game's event loop next drains the main queue; at most one waiting).
static _Atomic int screenReadQueued;
static void mc_queue_screen_read(void) {
	int expected = 0;
	if (!atomic_compare_exchange_strong(&screenReadQueued, &expected, 1)) return;
	dispatch_async(dispatch_get_main_queue(), ^{
		mc_read_game_screen();
		screenReadQueued = 0;
	});
}

// The game's screen's highest refresh rate (120 on ProMotion), 60 if unknown.
int mc_display_hz(void) {
	@autoreleasepool {
		if ([NSThread isMainThread]) mc_read_game_screen();
		else {
			mc_queue_screen_read();
			if (gameScreenHz == 0) {
				NSScreen *s = [NSScreen mainScreen];
				return s && [s respondsToSelector:@selector(maximumFramesPerSecond)] && s.maximumFramesPerSecond > 0 ? (int) s.maximumFramesPerSecond : 60;
			}
		}
		return gameScreenHz;
	}
}

// The game's screen's backing scale x 100 (200 on a Retina display, 100 on most external monitors).
int mc_display_scale(void) {
	@autoreleasepool {
		if ([NSThread isMainThread]) mc_read_game_screen();
		else {
			mc_queue_screen_read();
			if (gameScreenScale == 0) {
				NSScreen *s = [NSScreen mainScreen];
				return s && s.backingScaleFactor > 0 ? (int) lround(s.backingScaleFactor * 100) : 200;
			}
		}
		return gameScreenScale;
	}
}

// Cadence: fans, read from the System Management Controller (AppleSMC) the way monitoring tools do. Reading needs no
// privileges; *setting* fan speed does (root), which Cadence never does. A MacBook Air has no fans: FNum reads 0.
#include <IOKit/IOKitLib.h>
#include <string.h>

typedef struct { unsigned char major, minor, build, reserved; unsigned short release; } McSmcVers;
typedef struct { unsigned short version, length; unsigned int cpuPLimit, gpuPLimit, memPLimit; } McSmcPLimit;
typedef struct { unsigned int dataSize, dataType; unsigned char dataAttributes; } McSmcKeyInfo;
typedef struct {
	unsigned int key;
	McSmcVers vers;
	McSmcPLimit pLimitData;
	McSmcKeyInfo keyInfo;
	unsigned char result, status, data8;
	unsigned int data32;
	unsigned char bytes[32];
} McSmcData;

static io_connect_t mc_smc_conn;
static int mc_smc_state; // 0 not tried, 1 open, -1 unavailable
static pthread_mutex_t mc_smc_lock = PTHREAD_MUTEX_INITIALIZER;

static unsigned int mc_smc_key(const char *k) { return ((unsigned)k[0] << 24) | ((unsigned)k[1] << 16) | ((unsigned)k[2] << 8) | (unsigned)k[3]; }

static int mc_smc_open(void) {
	if (mc_smc_state != 0) return mc_smc_state;
	io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleSMC"));
	if (!service) { mc_smc_state = -1; return -1; }
	kern_return_t kr = IOServiceOpen(service, mach_task_self(), 0, &mc_smc_conn);
	IOObjectRelease(service);
	mc_smc_state = kr == KERN_SUCCESS ? 1 : -1;
	return mc_smc_state;
}

/** Reads key into out (up to 32 bytes); returns its size, or -1. *type gets its 4-char type code. */
static int mc_smc_read(const char *key, unsigned char *out, unsigned int *type) {
	if (mc_smc_open() != 1) return -1;
	McSmcData in, res;
	size_t size = sizeof(McSmcData);
	memset(&in, 0, sizeof in);
	memset(&res, 0, sizeof res);
	in.key = mc_smc_key(key);
	in.data8 = 9; // read key info
	if (IOConnectCallStructMethod(mc_smc_conn, 2, &in, sizeof in, &res, &size) != KERN_SUCCESS || res.result != 0) return -1;
	unsigned int dataSize = res.keyInfo.dataSize;
	*type = res.keyInfo.dataType;
	if (dataSize == 0 || dataSize > 32) return -1;
	in.keyInfo.dataSize = dataSize;
	in.data8 = 5; // read bytes
	memset(&res, 0, sizeof res);
	size = sizeof(McSmcData);
	if (IOConnectCallStructMethod(mc_smc_conn, 2, &in, sizeof in, &res, &size) != KERN_SUCCESS || res.result != 0) return -1;
	memcpy(out, res.bytes, dataSize);
	return (int) dataSize;
}

/** How many fans this Mac has (0 for a MacBook Air; -1 if the SMC can't be read). */
int mc_fan_count(void) {
	pthread_mutex_lock(&mc_smc_lock);
	unsigned char b[32];
	unsigned int type = 0;
	int n = mc_smc_read("FNum", b, &type);
	// no FNum key while the SMC itself answers (#KEY: its key count): a fanless Mac
	unsigned char k[32];  // (#KEY into its own 32 bytes: up to 32 are written)
	int readable = n >= 1 || mc_smc_read("#KEY", k, &type) > 0;
	pthread_mutex_unlock(&mc_smc_lock);
	return n >= 1 ? b[0] : readable ? 0 : -1;
}

/** Whole-system power draw in milliwatts (Apple Silicon's PSTR key), or -1. */
int mc_system_power_mw(void) {
	pthread_mutex_lock(&mc_smc_lock);
	unsigned char b[32];
	unsigned int type = 0;
	int n = mc_smc_read("PSTR", b, &type);
	pthread_mutex_unlock(&mc_smc_lock);
	if (n == 4 && type == mc_smc_key("flt ")) {
		float f;
		memcpy(&f, b, 4);
		return f >= 0 && f < 1000 ? (int) (f * 1000 + 0.5f) : -1;
	}
	return -1;
}

/** Fan i's current speed in rpm (Apple Silicon: float; Intel: fpe2 fixed point), or -1. */
int mc_fan_rpm(int i) {
	if (i < 0 || i > 9) return -1;
	char key[5] = {'F', (char) ('0' + i), 'A', 'c', 0};
	pthread_mutex_lock(&mc_smc_lock);
	unsigned char b[32];
	unsigned int type = 0;
	int n = mc_smc_read(key, b, &type);
	pthread_mutex_unlock(&mc_smc_lock);
	if (n == 4 && type == mc_smc_key("flt ")) {
		float f;
		memcpy(&f, b, 4);
		return (int) (f + 0.5f);
	}
	if (n == 2 && type == mc_smc_key("fpe2")) return ((b[0] << 8) | b[1]) >> 2;
	return -1;
}

// Cadence HDR (Hdr.java): the game screen's EDR headroom now (the brightest it shows at its current brightness: 1.0 = SDR white,
// and 1.0 also while nothing on screen asks for more) and at most (what it reaches once content asks for it), x 1000.
// AppKit's screens belong to the main thread: from any other thread both are -1.
void mc_screen_headroom(int *out) {
	if (![NSThread isMainThread]) {
		out[0] = out[1] = -1;
		return;
	}
	@autoreleasepool {
		NSScreen *s = mc_game_screen();
		out[0] = s ? (int) lround(s.maximumExtendedDynamicRangeColorComponentValue * 1000) : 1000;
		out[1] = s ? (int) lround(s.maximumPotentialExtendedDynamicRangeColorComponentValue * 1000) : 1000;
	}
}
