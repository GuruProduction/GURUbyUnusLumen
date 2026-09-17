//! Text reconstruction from token IDs (detokenization).
//!
//! Maps token ID sequences back to human-readable text via vocabulary lookup.
//! This is the inverse of tokenization: tokenize → compress → store →
//! decompress → detokenize.
//!
//! # Reconstruction guarantees
//! - Every token ID maps to exactly one token string (via [`TokenVocabulary::get_token`])
//! - Tokens are concatenated with a configurable separator (default: `""`)
//! - If a token ID is not in the vocabulary, the literal `"<unk_ID>"` is used

use crate::error::TokenResult;
use crate::vocabulary::TokenVocabulary;

/// Reconstruct a token-ID sequence back to a text string.
///
/// Each ID is looked up in the vocabulary. Missing IDs produce `"<unk_ID>"`
/// placeholders. Tokens are concatenated with the given separator (default `""`).
///
/// # Examples
/// ```
/// use cerebrum_token::vocabulary::TokenVocabulary;
/// use cerebrum_token::reconstruct::detokenize;
///
/// let mut vocab = TokenVocabulary::new("test", 0, 10);
/// vocab.insert("Hello", 0).unwrap();
/// vocab.insert(" ", 1).unwrap();
/// vocab.insert("world", 2).unwrap();
/// vocab.insert("!", 3).unwrap();
///
/// let tokens = vec![0u32, 1, 2, 3];
/// let text = detokenize(&vocab, &tokens, "").unwrap();
/// assert_eq!(text, "Hello world!");
/// ```
pub fn detokenize(
    vocab: &TokenVocabulary,
    tokens: &[u32],
    separator: &str,
) -> TokenResult<String> {
    let mut parts = Vec::with_capacity(tokens.len());

    for &id in tokens {
        match vocab.get_token(id) {
            Some(token) => parts.push(token.to_string()),
            None => parts.push(format!("<unk_{}>", id)),
        }
    }

    Ok(parts.join(separator))
}

/// Convenience: reconstruct a compressed token sequence back to text.
///
/// 1. Decode the varint+delta bytes to raw token IDs
/// 2. Map each ID to its token string via the vocabulary
/// 3. Join with the given separator
///
/// # Errors
/// Returns [`TokenError::VarintDecode`] or [`TokenError::DeltaOverflow`] if
/// the compressed data is invalid.
pub fn reconstruct_sequence(
    vocab: &TokenVocabulary,
    compressed_data: &[u8],
    separator: &str,
) -> TokenResult<String> {
    let tokens = crate::compress::decode_deltas(compressed_data)?;
    detokenize(vocab, &tokens, separator)
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_test_vocab() -> TokenVocabulary {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        vocab.insert("Hello", 0).unwrap();
        vocab.insert(" ", 1).unwrap();
        vocab.insert("world", 2).unwrap();
        vocab.insert("!", 3).unwrap();
        vocab
    }

    #[test]
    fn test_detokenize_basic() {
        let vocab = make_test_vocab();
        let tokens = vec![0u32, 1, 2, 3];
        let text = detokenize(&vocab, &tokens, "").unwrap();
        assert_eq!(text, "Hello world!");
    }

    #[test]
    fn test_detokenize_with_separator() {
        let vocab = make_test_vocab();
        let tokens = vec![0u32, 2, 3];
        let text = detokenize(&vocab, &tokens, " ").unwrap();
        assert_eq!(text, "Hello world !");
    }

    #[test]
    fn test_detokenize_unknown_token() {
        let vocab = make_test_vocab();
        let tokens = vec![0u32, 99, 2]; // 99 not in vocab
        let text = detokenize(&vocab, &tokens, "").unwrap();
        assert_eq!(text, "Hello<unk_99>world");
    }

    #[test]
    fn test_detokenize_empty() {
        let vocab = make_test_vocab();
        let text = detokenize(&vocab, &[], "").unwrap();
        assert_eq!(text, "");
    }

    #[test]
    fn test_reconstruct_sequence() {
        let vocab = make_test_vocab();
        let tokens = vec![0u32, 1, 2, 3];
        let compressed = crate::compress::encode_deltas(&tokens);
        let text = reconstruct_sequence(&vocab, &compressed, "").unwrap();
        assert_eq!(text, "Hello world!");
    }

    #[test]
    fn test_reconstruct_sequence_large() {
        let vocab = TokenVocabulary::deterministic("det", 0, 1000);
        let tokens: Vec<u32> = (0..500).collect();
        let compressed = crate::compress::encode_deltas(&tokens);
        let text = reconstruct_sequence(&vocab, &compressed, "").unwrap();
        // Each token is "t0", "t1", etc. concatenated
        assert!(text.starts_with("t0"));
        assert!(text.contains("t499"));
    }

    #[test]
    fn test_detokenize_roundtrip() {
        // Tokenize → compress → decompress → detokenize should match if
        // the tokenizer produces the same tokens.  This tests the full pipeline
        // with the simple tokenizer.
        use crate::tokenizer::simple_tokenize;
        use crate::vocabulary::TokenVocabulary;

        let vocab = TokenVocabulary::deterministic("det", 0, 1000);
        let text = "hello world test tokens";
        let tokens = simple_tokenize(text, &vocab);

        let compressed = crate::compress::encode_deltas(&tokens);
        let reconstructed = crate::compress::decode_deltas(&compressed).unwrap();
        assert_eq!(reconstructed, tokens);

        let _detokenized = detokenize(&vocab, &reconstructed, " ").unwrap();
        // The simple tokenizer splits on whitespace and maps words to IDs.
        // Since our test vocab is "t0", "t1", etc., the words won't map.
        // This test demonstrates the API; a real test would use a proper vocab.
        // Instead, test with tokens we know exist:
        let known_tokens = vec![10u32, 20, 30, 40];
        let known_text = detokenize(&vocab, &known_tokens, " ").unwrap();
        assert_eq!(known_text, "t10 t20 t30 t40");
    }
}
