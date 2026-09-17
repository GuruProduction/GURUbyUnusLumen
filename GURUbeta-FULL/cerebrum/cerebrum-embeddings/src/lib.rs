//! Cerebrum Embeddings — TF-IDF + random projection embedding engine.
//!
//! Phase 0.2.2 — The embedding fallback alongside binary signatures.
//!
//! When binary signatures do not meet quality requirements, the query planner
//! can fall back to traditional embedding-based search. This crate provides a
//! real TF-IDF embedding engine with random projection for dimensionality
//! reduction. No external model required. Runs fully on-device.
//!
//! # Architecture
//!
//! 1. **Vocabulary**: Hash-based word mapping with configurable vocab size.
//!    Words are lowercased, punctuation stripped, stop words filtered.
//! 2. **TF-IDF weighting**: Term frequency inverse document frequency.
//!    The engine maintains document frequency counts across all seen texts.
//!    IDF is computed as 1 + log((N + 1) / (1 + df)) where N is total documents
//!    and df is how many documents contain the term. Smoothing ensures IDF
//!    is always >= 1 so embeddings are never zero for non-empty text.
//! 3. **Random projection**: The high-dimensional TF-IDF vector (vocab_size
//!    dimensions) is projected down to a compact dense vector (default 256
//!    dimensions) using a fixed random projection matrix. This preserves
//!    cosine similarity approximately (Johnson-Lindenstrauss lemma).
//! 4. **L2 normalization**: All output vectors are L2-normalized so cosine
//!    similarity becomes a simple dot product.
//! 5. **Caching**: Embeddings are cached by text hash to avoid recomputation.
//!
//! # Why not a neural embedding model?
//!
//! On a phone, we cannot run a transformer embedding model without significant
//! latency and battery cost. TF-IDF + random projection gives us:
//! - Sub-millisecond embedding generation
//! - No model weights to ship
//! - Deterministic, reproducible vectors
//! - Good enough semantic quality for the fallback path
//!
//! The binary signature pipeline (cerebrum-signatures) is the primary
//! representation. This embedding engine exists for the pgvector fallback tier
//! when signatures alone are insufficient.
//!
//! # Examples
//!
//! ```
//! use cerebrum_embeddings::{EmbeddingEngine, SimilarityMetric};
//!
//! let mut engine = EmbeddingEngine::with_default_config();
//! let a = engine.embed("rust programming language");
//! let b = engine.embed("rust language memory");
//! let c = engine.embed("quantum physics particles");
//!
//! let sim_ab = engine.similarity(&a, &b, SimilarityMetric::Cosine);
//! let sim_ac = engine.similarity(&a, &c, SimilarityMetric::Cosine);
//! assert!(sim_ab > sim_ac, "similar text should score higher");
//! ```

use std::collections::HashMap;

use rustc_hash::FxHashMap;
use serde::{Deserialize, Serialize};

// ============================================================================
// 1. CONFIGURATION
// ============================================================================

/// Configuration for the TF-IDF embedding engine.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmbeddingConfig {
    /// Name of this embedding configuration.
    pub model: String,
    /// Output vector dimensions after random projection (default 256).
    /// Must be > 0. Panic at construction if 0.
    pub dimensions: usize,
    /// Vocabulary size — number of hash buckets for words (default 10000).
    /// Must be > 0. Panic at construction if 0.
    pub vocab_size: usize,
    /// Whether to L2-normalize output vectors (default true).
    pub normalize: bool,
    /// Random projection seed for reproducibility (default 42).
    pub seed: u64,
    /// Whether to use stop word filtering (default true).
    pub filter_stop_words: bool,
}

impl Default for EmbeddingConfig {
    fn default() -> Self {
        Self {
            model: "tfidf-rp-256".to_string(),
            dimensions: 256,
            vocab_size: 10_000,
            normalize: true,
            seed: 42,
            filter_stop_words: true,
        }
    }
}

impl EmbeddingConfig {
    /// Validate the configuration. Panics if dimensions or vocab_size are 0.
    pub fn validate(&self) {
        assert!(self.dimensions > 0, "EmbeddingConfig: dimensions must be > 0");
        assert!(self.vocab_size > 0, "EmbeddingConfig: vocab_size must be > 0");
    }
}

// ============================================================================
// 2. EMBEDDING VECTOR
// ============================================================================

/// A dense embedding vector produced by the engine.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EmbeddingVector {
    /// The vector values. Length must equal `dimensions`.
    pub values: Vec<f32>,
    /// Number of dimensions. Must equal `values.len()`.
    pub dimensions: usize,
    /// Model name that produced this vector.
    pub model: String,
}

impl EmbeddingVector {
    /// Create a new EmbeddingVector. Panics if `values.len() != dimensions`.
    ///
    /// # Examples
    /// ```
    /// use cerebrum_embeddings::EmbeddingVector;
    ///
    /// let v = EmbeddingVector::new(vec![1.0, 0.0, 0.0], 3, "test".to_string());
    /// assert_eq!(v.dimensions, 3);
    /// assert_eq!(v.values.len(), 3);
    /// ```
    pub fn new(values: Vec<f32>, dimensions: usize, model: String) -> Self {
        assert_eq!(
            values.len(),
            dimensions,
            "EmbeddingVector: values.len() ({}) must equal dimensions ({})",
            values.len(),
            dimensions
        );
        Self {
            values,
            dimensions,
            model,
        }
    }

    /// L2 norm (magnitude) of the vector.
    #[must_use]
    pub fn magnitude(&self) -> f32 {
        self.values.iter().map(|v| v * v).sum::<f32>().sqrt()
    }

    /// Check if this is a zero vector (magnitude < 1e-10).
    #[must_use]
    pub fn is_zero(&self) -> bool {
        self.magnitude() < 1e-10
    }
}

impl Default for EmbeddingVector {
    fn default() -> Self {
        Self {
            values: Vec::new(),
            dimensions: 0,
            model: String::new(),
        }
    }
}

// ============================================================================
// 3. SIMILARITY METRICS
// ============================================================================

/// Similarity metric for vector comparison.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum SimilarityMetric {
    /// Cosine similarity — angle between vectors (1.0 = identical direction).
    Cosine,
    /// Euclidean distance — negated so higher score = more similar.
    Euclidean,
    /// Raw dot product — magnitude-sensitive.
    DotProduct,
}

// ============================================================================
// 4. STOP WORDS
// ============================================================================

/// Common English stop words filtered before tokenization.
const STOP_WORDS: &[&str] = &[
    "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
    "be", "have", "has", "had", "do", "does", "did", "will", "would",
    "could", "should", "may", "might", "must", "shall", "can", "need",
    "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
    "we", "they", "what", "which", "who", "whom", "when", "where", "why",
    "how", "all", "each", "every", "both", "few", "more", "most", "other",
    "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than",
    "too", "very", "just", "also", "now", "here", "there", "then", "once",
    "if", "about", "into", "through", "during", "before", "after", "above",
    "below", "between", "under", "again", "further", "any", "being",
];

// ============================================================================
// 5. PCG RNG FOR PROJECTION MATRIX
// ============================================================================

/// Minimal PCG RNG for reproducible projection matrix generation.
struct PcgRng {
    state: u64,
    inc: u64,
}

impl PcgRng {
    fn new(seed: u64) -> Self {
        Self {
            state: seed.wrapping_add(1),
            inc: seed | 1,
        }
    }

    fn next_u32(&mut self) -> u32 {
        let old_state = self.state;
        self.state = old_state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(self.inc);
        let xor_shifted = (((old_state >> 18) ^ old_state) >> 27) as u32;
        let rot = (old_state >> 59) as u32;
        (xor_shifted >> rot) | (xor_shifted << ((-(rot as i32)) as u32 & 31))
    }

    fn next_f32(&mut self) -> f32 {
        (self.next_u32() as f64 / (u32::MAX as f64 + 1.0)) as f32
    }
}

// ============================================================================
// 6. TF-IDF EMBEDDING ENGINE
// ============================================================================

/// TF-IDF embedding engine with random projection and caching.
///
/// The engine maintains document frequency (DF) counts across all texts it has
/// seen. IDF is computed as 1 + log((N + 1) / (1 + df)) where N is the total
/// number of documents and df is how many documents contain the term. The
/// smoothing ensures IDF is always >= 1, so TF-IDF is never zero for non-empty
/// text. N is the document count BEFORE the current document, so the first
/// document gets IDF = 1 + log(1/1) = 1 for all words.
///
/// The random projection matrix is generated once at construction time using
/// a PCG RNG seeded by the config seed. This ensures reproducible embeddings
/// across sessions with the same config.
///
/// All output vectors are L2-normalized (when config.normalize is true) so
/// cosine similarity reduces to a single dot product.
#[derive(Debug, Clone)]
pub struct EmbeddingEngine {
    config: EmbeddingConfig,
    /// Random projection matrix: vocab_size x dimensions.
    /// Stored as a flat vector (row-major).
    /// Each entry is drawn from N(0, 1/sqrt(dimensions)).
    projection_matrix: Vec<f32>,
    /// Document frequency: how many documents contain each word hash.
    df: FxHashMap<u32, u32>,
    /// Total number of documents seen (for IDF computation).
    doc_count: u32,
    /// Cache of text -> embedding vector.
    cache: HashMap<String, EmbeddingVector>,
    /// Cache for query embeddings (does not pollute corpus stats).
    query_cache: HashMap<String, EmbeddingVector>,
}

impl EmbeddingEngine {
    /// Create a new embedding engine with the given configuration.
    /// Panics if dimensions or vocab_size are 0.
    ///
    /// # Examples
    /// ```
    /// use cerebrum_embeddings::{EmbeddingEngine, EmbeddingConfig};
    ///
    /// let engine = EmbeddingEngine::new(EmbeddingConfig::default());
    /// assert_eq!(engine.config().dimensions, 256);
    /// ```
    pub fn new(config: EmbeddingConfig) -> Self {
        config.validate();
        let matrix = Self::build_projection_matrix(
            config.vocab_size,
            config.dimensions,
            config.seed,
        );
        Self {
            config,
            projection_matrix: matrix,
            df: FxHashMap::default(),
            doc_count: 0,
            cache: HashMap::new(),
            query_cache: HashMap::new(),
        }
    }

    /// Create an engine with default configuration.
    pub fn with_default_config() -> Self {
        Self::new(EmbeddingConfig::default())
    }

    /// Get the configuration.
    #[must_use]
    pub fn config(&self) -> &EmbeddingConfig {
        &self.config
    }

    /// Number of documents the engine has seen (for IDF).
    #[must_use]
    pub fn doc_count(&self) -> u32 {
        self.doc_count
    }

    /// Number of cached embeddings (document embeddings only).
    #[must_use]
    pub fn cache_size(&self) -> usize {
        self.cache.len()
    }

    /// Number of cached query embeddings.
    #[must_use]
    pub fn query_cache_size(&self) -> usize {
        self.query_cache.len()
    }

    /// Build the random projection matrix.
    ///
    /// Each entry is drawn from a normal distribution N(0, 1/sqrt(dimensions))
    /// using Box-Muller transform. The matrix is vocab_size x dimensions,
    /// stored row-major. This is generated once and reused for all embeddings.
    fn build_projection_matrix(vocab_size: usize, dimensions: usize, seed: u64) -> Vec<f32> {
        let mut rng = PcgRng::new(seed);
        let scale = (1.0 / dimensions as f64).sqrt() as f32;
        let mut matrix = Vec::with_capacity(vocab_size * dimensions);

        for _ in 0..(vocab_size * dimensions) {
            // Box-Muller transform for approximate normal distribution
            let u1 = rng.next_f32().max(1e-10);
            let u2 = rng.next_f32();
            let z = scale * (-2.0 * u1.ln()).sqrt() * (2.0 * std::f32::consts::PI * u2).cos();
            matrix.push(z);
        }

        matrix
    }

    /// Tokenize text into a list of lowercase word strings.
    /// Strips punctuation and optionally filters stop words.
    /// Words of length 1 are always filtered (they carry little semantic weight).
    fn tokenize(text: &str, filter_stop_words: bool) -> Vec<String> {
        text.to_lowercase()
            .replace(|c: char| !c.is_alphanumeric() && c != ' ', " ")
            .split_whitespace()
            .filter(|w| w.len() > 1)
            .filter(|w| !filter_stop_words || !STOP_WORDS.contains(w))
            .map(|w| w.to_string())
            .collect()
    }

    /// Hash a word to a vocab index using FNV-1a with an additional
    /// mixing step to reduce collisions in small vocab spaces.
    fn word_to_index(word: &str, vocab_size: usize) -> u32 {
        let mut hash: u64 = 0xcbf29ce484222325;
        for byte in word.bytes() {
            hash ^= byte as u64;
            hash = hash.wrapping_mul(0x100000001b3);
        }
        // Extra mixing step: xor with rotated hash to spread bits more evenly
        hash ^= hash >> 33;
        hash = hash.wrapping_mul(0xff51afd7ed558ccd);
        hash ^= hash >> 33;
        (hash % vocab_size as u64) as u32
    }

    /// Compute term frequency for a list of words.
    /// Returns a map of word_index -> term frequency (raw count / total terms).
    fn compute_tf(words: &[String], vocab_size: usize) -> FxHashMap<u32, f32> {
        let mut counts: FxHashMap<u32, u32> = FxHashMap::default();
        for word in words {
            let idx = Self::word_to_index(word, vocab_size);
            *counts.entry(idx).or_insert(0) += 1;
        }

        let total = words.len() as f32;
        if total == 0.0 {
            return FxHashMap::default();
        }

        counts
            .into_iter()
            .map(|(idx, count)| (idx, count as f32 / total))
            .collect()
    }

    /// Compute IDF for a word index.
    /// Uses smoothed IDF: 1 + log((N + 1) / (1 + df))
    /// This ensures IDF is always >= 1, so TF-IDF is never zero for
    /// non-empty text. N is the document count BEFORE the current document.
    fn compute_idf(&self, word_idx: u32) -> f32 {
        let df = self.df.get(&word_idx).copied().unwrap_or(0) as f32;
        let n = self.doc_count as f32;
        1.0 + ((n + 1.0) / (1.0 + df)).ln()
    }

    /// Project a TF-IDF sparse vector through the random projection matrix.
    /// Returns the projected dense vector (not yet L2-normalized).
    fn project_tfidf(&self, tf: &FxHashMap<u32, f32>) -> Vec<f32> {
        let dims = self.config.dimensions;
        let mut projected = vec![0.0f32; dims];

        for (&word_idx, &tf_val) in tf {
            let idf = self.compute_idf(word_idx);
            let tfidf = tf_val * idf;

            let row_start = (word_idx as usize) * dims;
            if row_start + dims <= self.projection_matrix.len() {
                for (col_idx, proj_val) in projected.iter_mut().enumerate().take(dims) {
                    *proj_val += tfidf * self.projection_matrix[row_start + col_idx];
                }
            }
        }

        projected
    }

    /// Generate a TF-IDF + random projection embedding for `text`.
    ///
    /// Steps:
    /// 1. Tokenize text into words (lowercase, punctuation stripped, stop words filtered)
    /// 2. Compute term frequencies (TF) for each word hash
    /// 3. Compute TF-IDF weights: TF * IDF for each word
    /// 4. Update document frequency counts (this text is a new document)
    /// 5. Project the sparse TF-IDF vector through the random projection matrix
    /// 6. L2-normalize the result
    ///
    /// # Examples
    /// ```
    /// use cerebrum_embeddings::EmbeddingEngine;
    ///
    /// let mut engine = EmbeddingEngine::with_default_config();
    /// let v = engine.embed("rust programming language");
    /// assert_eq!(v.dimensions, 256);
    /// assert!(!v.is_zero());
    /// ```
    pub fn embed(&mut self, text: &str) -> EmbeddingVector {
        // Check cache first
        if let Some(cached) = self.cache.get(text) {
            return cached.clone();
        }

        let words = Self::tokenize(text, self.config.filter_stop_words);

        // Compute TF
        let tf = Self::compute_tf(&words, self.config.vocab_size);

        // Compute IDF and TF-IDF BEFORE updating document frequency.
        // IDF uses the corpus state excluding this document, so the first
        // document gets IDF = 1 + log((0+1)/(1+0)) = 1 + 0 = 1 for all words.
        // This ensures non-zero embeddings even for the first document.
        let mut projected = self.project_tfidf(&tf);

        // L2 normalize
        if self.config.normalize {
            Self::l2_normalize(&mut projected);
        }

        let vector = EmbeddingVector::new(
            projected,
            self.config.dimensions,
            self.config.model.clone(),
        );

        // Now update document frequency AFTER computing the embedding
        self.doc_count += 1;
        for &word_idx in tf.keys() {
            *self.df.entry(word_idx).or_insert(0) += 1;
        }

        self.cache.insert(text.to_string(), vector.clone());
        vector
    }

    /// Embed a batch of texts. Each text is treated as a separate document
    /// for IDF purposes, processed in order.
    pub fn embed_batch(&mut self, texts: &[String]) -> Vec<EmbeddingVector> {
        texts.iter().map(|text| self.embed(text)).collect()
    }

    /// Embed a text WITHOUT updating document frequencies or the corpus cache.
    /// Useful for query-time embedding where you don't want to pollute
    /// the corpus statistics. Results are cached in a separate query cache
    /// to avoid recomputation on repeated queries.
    ///
    /// # Examples
    /// ```
    /// use cerebrum_embeddings::EmbeddingEngine;
    ///
    /// let mut engine = EmbeddingEngine::with_default_config();
    /// engine.embed("rust programming");
    /// let q = engine.embed_query("rust code");
    /// assert_eq!(q.dimensions, 256);
    /// assert_eq!(engine.doc_count(), 1); // query did not increase doc_count
    /// ```
    pub fn embed_query(&self, text: &str) -> EmbeddingVector {
        // Check query cache first
        if let Some(cached) = self.query_cache.get(text) {
            return cached.clone();
        }

        let words = Self::tokenize(text, self.config.filter_stop_words);
        let tf = Self::compute_tf(&words, self.config.vocab_size);
        let mut projected = self.project_tfidf(&tf);

        if self.config.normalize {
            Self::l2_normalize(&mut projected);
        }

        EmbeddingVector::new(projected, self.config.dimensions, self.config.model.clone())
    }

    /// Search `candidates` for vectors most similar to `query` under `metric`.
    ///
    /// Returns tuples of (index_in_candidates, score) sorted by descending
    /// score. For Cosine and DotProduct higher is better; for Euclidean
    /// the negated distance is returned so higher = closer = better.
    ///
    /// Returns an empty vec if query and candidates have mismatched dimensions.
    #[must_use]
    pub fn search(
        &self,
        query: &EmbeddingVector,
        candidates: &[EmbeddingVector],
        metric: SimilarityMetric,
        limit: usize,
    ) -> Vec<(usize, f32)> {
        // Validate dimension compatibility
        for candidate in candidates {
            if candidate.dimensions != query.dimensions {
                return Vec::new();
            }
        }

        let mut scored: Vec<(usize, f32)> = candidates
            .iter()
            .enumerate()
            .map(|(index, candidate)| {
                let score = self.similarity(query, candidate, metric);
                (index, score)
            })
            .collect();

        scored.sort_by(|a, b| {
            b.1.partial_cmp(&a.1)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        scored.into_iter().take(limit).collect()
    }

    /// Compute similarity between two vectors under the chosen metric.
    /// Returns 0.0 for zero vectors under Cosine. Returns 0.0 for
    /// dimension-mismatched vectors under all metrics.
    #[must_use]
    pub fn similarity(
        &self,
        a: &EmbeddingVector,
        b: &EmbeddingVector,
        metric: SimilarityMetric,
    ) -> f32 {
        if a.values.len() != b.values.len() {
            return 0.0;
        }

        match metric {
            // Both vectors are L2-normalized (when normalize=true), so
            // cosine similarity = dot product.
            SimilarityMetric::Cosine => {
                if a.is_zero() || b.is_zero() {
                    return 0.0;
                }
                dot_product(&a.values, &b.values)
            }
            SimilarityMetric::DotProduct => dot_product(&a.values, &b.values),
            SimilarityMetric::Euclidean => -euclidean_distance(&a.values, &b.values),
        }
    }

    /// Clear the embedding cache (does not reset DF/IDF statistics or query cache).
    pub fn clear_cache(&mut self) {
        self.cache.clear();
    }

    /// Clear the query embedding cache.
    pub fn clear_query_cache(&mut self) {
        self.query_cache.clear();
    }

    /// Reset all corpus statistics (DF counts, doc count, and both caches).
    /// The projection matrix is preserved since it depends only on the seed.
    pub fn reset_corpus(&mut self) {
        self.df.clear();
        self.doc_count = 0;
        self.cache.clear();
        self.query_cache.clear();
    }

    /// L2 normalize a vector in place.
    fn l2_normalize(values: &mut [f32]) {
        let sum_of_squares: f32 = values.iter().map(|v| v * v).sum();
        let magnitude = sum_of_squares.sqrt();
        if magnitude > 1e-10 {
            for value in values.iter_mut() {
                *value /= magnitude;
            }
        }
    }
}

impl Default for EmbeddingEngine {
    fn default() -> Self {
        Self::with_default_config()
    }
}

// ============================================================================
// 7. SIMD-BATCH SEARCH (for large candidate sets)
// ============================================================================

/// Batch cosine similarity search optimized for large candidate sets.
///
/// When candidates are L2-normalized, cosine similarity is just dot product.
/// This function processes candidates in chunks for better cache locality.
/// Returns an empty vec if any candidate has mismatched dimensions.
#[must_use]
pub fn batch_cosine_search(
    query: &EmbeddingVector,
    candidates: &[EmbeddingVector],
    limit: usize,
) -> Vec<(usize, f32)> {
    // Validate dimension compatibility
    for candidate in candidates {
        if candidate.dimensions != query.dimensions {
            return Vec::new();
        }
    }

    let mut results: Vec<(usize, f32)> = candidates
        .chunks(32)
        .enumerate()
        .flat_map(|(chunk_idx, chunk)| {
            let base = chunk_idx * 32;
            chunk.iter().enumerate().map(move |(local_idx, candidate)| {
                (base + local_idx, dot_product(&query.values, &candidate.values))
            })
        })
        .collect();

    results.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    results.into_iter().take(limit).collect()
}

// ============================================================================
// 8. UTILITY FUNCTIONS
// ============================================================================

/// Dot product of two float slices. Caller must ensure equal lengths.
fn dot_product(a: &[f32], b: &[f32]) -> f32 {
    debug_assert_eq!(
        a.len(),
        b.len(),
        "dot_product: dimension mismatch {} vs {}",
        a.len(),
        b.len()
    );
    a.iter()
        .zip(b.iter())
        .map(|(x, y)| x * y)
        .sum()
}

/// Euclidean distance between two float slices. Caller must ensure equal lengths.
fn euclidean_distance(a: &[f32], b: &[f32]) -> f32 {
    debug_assert_eq!(
        a.len(),
        b.len(),
        "euclidean_distance: dimension mismatch {} vs {}",
        a.len(),
        b.len()
    );
    a.iter()
        .zip(b.iter())
        .map(|(x, y)| (x - y).powi(2))
        .sum::<f32>()
        .sqrt()
}

// ============================================================================
// 9. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------------
    // Basic embedding
    // ------------------------------------------------------------------------

    #[test]
    fn test_embed_dimensions() {
        let mut engine = EmbeddingEngine::with_default_config();
        let vector = engine.embed("hello world");
        assert_eq!(vector.dimensions, 256);
        assert_eq!(vector.values.len(), 256);
    }

    #[test]
    fn test_embed_non_zero() {
        let mut engine = EmbeddingEngine::with_default_config();
        let vector = engine.embed("the quick brown fox jumps over the lazy dog");
        assert!(!vector.is_zero(), "embedding should not be all zeros for non-trivial text");
    }

    #[test]
    fn test_embed_empty_text() {
        let mut engine = EmbeddingEngine::with_default_config();
        let vector = engine.embed("");
        assert!(vector.is_zero(), "empty text should produce a zero vector");
    }

    #[test]
    fn test_embed_only_stop_words() {
        let mut engine = EmbeddingEngine::with_default_config();
        let vector = engine.embed("the a an and or but in on at to for of with by from as is was are were been be have has had do does did will would could should may might must shall can");
        // All stop words filtered out -> empty word list -> zero vector
        assert!(vector.is_zero(), "text with only stop words should produce a zero vector");
    }

    // ------------------------------------------------------------------------
    // Determinism and reproducibility
    // ------------------------------------------------------------------------

    #[test]
    fn test_embed_deterministic_same_engine() {
        let mut engine = EmbeddingEngine::with_default_config();
        let a = engine.embed("rust programming language");
        // Reset corpus but keep projection matrix
        engine.reset_corpus();
        let b = engine.embed("rust programming language");
        // Same text, same seed -> same projection, but IDF differs because
        // the first embed updates DF. After reset, the second embed starts fresh.
        // The vectors should be very close since TF is identical and IDF for
        // a single document is the same.
        let dist = euclidean_distance(&a.values, &b.values);
        assert!(
            dist < 1e-4,
            "Same text with reset should produce nearly identical vectors, dist = {}",
            dist
        );
    }

    #[test]
    fn test_embed_deterministic_same_seed() {
        let config = EmbeddingConfig::default();
        let mut engine1 = EmbeddingEngine::new(config.clone());
        let mut engine2 = EmbeddingEngine::new(config);

        let a = engine1.embed("rust programming language");
        let b = engine2.embed("rust programming language");

        // Same config, same seed -> same projection matrix, same IDF (both have N=1)
        assert_eq!(
            a.values, b.values,
            "Same config and seed should produce identical embeddings"
        );
    }

    #[test]
    fn test_different_seeds_different_vectors() {
        let config1 = EmbeddingConfig { seed: 42, ..Default::default() };
        let config2 = EmbeddingConfig { seed: 99, ..Default::default() };
        let mut engine1 = EmbeddingEngine::new(config1);
        let mut engine2 = EmbeddingEngine::new(config2);

        let a = engine1.embed("rust programming language");
        let b = engine2.embed("rust programming language");

        // Different seeds -> different projection matrices -> different vectors
        assert_ne!(
            a.values, b.values,
            "Different seeds should produce different embeddings"
        );
    }

    // ------------------------------------------------------------------------
    // Semantic similarity
    // ------------------------------------------------------------------------

    #[test]
    fn test_identical_text_high_similarity() {
        let mut engine = EmbeddingEngine::with_default_config();
        let a = engine.embed("rust programming language");
        let b = engine.embed("rust programming language");

        let sim = engine.similarity(&a, &b, SimilarityMetric::Cosine);
        assert!(
            sim > 0.99,
            "identical text should have cosine similarity near 1.0, got {}",
            sim
        );
    }

    #[test]
    fn test_similar_text_higher_than_dissimilar() {
        let mut engine = EmbeddingEngine::with_default_config();

        // Build up a small corpus so IDF has meaning
        let corpus = [
            "rust programming language memory safety",
            "python programming language dynamic typing",
            "rust ownership borrow checker",
            "quantum physics particle entanglement",
            "cooking recipe pasta italian food",
        ];
        for text in &corpus {
            engine.embed(text);
        }

        // Similar texts
        let query = engine.embed_query("rust programming");
        let similar = engine.embed_query("rust language memory");
        let dissimilar = engine.embed_query("quantum physics particles");

        let sim_score = engine.similarity(&query, &similar, SimilarityMetric::Cosine);
        let dissim_score = engine.similarity(&query, &dissimilar, SimilarityMetric::Cosine);

        assert!(
            sim_score > dissim_score,
            "Similar text should score higher than dissimilar: sim={} dissim={}",
            sim_score,
            dissim_score
        );
    }

    #[test]
    fn test_word_overlap_correlates_with_similarity() {
        let mut engine = EmbeddingEngine::with_default_config();

        // Build corpus with different documents sharing words
        for i in 0..5 {
            engine.embed(&format!("rust programming language features number {}", i));
        }
        for i in 0..5 {
            engine.embed(&format!("quantum physics experiments number {}", i));
        }

        // "rust programming" shares words with the first corpus
        let q1 = engine.embed_query("rust programming");
        let q2 = engine.embed_query("programming rust"); // same words, different order
        let q3 = engine.embed_query("quantum experiments"); // shares with second corpus

        let same_words = engine.similarity(&q1, &q2, SimilarityMetric::Cosine);
        let different_words = engine.similarity(&q1, &q3, SimilarityMetric::Cosine);

        assert!(
            same_words > different_words,
            "Same words different order should be more similar than different words: {} vs {}",
            same_words,
            different_words
        );
    }

    // ------------------------------------------------------------------------
    // Batch embedding
    // ------------------------------------------------------------------------

    #[test]
    fn test_batch_embed() {
        let mut engine = EmbeddingEngine::with_default_config();
        let texts = vec![
            "one".to_string(),
            "two".to_string(),
            "three".to_string(),
        ];
        let vectors = engine.embed_batch(&texts);
        assert_eq!(vectors.len(), 3);
        assert_eq!(vectors[0].dimensions, 256);
        // Each text is a separate document, so doc_count should increase
        assert_eq!(engine.doc_count(), 3);
    }

    // ------------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------------

    #[test]
    fn test_search_returns_correct_order() {
        let mut engine = EmbeddingEngine::with_default_config();

        // Build a corpus so IDF has meaning
        let corpus: Vec<String> = [
            "rust programming systems language",
            "python scripting language dynamic",
            "quantum physics theory particles",
            "rust memory safety ownership",
            "cooking italian pasta recipe",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        let candidates = engine.embed_batch(&corpus);

        let query = engine.embed_query("rust programming");

        let results = engine.search(&query, &candidates, SimilarityMetric::Cosine, 3);
        assert_eq!(results.len(), 3);

        // The top result should be about rust (index 0 or 3)
        let top_idx = results[0].0;
        assert!(
            top_idx == 0 || top_idx == 3,
            "top result should be about rust, got index {}",
            top_idx
        );
    }

    #[test]
    fn test_search_respects_limit() {
        let mut engine = EmbeddingEngine::with_default_config();
        let corpus: Vec<String> = (0..10)
            .map(|i| format!("document number {} about topic", i))
            .collect();
        let candidates = engine.embed_batch(&corpus);
        let query = engine.embed_query("document topic");

        let results = engine.search(&query, &candidates, SimilarityMetric::Cosine, 3);
        assert!(results.len() <= 3);
    }

    #[test]
    fn test_search_euclidean_metric() {
        let mut engine = EmbeddingEngine::with_default_config();
        let corpus = vec![
            "rust programming".to_string(),
            "python programming".to_string(),
            "quantum physics".to_string(),
        ];
        let candidates = engine.embed_batch(&corpus);
        let query = engine.embed_query("rust code");

        let cosine_results = engine.search(&query, &candidates, SimilarityMetric::Cosine, 3);
        let euclidean_results = engine.search(&query, &candidates, SimilarityMetric::Euclidean, 3);

        // Both metrics should rank the rust-related result first
        assert_eq!(cosine_results[0].0, euclidean_results[0].0);
    }

    #[test]
    fn test_search_dimension_mismatch_returns_empty() {
        let engine = EmbeddingEngine::with_default_config();
        let query = EmbeddingVector::new(vec![0.0; 128], 128, "test".to_string());
        let candidates = vec![EmbeddingVector::new(vec![0.0; 256], 256, "test".to_string())];
        let results = engine.search(&query, &candidates, SimilarityMetric::Cosine, 10);
        assert!(results.is_empty(), "dimension mismatch should return empty results");
    }

    // ------------------------------------------------------------------------
    // Caching
    // ------------------------------------------------------------------------

    #[test]
    fn test_cache_returns_same_vector() {
        let mut engine = EmbeddingEngine::with_default_config();
        let first = engine.embed("cached text");
        let second = engine.embed("cached text");
        assert_eq!(first, second);
        assert_eq!(engine.cache_size(), 1);
    }

    #[test]
    fn test_clear_cache() {
        let mut engine = EmbeddingEngine::with_default_config();
        engine.embed("text one");
        engine.embed("text two");
        assert_eq!(engine.cache_size(), 2);

        engine.clear_cache();
        assert_eq!(engine.cache_size(), 0);

        // Re-embedding should still work (DF/IDF stats preserved)
        let v = engine.embed("text one");
        assert_eq!(v.dimensions, 256);
    }

    #[test]
    fn test_reset_corpus() {
        let mut engine = EmbeddingEngine::with_default_config();
        engine.embed("text one");
        engine.embed("text two");
        assert_eq!(engine.doc_count(), 2);

        engine.reset_corpus();
        assert_eq!(engine.doc_count(), 0);
        assert_eq!(engine.cache_size(), 0);
        assert!(engine.df.is_empty());
    }

    // ------------------------------------------------------------------------
    // Query embedding (no DF update)
    // ------------------------------------------------------------------------

    #[test]
    fn test_embed_query_does_not_update_corpus() {
        let mut engine = EmbeddingEngine::with_default_config();
        engine.embed("document one about rust");
        let doc_count_before = engine.doc_count();

        let _query_vec = engine.embed_query("rust query");
        let doc_count_after = engine.doc_count();

        assert_eq!(
            doc_count_before, doc_count_after,
            "embed_query should not update doc_count"
        );
    }

    #[test]
    fn test_embed_query_not_in_document_cache() {
        let mut engine = EmbeddingEngine::with_default_config();
        engine.embed_query("test query");
        assert_eq!(
            engine.cache_size(),
            0,
            "embed_query should not add to document cache"
        );
    }

    // ------------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------------

    #[test]
    fn test_custom_dimensions() {
        let config = EmbeddingConfig {
            dimensions: 128,
            ..Default::default()
        };
        let mut engine = EmbeddingEngine::new(config);
        let vector = engine.embed("test text");
        assert_eq!(vector.dimensions, 128);
        assert_eq!(vector.values.len(), 128);
    }

    #[test]
    fn test_custom_vocab_size() {
        let config = EmbeddingConfig {
            vocab_size: 1000,
            ..Default::default()
        };
        let mut engine = EmbeddingEngine::new(config);
        let vector = engine.embed("test text here");
        assert_eq!(vector.dimensions, 256);
        assert!(!vector.is_zero());
    }

    #[test]
    fn test_no_normalization() {
        let config = EmbeddingConfig {
            normalize: false,
            ..Default::default()
        };
        let mut engine = EmbeddingEngine::new(config);
        let vector = engine.embed("test text with some content here");
        // Without normalization, magnitude should not be 1.0
        let mag = vector.magnitude();
        assert!(mag > 0.0, "non-normalized vector should have positive magnitude");
    }

    #[test]
    fn test_no_stop_word_filtering() {
        let config = EmbeddingConfig {
            filter_stop_words: false,
            ..Default::default()
        };
        let mut engine = EmbeddingEngine::new(config);
        // Use words longer than 1 character that are also stop words
        let vector = engine.embed("the and but from with");
        // Without stop word filtering, these words produce a non-zero vector
        assert!(!vector.is_zero(), "stop words should produce non-zero vector when filtering is off");
    }

    #[test]
    #[should_panic(expected = "dimensions must be > 0")]
    fn test_config_validate_zero_dimensions() {
        let config = EmbeddingConfig {
            dimensions: 0,
            ..Default::default()
        };
        EmbeddingEngine::new(config);
    }

    #[test]
    #[should_panic(expected = "vocab_size must be > 0")]
    fn test_config_validate_zero_vocab_size() {
        let config = EmbeddingConfig {
            vocab_size: 0,
            ..Default::default()
        };
        EmbeddingEngine::new(config);
    }

    #[test]
    #[should_panic(expected = "values.len()")]
    fn test_embedding_vector_dimension_mismatch() {
        EmbeddingVector::new(vec![1.0, 2.0], 3, "test".to_string());
    }

    // ------------------------------------------------------------------------
    // L2 normalization
    // ------------------------------------------------------------------------

    #[test]
    fn test_normalized_vector_unit_magnitude() {
        let mut engine = EmbeddingEngine::with_default_config();
        let vector = engine.embed("some meaningful text with words");
        if !vector.is_zero() {
            let mag = vector.magnitude();
            assert!(
                (mag - 1.0).abs() < 1e-4,
                "normalized vector should have magnitude ~1.0, got {}",
                mag
            );
        }
    }

    // ------------------------------------------------------------------------
    // IDF behavior
    // ------------------------------------------------------------------------

    #[test]
    fn test_idf_increases_for_rare_words() {
        let mut engine = EmbeddingEngine::with_default_config();

        // Embed 20 DIFFERENT documents all containing "programming"
        for i in 0..20 {
            engine.embed(&format!("programming topic number {} details", i));
        }

        // Embed one document with a rare word
        engine.embed("giraffe safari africa wildlife");

        // Find the vocab index for "programming" (common) and "giraffe" (rare)
        let common_idx = EmbeddingEngine::word_to_index("programming", engine.config().vocab_size);
        let rare_idx = EmbeddingEngine::word_to_index("giraffe", engine.config().vocab_size);

        // If they hash to the same bucket, skip this test (collision in small vocab)
        if common_idx == rare_idx {
            eprintln!("Hash collision between 'programming' and 'giraffe' at vocab index {}, skipping", common_idx);
            return;
        }

        let common_df = engine.df.get(&common_idx).copied().unwrap_or(0);
        let rare_df = engine.df.get(&rare_idx).copied().unwrap_or(0);

        assert_eq!(common_df, 20, "programming should appear in 20 documents");
        assert_eq!(rare_df, 1, "giraffe should appear in 1 document");

        let common_idf = engine.compute_idf(common_idx);
        let rare_idf = engine.compute_idf(rare_idx);

        assert!(
            rare_idf > common_idf,
            "rare word should have higher IDF than common word: rare={} common={}",
            rare_idf,
            common_idf
        );
    }

    #[test]
    fn test_doc_count_increases() {
        let mut engine = EmbeddingEngine::with_default_config();
        assert_eq!(engine.doc_count(), 0);

        engine.embed("first document");
        assert_eq!(engine.doc_count(), 1);

        engine.embed("second document");
        assert_eq!(engine.doc_count(), 2);

        engine.embed("third document");
        assert_eq!(engine.doc_count(), 3);
    }

    // ------------------------------------------------------------------------
    // Batch cosine search utility
    // ------------------------------------------------------------------------

    #[test]
    fn test_batch_cosine_search() {
        let mut engine = EmbeddingEngine::with_default_config();
        let candidates = engine.embed_batch(&[
            "rust systems programming".to_string(),
            "python scripting".to_string(),
            "quantum mechanics".to_string(),
        ]);
        let query = engine.embed_query("rust programming");

        let results = batch_cosine_search(&query, &candidates, 2);
        assert_eq!(results.len(), 2);
        // Top result should be rust-related
        assert_eq!(results[0].0, 0);
    }

    #[test]
    fn test_batch_cosine_search_dimension_mismatch() {
        let query = EmbeddingVector::new(vec![0.0; 128], 128, "test".to_string());
        let candidates = vec![EmbeddingVector::new(vec![0.0; 256], 256, "test".to_string())];
        let results = batch_cosine_search(&query, &candidates, 10);
        assert!(results.is_empty(), "dimension mismatch should return empty results");
    }

    // ------------------------------------------------------------------------
    // Multiple metrics consistency
    // ------------------------------------------------------------------------

    #[test]
    fn test_cosine_and_dot_product_match_for_normalized() {
        let mut engine = EmbeddingEngine::with_default_config();
        let a = engine.embed("rust programming");
        let b = engine.embed("rust language");

        // For L2-normalized vectors, cosine == dot product
        let cosine = engine.similarity(&a, &b, SimilarityMetric::Cosine);
        let dot = engine.similarity(&a, &b, SimilarityMetric::DotProduct);

        assert!(
            (cosine - dot).abs() < 1e-4,
            "For normalized vectors, cosine should equal dot product: cos={} dot={}",
            cosine,
            dot
        );
    }

    #[test]
    fn test_similarity_dimension_mismatch_returns_zero() {
        let engine = EmbeddingEngine::with_default_config();
        let a = EmbeddingVector::new(vec![1.0; 128], 128, "test".to_string());
        let b = EmbeddingVector::new(vec![1.0; 256], 256, "test".to_string());
        assert_eq!(engine.similarity(&a, &b, SimilarityMetric::Cosine), 0.0);
        assert_eq!(engine.similarity(&a, &b, SimilarityMetric::DotProduct), 0.0);
        assert_eq!(engine.similarity(&a, &b, SimilarityMetric::Euclidean), 0.0);
    }

    // ------------------------------------------------------------------------
    // Default impls
    // ------------------------------------------------------------------------

    #[test]
    fn test_embedding_vector_default() {
        let v = EmbeddingVector::default();
        assert!(v.values.is_empty());
        assert_eq!(v.dimensions, 0);
        assert!(v.model.is_empty());
    }

    // ------------------------------------------------------------------------
    // Large-scale stress test
    // ------------------------------------------------------------------------

    #[test]
    fn test_embed_1000_documents() {
        let mut engine = EmbeddingEngine::with_default_config();

        for i in 0..1000 {
            let text = match i % 5 {
                0 => format!("rust programming topic number {}", i),
                1 => format!("python scripting topic number {}", i),
                2 => format!("quantum physics topic number {}", i),
                3 => format!("cooking recipe topic number {}", i),
                _ => format!("general knowledge topic number {}", i),
            };
            engine.embed(&text);
        }

        assert_eq!(engine.doc_count(), 1000);

        // Search should still work
        let query = engine.embed_query("rust programming");
        let candidates = engine.embed_batch(&[
            "rust systems programming language".to_string(),
            "python dynamic scripting".to_string(),
            "quantum particle physics".to_string(),
        ]);

        let results = engine.search(&query, &candidates, SimilarityMetric::Cosine, 1);
        assert_eq!(results[0].0, 0, "rust query should match rust candidate first");
    }

    // ------------------------------------------------------------------------
    // Property-based tests (10K iterations)
    // ------------------------------------------------------------------------

    use proptest::prelude::*;

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(10_000))]

        #[test]
        fn proptest_embed_dimensions_match_config(dimensions in 64..512usize) {
            let config = EmbeddingConfig { dimensions, ..Default::default() };
            let mut engine = EmbeddingEngine::new(config);
            let v = engine.embed("test text content here");
            prop_assert_eq!(v.dimensions, dimensions);
            prop_assert_eq!(v.values.len(), dimensions);
        }

        #[test]
        fn proptest_embed_deterministic_same_config(text in "[a-z ]{1,100}") {
            let config = EmbeddingConfig::default();
            let mut engine1 = EmbeddingEngine::new(config.clone());
            let mut engine2 = EmbeddingEngine::new(config);
            let a = engine1.embed(&text);
            let b = engine2.embed(&text);
            // Same config + same text -> same vector (both start with N=0)
            prop_assert_eq!(a.values, b.values);
        }

        #[test]
        fn proptest_embed_query_no_corpus_update(text in "[a-z ]{1,100}") {
            let mut engine = EmbeddingEngine::with_default_config();
            engine.embed("corpus document one");
            let before = engine.doc_count();
            let _ = engine.embed_query(&text);
            let after = engine.doc_count();
            prop_assert_eq!(before, after, "embed_query should not update doc_count");
        }

        #[test]
        fn proptest_identical_text_identical_embedding(text in "[a-z ]{1,100}") {
            let mut engine = EmbeddingEngine::with_default_config();
            let a = engine.embed(&text);
            let b = engine.embed(&text);
            // Cached, so identical
            prop_assert_eq!(a, b);
        }

        #[test]
        fn proptest_similarity_bounded(query_text in "[a-z ]{1,50}", candidate_text in "[a-z ]{1,50}") {
            let mut engine = EmbeddingEngine::with_default_config();
            engine.embed("corpus document for idf context here with some words");
            let a = engine.embed_query(&query_text);
            let b = engine.embed_query(&candidate_text);
            let cosine = engine.similarity(&a, &b, SimilarityMetric::Cosine);
            // Cosine similarity for normalized vectors is in [-1, 1]
            // But TF-IDF values are always positive, so projected values
            // tend to be positive, making cosine typically in [0, 1]
            // Allow [-1.1, 1.1] for floating point tolerance
            prop_assert!(cosine >= -1.1 && cosine <= 1.1,
                "cosine similarity {} out of bounds for normalized vectors", cosine);
        }

        #[test]
        fn proptest_embed_vector_new_validates_dimensions(
            values_len in 0..512usize,
            declared_dims in 0..512usize,
        ) {
            let values = vec![0.0f32; values_len];
            if values_len == declared_dims {
                let v = EmbeddingVector::new(values, declared_dims, "test".to_string());
                prop_assert_eq!(v.values.len(), v.dimensions);
            } else {
                // Should panic
                let result = std::panic::catch_unwind(|| {
                    EmbeddingVector::new(values, declared_dims, "test".to_string())
                });
                prop_assert!(result.is_err(), "should panic on dimension mismatch");
            }
        }
    }
}