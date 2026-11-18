package dev.cadence.metal.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cadence: Exclusive Fullscreen is borderless fullscreen on macOS. The exclusive mode switches the display to the Fullscreen
 * Resolution, and when that differs from the desktop's, Minecraft 26.3 (SDL3) leaves the window at the old place and size:
 * part of the picture off screen, the desktop showing beside or below it. It does on every backend (Cadence's Metal, OpenGL,
 * Vulkan). Borderless fullscreen at the desktop's resolution covers the screen; the frames reach the display directly either
 * way (Cadence's opaque layer), and a lower resolution for speed is World Resolution's job.
 */
@Mixin(Window.class)
abstract class BorderlessFullscreenMixin {
	@ModifyExpressionValue(method = "applyFullscreenMode", at = @At(value = "FIELD", target = "Lcom/mojang/blaze3d/platform/Window;exclusiveFullscreen:Z"))
	private boolean cadence$borderlessOnMac(boolean exclusive) {
		return exclusive && net.minecraft.util.Util.getPlatform() != net.minecraft.util.Util.OS.OSX;
	}
}
