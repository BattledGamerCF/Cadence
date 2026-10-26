package dev.cadence.metal.audio;

/** A SoundBuffer's PCM as an Apple engine buffer (SoundBufferMixin), made on first use; 0 when it has none. */
public interface AppleSoundBuffer {
	long cadence$appleBuffer();
}
