//! Error surfaces for the Cerebrum encrypted vault.

use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum CryptoError {
    #[error("vault file bytes could not be read from disk")]
    IoRead(String),
    #[error("vault file could not be written to disk (no partial plaintext was ever stored): {reason}")]
    IoWrite { reason: String },
    #[error("on-disk vault bytes are empty")]
    EmptyEnvelope,
    #[error("vault header rejected: {:?}", self)]
    HeaderRejected(#[from] crate::header::HeaderParseError),
    #[error("header does not start with the CERB magic bytes, so this file was not written by this reader")]
    MissingHeaderMagic,
    #[error("header version or reported layout length mismatched what this reader supports")]
    HeaderLayoutMismatch,
    #[error("header's Argon2id configuration does not match this binary's locked configuration")]
    HeaderKdfMismatch,
    #[error("Argon2id key derivation failed: {reason}")]
    Argon2Failure { reason: String },
    #[error("passphrase verify failed: this is an wrong passphrase or the vault file is damaged/tampered")]
    PassphraseRejected,
    #[error("ciphertext tampering detected: AEAD integrity for record body failed")]
    AeadOpenFailed { reason: String },
    #[error("ciphertext sealing failure: {reason}")]
    AeadSealFailed { reason: String },
    #[error("record write refused: a file already exists at this path, so plaintext-free creation cannot start safely: {reason}")]
    FileAlreadyExists { reason: String },
    #[error("secure randomness failed: {reason}")]
    RandomSourceFailed { reason: String },
    #[error("encrypted record bytes malformed (shorter than nonce+tag or truncated mid-stream)")]
    MalformedRecord,
    #[error("record length header exceeded a sane bound so read was rejected (defensive against corrupt metadata)")]
    RecordLengthAbsurd,
    #[error("unknown frame / unknown logical record encountered mid-stream: {reason}")]
    UnknownRecordFrame { reason: String },
}