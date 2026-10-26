package dev.cadence.metal.mixin.audio;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.cadence.metal.audio.AppleAudio;
import dev.cadence.metal.audio.AppleChannel;
import dev.cadence.metal.audio.AppleSoundBuffer;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence (AppleAudio): a sound's PCM becomes an Apple engine buffer the first time a channel plays it, as vanilla makes its
 * OpenAL buffer then, and the PCM is let go as vanilla lets it go; discarding the buffer frees it. It counts as loaded while
 * it holds one (the sound cache keeps it).
 */
@Mixin(SoundBuffer.class)
abstract class SoundBufferMixin implements AppleSoundBuffer {
	@Shadow private ByteBuffer data;
	@Shadow @Final private AudioFormat format;
	@Unique private long cadence$apple;

	@Override
	public long cadence$appleBuffer() {
		if (this.cadence$apple == 0 && this.data != null) {
			this.cadence$apple = AppleAudio.buffer(this.data, this.format);
			if (this.cadence$apple != 0) this.data = null;
		}
		return this.cadence$apple;
	}

	@Inject(method = "discardAlBuffer", at = @At("HEAD"))
	private void cadence$discard(CallbackInfo ci) {
		long b = this.cadence$apple;
		this.cadence$apple = 0;
		if (b != 0) AppleAudio.freeBuffer(b);
	}

	@Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
	private void cadence$valid(CallbackInfoReturnable<Boolean> cir) {
		if (this.cadence$apple != 0) cir.setReturnValue(true);
	}
}
