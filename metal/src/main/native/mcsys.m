// Cadence: macOS system signals for the heat and memory governors and thread priorities (Foundation, AppKit, libdispatch).
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#include <dispatch/dispatch.h>
#include <pthread.h>
#include <pthread/qos.h>
#include <stdatomic.h>

// NSProcessInfoThermalState: 0 nominal, 1 fair, 2 serious, 3 critical.
int mc_thermal_state(void) {
	@autoreleasepool {
		return (int) [[NSProcessInfo processInfo] thermalState];
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

// The main screen's highest refresh rate (120 on ProMotion), 60 if unknown.
int mc_display_hz(void) {
	@autoreleasepool {
		NSScreen *s = [NSScreen mainScreen];
		if (s && [s respondsToSelector:@selector(maximumFramesPerSecond)]) {
			NSInteger hz = s.maximumFramesPerSecond;
			if (hz > 0) return (int) hz;
		}
		return 60;
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
	int readable = n >= 1 || mc_smc_read("#KEY", b + 8, &type) > 0;
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
