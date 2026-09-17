//! Error types for cerebrum-token.

use thiserror::Error;

/// Unified result type for token operations.
pub type TokenResult<T> = Result<T, TokenError>;

/// Errors that can occur during token compression, decompression, or vocabulary operations.
#[derive(Debug, Clone, PartialEq, Error)]
pub enum TokenError {
    /// Vocabulary lookup failed — token not found.
    #[error("token not found in vocabulary: {0}")]
    UnknownToken(String),

    /// ID lookup failed — ID out of range.
    #[error("token ID out of range: {id} (vocab size: {vocab_size})")]
    IdOutOfRange {
        /// The token ID that was out of range.
        id: u32,
        /// The configured vocabulary size.
        vocab_size: u32,
    },

    /// Varint decoding failed — invalid or truncated data.
    #[error("varint decode error: {0}")]
    VarintDecode(String),

    /// Delta decoding failed — overflow or underflow.
    #[error("delta decode overflow at position {position}: previous={previous}, delta={delta}")]
    DeltaOverflow {
        /// Index in the token sequence where the overflow occurred.
        position: usize,
        /// The previous (valid) token ID.
        previous: u32,
        /// The delta that caused the overflow.
        delta: i32,
    },

    /// Vocabulary file could not be loaded.
    #[error("failed to load vocabulary: {0}")]
    VocabularyLoad(String),

    /// Vocabulary file has invalid format.
    #[error("invalid vocabulary format: {0}")]
    VocabularyFormat(String),

    /// Compressed data is empty or too short.
    #[error("compressed data too short: expected at least {expected} bytes, got {got}")]
    CompressedDataTooShort {
        /// Minimum number of bytes expected.
        expected: usize,
        /// Actual number of bytes received.
        got: usize,
    },

    /// Vocabulary ID mismatch during decompression.
    #[error("vocabulary ID mismatch: expected {expected}, got {got}")]
    VocabularyIdMismatch {
        /// Expected vocabulary ID.
        expected: u32,
        /// Actual vocabulary ID found in data.
        got: u32,
    },

    /// Compression ratio target not met (used in verification).
    #[error("compression ratio {ratio:.2} does not meet target {target:.2}")]
    CompressionRatio {
        /// Measured compression ratio.
        ratio: f64,
        /// Target compression ratio.
        target: f64,
    },

    /// Generic string error (for internal use).
    #[error("{0}")]
    Other(String),
}

/// Verify a boolean condition, returning a [`TokenError`] on failure.
#[inline]
pub fn verify(condition: bool, error: TokenError) -> TokenResult<()> {
    if condition {
        Ok(())
    } else {
        Err(error)
    }
}
