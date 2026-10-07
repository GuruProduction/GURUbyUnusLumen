//! Record stream: AEAD-encrypted records with chained position counters.
//!
//! Record layout on disk (binary):
//!     u32 LE record_ciphertext_len    (frame header, written by vault layer)
//!     record_ciphertext_len bytes:
//!         nonce (24 bytes)
//!         XChaCha20-Poly1305 ciphertext || tag
//!
//! Per-record nonce: base_nonce (one XChaCha PRF output per vault file) with
//! the low 8 bytes XORed against the record counter, so all nonces inside one
//! file are distinct whenever every counter is used at most once, which the
//! RecordNonceCounter (non-resettable per session) plus single-session writes
//! guarantee.
//!
//! AAD: domain tag || counter (u64 LE) || file_id (32). This binds every
//! ciphertext to its exact position and file; record transposition, deletion
//! midstream, or cross-vault ciphertext transplant fail closed at AEAD open.

use std::fmt;

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use zeroize::{Zeroize, Zeroizing};

use crate::error::CryptoError;

/// Number of bytes inside one XChaCha20-Poly1305 nonce.
pub const RECORD_NONCE_LEN: usize = 24;

/// Non-copyable 256-bit key. It can never be copied into a second key holder,
/// never be serialized, and gets zeroized on drop or explicit erase. All AEAD
/// constructions happen against a borrowed view of these bytes.
pub struct RamKey {
    key: Zeroizing<[u8; 32]>,
}

impl RamKey {
    /// Construct from 32 raw bytes (the KDF output holder inside vault.rs).
    /// The Zeroizing wrapper immediately takes ownership so intermediate raw
    /// arrays don't linger.
    pub fn from_raw_bytes(bytes: [u8; 32]) -> Self {
        Self {
            key: Zeroizing::new(bytes),
        }
    }

    /// Explicit self-erase ahead of natural drop.
    pub fn erase(&mut self) {
        self.key.zeroize();
    }

    /// Borrowed raw key bytes. In-RAM only; never serialize or broadcast.
    pub fn raw(&self) -> &[u8; 32] {
        &self.key
    }
}

impl fmt::Debug for RamKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "RamKey(<redacted>)")
    }
}

impl Drop for RamKey {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

/// Passphrase verified against a vault header's verifier: carries the in-RAM
/// key that the passphrase rederives. Redacted in Debug.
pub struct VerifiedPassphrase {
    key: RamKey,
}

impl VerifiedPassphrase {
    pub(crate) fn new(key: RamKey) -> Self {
        Self { key }
    }

    pub fn key(&self) -> &RamKey {
        &self.key
    }
}

impl fmt::Debug for VerifiedPassphrase {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "VerifiedPassphrase(<redacted>)")
    }
}

/// Per-file record nonce counter; single-direction monotonic per session.
pub struct RecordNonceCounter {
    counter: u64,
}

impl RecordNonceCounter {
    pub fn new() -> Self {
        Self { counter: 0 }
    }

    pub fn next_counter(&mut self) -> u64 {
        let c = self.counter;
        self.counter += 1;
        c
    }

    pub fn current(&self) -> u64 {
        self.counter
    }
}

impl Default for RecordNonceCounter {
    fn default() -> Self {
        Self::new()
    }
}

/// Per-record nonce = base_nonce with its low 8 bytes XORed with the record
/// counter (LE). Storing the counter never needed: it lives inline in the
/// stream order; the AAD bind is what enforces it.
pub fn record_nonce(base: &[u8; RECORD_NONCE_LEN], counter: u64) -> [u8; RECORD_NONCE_LEN] {
    let mut nonce = *base;
    for byte_index in 0..8 {
        nonce[byte_index] ^= (counter >> (byte_index * 8)) as u8;
    }
    nonce
}

/// AAD domain tags.
pub const AAD_RECORD_TAG: &[u8] = b"cerebrum-record-v1";
pub const AAD_VERIFIER_TAG: &[u8] = b"cerebrum-verifier-v1"; // verifier AAD construction actually lives in vault.rs (needs salt+kdf bytes)
pub const AAD_NONCE_DERIVE_TAG: &[u8] = b"cerebrum-nonce-derive-v1";
pub use AAD_NONCE_DERIVE_TAG as VERIFIER_OPEN_BIND_AAD; // (legacy alias)

/// Build the AAD for one record.
pub fn record_aad(counter: u64, file_id: &[u8; 32]) -> Vec<u8> {
    let mut aad = Vec::with_capacity(AAD_RECORD_TAG.len() + 8 + 32);
    aad.extend_from_slice(AAD_RECORD_TAG);
    aad.extend_from_slice(&counter.to_le_bytes());
    aad.extend_from_slice(file_id);
    aad
}

/// Build an AEAD instance against the given RamKey. Central helper so AEAD
/// construction is uniform across modules.
pub(crate) fn aead_for_key(key: &RamKey) -> XChaCha20Poly1305 {
    #[allow(clippy::let_and_return)]
    let aead = XChaCha20Poly1305::new(key.raw().into());
    aead
}

/// Encrypt one record; on-disk layout (nonce || ct||tag).
pub fn record_encrypt_with_key(
    key: &RamKey,
    plaintext: &[u8],
    counter: u64,
    base_nonce: &[u8; RECORD_NONCE_LEN],
    file_id: &[u8; 32],
) -> Result<Vec<u8>, CryptoError> {
    let aead = aead_for_key(key);
    let nonce_storage = record_nonce(base_nonce, counter);
    let nonce = XNonce::try_from(&nonce_storage[..RECORD_NONCE_LEN])
        .map_err(|_| CryptoError::MalformedRecord)?;
    let aad = record_aad(counter, file_id);

    let ct = aead
        .encrypt(&nonce, Payload { msg: plaintext, aad: &aad })
        .map_err(|_e| CryptoError::AeadSealFailed {
            reason: "AEAD seal integrity failure".into(),
        })?;

    let mut out = Vec::with_capacity(RECORD_NONCE_LEN + ct.len());
    out.extend_from_slice(&nonce_storage);
    out.extend_from_slice(ct.as_slice());
    Ok(out)
}

/// Decrypt one record; fails closed on any tampering, transposition, or drop.
pub fn record_decrypt_with_key(
    key: &RamKey,
    on_disk_record: &[u8],
    counter: u64,
    file_id: &[u8; 32],
) -> Result<Vec<u8>, CryptoError> {
    if on_disk_record.len() < RECORD_NONCE_LEN + 16 {
        return Err(CryptoError::MalformedRecord);
    }
    let nonce_bytes: [u8; RECORD_NONCE_LEN] = on_disk_record[..RECORD_NONCE_LEN]
        .try_into()
        .map_err(|_| CryptoError::MalformedRecord)?;
    let ct = &on_disk_record[RECORD_NONCE_LEN..];
    let aead = aead_for_key(key);
    let nonce = XNonce::try_from(&nonce_bytes[..RECORD_NONCE_LEN])
        .map_err(|_| CryptoError::MalformedRecord)?;
    let aad = record_aad(counter, file_id);

    aead
        .decrypt(&nonce, Payload { msg: ct, aad: &aad })
        .map(|v| v.to_vec())
        .map_err(|_e| CryptoError::AeadOpenFailed {
            reason: "AEAD open integrity failure".into(),
        })
}

/// Build an in-RAM key holder directly from raw Argon2 output bytes.
pub fn aead_key_from_raw(raw_argon_out: [u8; 32]) -> RamKey {
    RamKey::from_raw_bytes(raw_argon_out)
}

pub use AAD_VERIFIER_TAG as AAD_VERIFIER_TAG_PUBLIC;