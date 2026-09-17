//! Tokenizers: text → token-ID sequences.
//!
//! This module provides tokenization strategies for converting text into
//! sequences of token IDs.  In production, this will integrate with real
//! tokenizer libraries (tiktoken, tokenizers-rs, etc.).  For now, we provide:
//!
//! - [`SimpleTokenizer`] — whitespace split with vocabulary lookup
//! - [`simple_tokenize`] — convenience function
//! - [`Tokenizer`] trait — abstraction for plugging in real tokenizers

use crate::vocabulary::TokenVocabulary;

/// Trait for text → token-ID conversion.
///
/// Real implementations will wrap tiktoken, HuggingFace tokenizers, etc.
pub trait Tokenizer: Send + Sync {
    /// Tokenize text into token IDs.
    fn tokenize(&self, text: &str) -> Vec<u32>;

    /// Return the vocabulary ID this tokenizer uses.
    fn vocabulary_id(&self) -> u32;

    /// Return the vocabulary size.
    fn vocab_size(&self) -> u32;
}

/// Simple whitespace tokenizer that looks up each word in the vocabulary.
///
/// Words not found in the vocabulary are mapped to ID 0 (or skipped,
/// depending on configuration).  This is NOT a production tokenizer — it is
/// a lightweight implementation for testing and benchmarking.
#[derive(Debug, Clone)]
pub struct SimpleTokenizer {
    vocab: TokenVocabulary,
    unknown_id: u32,
}

impl SimpleTokenizer {
    /// Create a new simple tokenizer.
    pub fn new(vocab: TokenVocabulary, unknown_id: u32) -> Self {
        Self { vocab, unknown_id }
    }

    /// Create with the standard unknown token ID of 0.
    pub fn with_vocab(vocab: TokenVocabulary) -> Self {
        Self::new(vocab, 0)
    }

    /// Get a reference to the vocabulary.
    pub fn vocabulary(&self) -> &TokenVocabulary {
        &self.vocab
    }
}

impl Tokenizer for SimpleTokenizer {
    fn tokenize(&self, text: &str) -> Vec<u32> {
        text.split_whitespace()
            .map(|word| self.vocab.get_id(word).unwrap_or(self.unknown_id))
            .collect()
    }

    fn vocabulary_id(&self) -> u32 {
        self.vocab.vocabulary_id
    }

    fn vocab_size(&self) -> u32 {
        self.vocab.vocab_size()
    }
}

/// Tokenize text using a simple whitespace split + vocabulary lookup.
///
/// This is a convenience function for testing.  It does NOT handle
/// subword tokenization, special tokens, or any real tokenizer behavior.
///
/// # Arguments
/// * `text` — the input string
/// * `vocab` — vocabulary to look up words in
///
/// # Returns
/// A vector of token IDs. Unknown words map to ID 0.
pub fn simple_tokenize(text: &str, vocab: &TokenVocabulary) -> Vec<u32> {
    text.split_whitespace()
        .map(|word| vocab.get_id(word).unwrap_or(0))
        .collect()
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_vocab() -> TokenVocabulary {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        vocab.insert("hello", 1).unwrap();
        vocab.insert("world", 2).unwrap();
        vocab.insert("test", 3).unwrap();
        vocab.insert("tokens", 4).unwrap();
        vocab
    }

    #[test]
    fn test_simple_tokenize() {
        let vocab = make_vocab();
        let tokens = simple_tokenize("hello world", &vocab);
        assert_eq!(tokens, vec![1, 2]);
    }

    #[test]
    fn test_simple_tokenize_unknown() {
        let vocab = make_vocab();
        let tokens = simple_tokenize("hello unknown", &vocab);
        assert_eq!(tokens, vec![1, 0]);
    }

    #[test]
    fn test_simple_tokenize_empty() {
        let vocab = make_vocab();
        let tokens = simple_tokenize("", &vocab);
        assert!(tokens.is_empty());
    }

    #[test]
    fn test_simple_tokenize_multiple_spaces() {
        let vocab = make_vocab();
        let tokens = simple_tokenize("hello   world    test", &vocab);
        assert_eq!(tokens, vec![1, 2, 3]);
    }

    #[test]
    fn test_simple_tokenizer_trait() {
        let vocab = make_vocab();
        let tokenizer = SimpleTokenizer::with_vocab(vocab);
        let tokens = tokenizer.tokenize("hello world test");
        assert_eq!(tokens, vec![1, 2, 3]);
        assert_eq!(tokenizer.vocabulary_id(), 0);
        assert_eq!(tokenizer.vocab_size(), 100);
    }

    #[test]
    fn test_simple_tokenizer_unknown_mapping() {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        vocab.insert("hello", 1).unwrap();
        let tokenizer = SimpleTokenizer::new(vocab, 99);
        let tokens = tokenizer.tokenize("hello unknown");
        assert_eq!(tokens, vec![1, 99]);
    }
}
