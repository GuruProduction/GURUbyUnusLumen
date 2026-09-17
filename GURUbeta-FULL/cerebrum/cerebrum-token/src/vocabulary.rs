//! Vocabulary management: load, store, and query token ↔ ID mappings.
//!
//! Supports standard tokenizer formats:
//! - JSON vocabulary files (token → string representation of ID)
//! - Simple in-memory vocabulary construction
//! - Configurable vocabulary sizes (32K–128K)

use std::collections::HashMap;
use std::fs;
use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::error::{TokenError, TokenResult};

// ============================================================================
// TokenVocabulary
// ============================================================================

/// A bidirectional token ↔ ID vocabulary.
///
/// Stores `token_to_id` for fast text → ID lookups (tokenization) and
/// `id_to_token` for fast ID → text lookups (detokenization / reconstruction).
///
/// Vocabulary sizes range from 32K to 128K tokens, with each ID fitting in
/// 15–17 bits.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TokenVocabulary {
    token_to_id: HashMap<String, u32>,
    id_to_token: Vec<String>,
    vocab_size: u32,
    /// Human-readable name for this vocabulary (e.g., "gpt2", "llama3").
    pub name: String,
    /// Unique identifier for this vocabulary version.
    pub vocabulary_id: u32,
}

impl TokenVocabulary {
    /// Create a new empty vocabulary.
    pub fn new(name: impl Into<String>, vocabulary_id: u32, vocab_size: u32) -> Self {
        Self {
            token_to_id: HashMap::with_capacity(vocab_size as usize),
            id_to_token: Vec::with_capacity(vocab_size as usize),
            vocab_size,
            name: name.into(),
            vocabulary_id,
        }
    }

    /// Insert a token → ID mapping.
    ///
    /// # Errors
    /// Returns [`TokenError::IdOutOfRange`] if `id >= vocab_size`.
    pub fn insert(&mut self,
        token: impl Into<String>,
        id: u32,
    ) -> TokenResult<()> {
        let token = token.into();
        if id >= self.vocab_size {
            return Err(TokenError::IdOutOfRange {
                id,
                vocab_size: self.vocab_size,
            });
        }

        // If this token already exists with a different ID, remove the old ID mapping
        if let Some(&old_id) = self.token_to_id.get(&token) {
            if old_id != id {
                let old_idx = old_id as usize;
                if old_idx < self.id_to_token.len() {
                    self.id_to_token[old_idx].clear();
                }
            }
        }

        // Ensure id_to_token has capacity
        let idx = id as usize;
        if idx >= self.id_to_token.len() {
            self.id_to_token.resize(idx + 1, String::new());
        }

        self.id_to_token[idx] = token.clone();
        self.token_to_id.insert(token, id);
        Ok(())
    }

    /// Look up a token by ID.
    ///
    /// Returns `None` if the ID has never been inserted.
    pub fn get_token(&self, id: u32) -> Option<&str> {
        let idx = id as usize;
        if idx < self.id_to_token.len() && !self.id_to_token[idx].is_empty() {
            Some(&self.id_to_token[idx])
        } else {
            None
        }
    }

    /// Look up an ID by token string.
    pub fn get_id(&self, token: &str) -> Option<u32> {
        self.token_to_id.get(token).copied()
    }

    /// Number of entries in the vocabulary (may be less than vocab_size if
    /// the vocabulary is not fully populated).
    pub fn len(&self) -> usize {
        self.token_to_id.len()
    }

    /// Whether the vocabulary has no entries.
    pub fn is_empty(&self) -> bool {
        self.token_to_id.is_empty()
    }

    /// The configured maximum vocabulary size.
    pub fn vocab_size(&self) -> u32 {
        self.vocab_size
    }

    /// Check if the given ID is valid (within range, regardless of whether
    /// an entry exists).
    pub fn is_valid_id(&self, id: u32) -> bool {
        id < self.vocab_size
    }

    /// Get the ID mapping as a reference (for inspection / iteration).
    pub fn token_to_id_map(&self) -> &HashMap<String, u32> {
        &self.token_to_id
    }

    /// Get the token lookup as a reference.
    pub fn id_to_token_vec(&self) -> &[String] {
        &self.id_to_token
    }

    /// Build a deterministic in-memory vocabulary for testing.
    ///
    /// The vocabulary contains `count` tokens named `"t0"`, `"t1"`, ..., with
    /// IDs 0, 1, 2, ...  This is useful for unit tests and benchmarks.
    pub fn deterministic(name: impl Into<String>, vocabulary_id: u32, count: u32) -> Self {
        let mut vocab = Self::new(name, vocabulary_id, count);
        for i in 0..count {
            let token = format!("t{}", i);
            vocab.insert(token, i).unwrap();
        }
        vocab
    }
}

impl Default for TokenVocabulary {
    fn default() -> Self {
        Self::new("default", 0, 32_768)
    }
}

// ============================================================================
// VocabularyLoader trait + implementations
// ============================================================================

/// Trait for loading vocabulary from external sources.
pub trait VocabularyLoader {
    /// Load a vocabulary from a file or other source.
    fn load(&self, path: &Path, name: &str, vocabulary_id: u32) -> TokenResult<TokenVocabulary>;
}

/// Load a vocabulary from a JSON file.
///
/// Expected JSON format:
/// ```json
/// {
///   "vocab_size": 50000,
///   "tokens": {
///     "hello": 15496,
///     " world": 995,
///     ...
///   }
/// }
/// ```
#[derive(Debug, Clone, Default)]
pub struct JsonVocabularyLoader;

impl VocabularyLoader for JsonVocabularyLoader {
    fn load(
        &self,
        path: &Path,
        name: &str,
        vocabulary_id: u32,
    ) -> TokenResult<TokenVocabulary> {
        let content =
            fs::read_to_string(path).map_err(|e| {
                TokenError::VocabularyLoad(format!("{}: {}", path.display(), e))
            })?;

        let json: serde_json::Value =
            serde_json::from_str(&content).map_err(|e| {
                TokenError::VocabularyFormat(format!("JSON parse error: {}", e))
            })?;

        let vocab_size = json["vocab_size"]
            .as_u64()
            .ok_or_else(|| {
                TokenError::VocabularyFormat("missing or invalid 'vocab_size'".into())
            })? as u32;

        let mut vocab = TokenVocabulary::new(name, vocabulary_id, vocab_size);

        let tokens = json["tokens"].as_object().ok_or_else(|| {
            TokenError::VocabularyFormat("missing or invalid 'tokens' object".into())
        })?;

        for (token_str, id_val) in tokens {
            let id = id_val.as_u64().ok_or_else(|| {
                TokenError::VocabularyFormat(format!(
                    "token '{}' has non-integer ID",
                    token_str
                ))
            })? as u32;

            vocab.insert(token_str.clone(), id)?;
        }

        Ok(vocab)
    }
}

/// Load a vocabulary from a plain text file with one token per line.
///
/// Line number (0-indexed) becomes the token ID.
#[derive(Debug, Clone, Default)]
pub struct TextVocabularyLoader;

impl VocabularyLoader for TextVocabularyLoader {
    fn load(
        &self,
        path: &Path,
        name: &str,
        vocabulary_id: u32,
    ) -> TokenResult<TokenVocabulary> {
        let content =
            fs::read_to_string(path).map_err(|e| {
                TokenError::VocabularyLoad(format!("{}: {}", path.display(), e))
            })?;

        let lines: Vec<&str> = content.lines().collect();
        let vocab_size = lines.len() as u32;
        let mut vocab = TokenVocabulary::new(name, vocabulary_id, vocab_size);

        for (id, token) in lines.iter().enumerate() {
            let token = token.trim();
            if !token.is_empty() {
                vocab.insert(token.to_string(), id as u32)?;
            }
        }

        Ok(vocab)
    }
}

// ============================================================================
// Convenience functions
// ============================================================================

/// Load a vocabulary from a JSON file.
pub fn load_json_vocabulary(
    path: &Path,
    name: &str,
    vocabulary_id: u32,
) -> TokenResult<TokenVocabulary> {
    JsonVocabularyLoader.load(path, name, vocabulary_id)
}

/// Load a vocabulary from a text file.
pub fn load_text_vocabulary(
    path: &Path,
    name: &str,
    vocabulary_id: u32,
) -> TokenResult<TokenVocabulary> {
    TextVocabularyLoader.load(path, name, vocabulary_id)
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vocabulary_insert_and_lookup() {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        vocab.insert("hello", 10).unwrap();
        vocab.insert("world", 20).unwrap();

        assert_eq!(vocab.get_id("hello"), Some(10));
        assert_eq!(vocab.get_id("world"), Some(20));
        assert_eq!(vocab.get_id("missing"), None);

        assert_eq!(vocab.get_token(10), Some("hello"));
        assert_eq!(vocab.get_token(20), Some("world"));
        assert_eq!(vocab.get_token(99), None);
    }

    #[test]
    fn test_vocabulary_id_out_of_range() {
        let mut vocab = TokenVocabulary::new("test", 0, 10);
        assert!(vocab.insert("too_big", 10).is_err());
        assert!(vocab.insert("too_big", 100).is_err());
    }

    #[test]
    fn test_vocabulary_update_existing() {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        vocab.insert("token", 5).unwrap();
        vocab.insert("token", 7).unwrap(); // overwrite
        assert_eq!(vocab.get_id("token"), Some(7));
        assert_eq!(vocab.get_token(7), Some("token"));
        assert_eq!(vocab.get_token(5), None); // old ID no longer mapped
    }

    #[test]
    fn test_vocabulary_len_and_is_empty() {
        let mut vocab = TokenVocabulary::new("test", 0, 100);
        assert!(vocab.is_empty());
        assert_eq!(vocab.len(), 0);

        vocab.insert("a", 0).unwrap();
        assert!(!vocab.is_empty());
        assert_eq!(vocab.len(), 1);
    }

    #[test]
    fn test_vocabulary_vocab_size() {
        let vocab = TokenVocabulary::new("test", 0, 50_000);
        assert_eq!(vocab.vocab_size(), 50_000);
    }

    #[test]
    fn test_vocabulary_is_valid_id() {
        let vocab = TokenVocabulary::new("test", 0, 100);
        assert!(vocab.is_valid_id(0));
        assert!(vocab.is_valid_id(99));
        assert!(!vocab.is_valid_id(100));
    }

    #[test]
    fn test_vocabulary_deterministic() {
        let vocab = TokenVocabulary::deterministic("det", 1, 100);
        assert_eq!(vocab.len(), 100);
        assert_eq!(vocab.vocab_size(), 100);
        assert_eq!(vocab.get_id("t0"), Some(0));
        assert_eq!(vocab.get_id("t99"), Some(99));
        assert_eq!(vocab.get_token(50), Some("t50"));
    }

    #[test]
    fn test_vocabulary_default() {
        let vocab = TokenVocabulary::default();
        assert_eq!(vocab.vocab_size(), 32_768);
        assert_eq!(vocab.vocabulary_id, 0);
        assert!(vocab.is_empty());
    }

    #[test]
    fn test_vocabulary_serialization() {
        let mut vocab = TokenVocabulary::new("ser_test", 7, 1000);
        vocab.insert("hello", 1).unwrap();
        vocab.insert("world", 2).unwrap();

        let json = serde_json::to_string(&vocab).unwrap();
        let decoded: TokenVocabulary = serde_json::from_str(&json).unwrap();
        assert_eq!(vocab, decoded);
    }

    #[test]
    fn test_load_json_vocabulary() {
        let json = r#"{
            "vocab_size": 5,
            "tokens": {
                "hello": 0,
                "world": 1,
                "foo": 2,
                "bar": 3,
                "baz": 4
            }
        }"#;

        let tmpdir = std::env::temp_dir();
        let path = tmpdir.join("test_vocab.json");
        fs::write(&path, json).unwrap();

        let vocab = load_json_vocabulary(&path, "test", 0).unwrap();
        assert_eq!(vocab.vocab_size(), 5);
        assert_eq!(vocab.get_id("hello"), Some(0));
        assert_eq!(vocab.get_id("baz"), Some(4));
        assert_eq!(vocab.get_token(2), Some("foo"));

        fs::remove_file(&path).unwrap();
    }

    #[test]
    fn test_load_text_vocabulary() {
        let text = "hello\nworld\nfoo\nbar\nbaz\n";

        let tmpdir = std::env::temp_dir();
        let path = tmpdir.join("test_vocab.txt");
        fs::write(&path, text).unwrap();

        let vocab = load_text_vocabulary(&path, "test", 0).unwrap();
        assert_eq!(vocab.vocab_size(), 5);
        assert_eq!(vocab.get_id("hello"), Some(0));
        assert_eq!(vocab.get_id("baz"), Some(4));
        assert_eq!(vocab.get_token(2), Some("foo"));

        fs::remove_file(&path).unwrap();
    }

    #[test]
    fn test_load_json_vocabulary_invalid() {
        let json = r#"{"invalid": "data"}"#;
        let tmpdir = std::env::temp_dir();
        let path = tmpdir.join("test_bad_vocab.json");
        fs::write(&path, json).unwrap();

        assert!(load_json_vocabulary(&path, "test", 0).is_err());
        fs::remove_file(&path).unwrap();
    }

    #[test]
    fn test_vocabulary_large() {
        let count = 100_000u32;
        let vocab = TokenVocabulary::deterministic("large", 0, count);
        assert_eq!(vocab.len(), count as usize);
        assert_eq!(vocab.get_id("t0"), Some(0));
        assert_eq!(vocab.get_id(&format!("t{}", count - 1)), Some(count - 1));
        assert_eq!(vocab.get_token(count - 1), Some(format!("t{}", count - 1).as_str()));
    }
}
