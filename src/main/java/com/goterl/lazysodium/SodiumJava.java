/*
 * Copyright (c) Terl Tech Ltd • 01/04/2021, 12:31 • goterl.com
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v2.0. If a copy of the MPL was not distributed with this
 * file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.goterl.lazysodium;

import java.util.List;

import com.goterl.lazysodium.utils.Base64Java;
import com.goterl.lazysodium.utils.LibraryLoader;
import com.sun.jna.Native;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class SodiumJava extends Sodium {

	private final Object lock = new Object();

	/**
	 * Creates the SodiumJava instance. Uses the
	 * {@linkplain LibraryLoader.Mode#PREFER_SYSTEM default loading mode}, first
	 * attempting to load the system sodium, and, if that fails — the bundled
	 * one.
	 */
	@SuppressFBWarnings(
		{ "MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR",
				"ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD" }
	)
	public SodiumJava() {
		var sodiumPath = LibraryLoader.getSodiumPathInResources();
		synchronized (lock) {
			for (Class<?> clzz : List.of(Sodium.class, SodiumJava.class)) {
				Native.register(clzz, "/" + sodiumPath);
			}
		}
		base64Facade = new Base64Java();
		onRegistered();
	}

	// Scrypt

	public native int crypto_pwhash_scryptsalsa208sha256(
			byte[] out,
			long outLen,
			byte[] password,
			long passwordLen,
			byte[] salt,
			long opsLimit,
			long memLimit
	);

	public native int crypto_pwhash_scryptsalsa208sha256_str(
			byte[] out,
			byte[] password,
			long passwordLen,
			long opsLimit,
			long memLimit
	);

	public native int crypto_pwhash_scryptsalsa208sha256_str_verify(
			byte[] str,
			byte[] password,
			long passwordLen
	);

	public native int crypto_pwhash_scryptsalsa208sha256_ll(
			byte[] password,
			int passwordLen,
			byte[] salt,
			int saltLen,
			long N,
			long r,
			long p,
			byte[] buf,
			int bufLen
	);

	public native int crypto_pwhash_scryptsalsa208sha256_str_needs_rehash(
			byte[] password,
			long opsLimit,
			long memLimit
	);

	// Salsa20 12 rounds

	public native void crypto_stream_salsa2012_keygen(byte[] key);

	public native int crypto_stream_salsa2012(
			byte[] c,
			long cLen,
			byte[] nonce,
			byte[] key
	);

	public native int crypto_stream_salsa2012_xor(
			byte[] cipher,
			byte[] message,
			long messageLen,
			byte[] nonce,
			byte[] key
	);

	public native void crypto_stream_salsa208_keygen(byte[] key);

	public native int crypto_stream_salsa208(
			byte[] c,
			long cLen,
			byte[] nonce,
			byte[] key
	);

	public native int crypto_stream_salsa208_xor(
			byte[] cipher,
			byte[] message,
			long messageLen,
			byte[] nonce,
			byte[] key
	);

	// XChaCha20

	public native int crypto_stream_xchacha20(
			byte[] c,
			long cLen,
			byte[] nonce,
			byte[] key
	);

	public native int crypto_stream_xchacha20_xor(
			byte[] cipher,
			byte[] message,
			long messageLen,
			byte[] nonce,
			byte[] key
	);

	public native int crypto_stream_xchacha20_xor_ic(
			byte[] cipher,
			byte[] message,
			long messageLen,
			byte[] nonce,
			long ic,
			byte[] key
	);

	public native void crypto_stream_xchacha20_keygen(byte[] key);

}
