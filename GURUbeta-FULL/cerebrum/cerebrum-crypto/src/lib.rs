//! Cerebrum Crypto — encrypted-at-rest storage layer for the Cerebrum brain.
//!
//! This is Phase A of the Cerebrum full build. Vault layout doc lives at the
//! locked-design level: every byte this crate writes to disk is plaintext
//! header + ciphertext. Plaintext record data exists only inside process RAM,
//! for the lifetime of an in-RAM key, and never survives to disk.
//!
//! Fixed primitives (locked in the plan):
//! - KDF: Argon2id, m = 262144 KiB (256 MiB), t = 3, p = 1.
//! - AEAD: XChaCha20-Poly1305 (192-bit nonces; collision-safe).
//! - Key: 256-bit, derived per vault from passphrase + per-vault salt.
//! - RAM-only keys: key material is zeroized on drop, never serialized,
//!   never written to any file anywhere. No escrow path exists.
//!
//! Envelope layout for one vault file:
//!   [fixed plaintext header = 115 bytes]
//!     MAGIC (4 bytes: 'C','E','R','B')
//!     layout version (u32 LE = 1)
//!     fixed header length (u32 LE = 115)
//!     Argon2id m_costKiB (u32 LE = 262144)
//!     Argon2id t_cost (u32 LE = 3)
//!     Argon2id p_cost (u32 LE = 1)
//!     salt (19 random bytes, per vault)
//!     verifier nonce (12 random bytes)
//!     verifier ciphertext (32 bytes, AEAD over a fixed verifier plaintext tagged with this header's salt)
//!     file_id (32 random bytes)
//!   [sealed record stream]
//!     u32 LE record-ciphertext length, then record ciphertext
//!     (nonce 24 || AEAD ciphertext||tag), where record nonce is
//!     file nonce base XOR record counter, so any transposition or drop of a
//!     middle record is detected at decrypt time, since records bind their own
//!     position counter as AAD.

pub mod error;
pub mod header;
pub mod record;
pub mod vault;

pub use error::CryptoError;
pub use header::{
    HeaderParseError, VaultHeaderRaw, DEFAULT_KDF_M_COST_KIB, DEFAULT_KDF_T_COST,
    DEFAULT_KDF_P_COST, FILE_ID_LEN, HEADER_LEN_BYTES_V1, LAYOUT_VERSION_V1, MAGIC_PREFIX,
    SALT_LEN_V1, VERIFIER_CIPHERTEXT_LEN_V1, VERIFIER_NONCE_LEN,
};
pub use record::{
    record_decrypt_with_key, record_encrypt_with_key, RamKey, VerifiedPassphrase,
    RecordNonceCounter, AAD_RECORD_TAG, AAD_VERIFIER_TAG, AAD_NONCE_DERIVE_TAG,
    RECORD_NONCE_LEN, record_nonce, record_aad,
};
pub use vault::{EncryptedVault, VaultBuilder};