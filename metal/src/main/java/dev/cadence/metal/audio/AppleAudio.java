package dev.cadence.metal.audio;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.cadence.metal.MetalBridge;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.system.MemoryUtil;

/**
 * Cadence: the game's sound on Apple's audio engine (Options > Music & Sounds > Sound Engine; native mcaudio.m) instead of
 * OpenAL. The game's audio classes (com.mojang.blaze3d.audio: Library, Channel, SoundBuffer, Listener) stay what the rest of
 * the game uses; while the engine runs, the mixins in dev.cadence.metal.mixin.audio send their calls here: a Channel is an
 * AVAudioPlayerNode in an AVAudioEnvironmentNode, a SoundBuffer's PCM an AVAudioPCMBuffer, the Listener the environment's
 * listener. Sound is placed with Apple's spatial audio (HRTF on headphones; the game's Directional Audio option asks for the
 * highest-quality HRTF on any output), with the game's own loudness and distance falloff. Switching applies at once
 * (SoundManager.reload). -Dcadence.audio=openal: OpenAL as before; it is also kept when another mod hooks the game's audio
 * classes (CompatLayers: sound physics and reverb mods drive OpenAL themselves), or when the engine can't start.
 *
 * <p>Every call comes from the game's sound thread.
 */
public final class AppleAudio {
	private static final Linker LINKER = Linker.nativeLinker();
	private static volatile boolean wanted = !"openal".equalsIgnoreCase(System.getProperty("cadence.audio", "apple"));
	private static volatile boolean running;
	private static MethodHandle INIT, SHUTDOWN, LISTENER, BUFFER, BUFFER_FREE, VOICE_NEW, VOICE_FREE, VOICE_STATIC, VOICE_PUSH, VOICE_PROCESSED,
		VOICE_PLAY, VOICE_PAUSE, VOICE_UNPAUSE, VOICE_STOP, VOICE_STATE, VOICE_SET, STATS;
	/** mcaudio.m mca_voice_state */
	public static final int INITIAL = 0, PLAYING = 1, PAUSED = 2, STOPPED = 3;
	/** mcaudio.m mca_voice_set */
	public static final int POSITION = 0, PITCH = 1, VOLUME = 2, LOOPING = 3, LINEAR = 4, NO_ATTENUATION = 5, RELATIVE = 6;

	private AppleAudio() {
	}

	/** The setting (saved by the shaders module's config): the Apple engine at the next start of the sound system. */
	public static void setWanted(boolean on) {
		wanted = on && !"openal".equalsIgnoreCase(System.getProperty("cadence.audio", "apple"));
	}

	public static boolean wanted() {
		return wanted;
	}

	/** Whether the game's sound runs on the Apple engine now. */
	public static boolean running() {
		return running;
	}

	/** Library.init: the engine started (directional: the game's Directional Audio option); false: stay on OpenAL. */
	public static boolean start(boolean directional) {
		if (!wanted) return false;
		try {
			bind();
			long err = MemoryUtil.nmemAlloc(512);
			try {
				int ok = (int) INIT.invokeExact(directional ? 1 : 0, err, 512);
				if (ok == 0) {
					System.out.println("[cadence] Apple sound engine didn't start (" + MemoryUtil.memUTF8(err) + "); OpenAL instead");
					return false;
				}
			} finally {
				MemoryUtil.nmemFree(err);
			}
			running = true;
			System.out.println("[cadence] sound: Apple's audio engine (spatial audio" + (directional ? ", HRTF" : "") + ")");
			return true;
		} catch (Throwable t) {
			System.out.println("[cadence] Apple sound engine unavailable: " + t + "; OpenAL instead");
			return false;
		}
	}

	/** Library.cleanup, after its channels are gone. */
	public static void stop() {
		if (!running) return;
		running = false;
		try {
			SHUTDOWN.invokeExact();
		} catch (Throwable t) {
			System.out.println("[cadence] Apple sound engine: shutdown failed: " + t);
		}
	}

	public static void listener(double px, double py, double pz, double fx, double fy, double fz, double ux, double uy, double uz) {
		try {
			LISTENER.invokeExact((float) px, (float) py, (float) pz, (float) fx, (float) fy, (float) fz, (float) ux, (float) uy, (float) uz);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A sound's PCM (as the game decoded it) as an engine buffer; 0 when its format can't be read. */
	public static long buffer(ByteBuffer pcm, AudioFormat format) {
		if (pcm == null || !pcm.hasRemaining()) return 0;
		long[] address = new long[1];
		ByteBuffer copy = direct(pcm, address);
		try {
			return (long) BUFFER.invokeExact(address[0], copy.remaining(), format.getChannels(), format.getSampleSizeInBits(),
				AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding()) ? 1 : 0, format.isBigEndian() ? 1 : 0, format.getSampleRate());
		} catch (Throwable t) {
			throw rethrow(t);
		} finally {
			if (copy != pcm) MemoryUtil.memFree(copy);
		}
	}

	public static void freeBuffer(long buffer) {
		if (buffer == 0) return;
		try {
			BUFFER_FREE.invokeExact(buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static long voice() {
		try {
			return (long) VOICE_NEW.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void freeVoice(long v) {
		call(VOICE_FREE, v);
	}

	public static void attach(long v, long buffer) {
		try {
			VOICE_STATIC.invokeExact(v, buffer);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** A stream's next PCM queued on the voice; false when its format can't be read. */
	public static boolean push(long v, ByteBuffer pcm, AudioFormat format) {
		if (pcm == null || !pcm.hasRemaining()) return true;
		long[] address = new long[1];
		ByteBuffer copy = direct(pcm, address);
		try {
			return (int) VOICE_PUSH.invokeExact(v, address[0], copy.remaining(), format.getChannels(), format.getSampleSizeInBits(),
				AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding()) ? 1 : 0, format.isBigEndian() ? 1 : 0, format.getSampleRate()) != 0;
		} catch (Throwable t) {
			throw rethrow(t);
		} finally {
			if (copy != pcm) MemoryUtil.memFree(copy);
		}
	}

	public static int processed(long v) {
		try {
			return (int) VOICE_PROCESSED.invokeExact(v);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void play(long v) {
		call(VOICE_PLAY, v);
	}

	public static void pause(long v) {
		call(VOICE_PAUSE, v);
	}

	public static void unpause(long v) {
		call(VOICE_UNPAUSE, v);
	}

	public static void stopVoice(long v) {
		call(VOICE_STOP, v);
	}

	public static int state(long v) {
		try {
			return (int) VOICE_STATE.invokeExact(v);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static void set(long v, int what, float a, float b, float c) {
		try {
			VOICE_SET.invokeExact(v, what, a, b, c);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** voices, playing, engine restarts (output changes), engine running, voices whose audio is advancing */
	public static int[] stats() {
		int[] out = new int[5];
		if (!running) return out;
		long p = MemoryUtil.nmemAlloc(20);
		try {
			STATS.invokeExact(p);
			for (int i = 0; i < 5; i++) out[i] = MemoryUtil.memGetInt(p + 4L * i);
		} catch (Throwable t) {
			throw rethrow(t);
		} finally {
			MemoryUtil.nmemFree(p);
		}
		return out;
	}

	// ---- plumbing ----

	/** pcm itself when it is direct (address set), else a direct copy the caller frees. */
	private static ByteBuffer direct(ByteBuffer pcm, long[] address) {
		if (pcm.isDirect()) {
			address[0] = MemoryUtil.memAddress(pcm);
			return pcm;
		}
		ByteBuffer copy = MemoryUtil.memAlloc(pcm.remaining());
		copy.put(pcm.duplicate()).flip();
		address[0] = MemoryUtil.memAddress(copy);
		return copy;
	}

	private static void call(MethodHandle h, long v) {
		try {
			h.invokeExact(v);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	private static synchronized void bind() {
		if (INIT != null) return;
		INIT = fn("mca_init", JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT);
		SHUTDOWN = fn("mca_shutdown", null);
		LISTENER = fn("mca_listener", null, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
		BUFFER = fn("mca_buffer", JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT);
		BUFFER_FREE = fn("mca_buffer_free", null, JAVA_LONG);
		VOICE_NEW = fn("mca_voice_new", JAVA_LONG);
		VOICE_FREE = fn("mca_voice_free", null, JAVA_LONG);
		VOICE_STATIC = fn("mca_voice_static", null, JAVA_LONG, JAVA_LONG);
		VOICE_PUSH = fn("mca_voice_stream_push", JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_FLOAT);
		VOICE_PROCESSED = fn("mca_voice_processed", JAVA_INT, JAVA_LONG);
		VOICE_PLAY = fn("mca_voice_play", null, JAVA_LONG);
		VOICE_PAUSE = fn("mca_voice_pause", null, JAVA_LONG);
		VOICE_UNPAUSE = fn("mca_voice_unpause", null, JAVA_LONG);
		VOICE_STOP = fn("mca_voice_stop", null, JAVA_LONG);
		VOICE_STATE = fn("mca_voice_state", JAVA_INT, JAVA_LONG);
		VOICE_SET = fn("mca_voice_set", null, JAVA_LONG, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
		STATS = fn("mca_stats", null, JAVA_LONG);
	}

	private static MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor d = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		var symbol = MetalBridge.library().find(name).orElseThrow(() -> new IllegalStateException("missing native symbol " + name));
		return LINKER.downcallHandle(symbol, d);
	}
}
