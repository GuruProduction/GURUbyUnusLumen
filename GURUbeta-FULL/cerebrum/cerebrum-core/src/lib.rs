//! Cerebrum Core — Foundation types, traits, error types, and protocol frames.
//! Phase 0.2.1 — Foundation of the AGI memory brain.
//!
//! This crate defines the core types, traits, errors, and protocol frames that
//! every other Cerebrum crate depends on. It is intentionally thin — no logic,
//! only contracts and data structures.

use std::fmt;
use std::path::Path;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

// ============================================================================
// 1. CORE IDENTIFIERS
// ============================================================================

/// 256-bit UUID for each memory entry.
///
/// Fixed-size identifier used across all Cerebrum subsystems. The 32-byte
/// representation provides 2^256 possible values — effectively infinite for
/// any practical workload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MemoryId(pub [u8; 32]);

impl MemoryId {
    /// Generate a new random MemoryId using two UUIDv4s concatenated.
    /// This produces a full 256-bit identifier with 256 bits of entropy.
    pub fn new() -> Self {
        let uuid1 = Uuid::new_v4();
        let uuid2 = Uuid::new_v4();
        let mut bytes = [0u8; 32];
        bytes[..16].copy_from_slice(uuid1.as_bytes());
        bytes[16..].copy_from_slice(uuid2.as_bytes());
        MemoryId(bytes)
    }
}

impl Default for MemoryId {
    fn default() -> Self {
        Self::new()
    }
}

impl fmt::Display for MemoryId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in &self.0 {
            write!(f, "{:02x}", byte)?;
        }
        Ok(())
    }
}

/// 256-bit binary signature for compressed memory representation.
///
/// Generated via random indexing (arXiv:2602.13594). Each bit represents
/// a dimension projection threshold crossing. Configurable sizes: 128, 256, 512 bits.
/// The default is 256 bits (32 bytes).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct BinarySignature(pub [u8; 32]);

impl BinarySignature {
    /// Create a zeroed signature.
    pub fn zeros() -> Self {
        BinarySignature([0u8; 32])
    }

    /// Create a signature from a byte slice. Panics if slice length != 32.
    pub fn from_bytes(bytes: &[u8]) -> Self {
        assert_eq!(bytes.len(), 32, "BinarySignature requires exactly 32 bytes");
        let mut arr = [0u8; 32];
        arr.copy_from_slice(bytes);
        BinarySignature(arr)
    }

    /// XOR two signatures, returning a new signature.
    #[must_use]
    pub fn xor(&self, other: &Self) -> Self {
        let mut result = [0u8; 32];
        for (r, (a, b)) in result.iter_mut().zip(self.0.iter().zip(other.0.iter())) {
            *r = a ^ b;
        }
        BinarySignature(result)
    }

    /// Count the number of set bits (population count / Hamming weight).
    #[must_use]
    pub fn popcount(&self) -> u32 {
        self.0.iter().map(|b| b.count_ones()).sum()
    }

    /// Compute Hamming distance to another signature.
    /// This is the popcount of the XOR — the number of bit positions that differ.
    #[must_use]
    pub fn hamming_distance(&self, other: &Self) -> u32 {
        self.xor(other).popcount()
    }
}

impl Default for BinarySignature {
    fn default() -> Self {
        Self::zeros()
    }
}

impl fmt::Display for BinarySignature {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in &self.0 {
            write!(f, "{:02x}", byte)?;
        }
        Ok(())
    }
}

/// Compressed token-ID sequence.
///
/// Stores the tokenized representation of a memory entry.
///
/// The `tokens` field holds raw (uncompressed) token IDs. For storage and
/// transmission, the cerebrum-token crate provides `CompressedTokenSequence`
/// which applies delta + zigzag + varint encoding to reduce size. This type
/// is the in-memory representation used throughout the Cerebrum subsystems.
///
/// The `vocabulary_id` selects which tokenization vocabulary was used
/// (e.g., 0 = default, 1 = code-specific).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TokenSequence {
    pub vocabulary_id: u32,
    /// Raw (uncompressed) token IDs. Use cerebrum-token for delta+varint compression.
    pub tokens: Vec<u32>,
}

impl TokenSequence {
    /// Create a new TokenSequence from raw token IDs and a vocabulary ID.
    pub fn new(vocabulary_id: u32, tokens: Vec<u32>) -> Self {
        Self { vocabulary_id, tokens }
    }

    /// Create a TokenSequence from a slice of token IDs using the default
    /// vocabulary (ID 0).
    ///
    /// # Examples
    /// ```
    /// use cerebrum_core::TokenSequence;
    ///
    /// let ts = TokenSequence::from_tokens(&[1, 2, 3]);
    /// assert_eq!(ts.vocabulary_id, 0);
    /// assert_eq!(ts.len(), 3);
    /// ```
    pub fn from_tokens(tokens: &[u32]) -> Self {
        Self {
            vocabulary_id: 0,
            tokens: tokens.to_vec(),
        }
    }

    /// Number of tokens in the sequence.
    #[must_use]
    pub fn len(&self) -> usize {
        self.tokens.len()
    }

    /// Whether the sequence contains no tokens.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.tokens.is_empty()
    }

    /// Estimated size in bytes if stored as raw u32 values (4 bytes each).
    /// This is the uncompressed size. The cerebrum-token crate can compress
    /// this further via delta + varint encoding.
    #[must_use]
    pub fn raw_byte_size(&self) -> usize {
        self.tokens.len() * 4
    }

    /// Get a reference to the raw token IDs.
    #[must_use]
    pub fn as_slice(&self) -> &[u32] {
        &self.tokens
    }
}


// ============================================================================
// 2. CURATION OPERATIONS (Layer 4: Agent-Native Curation)
// ============================================================================

/// A fully curated memory entry in the Context Tree.
///
/// Domain → Topic → Subtopic → Entry. Each entry carries relations,
/// provenance, rationale, and lifecycle metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ContextEntry {
    pub memory_id: MemoryId,
    pub domain: String,
    pub topic: String,
    pub subtopic: String,
    pub content: String,
    pub relations: Vec<MemoryId>,
    pub provenance: String,
    pub rationale: String,
    pub created_at: DateTime<Utc>,
    pub modified_at: DateTime<Utc>,
}

impl Default for ContextEntry {
    fn default() -> Self {
        let now = Utc::now();
        Self {
            memory_id: MemoryId::new(),
            domain: String::new(),
            topic: String::new(),
            subtopic: String::new(),
            content: String::new(),
            relations: Vec::new(),
            provenance: String::new(),
            rationale: String::new(),
            created_at: now,
            modified_at: now,
        }
    }
}

impl ContextEntry {
    /// Create a new ContextEntry with the given hierarchy and content.
    /// Relations, provenance, and rationale are initialized to empty.
    /// Timestamps are set to the current time.
    ///
    /// # Examples
    /// ```
    /// use cerebrum_core::{ContextEntry, MemoryId};
    ///
    /// let entry = ContextEntry::new(
    ///     MemoryId::new(),
    ///     "preferences",
    ///     "ui",
    ///     "theme",
    ///     "User prefers dark mode",
    /// );
    /// assert_eq!(entry.domain, "preferences");
    /// assert_eq!(entry.content, "User prefers dark mode");
    /// assert!(entry.relations.is_empty());
    /// ```
    pub fn new(
        memory_id: MemoryId,
        domain: impl Into<String>,
        topic: impl Into<String>,
        subtopic: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            memory_id,
            domain: domain.into(),
            topic: topic.into(),
            subtopic: subtopic.into(),
            content: content.into(),
            relations: Vec::new(),
            provenance: String::new(),
            rationale: String::new(),
            created_at: now,
            modified_at: now,
        }
    }
}

/// Partial update to a ContextEntry.
///
/// All fields are optional — only specified fields are updated.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct PartialEntry {
    pub domain: Option<String>,
    pub topic: Option<String>,
    pub subtopic: Option<String>,
    pub content: Option<String>,
    pub relations: Option<Vec<MemoryId>>,
    pub provenance: Option<String>,
    pub rationale: Option<String>,
}

/// Status of a curation operation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CurateStatus {
    Success,
    Created,
    Updated,
    Merged,
    Deleted,
    Failed,
    Pending,
}

/// The five curation operations the agent can perform.
///
/// Every operation includes a reason — full auditability.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum CurateOp {
    /// Add a new entry to the Context Tree.
    Add { entry: ContextEntry, reason: String },
    /// Update an existing entry with partial changes.
    Update {
        memory_id: MemoryId,
        changes: PartialEntry,
        reason: String,
    },
    /// Insert or update — creates if absent, updates if present.
    Upsert { entry: ContextEntry, reason: String },
    /// Merge multiple entries into a single target entry.
    Merge {
        source_ids: Vec<MemoryId>,
        target: ContextEntry,
        reason: String,
    },
    /// Delete an entry (archive — Constitution §5).
    Delete { memory_id: MemoryId, reason: String },
}

/// Result of a curation operation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CurateResult {
    pub memory_id: MemoryId,
    pub status: CurateStatus,
    pub detail: String,
}

// ============================================================================
// 3. QUERY TYPES (Layer 1: Multi-Graph Views)
// ============================================================================

/// Classification of what kind of information the query seeks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum QueryType {
    /// "What relates to X?"
    Semantic,
    /// "When did X happen?"
    Temporal,
    /// "Why did X cause Y?"
    Causal,
    /// "Who was involved in X?"
    Entity,
    /// Multi-dimensional query combining multiple graph views.
    Composite,
}

/// Token budget for a query — limits how much context can be consumed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct TokenBudget {
    pub max_tokens: usize,
    pub used_tokens: usize,
}

impl TokenBudget {
    pub fn new(max_tokens: usize) -> Self {
        Self {
            max_tokens,
            used_tokens: 0,
        }
    }

    #[must_use]
    pub fn remaining(&self) -> usize {
        self.max_tokens.saturating_sub(self.used_tokens)
    }

    pub fn consume(&mut self, tokens: usize) -> Result<(), CerebrumError> {
        if self.used_tokens + tokens > self.max_tokens {
            return Err(CerebrumError::BudgetExceeded);
        }
        self.used_tokens += tokens;
        Ok(())
    }
}

impl Default for TokenBudget {
    fn default() -> Self {
        Self::new(4096)
    }
}

/// A structured query to Cerebrum.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Query {
    pub query_type: QueryType,
    pub text: String,
    pub budget: TokenBudget,
}

// ============================================================================
// 4. DEREFERENCING (Layer 2: MemexRL)
// ============================================================================

/// Access level for retrieving a memory artifact.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DereferenceLevel {
    /// Index only — compact summary.
    Shallow,
    /// Full artifact — complete reconstruction.
    Deep,
    /// Specific sections of the artifact.
    Partial { sections: Vec<String> },
}

impl std::hash::Hash for DereferenceLevel {
    fn hash<H: std::hash::Hasher>(&self, state: &mut H) {
        match self {
            DereferenceLevel::Shallow => 0u8.hash(state),
            DereferenceLevel::Deep => 1u8.hash(state),
            DereferenceLevel::Partial { sections } => {
                2u8.hash(state);
                sections.len().hash(state);
                for s in sections {
                    s.hash(state);
                }
            }
        }
    }
}

// ============================================================================
// 5. GRAPH EDGES (Layer 1: MAGMA)
// ============================================================================

/// Types of edges in the multi-graph views.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EdgeType {
    Semantic,
    Temporal,
    Causal,
    Entity,
}

/// A weighted, typed edge in the memory graph.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GraphEdge {
    pub edge_type: EdgeType,
    pub source: MemoryId,
    pub target: MemoryId,
    pub weight: f32,
    pub timestamp: DateTime<Utc>,
}

/// Strategy for traversing the memory graph.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TraverseStrategy {
    /// Breadth-first — explores all neighbors at current depth before deeper levels.
    Bfs,
    /// Depth-first — follows one path to its end before backtracking.
    Dfs,
    /// Follow highest-weight edges first (greedy).
    Weighted,
    /// Temporal ordering — follow edges forward/backward in time.
    Temporal,
}

// ============================================================================
// 6. EVENT SOURCING (System B: CortexDB pattern)
// ============================================================================

/// Status of async enrichment for an event.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EnrichmentStatus {
    /// Raw event, not yet enriched.
    Pending,
    /// Facts and knowledge graph extracted.
    Enriched,
    /// Enrichment failed, will be retried.
    Failed,
    /// Enrichment permanently failed (too many retries).
    DeadLetter,
}

/// An immutable event in the event-sourced storage layer.
///
/// Raw events are stored as-is. Async enrichment extracts facts
/// and knowledge graph entries. Every mutation is recorded — full audit trail.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Event {
    pub event_id: Uuid,
    pub event_type: String,
    pub memory_id: MemoryId,
    pub payload: serde_json::Value,
    pub timestamp: DateTime<Utc>,
    pub enrichment_status: EnrichmentStatus,
}

// ============================================================================
// 7. CONFIDENCE ROUTING
// ============================================================================

/// Confidence level for routing decisions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConfidenceLevel {
    High,
    Medium,
    Low,
}

// ============================================================================
// 8. EPISODIC CONTEXT (Layer 4: full context of discovery)
// ============================================================================

/// Episodic memory — the full context of how a memory was discovered.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Episode {
    pub memory_id: MemoryId,
    /// What file, what conversation, what notification.
    pub source: String,
    /// What led to this insight.
    pub trigger: String,
    /// Surrounding context.
    pub context: String,
    pub discovered_at: DateTime<Utc>,
}

// ============================================================================
// 9. PREFETCH HINTS
// ============================================================================

/// Hint from the query planner about what memories will likely be needed next.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrefetchHint {
    pub task_type: String,
    pub likely_needed: Vec<MemoryId>,
}

// ============================================================================
// 10. PROTOCOL FRAME TYPES (Track G uses these)
// ============================================================================

/// Protocol frames for the Cerebrum binary protocol.
///
/// These are the messages exchanged between the engine and Cerebrum.
/// The Curate variant is boxed to avoid a large enum variant, since
/// CurateOp::Merge can contain a full ContextEntry plus a Vec of MemoryIds.
///
/// Frame type constants are defined as associated constants on the Frame type
/// itself, so the server and any client share the same source of truth rather
/// than maintaining separate magic numbers.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum Frame {
    /// Submit a query to Cerebrum.
    Query(Query),
    /// Perform a curation operation.
    Curate(Box<CurateOp>),
    /// Retrieve a memory at a given dereference level.
    Retrieve {
        memory_id: MemoryId,
        level: DereferenceLevel,
    },
    /// Search by binary signature within a Hamming ball.
    Search {
        signature: BinarySignature,
        radius: u32,
        limit: usize,
    },
    /// Traverse the graph from a starting node.
    GraphTraverse {
        start: MemoryId,
        edge_type: EdgeType,
        depth: usize,
    },
    /// Trigger memory consolidation (dream phase).
    Consolidate,
    /// Prefetch likely-needed memories.
    Prefetch(PrefetchHint),
}

/// Frame type byte constants for the binary protocol.
///
/// These are the single-byte identifiers used in the 9-byte frame header
/// to indicate which frame variant follows in the payload. Both the server
/// and client use these constants, ensuring they never drift.
pub mod frame_types {
    /// Query frame type byte.
    pub const QUERY: u8 = 0x01;
    /// Curate frame type byte.
    pub const CURATE: u8 = 0x02;
    /// Retrieve frame type byte.
    pub const RETRIEVE: u8 = 0x03;
    /// Search frame type byte.
    pub const SEARCH: u8 = 0x04;
    /// GraphTraverse frame type byte.
    pub const GRAPH_TRAVERSE: u8 = 0x05;
    /// Consolidate frame type byte.
    pub const CONSOLIDATE: u8 = 0x06;
    /// Prefetch frame type byte.
    pub const PREFETCH: u8 = 0x07;
    /// Response frame type byte (server-to-client).
    pub const RESPONSE: u8 = 0x80;
    /// Error frame type byte (server-to-client).
    pub const ERROR: u8 = 0x81;

    /// All valid request frame type bytes.
    pub const ALL_REQUEST: [u8; 7] = [
        QUERY, CURATE, RETRIEVE, SEARCH, GRAPH_TRAVERSE, CONSOLIDATE, PREFETCH,
    ];

    /// All valid response frame type bytes.
    pub const ALL_RESPONSE: [u8; 2] = [RESPONSE, ERROR];

    /// All valid frame type bytes (requests + responses).
    pub const ALL: [u8; 9] = [
        QUERY, CURATE, RETRIEVE, SEARCH, GRAPH_TRAVERSE, CONSOLIDATE, PREFETCH,
        RESPONSE, ERROR,
    ];
}

/// Error returned when a frame type byte does not correspond to a known frame.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UnknownFrameTypeError {
    /// The unrecognized frame type byte that caused this error.
    pub frame_type: u8,
}

impl fmt::Display for UnknownFrameTypeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "unknown frame type: 0x{:02x}", self.frame_type)
    }
}

impl std::error::Error for UnknownFrameTypeError {}

/// A frame type tag — identifies which Frame variant a payload contains
/// without carrying the payload data itself. Used for protocol dispatch
/// where the header byte determines how to deserialize the payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameTag {
    /// Query frame — submit a structured query to Cerebrum.
    Query,
    /// Curate frame — perform a curation operation (add/update/upsert/merge/delete).
    Curate,
    /// Retrieve frame — fetch a memory at a specific dereference level.
    Retrieve,
    /// Search frame — Hamming-ball search over binary signatures.
    Search,
    /// GraphTraverse frame — traverse the memory graph from a starting node.
    GraphTraverse,
    /// Consolidate frame — trigger dream-phase memory consolidation.
    Consolidate,
    /// Prefetch frame — warm the hot cache for anticipated queries.
    Prefetch,
    /// Response frame — server-to-client response carrying query results.
    Response,
    /// Error frame — server-to-client error indicating a failed operation.
    ErrorFrame,
}

impl From<&Frame> for u8 {
    fn from(frame: &Frame) -> u8 {
        match frame {
            Frame::Query(_) => frame_types::QUERY,
            Frame::Curate(_) => frame_types::CURATE,
            Frame::Retrieve { .. } => frame_types::RETRIEVE,
            Frame::Search { .. } => frame_types::SEARCH,
            Frame::GraphTraverse { .. } => frame_types::GRAPH_TRAVERSE,
            Frame::Consolidate => frame_types::CONSOLIDATE,
            Frame::Prefetch(_) => frame_types::PREFETCH,
        }
    }
}

impl From<FrameTag> for u8 {
    fn from(tag: FrameTag) -> u8 {
        match tag {
            FrameTag::Query => frame_types::QUERY,
            FrameTag::Curate => frame_types::CURATE,
            FrameTag::Retrieve => frame_types::RETRIEVE,
            FrameTag::Search => frame_types::SEARCH,
            FrameTag::GraphTraverse => frame_types::GRAPH_TRAVERSE,
            FrameTag::Consolidate => frame_types::CONSOLIDATE,
            FrameTag::Prefetch => frame_types::PREFETCH,
            FrameTag::Response => frame_types::RESPONSE,
            FrameTag::ErrorFrame => frame_types::ERROR,
        }
    }
}

impl TryFrom<u8> for FrameTag {
    type Error = UnknownFrameTypeError;

    /// Convert a protocol byte into a FrameTag.
    ///
    /// # Examples
    /// ```
    /// use cerebrum_core::{FrameTag, frame_types};
    ///
    /// let tag = FrameTag::try_from(frame_types::QUERY).unwrap();
    /// assert_eq!(tag, FrameTag::Query);
    ///
    /// let invalid = FrameTag::try_from(0xFF);
    /// assert!(invalid.is_err());
    /// ```
    fn try_from(byte: u8) -> Result<Self, Self::Error> {
        match byte {
            frame_types::QUERY => Ok(FrameTag::Query),
            frame_types::CURATE => Ok(FrameTag::Curate),
            frame_types::RETRIEVE => Ok(FrameTag::Retrieve),
            frame_types::SEARCH => Ok(FrameTag::Search),
            frame_types::GRAPH_TRAVERSE => Ok(FrameTag::GraphTraverse),
            frame_types::CONSOLIDATE => Ok(FrameTag::Consolidate),
            frame_types::PREFETCH => Ok(FrameTag::Prefetch),
            frame_types::RESPONSE => Ok(FrameTag::Response),
            frame_types::ERROR => Ok(FrameTag::ErrorFrame),
            _ => Err(UnknownFrameTypeError { frame_type: byte }),
        }
    }
}

// ============================================================================
// 11. ERROR TYPES
// ============================================================================

/// Unified error type for all Cerebrum operations.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum CerebrumError {
    /// Error from the Dynamic Wavelet Matrix subsystem.
    DwmError(String),
    /// Error from the signature generation subsystem.
    SignatureError(String),
    /// Error from the token compression subsystem.
    TokenError(String),
    /// Error from the graph traversal subsystem.
    GraphError(String),
    /// Error from the storage/persistence subsystem.
    StorageError(String),
    /// Error from the query planning/routing subsystem.
    QueryError(String),
    /// Requested memory was not found.
    NotFound(MemoryId),
    /// Token budget exceeded.
    BudgetExceeded,
}

impl fmt::Display for CerebrumError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CerebrumError::DwmError(msg) => write!(f, "DWM error: {}", msg),
            CerebrumError::SignatureError(msg) => write!(f, "Signature error: {}", msg),
            CerebrumError::TokenError(msg) => write!(f, "Token error: {}", msg),
            CerebrumError::GraphError(msg) => write!(f, "Graph error: {}", msg),
            CerebrumError::StorageError(msg) => write!(f, "Storage error: {}", msg),
            CerebrumError::QueryError(msg) => write!(f, "Query error: {}", msg),
            CerebrumError::NotFound(id) => write!(f, "Memory not found: {}", id),
            CerebrumError::BudgetExceeded => write!(f, "Token budget exceeded"),
        }
    }
}

impl std::error::Error for CerebrumError {}

// ============================================================================
// 12. TRAITS
// ============================================================================

/// Generates binary signatures from text via random indexing.
///
/// Configurable signature sizes: 128, 256, 512 bits.
/// Primary path: token-ID sequences → random projection → threshold → binary.
/// Optional path: dense embeddings → random projection → binary.
pub trait SignatureGenerator: Send + Sync {
    /// Generate a binary signature from a single text string.
    fn generate(&self, text: &str) -> BinarySignature;

    /// Batch-generate signatures for multiple texts.
    fn generate_batch(&self, texts: &[&str]) -> Vec<BinarySignature>;

    /// Compute Hamming distance between two signatures.
    fn hamming_distance(&self, a: &BinarySignature, b: &BinarySignature) -> u32;

    /// Find all signatures within a Hamming ball of the query.
    ///
    /// Returns tuples of (index_in_candidates, distance).
    fn hamming_ball(
        &self,
        query: &BinarySignature,
        radius: u32,
        candidates: &[BinarySignature],
    ) -> Vec<(usize, u32)>;
}

/// Store for compressed binary signatures and token-ID sequences.
///
/// The DWM (Dynamic Wavelet Matrix) implements this trait.
pub trait DwmStore: Send + Sync {
    /// Insert a new memory into the store.
    fn insert(&mut self, memory_id: MemoryId, signature: BinarySignature, tokens: TokenSequence);

    /// Remove a memory from the store.
    fn remove(&mut self, memory_id: MemoryId);

    /// Search for memories within a Hamming distance radius of the query signature.
    ///
    /// Returns tuples of (MemoryId, hamming_distance), sorted by distance.
    fn search(
        &self,
        query: &BinarySignature,
        radius: u32,
        limit: usize,
    ) -> Vec<(MemoryId, u32)>;

    /// Reconstruct the original token sequence for a memory.
    fn reconstruct(&mut self, memory_id: MemoryId) -> Option<TokenSequence>;

    /// Count of memories in the store.
    fn count(&self) -> usize;

    /// Persist the store to disk.
    fn persist(&self, path: &Path) -> Result<(), CerebrumError>;

    /// Load a store from disk.
    fn load(path: &Path) -> Result<Self, CerebrumError>
    where
        Self: Sized;
}

/// Multi-graph view for memory relationships.
///
/// Supports four orthogonal graph types: semantic, temporal, causal, entity.
pub trait GraphView: Send + Sync {
    /// Add an edge to the graph.
    fn add_edge(&mut self, edge: GraphEdge);

    /// Traverse the graph from a starting node.
    ///
    /// Implementors should apply reasonable internal limits to prevent
    /// unbounded traversals. Use `traverse_bounded` for explicit control.
    fn traverse(&self, start: MemoryId, strategy: TraverseStrategy) -> Vec<MemoryId>;

    /// Traverse the graph with explicit depth and node limits.
    ///
    /// `max_depth` limits how many hops from the start node to explore.
    /// `max_nodes` limits the total number of nodes returned.
    ///
    /// The default implementation performs a depth-limited BFS using the
    /// `neighbors` method, so it works correctly for any implementor without
    /// requiring an override. For Weighted strategy, neighbors are sorted by
    /// descending weight at each level. Implementors MAY override for performance.
    fn traverse_bounded(
        &self,
        start: MemoryId,
        strategy: TraverseStrategy,
        max_depth: usize,
        max_nodes: usize,
    ) -> Vec<MemoryId> {
        if max_depth == 0 || max_nodes == 0 {
            return Vec::new();
        }

        let mut visited = std::collections::HashSet::new();
        visited.insert(start);

        let mut current_level: Vec<MemoryId> = vec![start];
        let mut results: Vec<MemoryId> = vec![start];

        for _ in 0..max_depth {
            if results.len() >= max_nodes {
                break;
            }

            let mut next_level: Vec<MemoryId> = Vec::new();

            for node in &current_level {
                let neighbors = self.neighbors(*node);

                let mut filtered: Vec<(MemoryId, f32)> = match strategy {
                    TraverseStrategy::Weighted => {
                        let mut weighted: Vec<(MemoryId, f32)> = neighbors
                            .into_iter()
                            .filter(|(id, _, _)| !visited.contains(id))
                            .map(|(id, _, weight)| (id, weight))
                            .collect();
                        weighted.sort_by(|a, b| {
                            b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal)
                        });
                        weighted
                    }
                    _ => {
                        neighbors
                            .into_iter()
                            .filter(|(id, _, _)| !visited.contains(id))
                            .map(|(id, _, _)| (id, 0.0))
                            .collect()
                    }
                };

                for (neighbor_id, _) in filtered.drain(..) {
                    if !visited.contains(&neighbor_id) {
                        visited.insert(neighbor_id);
                        results.push(neighbor_id);
                        next_level.push(neighbor_id);
                        if results.len() >= max_nodes {
                            break;
                        }
                    }
                }
                if results.len() >= max_nodes {
                    break;
                }
            }

            if next_level.is_empty() {
                break;
            }
            current_level = next_level;
        }

        results.into_iter().take(max_nodes).collect()
    }

    /// Get immediate neighbors of a node.
    ///
    /// Returns tuples of (neighbor_id, edge_type, weight).
    fn neighbors(&self, id: MemoryId) -> Vec<(MemoryId, EdgeType, f32)>;
}

/// Event-sourced storage for immutable memory events.
///
/// CortexDB pattern: raw events stored as-is, async enrichment extracts
/// facts and knowledge graph entries.
pub trait EventStore: Send + Sync {
    /// Append a pre-constructed event to the store.
    fn append(&mut self, event: Event);

    /// Append a new event from components. This is the preferred method for
    /// callers who want the store to handle UUID generation and timestamping.
    /// The default implementation constructs an Event and calls append().
    fn append_event(
        &mut self,
        event_type: impl Into<String>,
        memory_id: MemoryId,
        payload: serde_json::Value,
    ) -> Uuid {
        let event_id = Uuid::new_v4();
        let event = Event {
            event_id,
            event_type: event_type.into(),
            memory_id,
            payload,
            timestamp: Utc::now(),
            enrichment_status: EnrichmentStatus::Pending,
        };
        self.append(event);
        event_id
    }

    /// Get all events for a specific memory.
    fn events_for(&self, memory_id: MemoryId) -> Vec<Event>;

    /// Get events that have not yet been enriched.
    fn unenriched(&self, limit: usize) -> Vec<Event>;

    /// Mark an event as enriched.
    fn mark_enriched(&mut self, event_id: Uuid);
}

// ============================================================================
// 13. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    // ------------------------------------------------------------------------
    // BinarySignature tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_binary_signature_zeros() {
        let sig = BinarySignature::zeros();
        assert_eq!(sig.0, [0u8; 32]);
        assert_eq!(sig.popcount(), 0);
    }

    #[test]
    fn test_binary_signature_from_bytes() {
        let bytes: Vec<u8> = (0..32).collect();
        let sig = BinarySignature::from_bytes(&bytes);
        assert_eq!(sig.0.as_slice(), bytes.as_slice());
    }

    #[test]
    #[should_panic(expected = "BinarySignature requires exactly 32 bytes")]
    fn test_binary_signature_from_bytes_panic() {
        BinarySignature::from_bytes(&[1, 2, 3]);
    }

    #[test]
    fn test_binary_signature_xor() {
        let a = BinarySignature([0xFF; 32]);
        let b = BinarySignature([0x0F; 32]);
        let c = a.xor(&b);
        // 0xFF ^ 0x0F = 0xF0
        assert_eq!(c.0, [0xF0; 32]);
        assert_eq!(c.popcount(), 32 * 4); // 4 bits set per byte
    }

    #[test]
    fn test_binary_signature_xor_identity() {
        let a = BinarySignature([0xAB; 32]);
        let zero = BinarySignature::zeros();
        assert_eq!(a.xor(&zero).0, a.0);
        assert_eq!(a.xor(&a).popcount(), 0);
    }

    #[test]
    fn test_binary_signature_popcount() {
        // 0x55 = 0101_0101 → 4 bits set per byte
        let sig = BinarySignature([0x55; 32]);
        assert_eq!(sig.popcount(), 32 * 4);

        // 0xAA = 1010_1010 → 4 bits set per byte
        let sig = BinarySignature([0xAA; 32]);
        assert_eq!(sig.popcount(), 32 * 4);

        // 0xFF = 1111_1111 → 8 bits set per byte
        let sig = BinarySignature([0xFF; 32]);
        assert_eq!(sig.popcount(), 32 * 8);
    }

    #[test]
    fn test_binary_signature_hamming_distance() {
        let a = BinarySignature([0x00; 32]);
        let b = BinarySignature([0xFF; 32]);
        assert_eq!(a.hamming_distance(&b), 32 * 8);

        let c = BinarySignature([0x00; 32]);
        assert_eq!(a.hamming_distance(&c), 0);

        // 0x0F ^ 0xF0 = 0xFF → 8 bits per byte
        let d = BinarySignature([0x0F; 32]);
        let e = BinarySignature([0xF0; 32]);
        assert_eq!(d.hamming_distance(&e), 32 * 8);
    }

    #[test]
    fn test_binary_signature_hamming_distance_single_bit() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        a[0] = 0b0000_0001;
        b[0] = 0b0000_0010;
        let sig_a = BinarySignature(a);
        let sig_b = BinarySignature(b);
        assert_eq!(sig_a.hamming_distance(&sig_b), 2);
    }

    #[test]
    fn test_binary_signature_display() {
        let sig = BinarySignature([0xAB; 32]);
        let s = format!("{}", sig);
        assert_eq!(s.len(), 64); // 32 bytes * 2 hex chars
        assert!(s.starts_with("abababab"));
    }

    // ------------------------------------------------------------------------
    // MemoryId tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_memory_id_new() {
        let id1 = MemoryId::new();
        let id2 = MemoryId::new();
        assert_ne!(id1.0, id2.0);
        assert_eq!(id1.0.len(), 32);
    }

    #[test]
    fn test_memory_id_default() {
        let id = MemoryId::default();
        assert_eq!(id.0.len(), 32);
    }

    #[test]
    fn test_memory_id_display() {
        let id = MemoryId([0xAB; 32]);
        let s = format!("{}", id);
        assert_eq!(s.len(), 64);
        assert!(s.starts_with("abababab"));
    }

    #[test]
    fn test_memory_id_full_entropy() {
        // Verify that all 32 bytes are populated (not zero-padded in last 16)
        let id = MemoryId::new();
        let last_16 = &id.0[16..];
        // With true 256-bit randomness, the last 16 bytes should not be all zeros
        // (probability is 1/2^128 which is effectively impossible)
        assert!(!last_16.iter().all(|&b| b == 0), "MemoryId should use full 256 bits of entropy");
    }

    // ------------------------------------------------------------------------
    // TokenSequence tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_token_sequence_default() {
        let ts = TokenSequence::default();
        assert_eq!(ts.vocabulary_id, 0);
        assert!(ts.is_empty());
        assert_eq!(ts.len(), 0);
    }

    #[test]
    fn test_token_sequence_new() {
        let ts = TokenSequence::new(42, vec![1, 2, 3, 4, 5]);
        assert_eq!(ts.vocabulary_id, 42);
        assert_eq!(ts.len(), 5);
        assert_eq!(ts.tokens, vec![1, 2, 3, 4, 5]);
    }

    // ------------------------------------------------------------------------
    // TokenBudget tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_token_budget() {
        let mut budget = TokenBudget::new(100);
        assert_eq!(budget.remaining(), 100);
        budget.consume(30).unwrap();
        assert_eq!(budget.remaining(), 70);
        budget.consume(70).unwrap();
        assert_eq!(budget.remaining(), 0);
    }

    #[test]
    fn test_token_budget_exceeded() {
        let mut budget = TokenBudget::new(50);
        assert!(budget.consume(51).is_err());
        budget.consume(50).unwrap();
        assert!(budget.consume(1).is_err());
    }

    #[test]
    fn test_token_budget_display_error() {
        let err = CerebrumError::BudgetExceeded;
        assert_eq!(format!("{}", err), "Token budget exceeded");
    }

    // ------------------------------------------------------------------------
    // CurateOp serialization roundtrip
    // ------------------------------------------------------------------------

    #[test]
    fn test_curate_op_serialization_roundtrip_add() {
        let entry = ContextEntry {
            memory_id: MemoryId([1u8; 32]),
            domain: "test_domain".into(),
            topic: "test_topic".into(),
            subtopic: "test_subtopic".into(),
            content: "test content".into(),
            relations: vec![MemoryId([2u8; 32])],
            provenance: "test".into(),
            rationale: "because".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        let op = CurateOp::Add {
            entry,
            reason: "Initial curation".into(),
        };
        let json = serde_json::to_string(&op).unwrap();
        let de: CurateOp = serde_json::from_str(&json).unwrap();
        assert_eq!(op, de);
    }

    #[test]
    fn test_curate_op_serialization_roundtrip_update() {
        let changes = PartialEntry {
            content: Some("updated content".into()),
            ..Default::default()
        };
        let op = CurateOp::Update {
            memory_id: MemoryId([3u8; 32]),
            changes,
            reason: "Content update".into(),
        };
        let json = serde_json::to_string(&op).unwrap();
        let de: CurateOp = serde_json::from_str(&json).unwrap();
        assert_eq!(op, de);
    }

    #[test]
    fn test_curate_op_serialization_roundtrip_upsert() {
        let entry = ContextEntry {
            memory_id: MemoryId([4u8; 32]),
            domain: "upsert_domain".into(),
            topic: "upsert_topic".into(),
            subtopic: "upsert_subtopic".into(),
            content: "upsert content".into(),
            relations: vec![],
            provenance: "upsert".into(),
            rationale: "upsert reason".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        let op = CurateOp::Upsert {
            entry,
            reason: "Upsert test".into(),
        };
        let json = serde_json::to_string(&op).unwrap();
        let de: CurateOp = serde_json::from_str(&json).unwrap();
        assert_eq!(op, de);
    }

    #[test]
    fn test_curate_op_serialization_roundtrip_merge() {
        let target = ContextEntry {
            memory_id: MemoryId([5u8; 32]),
            domain: "merge_domain".into(),
            topic: "merge_topic".into(),
            subtopic: "merge_subtopic".into(),
            content: "merged content".into(),
            relations: vec![],
            provenance: "merge".into(),
            rationale: "merge reason".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        let op = CurateOp::Merge {
            source_ids: vec![MemoryId([6u8; 32]), MemoryId([7u8; 32])],
            target,
            reason: "Merge test".into(),
        };
        let json = serde_json::to_string(&op).unwrap();
        let de: CurateOp = serde_json::from_str(&json).unwrap();
        assert_eq!(op, de);
    }

    #[test]
    fn test_curate_op_serialization_roundtrip_delete() {
        let op = CurateOp::Delete {
            memory_id: MemoryId([8u8; 32]),
            reason: "Delete test".into(),
        };
        let json = serde_json::to_string(&op).unwrap();
        let de: CurateOp = serde_json::from_str(&json).unwrap();
        assert_eq!(op, de);
    }

    // ------------------------------------------------------------------------
    // Frame serialization roundtrip
    // ------------------------------------------------------------------------

    #[test]
    fn test_frame_serialization_roundtrip_query() {
        let query = Query {
            query_type: QueryType::Semantic,
            text: "What relates to Rust?".into(),
            budget: TokenBudget::new(1024),
        };
        let frame = Frame::Query(query);
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_search() {
        let frame = Frame::Search {
            signature: BinarySignature([0xDE; 32]),
            radius: 10,
            limit: 50,
        };
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_consolidate() {
        let frame = Frame::Consolidate;
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_curate() {
        let entry = ContextEntry {
            memory_id: MemoryId([20u8; 32]),
            domain: "frame_domain".into(),
            topic: "frame_topic".into(),
            subtopic: "frame_subtopic".into(),
            content: "frame content".into(),
            relations: vec![MemoryId([21u8; 32])],
            provenance: "test".into(),
            rationale: "testing".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        let op = CurateOp::Add {
            entry,
            reason: "Frame curate test".into(),
        };
        let frame = Frame::Curate(Box::new(op));
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_retrieve() {
        let frame = Frame::Retrieve {
            memory_id: MemoryId([22u8; 32]),
            level: DereferenceLevel::Deep,
        };
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_graph_traverse() {
        let frame = Frame::GraphTraverse {
            start: MemoryId([23u8; 32]),
            edge_type: EdgeType::Temporal,
            depth: 3,
        };
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    #[test]
    fn test_frame_serialization_roundtrip_prefetch() {
        let hint = PrefetchHint {
            task_type: "semantic_prefetch".into(),
            likely_needed: vec![MemoryId([24u8; 32]), MemoryId([25u8; 32])],
        };
        let frame = Frame::Prefetch(hint);
        let json = serde_json::to_string(&frame).unwrap();
        let de: Frame = serde_json::from_str(&json).unwrap();
        assert_eq!(frame, de);
    }

    // ------------------------------------------------------------------------
    // CerebrumError tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_cerebrum_error_display() {
        assert_eq!(
            format!("{}", CerebrumError::DwmError("boom".into())),
            "DWM error: boom"
        );
        assert_eq!(
            format!("{}", CerebrumError::SignatureError("sig fail".into())),
            "Signature error: sig fail"
        );
        assert_eq!(
            format!("{}", CerebrumError::TokenError("tok fail".into())),
            "Token error: tok fail"
        );
        assert_eq!(
            format!("{}", CerebrumError::GraphError("graph fail".into())),
            "Graph error: graph fail"
        );
        assert_eq!(
            format!("{}", CerebrumError::StorageError("disk fail".into())),
            "Storage error: disk fail"
        );
        assert_eq!(
            format!("{}", CerebrumError::QueryError("bad plan".into())),
            "Query error: bad plan"
        );
        let id = MemoryId([0u8; 32]);
        assert_eq!(
            format!("{}", CerebrumError::NotFound(id)),
            "Memory not found: 0000000000000000000000000000000000000000000000000000000000000000"
        );
    }

    #[test]
    fn test_cerebrum_error_send_sync() {
        fn assert_send<T: Send>() {}
        fn assert_sync<T: Sync>() {}
        assert_send::<CerebrumError>();
        assert_sync::<CerebrumError>();
    }

    // ------------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------------

    #[test]
    fn test_empty_token_sequence_serialization() {
        let ts = TokenSequence::default();
        let json = serde_json::to_string(&ts).unwrap();
        let de: TokenSequence = serde_json::from_str(&json).unwrap();
        assert_eq!(ts, de);
    }

    #[test]
    fn test_curate_result_serialization() {
        let result = CurateResult {
            memory_id: MemoryId([9u8; 32]),
            status: CurateStatus::Success,
            detail: "All good".into(),
        };
        let json = serde_json::to_string(&result).unwrap();
        let de: CurateResult = serde_json::from_str(&json).unwrap();
        assert_eq!(result, de);
    }

    #[test]
    fn test_graph_edge_serialization() {
        let edge = GraphEdge {
            edge_type: EdgeType::Causal,
            source: MemoryId([10u8; 32]),
            target: MemoryId([11u8; 32]),
            weight: 0.95,
            timestamp: Utc::now(),
        };
        let json = serde_json::to_string(&edge).unwrap();
        let de: GraphEdge = serde_json::from_str(&json).unwrap();
        assert_eq!(edge, de);
    }

    #[test]
    fn test_event_serialization() {
        let event = Event {
            event_id: Uuid::new_v4(),
            event_type: "memory.created".into(),
            memory_id: MemoryId([12u8; 32]),
            payload: serde_json::json!({"key": "value"}),
            timestamp: Utc::now(),
            enrichment_status: EnrichmentStatus::Pending,
        };
        let json = serde_json::to_string(&event).unwrap();
        let de: Event = serde_json::from_str(&json).unwrap();
        assert_eq!(event, de);
    }

    #[test]
    fn test_episode_serialization() {
        let episode = Episode {
            memory_id: MemoryId([13u8; 32]),
            source: "conversation://abc".into(),
            trigger: "user asked about Rust".into(),
            context: "Chat session #123, morning briefing".into(),
            discovered_at: Utc::now(),
        };
        let json = serde_json::to_string(&episode).unwrap();
        let de: Episode = serde_json::from_str(&json).unwrap();
        assert_eq!(episode, de);
    }

    #[test]
    fn test_prefetch_hint_serialization() {
        let hint = PrefetchHint {
            task_type: "semantic_search".into(),
            likely_needed: vec![MemoryId([14u8; 32]), MemoryId([15u8; 32])],
        };
        let json = serde_json::to_string(&hint).unwrap();
        let de: PrefetchHint = serde_json::from_str(&json).unwrap();
        assert_eq!(hint, de);
    }

    #[test]
    fn test_dereference_level_serialization() {
        let level = DereferenceLevel::Partial {
            sections: vec!["intro".into(), "body".into()],
        };
        let json = serde_json::to_string(&level).unwrap();
        let de: DereferenceLevel = serde_json::from_str(&json).unwrap();
        assert_eq!(level, de);
    }

    #[test]
    fn test_confidence_level_serialization() {
        let level = ConfidenceLevel::High;
        let json = serde_json::to_string(&level).unwrap();
        let de: ConfidenceLevel = serde_json::from_str(&json).unwrap();
        assert_eq!(level, de);
    }

    #[test]
    fn test_traverse_strategy_serialization() {
        let strat = TraverseStrategy::Weighted;
        let json = serde_json::to_string(&strat).unwrap();
        let de: TraverseStrategy = serde_json::from_str(&json).unwrap();
        assert_eq!(strat, de);
    }

    #[test]
    fn test_query_type_serialization() {
        let qt = QueryType::Composite;
        let json = serde_json::to_string(&qt).unwrap();
        let de: QueryType = serde_json::from_str(&json).unwrap();
        assert_eq!(qt, de);
    }

    #[test]
    fn test_enrichment_status_serialization() {
        let es = EnrichmentStatus::DeadLetter;
        let json = serde_json::to_string(&es).unwrap();
        let de: EnrichmentStatus = serde_json::from_str(&json).unwrap();
        assert_eq!(es, de);
    }

    // ------------------------------------------------------------------------
    // Additional tests for completeness
    // ------------------------------------------------------------------------

    #[test]
    fn test_context_entry_default() {
        let entry = ContextEntry::default();
        assert_eq!(entry.domain, "");
        assert_eq!(entry.topic, "");
        assert_eq!(entry.subtopic, "");
        assert_eq!(entry.content, "");
        assert!(entry.relations.is_empty());
        assert_eq!(entry.provenance, "");
        assert_eq!(entry.rationale, "");
        assert_eq!(entry.memory_id.0.len(), 32);
    }

    #[test]
    fn test_partial_entry_default() {
        let entry = PartialEntry::default();
        assert!(entry.domain.is_none());
        assert!(entry.topic.is_none());
        assert!(entry.subtopic.is_none());
        assert!(entry.content.is_none());
        assert!(entry.relations.is_none());
        assert!(entry.provenance.is_none());
        assert!(entry.rationale.is_none());
    }

    #[test]
    fn test_edge_type_serialization() {
        for edge_type in [EdgeType::Semantic, EdgeType::Temporal, EdgeType::Causal, EdgeType::Entity] {
            let json = serde_json::to_string(&edge_type).unwrap();
            let de: EdgeType = serde_json::from_str(&json).unwrap();
            assert_eq!(edge_type, de);
        }
    }

    #[test]
    fn test_curate_status_serialization() {
        for status in [CurateStatus::Success, CurateStatus::Created, CurateStatus::Updated, CurateStatus::Merged, CurateStatus::Deleted, CurateStatus::Failed, CurateStatus::Pending] {
            let json = serde_json::to_string(&status).unwrap();
            let de: CurateStatus = serde_json::from_str(&json).unwrap();
            assert_eq!(status, de);
        }
    }

    #[test]
    fn test_dereference_level_shallow_serialization() {
        let level = DereferenceLevel::Shallow;
        let json = serde_json::to_string(&level).unwrap();
        let de: DereferenceLevel = serde_json::from_str(&json).unwrap();
        assert_eq!(level, de);
    }

    #[test]
    fn test_dereference_level_deep_serialization() {
        let level = DereferenceLevel::Deep;
        let json = serde_json::to_string(&level).unwrap();
        let de: DereferenceLevel = serde_json::from_str(&json).unwrap();
        assert_eq!(level, de);
    }

    #[test]
    fn test_confidence_level_all_variants() {
        for level in [ConfidenceLevel::High, ConfidenceLevel::Medium, ConfidenceLevel::Low] {
            let json = serde_json::to_string(&level).unwrap();
            let de: ConfidenceLevel = serde_json::from_str(&json).unwrap();
            assert_eq!(level, de);
        }
    }

    #[test]
    fn test_traverse_strategy_all_variants() {
        for strat in [TraverseStrategy::Bfs, TraverseStrategy::Dfs, TraverseStrategy::Weighted, TraverseStrategy::Temporal] {
            let json = serde_json::to_string(&strat).unwrap();
            let de: TraverseStrategy = serde_json::from_str(&json).unwrap();
            assert_eq!(strat, de);
        }
    }

    #[test]
    fn test_query_type_all_variants() {
        for qt in [QueryType::Semantic, QueryType::Temporal, QueryType::Causal, QueryType::Entity, QueryType::Composite] {
            let json = serde_json::to_string(&qt).unwrap();
            let de: QueryType = serde_json::from_str(&json).unwrap();
            assert_eq!(qt, de);
        }
    }

    #[test]
    fn test_enrichment_status_all_variants() {
        for es in [EnrichmentStatus::Pending, EnrichmentStatus::Enriched, EnrichmentStatus::Failed, EnrichmentStatus::DeadLetter] {
            let json = serde_json::to_string(&es).unwrap();
            let de: EnrichmentStatus = serde_json::from_str(&json).unwrap();
            assert_eq!(es, de);
        }
    }

    #[test]
    fn test_token_budget_default() {
        let budget = TokenBudget::default();
        assert_eq!(budget.max_tokens, 4096);
        assert_eq!(budget.used_tokens, 0);
        assert_eq!(budget.remaining(), 4096);
    }

    #[test]
    fn test_token_budget_consume_exact() {
        let mut budget = TokenBudget::new(100);
        budget.consume(100).unwrap();
        assert_eq!(budget.remaining(), 0);
        assert!(budget.consume(1).is_err());
    }

    #[test]
    fn test_binary_signature_from_bytes_roundtrip() {
        let original: Vec<u8> = (0..32).map(|i| (i * 3 + 7) as u8).collect();
        let sig = BinarySignature::from_bytes(&original);
        assert_eq!(sig.0.to_vec(), original);
    }

    // ------------------------------------------------------------------------
    // Property-based tests (10K iterations)
    // ------------------------------------------------------------------------

    use proptest::prelude::*;

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(10_000))]

        #[test]
        fn proptest_xor_identity(a in any::<[u8; 32]>(), b in any::<[u8; 32]>()) {
            let sig_a = BinarySignature(a);
            let sig_b = BinarySignature(b);
            let zero = BinarySignature::zeros();
            // XOR with zero is identity
            prop_assert_eq!(sig_a.xor(&zero).0, sig_a.0);
            // XOR with self is zero
            prop_assert_eq!(sig_a.xor(&sig_a).popcount(), 0);
            // XOR is commutative
            prop_assert_eq!(sig_a.xor(&sig_b).0, sig_b.xor(&sig_a).0);
            // XOR is associative
            let ab = sig_a.xor(&sig_b);
            let bc = sig_b.xor(&zero);
            prop_assert_eq!(ab.xor(&zero).0, sig_a.xor(&bc).0);
        }

        #[test]
        fn proptest_popcount_bounds(a in any::<[u8; 32]>()) {
            let sig = BinarySignature(a);
            let pc = sig.popcount();
            // popcount of 256 bits must be in [0, 256]
            prop_assert!(pc <= 256, "popcount {} exceeds 256", pc);
            // popcount equals sum of byte popcounts
            let manual: u32 = a.iter().map(|b| b.count_ones()).sum();
            prop_assert_eq!(pc, manual);
        }

        #[test]
        fn proptest_hamming_distance_properties(a in any::<[u8; 32]>(), b in any::<[u8; 32]>()) {
            let sig_a = BinarySignature(a);
            let sig_b = BinarySignature(b);
            let dist_ab = sig_a.hamming_distance(&sig_b);
            // Self-distance is zero
            prop_assert_eq!(sig_a.hamming_distance(&sig_a), 0);
            // Symmetric
            prop_assert_eq!(dist_ab, sig_b.hamming_distance(&sig_a));
            // Triangle inequality: d(a,b) <= d(a,c) + d(c,b) for any c
            let sig_c = BinarySignature::zeros();
            let dist_ac = sig_a.hamming_distance(&sig_c);
            let dist_cb = sig_c.hamming_distance(&sig_b);
            prop_assert!(dist_ab <= dist_ac + dist_cb, "triangle inequality violated: {} > {} + {}", dist_ab, dist_ac, dist_cb);
            // Bounded by 256
            prop_assert!(dist_ab <= 256);
        }

        #[test]
        fn proptest_memory_id_uniqueness(_ in 0..1000u32) {
            let id1 = MemoryId::new();
            let id2 = MemoryId::new();
            // Two random IDs should differ (probability of collision is 1/2^256)
            prop_assert_ne!(id1.0, id2.0);
            // All 32 bytes should be present
            prop_assert_eq!(id1.0.len(), 32);
        }

        #[test]
        fn proptest_token_budget_arithmetic(max_tokens in 1..100_000usize, consume1 in 0..50_000usize, consume2 in 0..50_000usize) {
            let mut budget = TokenBudget::new(max_tokens);
            let total = consume1 + consume2;
            if total <= max_tokens {
                // Both consumes should succeed
                prop_assert!(budget.consume(consume1).is_ok());
                prop_assert!(budget.consume(consume2).is_ok());
                prop_assert_eq!(budget.remaining(), max_tokens - total);
            } else if consume1 <= max_tokens {
                // First consume succeeds, second fails
                prop_assert!(budget.consume(consume1).is_ok());
                prop_assert!(budget.consume(consume2).is_err());
            } else {
                // First consume fails
                prop_assert!(budget.consume(consume1).is_err());
            }
        }

        #[test]
        fn proptest_frame_tag_roundtrip(tag_byte in 0u8..=255) {
            let result = FrameTag::try_from(tag_byte);
            if frame_types::ALL.contains(&tag_byte) {
                // Valid frame type bytes should convert successfully
                prop_assert!(result.is_ok(), "valid byte 0x{:02x} failed to convert", tag_byte);
                // And converting back should give the same byte
                let tag = result.unwrap();
                let back: u8 = tag.into();
                prop_assert_eq!(back, tag_byte);
            } else {
                // Invalid bytes should produce an error
                prop_assert!(result.is_err(), "invalid byte 0x{:02x} should have failed", tag_byte);
            }
        }

        #[test]
        fn proptest_frame_to_byte_and_back(frame_byte in proptest::sample::select(frame_types::ALL_REQUEST.to_vec())) {
            // Every request frame type byte should roundtrip through FrameTag
            let tag = FrameTag::try_from(frame_byte).unwrap();
            let back: u8 = tag.into();
            prop_assert_eq!(back, frame_byte);
        }

        #[test]
        fn proptest_context_entry_new_preserves_fields(
            id_bytes in any::<[u8; 32]>(),
            domain in "[a-z]{1,20}",
            topic in "[a-z]{1,20}",
            subtopic in "[a-z]{1,20}",
            content in "[a-z ]{1,100}",
        ) {
            let mid = MemoryId(id_bytes);
            let entry = ContextEntry::new(mid, &domain, &topic, &subtopic, &content);
            prop_assert_eq!(entry.memory_id, mid);
            prop_assert_eq!(entry.domain, domain);
            prop_assert_eq!(entry.topic, topic);
            prop_assert_eq!(entry.subtopic, subtopic);
            prop_assert_eq!(entry.content, content);
            prop_assert!(entry.relations.is_empty());
            prop_assert_eq!(entry.provenance, "");
            prop_assert_eq!(entry.rationale, "");
        }
    }

    // ------------------------------------------------------------------------
    // Frame type constant and FrameTag tests
    // ------------------------------------------------------------------------

    #[test]
    fn test_frame_types_all_constants() {
        // Verify all constants are distinct
        let all = frame_types::ALL;
        let mut sorted = all.to_vec();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), all.len(), "frame type constants must be distinct");
    }

    #[test]
    fn test_frame_tag_try_from_valid() {
        assert_eq!(FrameTag::try_from(frame_types::QUERY).unwrap(), FrameTag::Query);
        assert_eq!(FrameTag::try_from(frame_types::CURATE).unwrap(), FrameTag::Curate);
        assert_eq!(FrameTag::try_from(frame_types::RETRIEVE).unwrap(), FrameTag::Retrieve);
        assert_eq!(FrameTag::try_from(frame_types::SEARCH).unwrap(), FrameTag::Search);
        assert_eq!(FrameTag::try_from(frame_types::GRAPH_TRAVERSE).unwrap(), FrameTag::GraphTraverse);
        assert_eq!(FrameTag::try_from(frame_types::CONSOLIDATE).unwrap(), FrameTag::Consolidate);
        assert_eq!(FrameTag::try_from(frame_types::PREFETCH).unwrap(), FrameTag::Prefetch);
        assert_eq!(FrameTag::try_from(frame_types::RESPONSE).unwrap(), FrameTag::Response);
        assert_eq!(FrameTag::try_from(frame_types::ERROR).unwrap(), FrameTag::ErrorFrame);
    }

    #[test]
    fn test_frame_tag_try_from_invalid() {
        assert!(FrameTag::try_from(0x00).is_err());
        assert!(FrameTag::try_from(0xFF).is_err());
        assert!(FrameTag::try_from(0x99).is_err());
    }

    #[test]
    fn test_frame_to_byte() {
        let query = Frame::Query(Query {
            query_type: QueryType::Semantic,
            text: "test".into(),
            budget: TokenBudget::new(100),
        });
        assert_eq!(u8::from(&query), frame_types::QUERY);

        let consolidate = Frame::Consolidate;
        assert_eq!(u8::from(&consolidate), frame_types::CONSOLIDATE);
    }

    #[test]
    fn test_unknown_frame_type_error_display() {
        let err = UnknownFrameTypeError { frame_type: 0xAB };
        assert_eq!(format!("{}", err), "unknown frame type: 0xab");
    }

    // ------------------------------------------------------------------------
    // TokenSequence convenience methods
    // ------------------------------------------------------------------------

    #[test]
    fn test_token_sequence_from_tokens() {
        let ts = TokenSequence::from_tokens(&[1, 2, 3]);
        assert_eq!(ts.vocabulary_id, 0);
        assert_eq!(ts.tokens, vec![1, 2, 3]);
    }

    #[test]
    fn test_token_sequence_raw_byte_size() {
        let ts = TokenSequence::new(0, vec![1, 2, 3, 4, 5]);
        assert_eq!(ts.raw_byte_size(), 20); // 5 tokens * 4 bytes
    }

    #[test]
    fn test_token_sequence_as_slice() {
        let ts = TokenSequence::new(0, vec![10, 20, 30]);
        assert_eq!(ts.as_slice(), &[10, 20, 30]);
    }

    // ------------------------------------------------------------------------
    // DereferenceLevel Hash test
    // ------------------------------------------------------------------------

    #[test]
    fn test_dereference_level_hash() {
        use std::collections::HashSet;
        let mut set = HashSet::new();
        set.insert(DereferenceLevel::Shallow);
        set.insert(DereferenceLevel::Deep);
        set.insert(DereferenceLevel::Partial { sections: vec!["intro".into()] });
        assert_eq!(set.len(), 3);
        // Inserting same variant again should not increase size
        set.insert(DereferenceLevel::Shallow);
        assert_eq!(set.len(), 3);
    }

    // ------------------------------------------------------------------------
    // traverse_bounded default implementation tests
    // ------------------------------------------------------------------------

    /// Mock graph for testing traverse_bounded.
    /// Creates a linear chain: A -> B -> C -> D -> E
    struct MockChainGraph {
        edges: std::collections::HashMap<MemoryId, Vec<(MemoryId, EdgeType, f32)>>,
    }

    impl MockChainGraph {
        fn new() -> Self {
            let mut edges: std::collections::HashMap<MemoryId, Vec<(MemoryId, EdgeType, f32)>> =
                std::collections::HashMap::new();
            let ids: Vec<MemoryId> = (0..5).map(|i| MemoryId([i as u8; 32])).collect();
            for i in 0..4 {
                edges.entry(ids[i]).or_default().push((
                    ids[i + 1],
                    EdgeType::Semantic,
                    1.0 - i as f32 * 0.1,
                ));
            }
            Self { edges }
        }

        fn node(n: usize) -> MemoryId {
            MemoryId([n as u8; 32])
        }
    }

    impl GraphView for MockChainGraph {
        fn add_edge(&mut self, _edge: GraphEdge) {}

        fn traverse(&self, _start: MemoryId, _strategy: TraverseStrategy) -> Vec<MemoryId> {
            Vec::new() // Not used in tests
        }

        fn neighbors(&self, id: MemoryId) -> Vec<(MemoryId, EdgeType, f32)> {
            self.edges.get(&id).cloned().unwrap_or_default()
        }
    }

    #[test]
    fn test_traverse_bounded_depth_1() {
        let graph = MockChainGraph::new();
        let start = MockChainGraph::node(0);
        let results = graph.traverse_bounded(start, TraverseStrategy::Bfs, 1, 100);
        // Depth 1: start + immediate neighbors = [0, 1]
        assert_eq!(results.len(), 2);
        assert_eq!(results[0], MockChainGraph::node(0));
        assert_eq!(results[1], MockChainGraph::node(1));
    }

    #[test]
    fn test_traverse_bounded_depth_3() {
        let graph = MockChainGraph::new();
        let start = MockChainGraph::node(0);
        let results = graph.traverse_bounded(start, TraverseStrategy::Bfs, 3, 100);
        // Depth 3: [0, 1, 2, 3]
        assert_eq!(results.len(), 4);
        assert_eq!(results[0], MockChainGraph::node(0));
        assert_eq!(results[1], MockChainGraph::node(1));
        assert_eq!(results[2], MockChainGraph::node(2));
        assert_eq!(results[3], MockChainGraph::node(3));
    }

    #[test]
    fn test_traverse_bounded_max_nodes() {
        let graph = MockChainGraph::new();
        let start = MockChainGraph::node(0);
        let results = graph.traverse_bounded(start, TraverseStrategy::Bfs, 100, 3);
        // max_nodes=3: [0, 1, 2]
        assert_eq!(results.len(), 3);
        assert_eq!(results[0], MockChainGraph::node(0));
        assert_eq!(results[1], MockChainGraph::node(1));
        assert_eq!(results[2], MockChainGraph::node(2));
    }

    #[test]
    fn test_traverse_bounded_depth_0() {
        let graph = MockChainGraph::new();
        let start = MockChainGraph::node(0);
        let results = graph.traverse_bounded(start, TraverseStrategy::Bfs, 0, 100);
        assert!(results.is_empty(), "depth 0 should return nothing");
    }

    #[test]
    fn test_traverse_bounded_max_nodes_0() {
        let graph = MockChainGraph::new();
        let start = MockChainGraph::node(0);
        let results = graph.traverse_bounded(start, TraverseStrategy::Bfs, 100, 0);
        assert!(results.is_empty(), "max_nodes 0 should return nothing");
    }

    #[test]
    fn test_traverse_bounded_weighted_strategy() {
        // Build a graph where node 0 has two neighbors with different weights
        let mut graph = MockChainGraph::new();
        let node0 = MockChainGraph::node(0);
        let node_high = MemoryId([99u8; 32]);
        let node_low = MemoryId([98u8; 32]);

        // Add a high-weight edge to node_high and a low-weight edge to node_low
        graph.edges.insert(
            node0,
            vec![
                (node_low, EdgeType::Semantic, 0.1),
                (node_high, EdgeType::Semantic, 0.9),
            ],
        );

        let results = graph.traverse_bounded(node0, TraverseStrategy::Weighted, 1, 3);
        // Weighted should visit high-weight neighbor first
        assert_eq!(results.len(), 3);
        assert_eq!(results[0], node0);
        assert_eq!(results[1], node_high, "high-weight neighbor should come first");
        assert_eq!(results[2], node_low);
    }

    #[test]
    fn test_traverse_bounded_no_cycles() {
        // Build a graph with a cycle: 0 -> 1 -> 2 -> 0
        let mut graph = MockChainGraph::new();
        let n0 = MockChainGraph::node(0);
        let n1 = MockChainGraph::node(1);
        let n2 = MockChainGraph::node(2);

        graph.edges.insert(n0, vec![(n1, EdgeType::Semantic, 1.0)]);
        graph.edges.insert(n1, vec![(n2, EdgeType::Semantic, 1.0)]);
        graph.edges.insert(n2, vec![(n0, EdgeType::Semantic, 1.0)]);

        let results = graph.traverse_bounded(n0, TraverseStrategy::Bfs, 100, 100);
        // Should visit each node exactly once despite cycle
        assert_eq!(results.len(), 3, "cyclic graph should visit each node once");
        assert!(results.contains(&n0));
        assert!(results.contains(&n1));
        assert!(results.contains(&n2));
    }

    #[test]
    fn test_traverse_bounded_isolated_node() {
        let graph = MockChainGraph::new();
        let isolated = MemoryId([255u8; 32]); // No edges from this node
        let results = graph.traverse_bounded(isolated, TraverseStrategy::Bfs, 100, 100);
        assert_eq!(results.len(), 1, "isolated node should return only itself");
        assert_eq!(results[0], isolated);
    }

}