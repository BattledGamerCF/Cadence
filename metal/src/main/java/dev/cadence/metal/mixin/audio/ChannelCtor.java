package dev.cadence.metal.mixin.audio;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The Apple engine's channels are the game's own Channel objects (source 0: no OpenAL source behind them). */
@Mixin(Channel.class)
public interface ChannelCtor {
	@Invoker("<init>")
	static Channel cadence$create(int source) {
		throw new AssertionError();
	}
}
