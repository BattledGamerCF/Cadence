// Cadence: derived from mcopt (c) Noah Dunnagan, Apache-2.0; modified.
package dev.cadence.metal.mixin.startup;

import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.client.main.Main;
import net.minecraft.util.MemoryReserve;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * -Dcadence.startup.crashPreload=async: CrashReport.preload() is MemoryReserve.allocate() plus a throw-away report that loads the
 * reporting classes and probes the hardware. The reserve stays on the main thread; the throw-away report moves to a daemon thread.
 */
@Mixin(Main.class)
abstract class CrashPreloadMixin {
	@Redirect(method = "main", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;preload()V"))
	private static void cadence$async() {
		MemoryReserve.allocate();
		Thread t = new Thread(() -> new CrashReport("Don't panic!", new Throwable()).getFriendlyReport(ReportType.CRASH), "Cadence crash-report preload");
		t.setDaemon(true);
		t.start();
		// -Dcadence.startup.crashPreloadTest=FILE (test only): a real crash report built now, on the main thread, while the preload thread
		// may still be probing the hardware: shows a crash in the first seconds still gets the full system details.
		String test = System.getProperty("cadence.startup.crashPreloadTest");
		if (test != null) {
			try {
				java.nio.file.Files.writeString(java.nio.file.Path.of(test),
					new CrashReport("Cadence crash-preload test", new Throwable()).getFriendlyReport(ReportType.CRASH));
				t.join();
				java.nio.file.Files.writeString(java.nio.file.Path.of(test + ".after"),
					new CrashReport("Cadence crash-preload test", new Throwable()).getFriendlyReport(ReportType.CRASH));
			} catch (java.io.IOException | InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
