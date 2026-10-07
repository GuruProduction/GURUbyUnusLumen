//! Tokenizers: text → token-ID sequences.
//!
//! Three strategies:
//! - [`HashTokenizer`] — PRODUCTION DEFAULT. Case-folded alphanumeric runs
//!   hashed (FNV-1a + avalanche mix) into a configurable token space.
//!   Deterministic, seed-free, unseen words carry real signal.
//! - [`SimpleTokenizer`] — whitespace split with vocabulary lookup (unknown
//!   words degrade to a single unknown id). Kept for vocab-driven flows and
//!   tests; not useful on an UNPOPULATED vocabulary.
//! - [`Tokenizer`] trait — pluggable surface (BPE, SentencePiece, tiktoken).

use crate::vocabulary::TokenVocabulary;

/// Trait for text → token-ID conversion.
///
/// Real implementations will wrap tiktoken, HuggingFace tokenizers, etc.
pub trait Tokenizer: Send + Sync {
    /// Tokenize text into token IDs.
    fn tokenize(&self, text: &str) -> Vec<u32>;

    /// Return the vocabulary ID this tokenizer uses.
    fn vocabulary_id(&self) -> u32;

    /// Return the vocabulary (or hash-space) size this tokenizer works in.
    fn vocab_size(&self) -> u32;

    /// Return `true` when the vocabulary is a registry (SimpleTokenizer),
    /// `false` for registry-free content hashing (HashTokenizer).
    fn has_registry(&self) -> bool {
        true
    }
}

// ============================================================================
// HashTokenizer — production default (deterministic content hashing)
// ============================================================================

/// Hash-based tokenizer: lowercased alphanumeric runs hashed (FNV-1a with an
/// extra avalanche mix, mirroring the embeddings engine's word-hash design)
/// into the configured hash space. Identical words always map identically
/// across runs (deterministic, seed-free); each unseen word IS its own token
/// id, so semantic signal survives arbitrary content. The default hash space
/// is 65,536: one 16-bit lane matching the DWM's default token alphabet.
///
/// Selected as the SignatureEngine production default instead of the old
/// SimpleTokenizer+empty-"default"-vocab pair (which collapsed every text to
/// all-unknown token ids and erased the semantic signal from signatures).
#[derive(Debug, Clone)]
pub struct HashTokenizer {
    /// Token-space ceiling. All token ids stay within `[0, hash_space)`.
    hash_space: u32,
}

impl HashTokenizer {
    /// Tokenizer with the full default hash space (65,536 slots).
    pub fn new() -> Self {
        Self { hash_space: 65_536 }
    }

    /// Tokenizer over a custom (smaller or larger) hash space.
    ///
    /// # Panics
    /// Asserts `hash_space > 0` (degenerate zero space carries no signal).
    pub fn with_hash_space(hash_space: u32) -> Self {
        assert!(hash_space > 0, "HashTokenizer hash_space must be > 0");
        Self { hash_space }
    }

    /// FNV-1a with a final avalanche mix; precisely the routine the
    /// embeddings engine uses for word→bucket, so signature and embedding
    /// layers share one comparable notion of word identity.
    fn word_hash(word: &str, hash_space: u32) -> u32 {
        let mut hash: u64 = 0xcbf29ce484222325;
        for byte in word.bytes() {
            hash ^= byte as u64;
            hash = hash.wrapping_mul(0x100000001b3);
        }
        hash ^= hash >> 33;
        hash = hash.wrapping_mul(0xff51afd7ed558ccd);
        hash ^= hash >> 33;
        (hash % hash_space as u64) as u32
    }
}

impl Default for HashTokenizer {
    fn default() -> Self {
        Self::new()
    }
}

impl Tokenizer for HashTokenizer {
    fn tokenize(&self, text: &str) -> Vec<u32> {
        text.to_lowercase()
            .split(|c: char| !c.is_alphanumeric())
            .filter(|word| !word.is_empty())
            .map(|word| Self::word_hash(word, self.hash_space))
            .collect()
    }

    fn vocabulary_id(&self) -> u32 {
        // Hash tokenizers carry no registry; the id versions the space and
        // 0 means the full default space.
        0
    }

    fn vocab_size(&self) -> u32 {
        self.hash_space
    }

    fn has_registry(&self) -> bool {
        false
    }
}

/// Test-side exact token-array equality helper (non-test builds drop it).
#[cfg(test)]
fn same_token_set(a: &[u32], b: &[u32]) -> bool {
    a.len() == b.len() && a.iter().zip(b.iter()).all(|(x, y)| x == y)
}

// ============================================================================
// SimpleTokenizer — vocabulary-based (registry flows + tests)
// ============================================================================

/// Simple whitespace tokenizer that looks up each word in the vocabulary.
///
/// Words missing from the vocabulary map to a fixed unknown id.
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
    /// Note: on an UNPOPULATED vocabulary (the historical engine default)
    /// every word lands at the unknown id and semantic signal is erased. For
    /// vocabulary-free production behaviour use HashTokenizer instead.
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

/// Tokenize text with whitespace split + vocabulary lookup (unknown → 0).
/// Convenience wrapper preserved for vocabulary-driven flows and tests.
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

    // ------------------------------------------------------------------
    // HashTokenizer — production default coverage
    // ------------------------------------------------------------------

    #[test]
    fn hash_deterministic_case_folded() {
        let t = HashTokenizer::new();
        let a = t.tokenize("Rust borrow checker enforces safe rules");
        let b = t.tokenize("rust borrow checker enforces safe rules");
        assert_eq!(a, b, "case-folded identical words hash identically");
        assert!(!a.is_empty());
    }

    #[test]
    fn hash_distinct_word_sets_differ() {
        let t = HashTokenizer::new();
        let tokens_a = t.tokenize("rust programming memory");
        let tokens_b = t.tokenize("cooking tomato pasta basil");
        assert!(
            !same_token_set(&tokens_a, &tokens_b),
            "distinct word sets produce distinct token vectors"
        );
    }

    #[test]
    fn hash_shared_words_map_identically() {
        let t = HashTokenizer::new();
        let single = t.tokenize("memory");
        let tokens_a = t.tokenize("memory safety rules");
        let tokens_b = t.tokenize("memory allocation rules list");
        assert!(tokens_a.contains(&single[0]), "'memory' present in set A");
        assert!(tokens_b.contains(&single[0]), "'memory' present in set B");
    }

    #[test]
    fn hash_punctuation_split_matches_plain() {
        let t = HashTokenizer::with_hash_space(50_000);
        let a = t.tokenize("hello, world!");
        let b = t.tokenize("hello world");
        assert_eq!(
            a, b,
            "punctuation strips to the same word ids"
        );
    }

    #[test]
    fn hash_ids_stay_within_hash_space() {
        let t = HashTokenizer::with_hash_space(1024);
        for token in t.tokenize("some words hash beyond one byte boundary") {
            assert!(token < 1024, "token {} must stay within the space", token);
        }
    }

    #[test]
    fn hash_trait_shape_and_registry_flag() {
        let t = HashTokenizer::new();
        assert_eq!(t.vocabulary_id(), 0);
        assert_eq!(t.vocab_size(), 65_536);
        assert!(!t.has_registry(), "hash tokenizer is registry-free");
    }

    #[test]
    fn hash_empty_text_is_empty() {
        let t = HashTokenizer::new();
        assert!(t.tokenize("").is_empty());
        let punct_only = t.tokenize("!!! ... ---");
        assert!(punct_only.is_empty(), "punctuation-only text has no words");
    }

    #[test]
    #[should_panic(expected = "hash_space must be > 0")]
    fn hash_zero_space_panics() {
        HashTokenizer::with_hash_space(0);
    }

    // ------------------------------------------------------------------
    // SimpleTokenizer — existing coverage, intact
    // ------------------------------------------------------------------

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
        assert!(tokenizer.has_registry(), "registry tokenizer keeps the flag on");
    }
}