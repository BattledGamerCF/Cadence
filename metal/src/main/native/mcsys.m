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
