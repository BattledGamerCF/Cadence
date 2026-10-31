package dev.cadence.shaders.cadence.system;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Cadence: whenever Cadence changes something on its own (Heat Control, the memory guard), a short notice in the corner of
 * the screen says what and why, so nothing changes silently. At most one every 20 seconds (a newer one replaces it).
 */
public final class Notices {
	private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId(6000L);
	private static volatile boolean muted = Boolean.getBoolean("cadence.quietNotices");

	private Notices() {
	}

	public static void post(String title, String detail) {
		if (muted || !CadenceMessages.enabled()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) return;
		mc.execute(() -> {
			if (mc.gui == null) return;
			SystemToast.addOrUpdate(mc.gui.toastManager(), ID, dev.cadence.metal.system.Lang.literal(title), dev.cadence.metal.system.Lang.literal(detail.isEmpty() ? " " : detail));
		});
	}
}
