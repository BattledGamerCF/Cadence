package dev.cadence.metal.mixin.audio;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import dev.cadence.metal.audio.AppleAudio;
import dev.cadence.metal.audio.AppleChannel;
import dev.cadence.metal.audio.AppleSoundBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cadence (AppleAudio): a channel with an Apple engine voice does what vanilla does with its OpenAL source, on the voice
 * (states, position, pitch, volume, looping, attenuation, static buffers and streams, 1-second stream buffers, 4 queued).
 * A channel without one (OpenAL running) is untouched.
 */
@Mixin(Channel.class)
abstract class ChannelMixin implements AppleChannel {
	@Shadow @Final private AtomicBoolean initialized;
	@Shadow private AudioStream stream;
	@Shadow private int streamingBufferSize;

	@Shadow
	private static int calculateBufferSize(javax.sound.sampled.AudioFormat format, int seconds) {
		throw new AssertionError();
	}

	@Unique private long cadence$voice;

	@Override
	public long cadence$voice() {
		return this.cadence$voice;
	}

	@Override
	public void cadence$voice(long voice) {
		this.cadence$voice = voice;
	}

	@Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
	private void cadence$destroy(CallbackInfo ci) {
		long v = this.cadence$voice;
		if (v == 0) return;
		ci.cancel();
		if (!this.initialized.compareAndSet(true, false)) return;
		this.cadence$voice = 0;
		AppleAudio.freeVoice(v);
		if (this.stream != null) {
			try {
				this.stream.close();
			} catch (IOException e) {
				System.out.println("[cadence] Apple sound engine: failed to close an audio stream: " + e);
			}
			this.stream = null;
		}
	}

	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void cadence$play(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.play(this.cadence$voice);
	}

	@Inject(method = "pause", at = @At("HEAD"), cancellable = true)
	private void cadence$pause(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.pause(this.cadence$voice);
	}

	@Inject(method = "unpause", at = @At("HEAD"), cancellable = true)
	private void cadence$unpause(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.unpause(this.cadence$voice);
	}

	@Inject(method = "stop", at = @At("HEAD"), cancellable = true)
	private void cadence$stop(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		if (this.initialized.get()) AppleAudio.stopVoice(this.cadence$voice);
	}

	@Inject(method = "playing", at = @At("HEAD"), cancellable = true)
	private void cadence$playing(CallbackInfoReturnable<Boolean> cir) {
		if (this.cadence$voice == 0) return;
		cir.setReturnValue(this.initialized.get() && AppleAudio.state(this.cadence$voice) == AppleAudio.PLAYING);
	}

	@Inject(method = "stopped", at = @At("HEAD"), cancellable = true)
	private void cadence$stopped(CallbackInfoReturnable<Boolean> cir) {
		if (this.cadence$voice == 0) return;
		cir.setReturnValue(!this.initialized.get() || AppleAudio.state(this.cadence$voice) == AppleAudio.STOPPED);
	}

	@Inject(method = "setSelfPosition", at = @At("HEAD"), cancellable = true)
	private void cadence$position(Vec3 pos, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
	}

	@Inject(method = "setPitch", at = @At("HEAD"), cancellable = true)
	private void cadence$pitch(float pitch, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.PITCH, pitch, 0, 0);
	}

	@Inject(method = "setLooping", at = @At("HEAD"), cancellable = true)
	private void cadence$looping(boolean looping, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.LOOPING, looping ? 1 : 0, 0, 0);
	}

	@Inject(method = "setVolume", at = @At("HEAD"), cancellable = true)
	private void cadence$volume(float volume, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.VOLUME, volume, 0, 0);
	}

	@Inject(method = "disableAttenuation", at = @At("HEAD"), cancellable = true)
	private void cadence$noAttenuation(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.NO_ATTENUATION, 0, 0, 0);
	}

	@Inject(method = "linearAttenuation", at = @At("HEAD"), cancellable = true)
	private void cadence$linear(float maxDistance, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.LINEAR, maxDistance, 0, 0);
	}

	@Inject(method = "setRelative", at = @At("HEAD"), cancellable = true)
	private void cadence$relative(boolean relative, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		AppleAudio.set(this.cadence$voice, AppleAudio.RELATIVE, relative ? 1 : 0, 0, 0);
	}

	@Inject(method = "attachStaticBuffer", at = @At("HEAD"), cancellable = true)
	private void cadence$attachStatic(SoundBuffer buffer, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		long b = ((AppleSoundBuffer) buffer).cadence$appleBuffer();
		if (b != 0) AppleAudio.attach(this.cadence$voice, b);
	}

	@Inject(method = "attachBufferStream", at = @At("HEAD"), cancellable = true)
	private void cadence$attachStream(AudioStream stream, CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		this.stream = stream;
		this.streamingBufferSize = calculateBufferSize(stream.getFormat(), 1);
		this.cadence$pump(4);
	}

	@Inject(method = "updateStream", at = @At("HEAD"), cancellable = true)
	private void cadence$updateStream(CallbackInfo ci) {
		if (this.cadence$voice == 0) return;
		ci.cancel();
		if (this.stream != null) this.cadence$pump(AppleAudio.processed(this.cadence$voice));
	}

	/** Vanilla's pumpBuffers: n more buffers of the stream queued (1 s each). */
	@Unique
	private void cadence$pump(int n) {
		if (this.stream == null) return;
		try {
			for (int i = 0; i < n; i++) {
				ByteBuffer pcm = this.stream.read(this.streamingBufferSize);
				if (pcm == null) break;
				if (!AppleAudio.push(this.cadence$voice, pcm, this.stream.getFormat())) break;
			}
		} catch (IOException e) {
			System.out.println("[cadence] Apple sound engine: failed to read from an audio stream: " + e);
		}
	}
}
