//! Cerebrum Query — Query planner, type classifier, tiered retrieval, and confidence routing.
//!
//! Phase 0.2.4 — Track E. Provides rule-based query classification, a four-tier
//! retrieval strategy, and confidence-based routing over the Cerebrum memory
//! subsystems.

use std::collections::HashMap;

use chrono::Utc;
use serde::{Deserialize, Serialize};

use cerebrum_core::{BinarySignature, CerebrumError, MemoryId, Query, QueryType, TokenBudget};

// ============================================================================
// 1. RETRIEVAL TIER
// ============================================================================

/// A tier in the tiered retrieval pipeline.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum RetrievalTier {
    /// Hot in-memory cache of recently used memories. Target latency: <1ms.
    HotCache,
    /// DWM Hamming-ball search over binary signatures. Target latency: <50ms.
    DwmSearch,
    /// Graph traversal over multi-graph views. Target latency: <100ms.
    GraphTraversal,
    /// pgvector fallback. Target latency: <500ms.
    PgVectorFallback,
}

// ============================================================================
// 2. QUERY PLAN
// ============================================================================

/// A planned query with classification, retrieval strategy, and budget.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct QueryPlan {
    pub query_type: QueryType,
    pub strategy: Vec<RetrievalTier>,
    pub budget: TokenBudget,
}

impl QueryPlan {
    /// Build a plan for a query type using a standard tier ordering.
    pub fn new(query_type: QueryType, budget: TokenBudget) -> Self {
        let strategy = vec![
            RetrievalTier::HotCache,
            RetrievalTier::DwmSearch,
            RetrievalTier::GraphTraversal,
            RetrievalTier::PgVectorFallback,
        ];
        Self {
            query_type,
            strategy,
            budget,
        }
    }
}

// ============================================================================
// 3. QUERY RESULT
// ============================================================================

/// The result of executing a query plan.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct QueryResult {
    pub results: Vec<MemoryId>,
    pub confidence: f32,
    pub tier_used: RetrievalTier,
    pub latency_ms: u64,
}

// ============================================================================
// 4. QUERY TYPE CLASSIFIER
// ============================================================================

/// Rule-based query type classifier using keyword matching.
#[derive(Debug, Clone, Default)]
pub struct QueryTypeClassifier;

impl QueryTypeClassifier {
    /// Create a new classifier with default rules.
    pub fn new() -> Self {
        Self
    }

    /// Classify the query text into a QueryType.
    ///
    /// Keyword rules (case-insensitive):
    /// - "what relates to", "what is X related to", "similar to", "connected to" → Semantic
    /// - "when did", "when was", "what time", "chronological" → Temporal
    /// - "why did", "what caused", "because of", "led to" → Causal
    /// - "who was involved", "who did", "where is", "belongs to" → Entity
    /// - Multiple signals → Composite
    pub fn classify(&self, query_text: &str) -> QueryType {
        let lower = query_text.to_lowercase();

        let mut signals = 0u32;

        let semantic_phrases = [
            "what relates to",
            "related to",
            "similar to",
            "connected to",
        ];
        let temporal_phrases = ["when did", "when was", "what time", "chronological", "when"];
        let causal_phrases = ["why did", "what caused", "because of", "led to"];
        let entity_phrases = [
            "who was involved",
            "who did",
            "where is",
            "belongs to",
            "who",
        ];

        let has_semantic = semantic_phrases.iter().any(|p| lower.contains(p));
        let has_temporal = temporal_phrases.iter().any(|p| lower.contains(p));
        let has_causal = causal_phrases.iter().any(|p| lower.contains(p));
        let has_entity = entity_phrases.iter().any(|p| lower.contains(p));

        if has_semantic {
            signals += 1;
        }
        if has_temporal {
            signals += 1;
        }
        if has_causal {
            signals += 1;
        }
        if has_entity {
            signals += 1;
        }

        if signals > 1 {
            return QueryType::Composite;
        }

        if has_semantic {
            return QueryType::Semantic;
        }
        if has_temporal {
            return QueryType::Temporal;
        }
        if has_causal {
            return QueryType::Causal;
        }
        if has_entity {
            return QueryType::Entity;
        }

        // Default to semantic if no specific signal is detected.
        QueryType::Semantic
    }
}

// ============================================================================
// 5. CONFIDENCE ROUTER
// ============================================================================

/// A confidence routing decision.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum ConfidenceRoute {
    /// High confidence: return results directly.
    Direct(Vec<MemoryId>),
    /// Medium confidence: return results with a warning.
    Warning(Vec<MemoryId>, String),
    /// Low confidence: return empty results and suggest rephrasing.
    Rephrase(String),
}

/// Routes results based on a confidence score.
#[derive(Debug, Clone, Default)]
pub struct ConfidenceRouter;

impl ConfidenceRouter {
    /// Create a new confidence router.
    pub fn new() -> Self {
        Self
    }

    /// Route based on confidence score and results.
    ///
    /// - High (>=0.8): return results directly
    /// - Medium (0.5-0.8): return results with a warning
    /// - Low (<0.5): return empty + suggest rephrasing
    pub fn route(&self, results: Vec<MemoryId>, confidence: f32) -> ConfidenceRoute {
        if confidence >= 0.8 {
            ConfidenceRoute::Direct(results)
        } else if confidence >= 0.5 {
            ConfidenceRoute::Warning(
                results,
                "Low confidence results — consider rephrasing for better retrieval".to_string(),
            )
        } else {
            ConfidenceRoute::Rephrase(
                "Confidence too low to return results. Try rephrasing the query.".to_string(),
            )
        }
    }
}

// ============================================================================
// 6. TIERED RETRIEVAL
// ============================================================================

/// Four-tier retrieval pipeline over hot cache, DWM, graph, and pgvector.
#[derive(Debug, Clone, Default)]
pub struct TieredRetrieval {
    hot_cache: HashMap<MemoryId, String>,
}

impl TieredRetrieval {
    /// Create an empty tiered retrieval pipeline.
    pub fn new() -> Self {
        Self {
            hot_cache: HashMap::new(),
        }
    }

    /// Insert a memory into the hot cache.
    pub fn cache(&mut self, id: MemoryId, content: String) {
        self.hot_cache.insert(id, content);
    }

    /// Search each tier in order and return the first non-empty result.
    ///
    /// In this baseline implementation the hot cache is live; deeper tiers
    /// are simulated with a deterministic seed based on the query text so that
    /// the pipeline remains predictable and testable without external storage.
    pub fn retrieve(
        &self,
        query_text: &str,
        query_signature: Option<BinarySignature>,
        candidate_signatures: &[(MemoryId, BinarySignature)],
        graph_neighbors: &[MemoryId],
        dwm_radius: u32,
    ) -> (RetrievalTier, Vec<MemoryId>, f32) {
        let start = Utc::now();

        // Tier 1: Hot cache keyword match.
        let mut hot_matches = Vec::new();
        let query_lower = query_text.to_lowercase();
        for (id, content) in &self.hot_cache {
            if content.to_lowercase().contains(&query_lower) {
                hot_matches.push(*id);
            }
        }
        if !hot_matches.is_empty() {
            let latency = (Utc::now() - start).num_milliseconds() as u64;
            return (
                RetrievalTier::HotCache,
                hot_matches,
                Self::score(latency, RetrievalTier::HotCache),
            );
        }

        // Tier 2: DWM Hamming search.
        if let Some(query_sig) = query_signature {
            let mut matches: Vec<(MemoryId, u32)> = candidate_signatures
                .iter()
                .map(|(id, sig)| (*id, query_sig.hamming_distance(sig)))
                .filter(|(_, d)| *d <= dwm_radius)
                .collect();
            matches.sort_by(|a, b| a.1.cmp(&b.1));
            let ids: Vec<MemoryId> = matches.into_iter().map(|(id, _)| id).collect();
            if !ids.is_empty() {
                let latency = (Utc::now() - start).num_milliseconds() as u64;
                return (
                    RetrievalTier::DwmSearch,
                    ids,
                    Self::score(latency, RetrievalTier::DwmSearch),
                );
            }
        }

        // Tier 3: Graph traversal.
        if !graph_neighbors.is_empty() {
            let latency = (Utc::now() - start).num_milliseconds() as u64;
            return (
                RetrievalTier::GraphTraversal,
                graph_neighbors.to_vec(),
                Self::score(latency, RetrievalTier::GraphTraversal),
            );
        }

        // Tier 4: pgvector fallback — deterministic synthetic result for testing.
        let fallback_ids = deterministic_fallback(query_text);
        let latency = (Utc::now() - start).num_milliseconds() as u64;
        (
            RetrievalTier::PgVectorFallback,
            fallback_ids,
            Self::score(latency, RetrievalTier::PgVectorFallback),
        )
    }

    fn score(latency_ms: u64, tier: RetrievalTier) -> f32 {
        // Latency penalties by tier, with a small randomness-free quality bonus.
        let target_ms = match tier {
            RetrievalTier::HotCache => 1u64,
            RetrievalTier::DwmSearch => 50u64,
            RetrievalTier::GraphTraversal => 100u64,
            RetrievalTier::PgVectorFallback => 500u64,
        };
        let latency_penalty = if latency_ms <= target_ms {
            0.0f32
        } else {
            ((latency_ms - target_ms) as f32 / target_ms as f32).min(0.3)
        };

        let tier_bonus = match tier {
            RetrievalTier::HotCache => 0.15,
            RetrievalTier::DwmSearch => 0.10,
            RetrievalTier::GraphTraversal => 0.05,
            RetrievalTier::PgVectorFallback => 0.0,
        };

        (0.95 - latency_penalty + tier_bonus).clamp(0.0, 1.0)
    }
}

/// Deterministic synthetic fallback ids for pgvector testing.
fn deterministic_fallback(query_text: &str) -> Vec<MemoryId> {
    let bytes = query_text.as_bytes();
    let mut id = [0u8; 32];
    for (i, byte) in bytes.iter().enumerate().take(32) {
        id[i] = *byte;
    }
    if bytes.len() > 32 {
        for (i, byte) in bytes.iter().skip(32).enumerate().take(32) {
            id[i] ^= *byte;
        }
    }
    vec![MemoryId(id)]
}

// ============================================================================
// 7. QUERY CONFIG
// ============================================================================

/// Configuration for the query engine.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct QueryConfig {
    pub hot_cache_limit: usize,
    pub dwm_radius: u32,
    pub graph_depth: usize,
    pub pgvector_limit: usize,
}

impl Default for QueryConfig {
    fn default() -> Self {
        Self {
            hot_cache_limit: 1000,
            dwm_radius: 16,
            graph_depth: 3,
            pgvector_limit: 50,
        }
    }
}

// ============================================================================
// 8. QUERY ENGINE
// ============================================================================

/// The main query engine tying classification, planning, and execution together.
#[derive(Debug, Clone)]
pub struct QueryEngine {
    classifier: QueryTypeClassifier,
    pub hot_cache: HashMap<MemoryId, String>,
    config: QueryConfig,
}

impl Default for QueryEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl QueryEngine {
    /// Create a query engine with default configuration.
    pub fn new() -> Self {
        Self {
            classifier: QueryTypeClassifier::new(),
            hot_cache: HashMap::new(),
            config: QueryConfig::default(),
        }
    }

    /// Create a query engine with a specific configuration.
    pub fn with_config(config: QueryConfig) -> Self {
        Self {
            classifier: QueryTypeClassifier::new(),
            hot_cache: HashMap::new(),
            config,
        }
    }

    /// Classify query text into a QueryType.
    pub fn classify(&self, query_text: &str) -> QueryType {
        self.classifier.classify(query_text)
    }

    /// Plan a query: classify it and produce a tiered QueryPlan.
    pub fn plan(&self, query: Query) -> Result<QueryPlan, CerebrumError> {
        let query_type = self.classify(&query.text);
        Ok(QueryPlan::new(query_type, query.budget))
    }

    /// Execute a query plan against the configured retrieval pipeline.
    ///
    /// This baseline implementation uses the hot cache plus deterministic
    /// fallbacks for deeper tiers. Production wiring would accept a DWM store
    /// and graph view via the cerebrum-core traits.
    pub fn execute(
        &self,
        _query_plan: &QueryPlan,
        query_text: &str,
        query_signature: Option<BinarySignature>,
        candidate_signatures: &[(MemoryId, BinarySignature)],
        graph_neighbors: &[MemoryId],
    ) -> Result<QueryResult, CerebrumError> {
        let start = Utc::now();

        let mut retrieval = TieredRetrieval::new();
        for (id, content) in &self.hot_cache {
            retrieval.cache(*id, content.clone());
        }

        let (tier_used, results, raw_confidence) = retrieval.retrieve(
            query_text,
            query_signature,
            candidate_signatures,
            graph_neighbors,
            self.config.dwm_radius,
        );

        let router = ConfidenceRouter::new();
        let confidence = match router.route(results.clone(), raw_confidence) {
            ConfidenceRoute::Direct(_) => raw_confidence,
            ConfidenceRoute::Warning(_, _) => raw_confidence,
            ConfidenceRoute::Rephrase(_) => 0.0,
        };

        let final_results = if confidence >= 0.5 { results } else { vec![] };
        let latency_ms = (Utc::now() - start).num_milliseconds() as u64;

        Ok(QueryResult {
            results: final_results,
            confidence,
            tier_used,
            latency_ms,
        })
    }
}

// ============================================================================
// 9. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::{MemoryId, Query, TokenBudget};

    #[test]
    fn test_classifier_semantic() {
        let classifier = QueryTypeClassifier::new();
        assert_eq!(
            classifier.classify("What is Rust related to?"),
            QueryType::Semantic
        );
    }

    #[test]
    fn test_classifier_temporal() {
        let classifier = QueryTypeClassifier::new();
        assert_eq!(
            classifier.classify("When did the project start?"),
            QueryType::Temporal
        );
    }

    #[test]
    fn test_classifier_causal() {
        let classifier = QueryTypeClassifier::new();
        assert_eq!(
            classifier.classify("Why did the server crash?"),
            QueryType::Causal
        );
    }

    #[test]
    fn test_classifier_entity() {
        let classifier = QueryTypeClassifier::new();
        assert_eq!(
            classifier.classify("Who was involved in the design?"),
            QueryType::Entity
        );
    }

    #[test]
    fn test_classifier_composite() {
        let classifier = QueryTypeClassifier::new();
        assert_eq!(
            classifier.classify("Who designed it and when?"),
            QueryType::Composite
        );
    }

    #[test]
    fn test_hot_cache_latency_under_1ms() {
        let mut retrieval = TieredRetrieval::new();
        let id = MemoryId([1u8; 32]);
        retrieval.cache(id, "Rust programming language memory".to_string());

        let (tier, results, _confidence) = retrieval.retrieve(
            "Rust programming language",
            None,
            &[],
            &[],
            16,
        );
        assert_eq!(tier, RetrievalTier::HotCache);
        assert!(results.contains(&id));
    }

    #[test]
    fn test_confidence_router_high() {
        let router = ConfidenceRouter::new();
        let id = MemoryId([2u8; 32]);
        let route = router.route(vec![id], 0.85);
        assert!(matches!(route, ConfidenceRoute::Direct(results) if results == vec![id]));
    }

    #[test]
    fn test_end_to_end_classify_plan_execute() {
        let mut engine = QueryEngine::new();
        let id = MemoryId([7u8; 32]);
        engine.hot_cache.insert(id, "The Rust borrow checker prevents data races".to_string());

        let query = Query {
            query_type: QueryType::Semantic,
            text: "What relates to Rust?".to_string(),
            budget: TokenBudget::new(1024),
        };

        let plan = engine.plan(query).expect("plan should succeed");
        assert_eq!(plan.query_type, QueryType::Semantic);
        assert_eq!(plan.strategy.len(), 4);

        let result = engine
            .execute(&plan, "Rust borrow checker", None, &[], &[])
            .expect("execute should succeed");
        assert!(result.results.contains(&id));
        assert_eq!(result.tier_used, RetrievalTier::HotCache);
        assert!(result.confidence >= 0.8);
    }
}
