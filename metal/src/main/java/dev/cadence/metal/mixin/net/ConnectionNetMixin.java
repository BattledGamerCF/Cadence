package dev.cadence.metal.mixin.net;

import dev.cadence.metal.net.FastNet;
import javax.crypto.Cipher;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The decrypting and decompressing stages a remote connection adds: Cadence's native ones (FastNet). */
@Mixin(Connection.class)
abstract class ConnectionNetMixin {
	@Redirect(method = "setEncryptionKey", at = @At(value = "NEW", target = "(Ljavax/crypto/Cipher;)Lnet/minecraft/network/CipherDecoder;"))
	private CipherDecoder cadence$decrypt(Cipher cipher) {
		return FastNet.decoder(cipher);
	}

	@Redirect(method = "setupCompression", at = @At(value = "NEW", target = "(IZ)Lnet/minecraft/network/CompressionDecoder;"))
	private CompressionDecoder cadence$inflate(int threshold, boolean validate) {
		return FastNet.decompressor(threshold, validate);
	}
}
