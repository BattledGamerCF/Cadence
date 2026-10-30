package dev.cadence.metal.mixin.net;

import dev.cadence.metal.net.FastNet;
import java.security.Key;
import javax.crypto.Cipher;
import net.minecraft.util.Crypt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The connection's AES key, kept beside the decrypting cipher made from it, for the native decrypter (FastNet). */
@Mixin(Crypt.class)
abstract class CryptKeyMixin {
	@Inject(method = "getCipher", at = @At("RETURN"))
	private static void cadence$key(int mode, Key key, CallbackInfoReturnable<Cipher> cir) {
		FastNet.rememberKey(mode, cir.getReturnValue(), key);
	}
}
