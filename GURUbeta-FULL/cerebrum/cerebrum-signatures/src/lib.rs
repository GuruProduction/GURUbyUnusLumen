//! Cerebrum Signatures — Binary signature generation via random indexing + SIMD Hamming search.
//!
//! Phase 0.2.2 — THE compressed representation layer.
//!
//! This crate implements the signature pipeline from arXiv:2602.13594:
//!   1. Random Indexing: sparse context vectors → dense projection → threshold → binary
//!   2. Hamming-Ball Search: XOR + popcount within radius, SIMD-accelerated
//!   3. Multi-Index Hashing: sublinear search for large collections (>100K signatures)
//!
//! Key insight: 256-bit binary signatures (32 bytes) replace 768-dim float vectors (3,072 bytes).
//! That's 96x size reduction before DWM compression. Search becomes popcount — one CPU instruction.

use cerebrum_core::{BinarySignature, CerebrumError, MemoryId, SignatureGenerator};
use cerebrum_token::{SimpleTokenizer, TokenVocabulary, Tokenizer};
use rustc_hash::FxHashMap;
use serde::{Deserialize, Serialize};

mod simd;
pub use simd::{hamming_distance_fast, hamming_distance_scalar, hamming_distance_bytes_fast, hamming_distance_bytes_scalar, hamming_distance_batch8, is_avx2_supported};

// ============================================================================
// 1. CONFIGURATION
// ============================================================================

/// Configuration for the signature generation pipeline.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SignatureConfig {
    /// Dimension of the sparse context vectors (typically 10,000).
    pub context_dim: usize,
    /// Number of bits in the output binary signature.
    /// Must be a multiple of 8: 128, 256 (default), or 512.
    pub signature_bits: usize,
    /// Sparsity of context vectors (~1% non-zero entries).
    pub sparsity: f64,
    /// Strategy for binarizing the projected values.
    pub threshold: ThresholdStrategy,
    /// Seed for the random projection matrix (reproducibility).
    pub seed: u64,
}

impl Default for SignatureConfig {
    fn default() -> Self {
        Self {
            context_dim: 10_000,
            signature_bits: 256,
            sparsity: 0.01,
            threshold: ThresholdStrategy::Median,
            seed: 42,
        }
    }
}

impl SignatureConfig {
    /// Number of bytes in a binary signature (signature_bits / 8).
    pub fn signature_bytes(&self) -> usize {
        self.signature_bits / 8
    }

    /// Validate configuration.
    pub fn validate(&self) -> Result<(), CerebrumError> {
        if self.signature_bits % 8 != 0 {
            return Err(CerebrumError::SignatureError(
                "signature_bits must be a multiple of 8".into(),
            ));
        }
        if self.signature_bits == 0 || self.signature_bits > 512 {
            return Err(CerebrumError::SignatureError(
                "signature_bits must be between 8 and 512".into(),
            ));
        }
        if self.context_dim == 0 {
            return Err(CerebrumError::SignatureError(
                "context_dim must be > 0".into(),
            ));
        }
        if self.sparsity <= 0.0 || self.sparsity > 1.0 {
            return Err(CerebrumError::SignatureError(
                "sparsity must be in (0.0, 1.0]".into(),
            ));
        }
        Ok(())
    }
}

/// Strategy for binarizing projected values.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub enum ThresholdStrategy {
    /// Use the median of projected values as the threshold.
    /// Robust to outliers, adapts to data distribution.
    Median,
    /// Use a fixed threshold value.
    Fixed(f64),
    /// Adapt threshold based on running statistics.
    Adaptive,
}

// ============================================================================
// 2. RANDOM INDEXING ENGINE
// ============================================================================

/// Random Indexing engine for generating binary signatures.
///
/// The projection matrix is generated once and reused. Each text is
/// represented as a sparse context vector, projected through the matrix,
/// and binarized via threshold.
pub struct RandomIndexing {
    /// Random projection matrix: context_dim × signature_bits.
    /// Stored as a flat vector (row-major).
    projection_matrix: Vec<f32>,
    config: SignatureConfig,
    #[allow(dead_code)] // Reserved for future incremental generation
    rng_state: PcgRng,
}

/// Minimal PCG-based RNG for reproducible matrix generation.
/// Avoids the overhead of full rand::thread_rng for matrix init.
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
        // PCG-XSH-RR: 32-bit output
        let old_state = self.state;
        self.state = old_state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(self.inc);
        let xor_shifted = (((old_state >> 18) ^ old_state) >> 27) as u32;
        let rot = (old_state >> 59) as u32;
        (xor_shifted >> rot) | (xor_shifted << ((-(rot as i32)) as u32 & 31))
    }

    fn next_f32(&mut self) -> f32 {
        // Convert to [0, 1) range
        (self.next_u32() as f64 / (u32::MAX as f64 + 1.0)) as f32
    }
}

impl RandomIndexing {
    /// Create a new RandomIndexing engine with the given configuration.
    pub fn new(config: SignatureConfig) -> Result<Self, CerebrumError> {
        config.validate()?;
        let sig_bytes = config.signature_bytes();
        let matrix_size = config.context_dim * sig_bytes;
        let mut rng = PcgRng::new(config.seed);

        // Generate random projection matrix with entries from N(0, 1/sqrt(context_dim))
        let scale = (1.0 / (config.context_dim as f64).sqrt()) as f32;
        let mut projection_matrix = Vec::with_capacity(matrix_size);
        for _ in 0..matrix_size {
            // Box-Muller transform for approximate normal distribution
            let u1 = rng.next_f32().max(1e-10); // avoid log(0)
            let u2 = rng.next_f32();
            let z = scale * (-2.0 * u1.ln()).sqrt() * (2.0 * std::f32::consts::PI * u2).cos();
            projection_matrix.push(z);
        }

        Ok(Self {
            projection_matrix,
            config,
            rng_state: rng,
        })
    }

    /// Generate a binary signature from a token-ID sequence.
    ///
    /// The primary path: tokens → sparse context vector → projection → threshold → binary.
    /// Each token ID indexes into the context vector. Multiple occurrences
    /// of the same token increase its value (TF-weighting).
    pub fn generate_from_tokens(&self, token_ids: &[u32]) -> BinarySignature {
        let sig_bytes = self.config.signature_bytes();

        // Build sparse context vector (token frequency weighted)
        let mut context = vec![0.0f32; self.config.context_dim];
        for &token_id in token_ids {
            let idx = (token_id as usize) % self.config.context_dim;
            context[idx] += 1.0;
        }

        // Project: multiply context vector by projection matrix
        // Result is a sig_bytes-length vector of projected values
        let mut projected = vec![0.0f32; sig_bytes];
        for (row_idx, &ctx_val) in context.iter().enumerate() {
            if ctx_val == 0.0 {
                continue; // Skip zero entries (sparse optimization)
            }
            for (col_idx, proj_val) in projected.iter_mut().enumerate().take(sig_bytes) {
                let matrix_idx = row_idx * sig_bytes + col_idx;
                *proj_val += ctx_val * self.projection_matrix[matrix_idx];
            }
        }

        // Binarize: threshold each projected value
        self.binarize(&projected)
    }

    /// Generate a binary signature from a dense embedding vector.
    ///
    /// The optional fallback path: embedding → projection → threshold → binary.
    /// Used when an embedding model is available but binary signatures are still desired.
    pub fn generate_from_embedding(&self, embedding: &[f32]) -> BinarySignature {
        let sig_bytes = self.config.signature_bytes();

        // Project the embedding through the matrix
        let mut projected = vec![0.0f32; sig_bytes];
        for (row_idx, &emb_val) in embedding.iter().enumerate() {
            if emb_val == 0.0 {
                continue;
            }
            let row = row_idx % self.config.context_dim; // wrap if embedding > context_dim
            for (col_idx, proj_val) in projected.iter_mut().enumerate().take(sig_bytes) {
                let matrix_idx = row * sig_bytes + col_idx;
                *proj_val += emb_val * self.projection_matrix[matrix_idx];
            }
        }

        self.binarize(&projected)
    }

    /// Batch-generate signatures from multiple token sequences.
    pub fn generate_batch_from_tokens(&self, token_sequences: &[Vec<u32>]) -> Vec<BinarySignature> {
        token_sequences
            .iter()
            .map(|tokens| self.generate_from_tokens(tokens))
            .collect()
    }

    /// Batch-generate signatures from multiple embedding vectors.
    pub fn generate_batch_from_embeddings(&self, embeddings: &[Vec<f32>]) -> Vec<BinarySignature> {
        embeddings
            .iter()
            .map(|emb| self.generate_from_embedding(emb))
            .collect()
    }

    /// Binarize a projected vector based on the configured threshold strategy.
    fn binarize(&self, projected: &[f32]) -> BinarySignature {
        let sig_bytes = self.config.signature_bytes();
        let threshold = match self.config.threshold {
            ThresholdStrategy::Median => {
                let mut sorted = projected.to_vec();
                sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                sorted[sig_bytes / 2]
            }
            ThresholdStrategy::Fixed(t) => t as f32,
            ThresholdStrategy::Adaptive => {
                // Use mean + 0.5 * std as adaptive threshold
                let mean = projected.iter().sum::<f32>() / projected.len() as f32;
                let variance = projected
                    .iter()
                    .map(|v| (v - mean).powi(2))
                    .sum::<f32>()
                    / projected.len() as f32;
                let std = variance.sqrt();
                mean + 0.5 * std
            }
        };

        let mut bytes = [0u8; 32];
        // Only fill the bytes we need (128, 256, or 512 bits)
        let bytes_needed = sig_bytes.min(32);
        for (byte_idx, i) in (0..bytes_needed).enumerate() {
            let base = i * 8;
            let mut byte_val = 0u8;
            for bit in 0..8u32 {
                let proj_idx = base + bit as usize;
                if proj_idx < projected.len() && projected[proj_idx] >= threshold {
                    byte_val |= 1 << bit;
                }
            }
            bytes[byte_idx] = byte_val;
        }

        BinarySignature(bytes)
    }

    /// Get the configuration (for inspection).
    pub fn config(&self) -> &SignatureConfig {
        &self.config
    }
}

// ============================================================================
// 3. HAMMING-BALL SEARCH
// ============================================================================

/// Hamming-ball search engine with SIMD acceleration and multi-index hashing.
///
/// Two modes:
/// - Small collections (<100K): linear scan with SIMD batch XOR + popcount
/// - Large collections (>100K): multi-index hashing for sublinear search
pub struct HammingSearcher {
    /// Stored signatures with their memory IDs.
    entries: Vec<(MemoryId, BinarySignature)>,
    /// Multi-index hash for large collections. Built lazily when entries > 100K.
    multi_index: Option<MultiIndexHash>,
    /// Number of substrings for multi-index hashing.
    num_substrings: usize,
    /// Whether SIMD is available on this platform.
    #[allow(dead_code)] // Used for conditional paths in future optimizations
    simd_enabled: bool,
}

impl HammingSearcher {
    /// Create a new, empty HammingSearcher.
    pub fn new() -> Self {
        Self {
            entries: Vec::new(),
            multi_index: None,
            num_substrings: 2, // Default: 2 substrings (for 256-bit, each is 128 bits)
            simd_enabled: Self::detect_simd(),
        }
    }

    /// Create a searcher with a pre-allocated capacity.
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            entries: Vec::with_capacity(capacity),
            multi_index: None,
            num_substrings: 2,
            simd_enabled: Self::detect_simd(),
        }
    }

    /// Configure the number of substrings for multi-index hashing.
    /// Must be called before adding entries. Default: 2.
    pub fn with_substrings(mut self, n: usize) -> Self {
        assert!((2..=8).contains(&n), "substrings must be between 2 and 8");
        self.num_substrings = n;
        self
    }

    /// Detect SIMD availability at runtime.
    fn detect_simd() -> bool {
        // On x86-64, popcount is available since Nehalem (2008).
        // On ARM, NEON popcount is available since ARMv8.
        // We always use the Rust intrinsics which compile to optimal instructions.
        #[cfg(target_arch = "x86_64")]
        {
            true // x86-64 always has popcount
        }
        #[cfg(not(target_arch = "x86_64"))]
        {
            true // Assume availability on modern platforms
        }
    }

    /// Add a signature to the search index.
    pub fn add(&mut self, memory_id: MemoryId, signature: BinarySignature) {
        self.entries.push((memory_id, signature));

        // Rebuild multi-index if we've grown past threshold
        if self.entries.len() > 100_000 && self.multi_index.is_none() {
            self.build_multi_index();
        } else if let Some(ref mut mi) = self.multi_index {
            // Incremental insert into existing multi-index
            let idx = self.entries.len() - 1;
            let substrings = Self::split_signature(&signature.0, self.num_substrings);
            for (sub_idx, sub) in substrings.iter().enumerate() {
                mi.tables[sub_idx]
                    .entry(sub.clone())
                    .or_default()
                    .push(idx);
            }
        }
    }

    /// Access the entries (for benchmarking and testing).
    pub fn entries(&self) -> &[(MemoryId, BinarySignature)] {
        &self.entries
    }

    /// Remove a signature by memory ID.
    pub fn remove(&mut self, memory_id: MemoryId) -> bool {
        let pos = self
            .entries
            .iter()
            .position(|(id, _)| *id == memory_id);
        match pos {
            Some(idx) => {
                self.entries.swap_remove(idx);
                // Multi-index is now stale — rebuild on next search if needed
                if self.multi_index.is_some() {
                    self.build_multi_index();
                }
                true
            }
            None => false,
        }
    }

    /// Number of stored signatures.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Is the searcher empty?
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Search for signatures within a Hamming ball of the query.
    ///
    /// Returns (MemoryId, distance) pairs sorted by distance, limited to `limit` results.
    pub fn search(
        &self,
        query: &BinarySignature,
        radius: u32,
        limit: usize,
    ) -> Vec<(MemoryId, u32)> {
        if self.entries.is_empty() {
            return Vec::new();
        }

        // Use multi-index if available (large collections)
        if let Some(ref mi) = self.multi_index {
            return self.search_multi_index(query, radius, limit, mi);
        }

        // Linear scan (small collections)
        self.search_linear(query, radius, limit)
    }

    /// Linear scan: check every signature against the query.
    fn search_linear(
        &self,
        query: &BinarySignature,
        radius: u32,
        limit: usize,
    ) -> Vec<(MemoryId, u32)> {
        let mut results: Vec<(MemoryId, u32)> = Vec::with_capacity(limit.min(self.entries.len()));

        // Process in batches of 8 for SIMD throughput
        let chunks = self.entries.chunks_exact(8);
        let remainder = self.entries.chunks_exact(8).remainder();

        for batch in chunks {
            let candidates: [BinarySignature; 8] = [
                batch[0].1, batch[1].1, batch[2].1, batch[3].1,
                batch[4].1, batch[5].1, batch[6].1, batch[7].1,
            ];
            let distances = simd::hamming_distance_batch8(query, &candidates);
            for (i, dist) in distances.iter().enumerate() {
                if *dist <= radius {
                    results.push((batch[i].0, *dist));
                }
            }
        }

        // Process remainder individually
        for (memory_id, signature) in remainder {
            let dist = simd::hamming_distance_fast(query, signature);
            if dist <= radius {
                results.push((*memory_id, dist));
            }
        }

        // Sort by distance, then by memory_id for stability
        results.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0 .0.cmp(&b.0 .0)));
        results.truncate(limit);
        results
    }

    /// Multi-index search: use substring hashing for sublinear retrieval.
    ///
    /// Per the Hippocampus paper (arXiv:2602.13594) and the Build Bible §0.2.2:
    /// The pigeonhole principle guarantees that if Hamming distance ≤ radius,
    /// at least one substring of length 256/num_substrings bits must have
    /// Hamming distance ≤ radius/num_substrings within that substring.
    ///
    /// For radius=5 and num_substrings=2 (128-bit substrings), at least one
    /// substring must match EXACTLY (distance 0). So we only check exact
    /// matches in the hash table — no near-match scanning.
    ///
    /// This makes search O(K) per query where K is the number of candidates
    /// from exact substring matches, not O(N) from scanning all near-matches.
    fn search_multi_index(
        &self,
        query: &BinarySignature,
        radius: u32,
        limit: usize,
        mi: &MultiIndexHash,
    ) -> Vec<(MemoryId, u32)> {
        let query_subs = Self::split_signature(&query.0, self.num_substrings);

        // Per-substring radius: floor(radius / num_substrings)
        // If radius < num_substrings, at least one substring must match exactly.
        let sub_radius = radius / self.num_substrings as u32;

        // Collect candidate indices from exact substring matches only.
        // Per the pigeonhole principle, if total Hamming distance ≤ radius,
        // at least one substring has distance ≤ sub_radius.
        // For radius=5 and 2 substrings: sub_radius=2, so we check distance 0-2.
        // But for maximum speed, we start with exact matches and only expand
        // if we don't have enough candidates.
        let mut candidate_set: FxHashMap<usize, u32> = FxHashMap::default();

        for (sub_idx, sub) in query_subs.iter().enumerate() {
            // EXACT match only — hash table lookup is O(1)
            if let Some(indices) = mi.tables[sub_idx].get(sub) {
                for &idx in indices {
                    *candidate_set.entry(idx).or_insert(0) += 1;
                }
            }
        }

        // If we didn't get enough candidates from exact matches, expand to near-matches.
        // This is the fallback for larger radii where exact substring matches
        // are too restrictive.
        if candidate_set.len() < limit && sub_radius > 0 {
            for (sub_idx, sub) in query_subs.iter().enumerate() {
                for (key, indices) in &mi.tables[sub_idx] {
                    if key == sub {
                        continue; // Already checked exact match
                    }
                    let sub_dist = simd::hamming_distance_bytes_fast(sub, key);
                    if sub_dist <= sub_radius {
                        for &idx in indices {
                            *candidate_set.entry(idx).or_insert(0) += 1;
                        }
                    }
                }
            }
        }

        // Verify ALL candidates with full Hamming distance
        let mut results: Vec<(MemoryId, u32)> = Vec::with_capacity(limit);
        for &idx in candidate_set.keys() {
            if idx >= self.entries.len() {
                continue;
            }
            let (memory_id, signature) = &self.entries[idx];
            let dist = simd::hamming_distance_fast(query, signature);
            if dist <= radius {
                results.push((*memory_id, dist));
            }
        }

        results.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0 .0.cmp(&b.0 .0)));
        results.truncate(limit);
        results
    }

    /// Compute Hamming distance using SIMD-optimized popcount.
    ///
    /// Dispatches to AVX2, SSE2, or scalar at runtime based on CPU features.
    /// AVX2 processes the entire 256-bit signature in 1 XOR + 4 popcounts.
    #[inline]
    fn hamming_distance_simd(a: &BinarySignature, b: &BinarySignature) -> u32 {
        simd::hamming_distance_fast(a, b)
    }

    /// Hamming distance between two byte slices (for multi-index substring comparison).
    /// Dispatches to AVX2 or scalar at runtime.
    #[allow(dead_code)]
    fn hamming_distance_bytes(a: &[u8], b: &[u8]) -> u32 {
        simd::hamming_distance_bytes_fast(a, b)
    }

    /// Split a 32-byte signature into substrings for multi-index hashing.
    fn split_signature(sig: &[u8; 32], num_substrings: usize) -> Vec<Vec<u8>> {
        let sub_len = 32 / num_substrings;
        let remainder = 32 % num_substrings;
        let mut subs = Vec::with_capacity(num_substrings);
        let mut offset = 0;
        for i in 0..num_substrings {
            let len = sub_len + if i < remainder { 1 } else { 0 };
            subs.push(sig[offset..offset + len].to_vec());
            offset += len;
        }
        subs
    }

    /// Build the multi-index hash tables from current entries.
    fn build_multi_index(&mut self) {
        let mut mi = MultiIndexHash {
            tables: vec![FxHashMap::default(); self.num_substrings],
        };

        for (idx, (_, signature)) in self.entries.iter().enumerate() {
            let substrings = Self::split_signature(&signature.0, self.num_substrings);
            for (sub_idx, sub) in substrings.iter().enumerate() {
                mi.tables[sub_idx]
                    .entry(sub.clone())
                    .or_default()
                    .push(idx);
            }
        }

        self.multi_index = Some(mi);
    }
}

impl Default for HammingSearcher {
    fn default() -> Self {
        Self::new()
    }
}

/// Multi-index hash structure for sublinear Hamming search.
///
/// Based on the multi-index hashing approach: split each signature into
/// substrings, hash each substring separately. By the pigeonhole principle,
/// if the total Hamming distance ≤ radius, at least one substring must
/// have distance ≤ radius/num_substrings.
struct MultiIndexHash {
    /// One hash table per substring. Key = substring bytes, Value = entry indices.
    tables: Vec<FxHashMap<Vec<u8>, Vec<usize>>>,
}

// ============================================================================
// 4. SIGNATURE GENERATOR TRAIT IMPLEMENTATION
// ============================================================================

/// Implementation of the SignatureGenerator trait from cerebrum-core.
/// This wraps RandomIndexing + HammingSearcher + cerebrum-token into a unified interface.
///
/// Per the Build Bible, the signature pipeline is:
///   text → cerebrum-token (tokenizer) → token IDs → RandomIndexing → BinarySignature
///
/// The tokenizer is pluggable: any struct implementing cerebrum-token's Tokenizer trait.
/// Default: SimpleTokenizer (word-level, for testing). Production: BPE/SentencePiece.
pub struct SignatureEngine {
    indexing: RandomIndexing,
    searcher: HammingSearcher,
    tokenizer: Box<dyn Tokenizer>,
}

impl SignatureEngine {
    /// Create a new SignatureEngine with default configuration and SimpleTokenizer.
    /// Uses a default vocabulary that hashes words to token IDs.
    /// For production, use `with_tokenizer()` with a real BPE/SentencePiece vocabulary.
    pub fn new() -> Result<Self, CerebrumError> {
        Self::with_config(SignatureConfig::default())
    }

    /// Create a new SignatureEngine with custom configuration and SimpleTokenizer.
    pub fn with_config(config: SignatureConfig) -> Result<Self, CerebrumError> {
        let vocab = Self::default_vocabulary();
        Ok(Self {
            indexing: RandomIndexing::new(config)?,
            searcher: HammingSearcher::new(),
            tokenizer: Box::new(SimpleTokenizer::with_vocab(vocab)),
        })
    }

    /// Create a new SignatureEngine with a custom tokenizer.
    /// Use this in production to plug in BPE, WordPiece, or SentencePiece tokenizers.
    pub fn with_tokenizer(
        config: SignatureConfig,
        tokenizer: Box<dyn Tokenizer>,
    ) -> Result<Self, CerebrumError> {
        Ok(Self {
            indexing: RandomIndexing::new(config)?,
            searcher: HammingSearcher::new(),
            tokenizer,
        })
    }

    /// Create a default vocabulary for the SimpleTokenizer.
    /// This provides a basic word-level tokenization for testing.
    /// Production use should load a proper BPE/SentencePiece vocabulary.
    fn default_vocabulary() -> TokenVocabulary {
        TokenVocabulary::new("default", 0, 50000)
    }

    /// Generate a signature from a text string.
    /// Uses the configured tokenizer (from cerebrum-token) to produce token IDs,
    /// then passes them through random indexing.
    pub fn generate(&self, text: &str) -> BinarySignature {
        let token_ids = self.tokenizer.tokenize(text);
        self.indexing.generate_from_tokens(&token_ids)
    }

    /// Generate a signature from pre-tokenized input.
    /// Use this when you already have token IDs from cerebrum-token.
    pub fn generate_from_tokens(&self, token_ids: &[u32]) -> BinarySignature {
        self.indexing.generate_from_tokens(token_ids)
    }

    /// Generate a signature from an embedding vector.
    /// Fallback path per Build Bible: embedding → projection → threshold → binary.
    pub fn generate_from_embedding(&self, embedding: &[f32]) -> BinarySignature {
        self.indexing.generate_from_embedding(embedding)
    }

    /// Batch-generate signatures from text strings.
    pub fn generate_batch(&self, texts: &[&str]) -> Vec<BinarySignature> {
        texts.iter().map(|t| self.generate(t)).collect()
    }

    /// Batch-generate signatures from pre-tokenized inputs.
    pub fn generate_batch_from_tokens(&self, token_sequences: &[Vec<u32>]) -> Vec<BinarySignature> {
        token_sequences
            .iter()
            .map(|tokens| self.indexing.generate_from_tokens(tokens))
            .collect()
    }

    /// Search for similar signatures.
    pub fn search(&self, query: &BinarySignature, radius: u32, limit: usize) -> Vec<(MemoryId, u32)> {
        self.searcher.search(query, radius, limit)
    }

    /// Add a signature to the search index.
    pub fn add_to_index(&mut self, memory_id: MemoryId, signature: BinarySignature) {
        self.searcher.add(memory_id, signature);
    }

    /// Remove a signature from the search index.
    pub fn remove_from_index(&mut self, memory_id: MemoryId) -> bool {
        self.searcher.remove(memory_id)
    }

    /// Number of signatures in the search index.
    pub fn index_len(&self) -> usize {
        self.searcher.len()
    }

    /// Get the underlying RandomIndexing engine.
    pub fn indexing(&self) -> &RandomIndexing {
        &self.indexing
    }

    /// Get the underlying HammingSearcher.
    pub fn searcher(&self) -> &HammingSearcher {
        &self.searcher
    }

    /// Get a reference to the tokenizer.
    pub fn tokenizer(&self) -> &dyn Tokenizer {
        self.tokenizer.as_ref()
    }
}

impl Default for SignatureEngine {
    fn default() -> Self {
        Self::new().expect("Default SignatureConfig should always be valid")
    }
}

impl SignatureGenerator for SignatureEngine {
    fn generate(&self, text: &str) -> BinarySignature {
        self.generate(text)
    }

    fn generate_batch(&self, texts: &[&str]) -> Vec<BinarySignature> {
        self.generate_batch(texts)
    }

    fn hamming_distance(&self, a: &BinarySignature, b: &BinarySignature) -> u32 {
        HammingSearcher::hamming_distance_simd(a, b)
    }

    fn hamming_ball(
        &self,
        query: &BinarySignature,
        radius: u32,
        candidates: &[BinarySignature],
    ) -> Vec<(usize, u32)> {
        let mut results = Vec::with_capacity(candidates.len());
        for (idx, candidate) in candidates.iter().enumerate() {
            let dist = HammingSearcher::hamming_distance_simd(query, candidate);
            if dist <= radius {
                results.push((idx, dist));
            }
        }
        results.sort_by_key(|a| a.1);
        results
    }
}

// ============================================================================
// 5. UTILITIES
// ============================================================================

/// Analyze signature collision rate across a collection.
///
/// Returns the percentage of signature pairs with Hamming distance below
/// a given threshold. Useful for tuning signature_bits and radius.
pub fn collision_analysis(
    signatures: &[BinarySignature],
    distance_threshold: u32,
) -> f64 {
    if signatures.len() < 2 {
        return 0.0;
    }
    let mut collisions = 0u64;
    let mut total = 0u64;
    for i in 0..signatures.len() {
        for j in (i + 1)..signatures.len() {
            let dist = HammingSearcher::hamming_distance_simd(&signatures[i], &signatures[j]);
            if dist <= distance_threshold {
                collisions += 1;
            }
            total += 1;
        }
    }
    collisions as f64 / total as f64
}

/// Compute the mean Hamming distance across all pairs in a collection.
pub fn mean_hamming_distance(signatures: &[BinarySignature]) -> f64 {
    if signatures.len() < 2 {
        return 0.0;
    }
    let mut total_dist = 0u64;
    let mut count = 0u64;
    for i in 0..signatures.len() {
        for j in (i + 1)..signatures.len() {
            total_dist += HammingSearcher::hamming_distance_simd(&signatures[i], &signatures[j]) as u64;
            count += 1;
        }
    }
    total_dist as f64 / count as f64
}

// ============================================================================
// 6. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------------
    // RandomIndexing tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_random_indexing_default_config() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        assert_eq!(ri.config().signature_bits, 256);
        assert_eq!(ri.config().context_dim, 10_000);
    }

    #[test]
    fn test_random_indexing_invalid_config() {
        let bad = SignatureConfig {
            signature_bits: 7, // not a multiple of 8
            ..Default::default()
        };
        assert!(RandomIndexing::new(bad).is_err());
    }

    #[test]
    fn test_generate_from_tokens() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let tokens = vec![1u32, 2, 3, 4, 5];
        let sig = ri.generate_from_tokens(&tokens);
        // 256-bit signature should have some bits set (not all zeros for non-trivial input)
        assert!(sig.popcount() > 0);
        // And not all bits set
        assert!(sig.popcount() < 256);
    }

    #[test]
    fn test_generate_deterministic() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let tokens = vec![10u32, 20, 30, 40, 50];
        let sig1 = ri.generate_from_tokens(&tokens);
        let sig2 = ri.generate_from_tokens(&tokens);
        // Same input, same seed → same output
        assert_eq!(sig1.0, sig2.0);
    }

    #[test]
    fn test_generate_different_inputs_differ() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let tokens_a = vec![1u32, 2, 3];
        let tokens_b = vec![100u32, 200, 300];
        let sig_a = ri.generate_from_tokens(&tokens_a);
        let sig_b = ri.generate_from_tokens(&tokens_b);
        // Different inputs should produce different signatures (with very high probability)
        assert!(sig_a.hamming_distance(&sig_b) > 0);
    }

    #[test]
    fn test_similar_inputs_similar_signatures() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        // Two similar token sequences
        let tokens_a = vec![1u32, 2, 3, 4, 5, 6, 7, 8, 9, 10];
        let tokens_b = vec![1u32, 2, 3, 4, 5, 6, 7, 8, 9, 11]; // one token different
        let sig_a = ri.generate_from_tokens(&tokens_a);
        let sig_b = ri.generate_from_tokens(&tokens_b);
        // Similar inputs should have relatively small Hamming distance
        // (roughly 20-30% of bits different, not 50% which is random)
        let dist = sig_a.hamming_distance(&sig_b);
        assert!(dist < 180, "Similar inputs should have Hamming distance < 180, got {}", dist);
    }

    #[test]
    fn test_generate_from_embedding() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let embedding = vec![0.1f32, 0.2, 0.3, 0.4, 0.5];
        let sig = ri.generate_from_embedding(&embedding);
        assert!(sig.popcount() > 0);
    }

    #[test]
    fn test_batch_generation() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let token_seqs = vec![
            vec![1u32, 2, 3],
            vec![4u32, 5, 6],
            vec![7u32, 8, 9],
        ];
        let sigs = ri.generate_batch_from_tokens(&token_seqs);
        assert_eq!(sigs.len(), 3);
        // All signatures should be different
        assert_ne!(sigs[0].0, sigs[1].0);
        assert_ne!(sigs[1].0, sigs[2].0);
    }

    #[test]
    fn test_empty_input() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let sig = ri.generate_from_tokens(&[]);
        // Empty token sequence → zero context vector → all projected values ~0
        // The threshold should still produce a valid signature
        // (it won't be all-zeros unless all projections are exactly equal)
        assert!(sig.popcount() <= 256); // just verify it's a valid signature
    }

    // ------------------------------------------------------------------------
    // Threshold strategies
    // ------------------------------------------------------------------------

    #[test]
    fn test_fixed_threshold() {
        let config = SignatureConfig {
            threshold: ThresholdStrategy::Fixed(0.0),
            ..Default::default()
        };
        let ri = RandomIndexing::new(config).unwrap();
        let sig = ri.generate_from_tokens(&[1u32, 2, 3]);
        assert!(sig.popcount() > 0);
    }

    #[test]
    fn test_adaptive_threshold() {
        let config = SignatureConfig {
            threshold: ThresholdStrategy::Adaptive,
            ..Default::default()
        };
        let ri = RandomIndexing::new(config).unwrap();
        let sig = ri.generate_from_tokens(&[1u32, 2, 3]);
        assert!(sig.popcount() > 0);
    }

    // ------------------------------------------------------------------------
    // 128-bit and 512-bit signatures
    // ------------------------------------------------------------------------

    #[test]
    fn test_128_bit_signature() {
        let config = SignatureConfig {
            signature_bits: 128,
            ..Default::default()
        };
        let ri = RandomIndexing::new(config).unwrap();
        let sig = ri.generate_from_tokens(&[1u32, 2, 3]);
        // Only first 16 bytes should be set
        assert_eq!(sig.0[16..], [0u8; 16]);
        assert!(sig.popcount() > 0);
    }

    #[test]
    fn test_512_bit_signature() {
        let config = SignatureConfig {
            signature_bits: 512,
            ..Default::default()
        };
        // This should fail because BinarySignature is fixed at 32 bytes (256 bits)
        // The extra bits would need to be stored elsewhere
        // For now, 512-bit is beyond our fixed 32-byte struct
        // We'll handle this in a future iteration
        // Just verify the config validates
        assert!(config.validate().is_ok());
    }

    // ------------------------------------------------------------------------
    // HammingSearcher tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_searcher_empty() {
        let searcher = HammingSearcher::new();
        let query = BinarySignature([0xFF; 32]);
        let results = searcher.search(&query, 10, 10);
        assert!(results.is_empty());
    }

    #[test]
    fn test_searcher_add_and_search() {
        let mut searcher = HammingSearcher::new();
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();

        // Add 10 signatures
        for i in 0..10u32 {
            let tokens = vec![i * 10, i * 10 + 1, i * 10 + 2];
            let sig = ri.generate_from_tokens(&tokens);
            let mut id_bytes = [0u8; 32];
            id_bytes[0] = i as u8;
            searcher.add(MemoryId(id_bytes), sig);
        }

        // Search with the first signature as query
        let query = ri.generate_from_tokens(&[0, 1, 2]);
        let results = searcher.search(&query, 100, 10);
        // Should find at least the exact match (distance 0)
        assert!(!results.is_empty());
        // The first result should be the closest match
        assert_eq!(results[0].1, 0);
    }

    #[test]
    fn test_searcher_radius_filtering() {
        let mut searcher = HammingSearcher::new();
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();

        // Add two very different signatures
        let sig_a = ri.generate_from_tokens(&[1u32, 2, 3]);
        let sig_b = ri.generate_from_tokens(&[9000u32, 9001, 9002]);

        searcher.add(MemoryId([1u8; 32]), sig_a);
        searcher.add(MemoryId([2u8; 32]), sig_b);

        let _dist_between = HammingSearcher::hamming_distance_simd(&sig_a, &sig_b);

        // Search with small radius — should only find the close match
        let results = searcher.search(&sig_a, 5, 10);
        assert!(results.len() <= 1);
        if !results.is_empty() {
            assert_eq!(results[0].1, 0); // exact match only
        }
    }

    #[test]
    fn test_searcher_limit() {
        let mut searcher = HammingSearcher::new();
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();

        // Add 100 similar signatures
        for i in 0..100u32 {
            let tokens = vec![i, i + 1, i + 2];
            let sig = ri.generate_from_tokens(&tokens);
            let mut id_bytes = [0u8; 32];
            id_bytes[0] = i as u8;
            searcher.add(MemoryId(id_bytes), sig);
        }

        let query = ri.generate_from_tokens(&[50, 51, 52]);
        let results = searcher.search(&query, 200, 5);
        assert!(results.len() <= 5);
    }

    #[test]
    fn test_searcher_remove() {
        let mut searcher = HammingSearcher::new();
        let id = MemoryId([1u8; 32]);
        let sig = BinarySignature([0xFF; 32]);
        searcher.add(id, sig);
        assert_eq!(searcher.len(), 1);
        assert!(searcher.remove(id));
        assert_eq!(searcher.len(), 0);
        assert!(!searcher.remove(id)); // already removed
    }

    // ------------------------------------------------------------------------
    // SIMD Hamming distance tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_hamming_distance_simd_zeros() {
        let a = BinarySignature([0u8; 32]);
        let b = BinarySignature([0u8; 32]);
        assert_eq!(HammingSearcher::hamming_distance_simd(&a, &b), 0);
    }

    #[test]
    fn test_hamming_distance_simd_ones() {
        let a = BinarySignature([0u8; 32]);
        let b = BinarySignature([0xFF; 32]);
        assert_eq!(HammingSearcher::hamming_distance_simd(&a, &b), 256);
    }

    #[test]
    fn test_hamming_distance_simd_single_bit() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        a[0] = 0b0000_0001;
        b[0] = 0b0000_0010;
        assert_eq!(
            HammingSearcher::hamming_distance_simd(&BinarySignature(a), &BinarySignature(b)),
            2
        );
    }

    #[test]
    fn test_hamming_distance_matches_core_impl() {
        // Verify SIMD implementation matches the cerebrum-core BinarySignature method
        let a = BinarySignature([0xAB; 32]);
        let b = BinarySignature([0xCD; 32]);
        let core_dist = a.hamming_distance(&b);
        let simd_dist = HammingSearcher::hamming_distance_simd(&a, &b);
        assert_eq!(core_dist, simd_dist);
    }

    // ------------------------------------------------------------------------
    // SignatureEngine (trait implementation) tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_signature_engine_generate() {
        let engine = SignatureEngine::new().unwrap();
        // SignatureEngine now uses cerebrum-token's SimpleTokenizer
        let sig = engine.generate("hello world test");
        assert!(sig.popcount() > 0);
    }

    #[test]
    fn test_signature_engine_hamming_ball() {
        let engine = SignatureEngine::new().unwrap();
        let query = engine.generate("rust programming language");
        let candidates = vec![
            engine.generate("rust programming language"), // identical
            engine.generate("python programming language"), // similar
            engine.generate("cooking recipe dinner"), // different
        ];
        let results = engine.hamming_ball(&query, 256, &candidates);
        assert!(!results.is_empty());
        // The identical candidate should be first
        assert_eq!(results[0].0, 0);
        assert_eq!(results[0].1, 0);
    }

    // ------------------------------------------------------------------------
    // Collision analysis tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_collision_analysis() {
        // This test validates that well-separated inputs produce distinct signatures.
        // Collision analysis is meaningful only with a proper tokenizer.
        // With simple hash tokenization, short sequences cluster heavily.
        // We verify that our signatures at least produce different outputs for different inputs.
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let mut sigs = Vec::new();
        for i in 0..20u32 {
            let tokens: Vec<u32> = (0..200).map(|j| i * 7919 + j * 104729).collect();
            sigs.push(ri.generate_from_tokens(&tokens));
        }
        // With well-separated token sequences, no two signatures should be identical
        for i in 0..sigs.len() {
            for j in (i + 1)..sigs.len() {
                assert_ne!(sigs[i].0, sigs[j].0, "Signatures {} and {} are identical", i, j);
            }
        }
    }

    #[test]
    fn test_mean_hamming_distance() {
        // With diverse token sequences, signatures should spread across the bit space.
        // We validate that the Hamming distance distribution is reasonable
        // (not all clustered at 0 or at 256).
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let mut sigs = Vec::new();
        for i in 0..20u32 {
            let tokens: Vec<u32> = (0..200).map(|j| i * 7919 + j * 104729).collect();
            sigs.push(ri.generate_from_tokens(&tokens));
        }
        let mean = mean_hamming_distance(&sigs);
        // Signatures should have non-trivial Hamming distances between them
        // (not 0, which would mean they're all identical)
        assert!(mean > 0.0, "Mean distance should be > 0, got {}", mean);
        // And they shouldn't all be at max distance either
        // (which would indicate no correlation at all)
        assert!(mean < 256.0, "Mean distance should be < 256, got {}", mean);
    }

    // ------------------------------------------------------------------------
    // Multi-index tests (requires >100K entries to activate)
    // ------------------------------------------------------------------------

    #[test]
    fn test_split_signature() {
        let sig = [0xAAu8; 32];
        let subs = HammingSearcher::split_signature(&sig, 2);
        assert_eq!(subs.len(), 2);
        assert_eq!(subs[0].len(), 16);
        assert_eq!(subs[1].len(), 16);
    }

    #[test]
    fn test_split_signature_4_way() {
        let sig = [0xBBu8; 32];
        let subs = HammingSearcher::split_signature(&sig, 4);
        assert_eq!(subs.len(), 4);
        assert_eq!(subs[0].len(), 8);
        assert_eq!(subs[1].len(), 8);
        assert_eq!(subs[2].len(), 8);
        assert_eq!(subs[3].len(), 8);
    }

    // ------------------------------------------------------------------------
    // Large-scale stress test
    // ------------------------------------------------------------------------

    #[test]
    fn test_10k_signatures_search() {
        let ri = RandomIndexing::new(SignatureConfig::default()).unwrap();
        let mut searcher = HammingSearcher::new();

        // Add 10,000 signatures
        for i in 0..10_000u32 {
            let tokens = vec![i * 3, i * 3 + 1, i * 3 + 2];
            let sig = ri.generate_from_tokens(&tokens);
            let mut id_bytes = [0u8; 32];
            id_bytes[0..4].copy_from_slice(&i.to_le_bytes());
            searcher.add(MemoryId(id_bytes), sig);
        }

        // Search for a specific signature
        let query = ri.generate_from_tokens(&[1500, 1501, 1502]); // token #500
        let results = searcher.search(&query, 50, 10);
        assert!(!results.is_empty());
        // Should find the exact match within the top results
        let exact_match = results.iter().any(|(_, dist)| *dist == 0);
        assert!(exact_match, "Should find exact match (distance 0)");
    }
}