package dev.cadence.metal.audio;

/** A Channel's Apple engine voice (ChannelMixin): 0 for an OpenAL channel. */
public interface AppleChannel {
	long cadence$voice();

	void cadence$voice(long voice);
}
