//! crypto.zig — GitHub secret encryption via libsodium
//!
//! GitHub requires secrets to be encrypted with the repository's or
//! environment's public key before uploading via the API. The algorithm is
//! libsodium's crypto_box_seal (X25519 + XSalsa20-Poly1305 anonymous box).
//!
//! Java equivalent in OrgChecker.encryptSecret():
//!   var sodium = new LazySodiumJava(new SodiumJava());
//!   byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
//!   byte[] msgBytes = plaintext.getBytes(StandardCharsets.UTF_8);
//!   byte[] cipherText = new byte[msgBytes.length + Box.SEALBYTES];
//!   sodium.cryptoBoxSeal(cipherText, msgBytes, msgBytes.length, decodedKey);
//!   return Base64.getEncoder().encodeToString(cipherText);
//!
//! Zig uses @cImport to call libsodium directly; no wrapper library needed.

const std = @import("std");
const Allocator = std.mem.Allocator;

const c = @cImport({
    @cInclude("sodium.h");
});

/// Encrypt `plaintext` with the repository's base64-encoded public key.
/// Returns a base64-encoded ciphertext string, allocated from `alloc`.
/// Caller is responsible for freeing the returned slice.
///
/// The public key and ciphertext use the standard (non-URL-safe) base64
/// variant, matching GitHub's API expectations.
pub fn encryptSecret(
    alloc: Allocator,
    public_key_b64: []const u8,
    plaintext: []const u8,
) ![]u8 {
    // sodium_init() is idempotent; safe to call multiple times.
    if (c.sodium_init() < 0) return error.SodiumInitFailed;

    // Decode the base64 public key (32 bytes for X25519).
    var pk: [c.crypto_box_PUBLICKEYBYTES]u8 = undefined;
    var pk_len: usize = 0;
    const decode_rc = c.sodium_base642bin(
        &pk,
        pk.len,
        public_key_b64.ptr,
        public_key_b64.len,
        null,    // ignore_chars (none)
        &pk_len,
        null,    // b64_end (don't need the end pointer)
        c.sodium_base64_VARIANT_ORIGINAL,
    );
    if (decode_rc != 0) return error.Base64DecodeFailed;
    if (pk_len != c.crypto_box_PUBLICKEYBYTES) return error.InvalidPublicKeyLength;

    // Encrypt: ciphertext = crypto_box_SEALBYTES overhead + plaintext.
    const ct_len = c.crypto_box_SEALBYTES + plaintext.len;
    const ciphertext = try alloc.alloc(u8, ct_len);
    defer alloc.free(ciphertext);

    const seal_rc = c.crypto_box_seal(
        ciphertext.ptr,
        plaintext.ptr,
        plaintext.len,
        &pk,
    );
    if (seal_rc != 0) return error.EncryptFailed;

    // Base64-encode the ciphertext for the GitHub API.
    // sodium_base64_encoded_len includes the null terminator in its count.
    const b64_max_len = c.sodium_base64_encoded_len(ct_len, c.sodium_base64_VARIANT_ORIGINAL);
    const b64_buf = try alloc.alloc(u8, b64_max_len);
    errdefer alloc.free(b64_buf);

    _ = c.sodium_bin2base64(
        b64_buf.ptr,
        b64_max_len,
        ciphertext.ptr,
        ct_len,
        c.sodium_base64_VARIANT_ORIGINAL,
    );

    // sodium_bin2base64 null-terminates; return the slice without the null byte.
    return std.mem.sliceTo(b64_buf, 0);
}
