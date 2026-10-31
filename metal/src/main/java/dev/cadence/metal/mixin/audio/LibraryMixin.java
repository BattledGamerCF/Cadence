package dev.cadence.metal.mixin.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.Library;
import dev.cadence.metal.audio.AppleAudio;
import dev.cadence.metal.audio.AppleChannel;
import dev.cadence.metal.audio.AppleSoundBuffer;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence (AppleAudio): with the Apple sound engine chosen, Library.init starts it instead of opening an OpenAL device (and
 * falls back to OpenAL if it can't start), hands out channels backed by its voices (247 for sounds and 8 for streams, as
 * OpenAL Soft's pools are sized), and cleanup stops it. The engine follows macOS's output device itself (headphones,
 * AirPods, a new default), so it never reports a lost device.
 */
@Mixin(Library.class)
abstract class LibraryMixin {
	@Shadow private String currentDeviceName;
	@Unique private boolean cadence$apple;
	@Unique private final List<Channel> cadence$static = new ArrayList<>(), cadence$streaming = new ArrayList<>();
	@Unique private static final int STATIC_MAX = 247, STREAMING_MAX = 8;

	@Inject(method = "init", at = @At("HEAD"), cancellable = true)
	private void cadence$init(String device, DeviceList devices, boolean directional, CallbackInfo ci) {
		if (!AppleAudio.start(directional)) return;
		this.cadence$apple = true;
		// (the engine plays to macOS's default output: named as OpenAL names that device, so the game's device tracker, which
		// compares it with OpenAL's list, doesn't take it for a lost device and restart the sound system)
		this.currentDeviceName = devices != null && devices.defaultDevice() != null ? devices.defaultDevice() : "";
		ci.cancel();
	}

	@Inject(method = "cleanup", at = @At("HEAD"), cancellable = true)
	private void cadence$cleanup(CallbackInfo ci) {
		if (!this.cadence$apple) return;
		ci.cancel();
		for (Channel c : this.cadence$static) c.destroy();
		for (Channel c : this.cadence$streaming) c.destroy();
		this.cadence$static.clear();
		this.cadence$streaming.clear();
		AppleAudio.stop();
		this.cadence$apple = false;
		this.currentDeviceName = null;
	}

	@Inject(method = "acquireChannel", at = @At("HEAD"), cancellable = true)
	private void cadence$acquire(Library.Pool pool, CallbackInfoReturnable<Channel> cir) {
		if (!this.cadence$apple) return;
		List<Channel> list = pool == Library.Pool.STREAMING ? this.cadence$streaming : this.cadence$static;
		int max = pool == Library.Pool.STREAMING ? STREAMING_MAX : STATIC_MAX;
		if (list.size() >= max) {
			cir.setReturnValue(null);  // (as vanilla's pools: none free, the sound isn't played)
			return;
		}
		long voice = AppleAudio.voice();
		if (voice == 0) {
			cir.setReturnValue(null);
			return;
		}
		Channel c = ChannelCtor.cadence$create(0);
		((AppleChannel) c).cadence$voice(voice);
		list.add(c);
		cir.setReturnValue(c);
	}

	@Inject(method = "releaseChannel", at = @At("HEAD"), cancellable = true)
	private void cadence$release(Channel channel, CallbackInfo ci) {
		if (!this.cadence$apple) return;
		ci.cancel();
		if (this.cadence$static.remove(channel) || this.cadence$streaming.remove(channel)) channel.destroy();
	}

	@Inject(method = "getChannelDebugString", at = @At("HEAD"), cancellable = true)
	private void cadence$debug(CallbackInfoReturnable<String> cir) {
		if (!this.cadence$apple) return;
		cir.setReturnValue(String.format("Sounds: %d/%d + %d/%d (Apple spatial audio)", this.cadence$static.size(), STATIC_MAX,
			this.cadence$streaming.size(), STREAMING_MAX));
	}

	@Inject(method = "isCurrentDeviceDisconnected", at = @At("HEAD"), cancellable = true)
	private void cadence$connected(CallbackInfoReturnable<Boolean> cir) {
		if (this.cadence$apple) cir.setReturnValue(false);
	}
}
