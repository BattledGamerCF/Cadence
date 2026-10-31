// Cadence: the game's sound on Apple's audio engine (dev.cadence.metal.audio.AppleAudio, the mixins in mixin/audio).
//
// Minecraft plays sound through OpenAL (OpenAL Soft): a source per playing sound, buffers of PCM, a listener. Here the same
// calls drive AVAudioEngine: one AVAudioEnvironmentNode (Apple's 3D mixer: HRTF spatial audio on headphones, panning on
// speakers, chosen by the output, AVAudioEnvironmentOutputTypeAuto) and an AVAudioPlayerNode per channel. A mono sound is
// placed in 3D as the game places it; stereo (music, the menu) plays as it is, as OpenAL plays it. Loudness follows OpenAL's
// exactly: the game's linear distance falloff per sound (gain = 1 - distance / max distance, the sound's own range) and its
// volume clamped to 0..1 are computed here and set as the node's volume, so the environment's own distance model is off (one
// model for all sources there; the game gives each sound its own range). Pitch is the node's rate (0.5 to 2, the game's range).
//
// Threading: the game calls in from its sound thread; the players' completion handlers run on the audio engine's threads and
// only touch atomics. The engine restarts itself when the output changes (headphones plugged in, a new default device).
#import <AVFAudio/AVFAudio.h>
#include <math.h>
#include <os/lock.h>
#include <stdatomic.h>
#include <string.h>

enum { V_INITIAL = 0, V_PLAYING = 1, V_PAUSED = 2, V_STOPPED = 3 };

typedef struct Voice {
	AVAudioPlayerNode *node;
	AVAudioFormat *connected;      // the format the node is connected to the environment with (retained), nil: not yet
	AVAudioPCMBuffer *staticBuf;   // the static sound attached (retained), nil for a stream
	atomic_int pending;            // buffers scheduled and not yet played (this generation's)
	atomic_int processed;          // buffers played since the game last asked (streams)
	atomic_int generation;         // a stop or a new attach starts a new one: older completions are ignored
	int state, looping, relative, attenuate, scheduled;
	float x, y, z, pitch, volume, maxDistance;
	float occlusion;               // World Reverb: dB of the blocks between the listener and the sound (0: none)
	// Sound Around Corners (SoundPaths.java): the way round to the listener, if one was found: where the sound seems to come
	// from (the first corner, as far away as the way is long), the way's length and the dB its corners take off
	int routed;
	float ax, ay, az, routeLength, routeDb;
} Voice;

#define MAX_VOICES 512
static AVAudioEngine *gEngine;
static AVAudioEnvironmentNode *gEnv;
static id gObserver;
static Voice *gVoices[MAX_VOICES];
static int gVoiceCount;
static os_unfair_lock gLock = OS_UNFAIR_LOCK_INIT;
static float gLx, gLy, gLz, gFx, gFy = 0, gFz = -1, gUx, gUy = 1, gUz;
static atomic_int gRestarts;
// The game's Directional Audio option: on, Apple's best HRTF everywhere; off, the output decides (spatial audio on headphones,
// Apple's own rendering on the Mac's speakers)
static AVAudio3DMixingRenderingAlgorithm gAlgorithm = AVAudio3DMixingRenderingAlgorithmAuto;
static void playNode(Voice *v);
// World Reverb (WorldAcoustics.java): the reverb of the space around the listener (Apple's environment reverb, its preset and
// level from rays cast into the blocks around) and how much of each spatial sound goes into it
static int gReverbOn;
static float gReverbBlend;
static int gLastPreset = -1;  // (the reverb preset loaded into gEnv: -1 for a new environment, BH7-10)

// ---- loudness and placement (gLock held) ----

// The game's linear distance falloff for this sound at distance d.
static float falloff(Voice *v, float d) {
	return v->maxDistance > 0 ? fminf(fmaxf(1.0f - fminf(d, v->maxDistance) / v->maxDistance, 0.0f), 1.0f) : 0.0f;
}

static void place(Voice *v) {
	float px = v->x, py = v->y, pz = v->z;
	if (v->relative) {
		// OpenAL's relative source: its position is in the listener's frame (x right, y up, -z forward)
		float rx = gFy * gUz - gFz * gUy, ry = gFz * gUx - gFx * gUz, rz = gFx * gUy - gFy * gUx;
		px = gLx + rx * v->x + gUx * v->y - gFx * v->z;
		py = gLy + ry * v->x + gUy * v->y - gFy * v->z;
		pz = gLz + rz * v->x + gUz * v->y - gFz * v->z;
	}
	float dx = px - gLx, dy = py - gLy, dz = pz - gLz, d = sqrtf(dx * dx + dy * dy + dz * dz);
	float gain = fminf(fmaxf(v->volume, 0.0f), 1.0f);
	float occlusion = v->occlusion, obstruction = 0.0f;
	if (v->attenuate) {
		float fall = falloff(v, d);
		// round the corner or through the wall, whichever is louder (the way round as far as it is long, duller per corner)
		if (v->routed && gReverbOn && !v->relative) {
			float round = falloff(v, v->routeLength) * powf(10.0f, v->routeDb / 20.0f);
			float through = fall * powf(10.0f, v->occlusion / 20.0f);
			if (round > through) {
				px = v->ax;
				py = v->ay;
				pz = v->az;
				fall = falloff(v, v->routeLength);
				occlusion = 0.0f;
				obstruction = v->routeDb;
			}
		}
		gain *= fall;
	}
	v->node.volume = gain;
	// a relative sound at the listener (the menu, music) isn't placed anywhere: it plays as recorded
	v->node.sourceMode = v->relative && fabsf(v->x) + fabsf(v->y) + fabsf(v->z) < 1e-3f ? AVAudio3DMixingSourceModeBypass
		: AVAudio3DMixingSourceModeSpatializeIfMono;
	v->node.position = AVAudioMake3DPoint(px, py, pz);
	int spatial = v->node.sourceMode != AVAudio3DMixingSourceModeBypass;
	v->node.reverbBlend = gReverbOn && spatial ? gReverbBlend : 0.0f;
	v->node.occlusion = gReverbOn && spatial ? occlusion : 0.0f;
	v->node.obstruction = gReverbOn && spatial ? obstruction : 0.0f;
}

// ---- the engine ----

static int startEngine(char *err, int cap) {
	NSError *e = nil;
	if (![gEngine startAndReturnError:&e]) {
		if (err && cap > 0) snprintf(err, (size_t) cap, "%s", e ? e.localizedDescription.UTF8String : "the audio engine didn't start");
		return 0;
	}
	return 1;
}

int mca_init(int directional, char *err, int cap) {
	gLastPreset = -1;
	@autoreleasepool {
		gAlgorithm = directional ? AVAudio3DMixingRenderingAlgorithmHRTFHQ : AVAudio3DMixingRenderingAlgorithmAuto;
		if (gEngine) return 1;
		gEngine = [[AVAudioEngine alloc] init];
		gEnv = [[AVAudioEnvironmentNode alloc] init];
		[gEngine attachNode:gEnv];
		// (the main mixer only: the input node is never touched, so nothing asks for the microphone)
		AVAudioMixerNode *main = gEngine.mainMixerNode;
		AVAudioFormat *out = [main outputFormatForBus:0];
		AVAudioFormat *envOut = [[[AVAudioFormat alloc] initStandardFormatWithSampleRate:out.sampleRate channels:2] autorelease];
		[gEngine connect:gEnv to:main format:envOut];
		gEnv.outputType = AVAudioEnvironmentOutputTypeAuto;
		gEnv.distanceAttenuationParameters.distanceAttenuationModel = AVAudioEnvironmentDistanceAttenuationModelLinear;
		gEnv.distanceAttenuationParameters.referenceDistance = 1.0f;
		gEnv.distanceAttenuationParameters.maximumDistance = 100000.0f;
		gEnv.distanceAttenuationParameters.rolloffFactor = 0.0f;  // (no distance model here: the game's own, in place())
		gEnv.reverbParameters.enable = NO;
		gEnv.listenerPosition = AVAudioMake3DPoint(0, 0, 0);
		[gEngine prepare];
		if (!startEngine(err, cap)) {
			[gEngine release];
			[gEnv release];
			gEngine = nil;
			gEnv = nil;
			return 0;
		}
		// a new output (headphones, another default device) stops the engine: start it again and resume what was playing
		gObserver = [[[NSNotificationCenter defaultCenter] addObserverForName:AVAudioEngineConfigurationChangeNotification object:gEngine queue:nil
			usingBlock:^(NSNotification *n) {
				(void) n;
				dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
					os_unfair_lock_lock(&gLock);
					if (gEngine && !gEngine.running && startEngine(NULL, 0)) {
						atomic_fetch_add(&gRestarts, 1);
						for (int i = 0; i < gVoiceCount; i++)
							if (gVoices[i]->state == V_PLAYING) playNode(gVoices[i]);
					}
					os_unfair_lock_unlock(&gLock);
				});
			}] retain];
		return 1;
	}
}

void mca_shutdown(void) {
	@autoreleasepool {
		os_unfair_lock_lock(&gLock);
		if (gObserver) {
			[[NSNotificationCenter defaultCenter] removeObserver:gObserver];
			[gObserver release];
			gObserver = nil;
		}
		if (gEngine) [gEngine stop];
		os_unfair_lock_unlock(&gLock);
		// (voices are freed by the game's channel cleanup first; any left go with the engine)
		os_unfair_lock_lock(&gLock);
		for (int i = 0; i < gVoiceCount; i++) {
			Voice *v = gVoices[i];
			[gEngine detachNode:v->node];
			[v->node release];
			[v->connected release];
			[v->staticBuf release];
			free(v);
		}
		gVoiceCount = 0;
		[gEnv release];
		[gEngine release];
		gEnv = nil;
		gEngine = nil;
		os_unfair_lock_unlock(&gLock);
	}
}

void mca_listener(float px, float py, float pz, float fx, float fy, float fz, float ux, float uy, float uz) {
	os_unfair_lock_lock(&gLock);
	gLx = px; gLy = py; gLz = pz;
	gFx = fx; gFy = fy; gFz = fz;
	gUx = ux; gUy = uy; gUz = uz;
	if (gEnv) {
		gEnv.listenerPosition = AVAudioMake3DPoint(px, py, pz);
		gEnv.listenerVectorOrientation = AVAudioMake3DVectorOrientation(AVAudioMake3DVector(fx, fy, fz), AVAudioMake3DVector(ux, uy, uz));
		for (int i = 0; i < gVoiceCount; i++) if (gVoices[i]->attenuate || gVoices[i]->relative) place(gVoices[i]);
	}
	os_unfair_lock_unlock(&gLock);
}

// ---- sample data ----

// PCM as the game decodes it (8-bit unsigned, 16-bit signed, 32-bit float; interleaved; either byte order) into a buffer of
// the engine's standard (deinterleaved float) format.
static AVAudioPCMBuffer *convert(const unsigned char *data, int bytes, int channels, int bits, int isFloat, int bigEndian, float rate) {
	if (channels < 1 || channels > 2 || (bits != 8 && bits != 16 && bits != 32) || rate <= 0) return nil;
	int frameBytes = channels * bits / 8;
	AVAudioFrameCount frames = (AVAudioFrameCount) (bytes / frameBytes);
	AVAudioFormat *fmt = [[[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:(AVAudioChannelCount) channels] autorelease];
	AVAudioPCMBuffer *b = [[AVAudioPCMBuffer alloc] initWithPCMFormat:fmt frameCapacity:frames > 0 ? frames : 1];
	if (!b) return nil;
	b.frameLength = frames;
	float *const *out = b.floatChannelData;
	for (AVAudioFrameCount i = 0; i < frames; i++) {
		for (int c = 0; c < channels; c++) {
			const unsigned char *s = data + (size_t) i * frameBytes + c * (bits / 8);
			float f;
			if (bits == 8) f = ((int) s[0] - 128) / 128.0f;
			else if (bits == 16) {
				int16_t v = bigEndian ? (int16_t) (s[0] << 8 | s[1]) : (int16_t) (s[1] << 8 | s[0]);
				f = v / 32768.0f;
			} else if (isFloat) {
				uint32_t u = bigEndian ? (uint32_t) s[0] << 24 | (uint32_t) s[1] << 16 | (uint32_t) s[2] << 8 | s[3]
					: (uint32_t) s[3] << 24 | (uint32_t) s[2] << 16 | (uint32_t) s[1] << 8 | s[0];
				memcpy(&f, &u, 4);
			} else {
				int32_t v = bigEndian ? (int32_t) ((uint32_t) s[0] << 24 | (uint32_t) s[1] << 16 | (uint32_t) s[2] << 8 | s[3])
					: (int32_t) ((uint32_t) s[3] << 24 | (uint32_t) s[2] << 16 | (uint32_t) s[1] << 8 | s[0]);
				f = (float) (v / 2147483648.0);
			}
			out[c][i] = f;
		}
	}
	return b;  // (retained: the caller owns it)
}

void *mca_buffer(const void *data, int bytes, int channels, int bits, int isFloat, int bigEndian, float rate) {
	@autoreleasepool {
		return convert((const unsigned char *) data, bytes, channels, bits, isFloat, bigEndian, rate);
	}
}

void mca_buffer_free(void *b) {
	if (b) [(AVAudioPCMBuffer *) b release];
}

// ---- voices (a channel each) ----

void *mca_voice_new(void) {
	@autoreleasepool {
		os_unfair_lock_lock(&gLock);
		if (!gEngine || gVoiceCount >= MAX_VOICES) {
			os_unfair_lock_unlock(&gLock);
			return NULL;
		}
		Voice *v = calloc(1, sizeof(Voice));
		v->node = [[AVAudioPlayerNode alloc] init];
		[gEngine attachNode:v->node];
		v->node.renderingAlgorithm = gAlgorithm;
		v->pitch = 1;
		v->volume = 1;
		v->attenuate = 0;
		gVoices[gVoiceCount++] = v;
		os_unfair_lock_unlock(&gLock);
		return v;
	}
}

void mca_voice_free(void *p) {
	@autoreleasepool {
		Voice *v = p;
		if (!v) return;
		os_unfair_lock_lock(&gLock);
		atomic_fetch_add(&v->generation, 1);
		[v->node stop];
		if (gEngine) [gEngine detachNode:v->node];
		for (int i = 0; i < gVoiceCount; i++)
			if (gVoices[i] == v) {
				gVoices[i] = gVoices[--gVoiceCount];
				break;
			}
		os_unfair_lock_unlock(&gLock);
		[v->node release];
		[v->connected release];
		[v->staticBuf release];
		free(v);
	}
}

// The node connected for buffers of this format (mono and stereo, any rate: the environment mixer converts the rate).
static void connectFor(Voice *v, AVAudioFormat *f) {
	if (v->connected && v->connected.channelCount == f.channelCount && v->connected.sampleRate == f.sampleRate) return;
	[v->node stop];
	@try {
		[gEngine disconnectNodeOutput:v->node];
		AVAudioNodeBus bus = gEnv.nextAvailableInputBus;
		[gEngine connect:v->node to:gEnv fromBus:0 toBus:bus format:f];
	} @catch (NSException *e) {
		(void) e;
		[v->connected release];
		v->connected = nil;
		return;
	}
	[v->connected release];
	v->connected = [f retain];
	// (a node's 3D settings are on its connection to the environment: set again)
	v->node.renderingAlgorithm = gAlgorithm;
	v->node.rate = v->pitch;
	place(v);
}

// The node playing, if the engine runs (AVAudioPlayerNode throws otherwise: the engine restarting after an output change).
static void playNode(Voice *v) {
	if (!v->connected || !gEngine.running) return;
	@try {
		[v->node play];
	} @catch (NSException *e) {
		(void) e;
	}
}

static void schedule(Voice *v, AVAudioPCMBuffer *b, int loop) {
	if (!v->connected) return;
	int gen = atomic_load(&v->generation);
	atomic_fetch_add(&v->pending, 1);
	v->scheduled = 1;
	Voice *voice = v;
	[v->node scheduleBuffer:b atTime:nil options:loop ? AVAudioPlayerNodeBufferLoops : 0 completionCallbackType:AVAudioPlayerNodeCompletionDataPlayedBack
		completionHandler:^(AVAudioPlayerNodeCompletionCallbackType t) {
			(void) t;
			if (atomic_load(&voice->generation) != gen) return;
			atomic_fetch_sub(&voice->pending, 1);
			atomic_fetch_add(&voice->processed, 1);
		}];
}

static void resetQueue(Voice *v) {
	atomic_fetch_add(&v->generation, 1);
	[v->node stop];
	atomic_store(&v->pending, 0);
	atomic_store(&v->processed, 0);
	v->scheduled = 0;
}

void mca_voice_static(void *p, void *buf) {
	@autoreleasepool {
		Voice *v = p;
		AVAudioPCMBuffer *b = buf;
		if (!v || !b) return;
		os_unfair_lock_lock(&gLock);
		resetQueue(v);
		v->occlusion = 0.0f;  // (a new sound on this channel: the last one's wall and way round don't apply)
		v->routed = 0;
		[b retain];
		[v->staticBuf release];
		v->staticBuf = b;
		connectFor(v, b.format);
		schedule(v, b, v->looping);
		os_unfair_lock_unlock(&gLock);
	}
}

// A stream's next buffer (converted from the game's PCM), queued after what is there. Returns 0 when the format can't be read.
int mca_voice_stream_push(void *p, const void *data, int bytes, int channels, int bits, int isFloat, int bigEndian, float rate) {
	@autoreleasepool {
		Voice *v = p;
		if (!v) return 0;
		AVAudioPCMBuffer *b = convert((const unsigned char *) data, bytes, channels, bits, isFloat, bigEndian, rate);
		if (!b) return 0;
		os_unfair_lock_lock(&gLock);
		if (v->staticBuf) {
			resetQueue(v);
			[v->staticBuf release];
			v->staticBuf = nil;
		}
		connectFor(v, b.format);
		schedule(v, b, 0);
		os_unfair_lock_unlock(&gLock);
		[b release];
		return 1;
	}
}

int mca_voice_processed(void *p) {
	Voice *v = p;
	return v ? atomic_exchange(&v->processed, 0) : 0;
}

void mca_voice_play(void *p) {
	Voice *v = p;
	if (!v) return;
	os_unfair_lock_lock(&gLock);
	playNode(v);
	v->state = V_PLAYING;
	os_unfair_lock_unlock(&gLock);
}

void mca_voice_pause(void *p) {
	Voice *v = p;
	if (!v) return;
	os_unfair_lock_lock(&gLock);
	if (v->state == V_PLAYING) {
		[v->node pause];
		v->state = V_PAUSED;
	}
	os_unfair_lock_unlock(&gLock);
}

void mca_voice_unpause(void *p) {
	Voice *v = p;
	if (!v) return;
	os_unfair_lock_lock(&gLock);
	if (v->state == V_PAUSED) {
		playNode(v);
		v->state = V_PLAYING;
	}
	os_unfair_lock_unlock(&gLock);
}

void mca_voice_stop(void *p) {
	Voice *v = p;
	if (!v) return;
	os_unfair_lock_lock(&gLock);
	resetQueue(v);
	v->state = V_STOPPED;
	os_unfair_lock_unlock(&gLock);
}

// OpenAL's source state: initial, playing, paused, stopped; a playing voice whose buffers have all played has stopped (a
// stream that ran dry stops too, as OpenAL's does).
int mca_voice_state(void *p) {
	Voice *v = p;
	if (!v) return V_STOPPED;
	if (v->state == V_PLAYING && v->scheduled && atomic_load(&v->pending) <= 0) v->state = V_STOPPED;
	return v->state;
}

enum { P_POSITION = 0, P_PITCH = 1, P_VOLUME = 2, P_LOOPING = 3, P_LINEAR = 4, P_NO_ATTENUATION = 5, P_RELATIVE = 6 };

void mca_voice_set(void *p, int what, float a, float b, float c) {
	Voice *v = p;
	if (!v) return;
	os_unfair_lock_lock(&gLock);
	switch (what) {
		case P_POSITION: v->x = a; v->y = b; v->z = c; break;
		case P_PITCH:
			v->pitch = fminf(fmaxf(a, 0.5f), 2.0f);
			v->node.rate = v->pitch;
			break;
		case P_VOLUME: v->volume = a; break;
		case P_LOOPING: {
			int loop = a != 0;
			// (set after a static sound was attached and before it plays: schedule it again with the new looping)
			if (loop != v->looping && v->staticBuf && v->state != V_PLAYING) {
				v->looping = loop;
				AVAudioPCMBuffer *s = v->staticBuf;
				resetQueue(v);
				schedule(v, s, loop);
			}
			v->looping = loop;
			break;
		}
		case P_LINEAR: v->attenuate = 1; v->maxDistance = a; break;
		case P_NO_ATTENUATION: v->attenuate = 0; break;
		case P_RELATIVE: v->relative = a != 0; break;
		default: break;
	}
	if (what != P_PITCH && what != P_LOOPING && v->connected) place(v);
	os_unfair_lock_unlock(&gLock);
}

// voices, voices playing, engine restarts, the engine running, nodes really rendering (their player time advances)
void mca_stats(int *out) {
	os_unfair_lock_lock(&gLock);
	int playing = 0, rendering = 0;
	for (int i = 0; i < gVoiceCount; i++) {
		Voice *v = gVoices[i];
		if (v->state == V_PLAYING) playing++;
		if (v->connected && v->node.playing) {
			AVAudioTime *t = v->node.lastRenderTime;
			AVAudioTime *pt = t ? [v->node playerTimeForNodeTime:t] : nil;
			if (pt && pt.sampleTimeValid && pt.sampleTime > 0) rendering++;
		}
	}
	out[0] = gVoiceCount;
	out[1] = playing;
	out[2] = atomic_load(&gRestarts);
	out[3] = gEngine && gEngine.running ? 1 : 0;
	out[4] = rendering;
	os_unfair_lock_unlock(&gLock);
}

// ---- World Reverb (dev.cadence.metal.audio.WorldAcoustics) ----

// on: reverb and occlusion used; preset: an AVAudioUnitReverbPreset; level: the reverb's level (dB); blend: how much of each
// spatial sound goes into it (0..1).
void mca_environment(int on, int preset, float level, float blend) {
	@autoreleasepool {
		os_unfair_lock_lock(&gLock);
		if (gEnv) {
			gEnv.reverbParameters.enable = on ? YES : NO;
			if (on && preset != gLastPreset) {
				[gEnv.reverbParameters loadFactoryReverbPreset:(AVAudioUnitReverbPreset) preset];
				gLastPreset = preset;
			}
			gEnv.reverbParameters.level = level;
		}
		gReverbOn = on;
		gReverbBlend = blend;
		for (int i = 0; i < gVoiceCount; i++) if (gVoices[i]->connected) place(gVoices[i]);
		os_unfair_lock_unlock(&gLock);
	}
}

// The playing spatial sounds now: up to max of them, their handles into ids and positions (x, y, z) into pos. Returns how many.
int mca_snapshot(void **ids, float *pos, int max) {
	os_unfair_lock_lock(&gLock);
	int n = 0;
	for (int i = 0; i < gVoiceCount && n < max; i++) {
		Voice *v = gVoices[i];
		if (v->state != V_PLAYING || v->relative || !v->attenuate) continue;
		ids[n] = v;
		pos[n * 3] = v->x;
		pos[n * 3 + 1] = v->y;
		pos[n * 3 + 2] = v->z;
		n++;
	}
	os_unfair_lock_unlock(&gLock);
	return n;
}

// A sound's occlusion (dB, <= 0), if it still exists (the sound thread may have let it go since the snapshot).
void mca_occlusion(void *p, float db) {
	os_unfair_lock_lock(&gLock);
	for (int i = 0; i < gVoiceCount; i++)
		if (gVoices[i] == p) {
			Voice *v = p;
			v->occlusion = db;
			if (v->connected) place(v);
			break;
		}
	os_unfair_lock_unlock(&gLock);
}

// Sound Around Corners: a sound's way round to the listener (routed 0: none found, only the occlusion through the wall counts).
void mca_route(void *p, int routed, float ax, float ay, float az, float length, float db) {
	os_unfair_lock_lock(&gLock);
	for (int i = 0; i < gVoiceCount; i++)
		if (gVoices[i] == p) {
			Voice *v = p;
			v->routed = routed;
			v->ax = ax;
			v->ay = ay;
			v->az = az;
			v->routeLength = length;
			v->routeDb = db;
			if (v->connected) place(v);
			break;
		}
	os_unfair_lock_unlock(&gLock);
}
