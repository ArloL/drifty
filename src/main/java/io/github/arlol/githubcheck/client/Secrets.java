package io.github.arlol.githubcheck.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;

public final class Secrets {

	private Secrets() {
	}

	public static String encryptSecret(
			String base64PublicKey,
			String plaintext
	) {
		var sodium = new LazySodiumJava(new SodiumJava());
		byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
		byte[] msgBytes = plaintext.getBytes(StandardCharsets.UTF_8);
		byte[] cipherText = new byte[msgBytes.length + Box.SEALBYTES];
		sodium.cryptoBoxSeal(cipherText, msgBytes, msgBytes.length, decodedKey);
		return Base64.getEncoder().encodeToString(cipherText);
	}

}
