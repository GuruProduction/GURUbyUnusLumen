//! Delta + varint compression for token-ID sequences.
//!
//! Token sequences from real text have strong locality — adjacent token IDs
//! are typically close together (e.g., common words cluster in the same
//! vocabulary region). Delta encoding turns these small absolute differences
//! into even smaller signed values, which varint compresses to 1–2 bytes each.
//!
//! # Algorithm
//!
//! 1. **Delta encoding**: given `[t₀, t₁, t₂, ...]`, output `[t₀, t₁−t₀, t₂−t₁, ...]`
//! 2. **Zigzag**: convert signed deltas to unsigned (so negative values stay small)
//! 3. **Varint**: encode each as LEB128
//!
//! # Example
//! ```
//! use cerebrum_token::compress::{encode_deltas, decode_deltas};
//!
//! let tokens = vec![100u32, 102, 101, 105, 104];
//! let deltas = encode_deltas(&tokens);
//! // deltas = [100, +2, -1, +4, -1] zigzag → [100, 4, 1, 8, 1]
//! let recovered = decode_deltas(&deltas).unwrap();
//! assert_eq!(recovered, tokens);
//! ```

use serde::{Deserialize, Serialize};

use crate::error::{TokenError, TokenResult};
use crate::varint;

// ============================================================================
// CompressedTokenSequence
// ============================================================================

/// A compressed token-ID sequence ready for storage or transmission.
///
/// The `data` field holds delta+zigzag+varint encoded bytes.  The
/// `original_len` records how many tokens were present before compression
/// (useful for memory budgeting and validation).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CompressedTokenSequence {
    /// Identifies which vocabulary was used for tokenization.
    pub vocabulary_id: u32,
    /// Delta + varint encoded token data.
    pub data: Vec<u8>,
    /// Number of tokens before compression.
    pub original_len: usize,
}

impl CompressedTokenSequence {
    /// Create a new compressed sequence.
    pub fn new(vocabulary_id: u32, data: Vec<u8>, original_len: usize) -> Self {
        Self {
            vocabulary_id,
            data,
            original_len,
        }
    }

    /// Return the compression ratio: raw bytes (4 bytes per u32) / compressed bytes.
    ///
    /// Returns `None` if the compressed data is empty (should not happen for
    /// valid sequences, but the API handles it defensively).
    pub fn compression_ratio(&self) -> Option<f64> {
        if self.data.is_empty() {
            None
        } else {
            let raw = self.original_len * 4;
            Some(raw as f64 / self.data.len() as f64)
        }
    }

    /// Total bytes saved compared to raw 4-byte-per-token storage.
    pub fn bytes_saved(&self) -> usize {
        let raw = self.original_len * 4;
        raw.saturating_sub(self.data.len())
    }

    /// Verify that decompression produces the expected token count.
    pub fn verify(&self, expected_tokens: &[u32]) -> TokenResult<()> {
        let decoded = decode_deltas(&self.data)?;
        if decoded != expected_tokens {
            return Err(TokenError::Other(
                "decompressed data does not match expected tokens".into(),
            ));
        }
        Ok(())
    }
}

// ============================================================================
// Delta encoding
// ============================================================================

/// Encode a token-ID sequence as deltas → zigzag → varint.
///
/// Format: [token_count, first_token, delta1, delta2, ...]
/// where each value is varint-encoded for compactness.
///
/// The token count prefix allows the decoder to validate that the
/// expected number of tokens was decoded, detecting corruption.
///
/// # Examples
/// ```
/// use cerebrum_token::compress::encode_deltas;
///
/// let tokens = vec![1000u32, 1001, 999, 1002];
/// let deltas = encode_deltas(&tokens);
/// ```
pub fn encode_deltas(tokens: &[u32]) -> Vec<u8> {
    if tokens.is_empty() {
        return Vec::new();
    }

    let mut result = Vec::with_capacity(tokens.len() * 2);

    // Prefix: number of tokens for validation
    result.extend_from_slice(&varint::encode_varint(tokens.len() as u32));

    // First token: stored as-is (unsigned varint)
    result.extend_from_slice(&varint::encode_varint(tokens[0]));

    // Subsequent tokens: delta + zigzag + varint
    let mut prev = tokens[0] as i64;
    for &token in &tokens[1..] {
        let current = token as i64;
        let delta = current - prev;
        // Use i64 for zigzag encoding to handle full u32 range
        let zigzagged = varint::zigzag_encode_i64(delta);
        result.extend_from_slice(&varint::encode_varint_u64(zigzagged));
        prev = current;
    }

    result
}

/// Decode a delta+varint encoded byte stream back to the original token IDs.
///
/// Validates that the decoded token count matches the encoded count to detect corruption.
///
/// # Errors
/// Returns [`TokenError::VarintDecode`] if the byte stream contains invalid
/// varints, [`TokenError::DeltaOverflow`] if reconstructing the absolute
/// token ID would overflow `u32`, or [`TokenError::Other`] if the token count
/// doesn't match (indicating corruption).
///
/// # Examples
/// ```
/// use cerebrum_token::compress::{encode_deltas, decode_deltas};
///
/// let tokens = vec![42u32, 43, 41, 44];
/// let deltas = encode_deltas(&tokens);
/// let recovered = decode_deltas(&deltas).unwrap();
/// assert_eq!(recovered, tokens);
/// ```
pub fn decode_deltas(data: &[u8]) -> TokenResult<Vec<u32>> {
    if data.is_empty() {
        return Ok(Vec::new());
    }

    let mut tokens = Vec::new();
    let mut pos: usize = 0;

    // Decode expected token count
    let (expected_count, consumed) = varint::decode_varint(&data[pos..])?;
    pos += consumed;

    if expected_count == 0 {
        return Ok(Vec::new());
    }

    // Decode first token (raw value)
    let (first, consumed) = varint::decode_varint(&data[pos..])?;
    tokens.push(first);
    pos += consumed;

    // Decode deltas
    let mut prev = first as i64;
    while pos < data.len() {
        let (zigzagged, consumed) = varint::decode_varint_u64(&data[pos..])?;
        pos += consumed;

        let delta = varint::zigzag_decode_i64(zigzagged);
        let next = prev + delta;

        if next < 0 || next > u32::MAX as i64 {
            return Err(TokenError::DeltaOverflow {
                position: tokens.len(),
                previous: prev as u32,
                delta: delta as i32,
            });
        }

        let next_u32 = next as u32;
        tokens.push(next_u32);
        prev = next;
    }

    // Validate token count to detect corruption
    if tokens.len() != expected_count as usize {
        return Err(TokenError::Other(format!(
            "token count mismatch: expected {}, got {}",
            expected_count, tokens.len()
        )));
    }

    Ok(tokens)
}

// ============================================================================
// TokenCompressor (convenience API)
// ============================================================================

/// High-level compressor / decompressor for token sequences.
///
/// This struct wraps the low-level functions with a clean API and optional
/// metadata (vocabulary tracking).
#[derive(Debug, Clone, Default)]
pub struct TokenCompressor;

impl TokenCompressor {
    /// Create a new compressor.
    pub fn new() -> Self {
        Self
    }

    /// Compress a token sequence with the given vocabulary ID.
    pub fn compress(&self, vocabulary_id: u32, tokens: &[u32]) -> CompressedTokenSequence {
        let data = encode_deltas(tokens);
        CompressedTokenSequence {
            vocabulary_id,
            data,
            original_len: tokens.len(),
        }
    }

    /// Decompress a [`CompressedTokenSequence`] back to token IDs.
    pub fn decompress(&self,
        compressed: &CompressedTokenSequence,
    ) -> TokenResult<Vec<u32>> {
        decode_deltas(&compressed.data)
    }

    /// Roundtrip compress then decompress, verifying exact match.
    ///
    /// Returns the compressed sequence on success, or a token error
    /// if the roundtrip does not produce identical tokens.
    pub fn roundtrip(&self,
        vocabulary_id: u32,
        tokens: &[u32],
    ) -> TokenResult<CompressedTokenSequence> {
        let compressed = self.compress(vocabulary_id, tokens);
        let decompressed = self.decompress(&compressed)?;
        if decompressed != tokens {
            return Err(TokenError::Other(
                "roundtrip mismatch: decompressed tokens differ from original".into(),
            ));
        }
        Ok(compressed)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encode_decode_empty() {
        let tokens: Vec<u32> = vec![];
        let encoded = encode_deltas(&tokens);
        assert!(encoded.is_empty());
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_single() {
        let tokens = vec![42u32];
        let encoded = encode_deltas(&tokens);
        // count prefix (1 byte) + first token (1 byte) = 2 bytes
        assert_eq!(encoded.len(), 2);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_small_deltas() {
        let tokens = vec![1000u32, 1001, 999, 1002, 1000];
        let encoded = encode_deltas(&tokens);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_large_gaps() {
        let tokens = vec![0u32, 100000, 0, 50000, 99999];
        let encoded = encode_deltas(&tokens);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_u32_max() {
        let tokens = vec![0u32, u32::MAX, 0, u32::MAX];
        let encoded = encode_deltas(&tokens);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_repeated() {
        let tokens = vec![42u32; 1000];
        let encoded = encode_deltas(&tokens);
        // Count prefix (2 bytes for 1000) + first token (1 byte) + 999 zero deltas (1 byte each)
        // = 2 + 1 + 999 = 1002 bytes
        assert_eq!(encoded.len(), 1002);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_monotonic_increase() {
        let tokens: Vec<u32> = (0..1000).collect();
        let encoded = encode_deltas(&tokens);
        // Count prefix (2 bytes for 1000) + first token 0 (1 byte) + 999 deltas of +1 (zigzag=2, 1 byte each)
        // = 2 + 1 + 999 = 1002 bytes
        assert_eq!(encoded.len(), 1002);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_encode_decode_monotonic_decrease() {
        let tokens: Vec<u32> = (0..1000).rev().collect();
        let encoded = encode_deltas(&tokens);
        // Count prefix (2 bytes for 1000) + first token 999 (2 bytes) + 999 deltas of -1 (zigzag=1, 1 byte each)
        // = 2 + 2 + 999 = 1003 bytes
        assert_eq!(encoded.len(), 1003);
        let decoded = decode_deltas(&encoded).unwrap();
        assert_eq!(decoded, tokens);
    }

    #[test]
    fn test_compression_ratio() {
        let tokens = vec![42u32; 1000];
        let compressed = TokenCompressor::new().compress(0, &tokens);
        assert_eq!(compressed.original_len, 1000);
        let ratio = compressed.compression_ratio().unwrap();
        // Raw: 4000 bytes, compressed includes count prefix so ~1002 bytes → ratio ≈ 3.99
        // The exact ratio depends on the count prefix encoding
        assert!(ratio > 3.9, "compression ratio too low: {}", ratio);
        assert!(ratio <= 4.0, "compression ratio too high: {}", ratio);
        assert!(compressed.bytes_saved() > 2990, "bytes saved too low: {}", compressed.bytes_saved());
    }

    #[test]
    fn test_compression_ratio_empty() {
        let compressed = TokenCompressor::new().compress(0, &[]);
        assert_eq!(compressed.compression_ratio(), None);
        assert_eq!(compressed.bytes_saved(), 0);
    }

    #[test]
    fn test_roundtrip_ok() {
        let compressor = TokenCompressor::new();
        let tokens = vec![100u32, 102, 101, 105, 104];
        let compressed = compressor.roundtrip(1, &tokens).unwrap();
        assert_eq!(compressed.vocabulary_id, 1);
    }

    #[test]
    fn test_compressed_token_sequence_verify() {
        let tokens = vec![1u32, 2, 3, 4, 5];
        let compressed = TokenCompressor::new().compress(0, &tokens);
        assert!(compressed.verify(&tokens).is_ok());
    }

    #[test]
    fn test_compressed_token_sequence_verify_fail() {
        let tokens = vec![1u32, 2, 3, 4, 5];
        let mut compressed = TokenCompressor::new().compress(0, &tokens);
        compressed.data[0] ^= 0xFF; // corrupt data
        assert!(compressed.verify(&tokens).is_err());
    }

    #[test]
    fn test_decode_truncated() {
        // Truncated after first token
        let data = vec![0x01, 0x80]; // first token=1, then incomplete varint
        assert!(decode_deltas(&data).is_err());
    }

    #[test]
    fn test_decode_corrupted() {
        let tokens = vec![100u32, 200, 300];
        let mut encoded = encode_deltas(&tokens);
        encoded[2] = 0xFF; // corrupt a delta
        assert!(decode_deltas(&encoded).is_err());
    }

    #[test]
    fn test_random_sequences() {
        let compressor = TokenCompressor::new();
        // deterministic "random" sequences
        for seed in 0..100 {
            let tokens: Vec<u32> = (0..seed + 1)
                .map(|i| ((i * 7919 + seed * 104729) % 50000) as u32)
                .collect();
            let compressed = compressor.roundtrip(0, &tokens).unwrap();
            assert_eq!(compressed.original_len, tokens.len());
        }
    }

    #[test]
    fn test_large_sequence() {
        let compressor = TokenCompressor::new();
        let tokens: Vec<u32> = (0..100_000).map(|i| (i * 17 % 50_000) as u32).collect();
        let compressed = compressor.roundtrip(0, &tokens).unwrap();
        assert_eq!(compressed.original_len, 100_000);
        // Should achieve decent compression due to small deltas
        let ratio = compressed.compression_ratio().unwrap();
        assert!(ratio > 1.5, "compression ratio too low: {}", ratio);
    }
}
