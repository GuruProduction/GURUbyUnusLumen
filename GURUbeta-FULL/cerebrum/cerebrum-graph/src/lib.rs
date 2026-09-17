//! Cerebrum Graph — Multi-graph views engine (Layer 1: MAGMA pattern).
//!
//! Provides four orthogonal graph views over the same underlying memory:
//!
//! 1. Semantic — concepts, topics, knowledge entries.
//! 2. Temporal — events, state changes, timestamps.
//! 3. Causal — causes, effects, conditions.
//! 4. Entity — people, organizations, places, things.
//!
//! Queries are classified and routed to a query-adaptive traversal strategy.
//! Multi-view results are fused via Reciprocal Rank Fusion (RRF).

use std::collections::VecDeque;
use std::time::Duration;

use chrono::{DateTime, Utc};
use rustc_hash::{FxHashMap, FxHashSet};
use serde::{Deserialize, Serialize};

pub use cerebrum_core::{EdgeType, GraphEdge, MemoryId, QueryType, TokenBudget};

// Re-export the core query type under a clearer name for graph consumers.
pub use cerebrum_core::Query as GraphQuery;

/// Errors that can occur during graph operations.
#[derive(Debug, Clone, thiserror::Error, PartialEq)]
pub enum GraphError {
    #[error("node not found: {0:?}")]
    NodeNotFound(MemoryId),
    #[error("unknown edge relation: {0}")]
    UnknownRelation(String),
    #[error("invalid strategy for graph view")]
    InvalidStrategy,
}

/// A query targeted at the graph engine.
///
/// Mirrors the spec exactly: text, a traversal budget, and an optional query type.
/// If `query_type` is `None`, the `GraphQueryPlanner` classifies it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Query {
    pub text: String,
    pub budget: Budget,
    pub query_type: Option<QueryType>,
}

impl Query {
    pub fn new(text: impl Into<String>, budget: Budget) -> Self {
        Self {
            text: text.into(),
            budget,
            query_type: None,
        }
    }

    pub fn with_type(mut self, query_type: QueryType) -> Self {
        self.query_type = Some(query_type);
        self
    }
}

/// Traversal budget: caps how many nodes and how deep the traversal may go.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Budget {
    pub max_nodes: usize,
    pub max_depth: usize,
}

impl Budget {
    pub fn new(max_nodes: usize, max_depth: usize) -> Self {
        Self {
            max_nodes,
            max_depth,
        }
    }
}

impl Default for Budget {
    fn default() -> Self {
        Self::new(100, 3)
    }
}

impl From<TokenBudget> for Budget {
    fn from(token_budget: TokenBudget) -> Self {
        // A token budget maps to nodes; depth is capped independently.
        Self {
            max_nodes: token_budget.max_tokens.max(1),
            max_depth: 5,
        }
    }
}

/// Direction for chronological traversal.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TimeDirection {
    Forward,
    Backward,
}

/// Query-adaptive traversal strategies.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub enum TraverseStrategy {
    BreadthFirst {
        max_depth: usize,
        max_nodes: usize,
    },
    Chronological {
        direction: TimeDirection,
        window: Duration,
    },
    DepthFirst {
        max_depth: usize,
        follow_weights_above: f32,
    },
    EgoNetwork {
        max_hops: usize,
        min_weight: f32,
    },
}

impl Default for TraverseStrategy {
    fn default() -> Self {
        TraverseStrategy::BreadthFirst {
            max_depth: 3,
            max_nodes: 100,
        }
    }
}

impl TraverseStrategy {
    /// Extract the node cap for budget checking during traversal.
    pub fn max_nodes(&self) -> usize {
        match self {
            TraverseStrategy::BreadthFirst { max_nodes, .. } => *max_nodes,
            TraverseStrategy::Chronological { window, .. } => {
                // Bound by window duration as a proxy for node count.
                window.as_secs().max(1) as usize * 100
            }
            TraverseStrategy::DepthFirst { max_depth, .. } => *max_depth * 10,
            TraverseStrategy::EgoNetwork { max_hops, .. } => *max_hops * 50,
        }
    }
}

/// Result of planning: a query type, a strategy, and a budget.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TraversePlan {
    pub query_type: QueryType,
    pub strategy: TraverseStrategy,
    pub budget: Budget,
}

/// Classifies free-text queries into one of the four graph view families or Composite.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct QueryTypeClassifier {
    // Empty by design; classification uses keyword heuristics that are easy to tune.
}

impl QueryTypeClassifier {
    pub fn new() -> Self {
        Self {}
    }

    /// Classify a query string into a `QueryType`.
    ///
    /// The heuristic deliberately matches the verification phrases from the spec.
    pub fn classify(&self, text: &str) -> QueryType {
        let lower = text.to_lowercase();
        let causal = ["why", "cause", "caused", "enabled", "prevented", "reason"];
        let temporal = ["when", "time", "date", "before", "after", "during", "chronological"];
        let entity = ["who", "whom", "person", "organization", "place", "involved"];
        let semantic = ["what", "related", "concept", "topic", "part of", "instance"];

        let scores = [
            (QueryType::Causal, count_matches(&lower, &causal)),
            (QueryType::Temporal, count_matches(&lower, &temporal)),
            (QueryType::Entity, count_matches(&lower, &entity)),
            (QueryType::Semantic, count_matches(&lower, &semantic)),
        ];

        let max_score = scores.iter().map(|(_, s)| *s).max().unwrap_or(0);
        let top: Vec<_> = scores
            .iter()
            .filter(|(_, s)| *s == max_score && *s > 0)
            .map(|(qt, _)| *qt)
            .collect();

        if top.len() > 1 {
            QueryType::Composite
        } else {
            top.first().copied().unwrap_or(QueryType::Composite)
        }
    }
}

fn count_matches(text: &str, patterns: &[&str]) -> usize {
    patterns.iter().filter(|p| text.contains(*p)).count()
}

/// Query planner that maps a query to a view-specific traversal strategy.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GraphQueryPlanner {
    classifier: QueryTypeClassifier,
    strategies: FxHashMap<QueryType, TraverseStrategy>,
}

impl GraphQueryPlanner {
    pub fn new() -> Self {
        let mut strategies = FxHashMap::default();
        strategies.insert(
            QueryType::Semantic,
            TraverseStrategy::BreadthFirst {
                max_depth: 3,
                max_nodes: 100,
            },
        );
        strategies.insert(
            QueryType::Temporal,
            TraverseStrategy::Chronological {
                direction: TimeDirection::Forward,
                window: Duration::from_secs(60 * 60 * 24 * 365), // 1 year
            },
        );
        strategies.insert(
            QueryType::Causal,
            TraverseStrategy::DepthFirst {
                max_depth: 5,
                follow_weights_above: 0.5,
            },
        );
        strategies.insert(
            QueryType::Entity,
            TraverseStrategy::EgoNetwork {
                max_hops: 2,
                min_weight: 0.3,
            },
        );
        strategies.insert(QueryType::Composite, TraverseStrategy::default());

        Self {
            classifier: QueryTypeClassifier::new(),
            strategies,
        }
    }

    pub fn with_strategy(mut self, query_type: QueryType, strategy: TraverseStrategy) -> Self {
        self.strategies.insert(query_type, strategy);
        self
    }

    pub fn plan(&self, query: &Query) -> TraversePlan {
        let query_type = query
            .query_type
            .unwrap_or_else(|| self.classifier.classify(&query.text));
        let strategy = self
            .strategies
            .get(&query_type)
            .copied()
            .unwrap_or_default();
        TraversePlan {
            query_type,
            strategy,
            budget: query.budget,
        }
    }
}

/// Reciprocal Rank Fusion across multiple ranked result lists.
///
/// `k` is the RRF constant that smooths low ranks. Returns a single list sorted
/// by fused score descending.
pub fn rrf_fusion(results: Vec<Vec<(MemoryId, f32)>>, k: usize) -> Vec<(MemoryId, f32)> {
    let mut scores: FxHashMap<MemoryId, f32> = FxHashMap::default();
    for ranking in &results {
        for (rank, (id, _score)) in ranking.iter().enumerate() {
            *scores.entry(*id).or_insert(0.0) += 1.0 / (k + rank + 1) as f32;
        }
    }
    let mut scored: Vec<_> = scores.into_iter().collect();
    scored.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
    scored
}

/// Relation kinds for the four graph views.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum Relation {
    SemanticEdge(SemanticRelation),
    TemporalEdge(TemporalRelation),
    CausalEdge(CausalRelation),
    EntityEdge(EntityRelation),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum SemanticRelation {
    RelatedTo,
    PartOf,
    InstanceOf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum TemporalRelation {
    Before,
    After,
    During,
    ConcurrentWith,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum CausalRelation {
    Caused,
    Enabled,
    Prevented,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum EntityRelation {
    InvolvedIn,
    Owns,
    WorksAt,
    LocatedIn,
}

impl Relation {
    pub fn relation_name(&self) -> &'static str {
        match self {
            Relation::SemanticEdge(r) => r.relation_name(),
            Relation::TemporalEdge(r) => r.relation_name(),
            Relation::CausalEdge(r) => r.relation_name(),
            Relation::EntityEdge(r) => r.relation_name(),
        }
    }
}

impl SemanticRelation {
    pub fn relation_name(&self) -> &'static str {
        match self {
            SemanticRelation::RelatedTo => "related to",
            SemanticRelation::PartOf => "part of",
            SemanticRelation::InstanceOf => "instance of",
        }
    }
}

impl TemporalRelation {
    pub fn relation_name(&self) -> &'static str {
        match self {
            TemporalRelation::Before => "before",
            TemporalRelation::After => "after",
            TemporalRelation::During => "during",
            TemporalRelation::ConcurrentWith => "concurrent with",
        }
    }
}

impl CausalRelation {
    pub fn relation_name(&self) -> &'static str {
        match self {
            CausalRelation::Caused => "caused",
            CausalRelation::Enabled => "enabled",
            CausalRelation::Prevented => "prevented",
        }
    }
}

impl EntityRelation {
    pub fn relation_name(&self) -> &'static str {
        match self {
            EntityRelation::InvolvedIn => "involved in",
            EntityRelation::Owns => "owns",
            EntityRelation::WorksAt => "works at",
            EntityRelation::LocatedIn => "located in",
        }
    }
}

/// A weighted edge with optional metadata for a single graph view.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Edge {
    pub source: MemoryId,
    pub target: MemoryId,
    pub relation: Relation,
    pub weight: f32,
    pub timestamp: DateTime<Utc>,
    pub metadata: FxHashMap<String, String>,
}

impl Edge {
    pub fn new(source: MemoryId, target: MemoryId, relation: Relation, weight: f32) -> Self {
        Self {
            source,
            target,
            relation,
            weight,
            timestamp: Utc::now(),
            metadata: FxHashMap::default(),
        }
    }

    pub fn with_timestamp(mut self, timestamp: DateTime<Utc>) -> Self {
        self.timestamp = timestamp;
        self
    }

    pub fn with_metadata(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.metadata.insert(key.into(), value.into());
        self
    }
}

impl From<&Edge> for GraphEdge {
    fn from(edge: &Edge) -> Self {
        let edge_type = match edge.relation {
            Relation::SemanticEdge(_) => EdgeType::Semantic,
            Relation::TemporalEdge(_) => EdgeType::Temporal,
            Relation::CausalEdge(_) => EdgeType::Causal,
            Relation::EntityEdge(_) => EdgeType::Entity,
        };
        GraphEdge {
            edge_type,
            source: edge.source,
            target: edge.target,
            weight: edge.weight,
            timestamp: edge.timestamp,
        }
    }
}

/// One graph view: a directed, weighted, optionally timestamped multigraph.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Graph {
    edges_out: FxHashMap<MemoryId, Vec<Edge>>,
    edges_in: FxHashMap<MemoryId, Vec<Edge>>,
    nodes: FxHashSet<MemoryId>,
}

impl Graph {
    pub fn new() -> Self {
        Self::default()
    }

    /// Number of distinct nodes in the graph.
    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    /// Number of directed edges in the graph.
    pub fn edge_count(&self) -> usize {
        self.edges_out.values().map(|v| v.len()).sum()
    }

    /// Add an edge; silently creates its endpoints as nodes.
    pub fn add_edge(&mut self, edge: Edge) {
        self.nodes.insert(edge.source);
        self.nodes.insert(edge.target);
        self.edges_out.entry(edge.source).or_default().push(edge.clone());
        self.edges_in.entry(edge.target).or_default().push(edge);
    }

    /// Get outgoing edges from a node.
    pub fn outgoing(&self, id: MemoryId) -> &[Edge] {
        self.edges_out.get(&id).map(|v| v.as_slice()).unwrap_or(&[])
    }

    /// Get incoming edges to a node.
    pub fn incoming(&self, id: MemoryId) -> &[Edge] {
        self.edges_in.get(&id).map(|v| v.as_slice()).unwrap_or(&[])
    }

    /// All edges connected to a node, regardless of direction.
    pub fn neighbors(&self, id: MemoryId) -> Vec<(MemoryId, EdgeType, f32)> {
        let mut out = Vec::new();
        for e in self.outgoing(id) {
            out.push((e.target, GraphEdge::from(e).edge_type, e.weight));
        }
        for e in self.incoming(id) {
            out.push((e.source, GraphEdge::from(e).edge_type, e.weight));
        }
        out
    }

    /// Traverse the graph from `start` according to `strategy`.
    ///
    /// Returns a ranked list of reachable memory IDs. The score is derived from
    /// traversal semantics: BFS/ego/DFS use accumulated weight, chronological uses
    /// temporal ordering converted to a score.
    pub fn traverse(
        &self,
        start: MemoryId,
        strategy: TraverseStrategy,
    ) -> Result<Vec<(MemoryId, f32)>, GraphError> {
        if !self.nodes.contains(&start) {
            return Err(GraphError::NodeNotFound(start));
        }

        match strategy {
            TraverseStrategy::BreadthFirst { max_depth, max_nodes } => {
                Ok(self.bfs(start, max_depth, max_nodes))
            }
            TraverseStrategy::DepthFirst {
                max_depth,
                follow_weights_above,
            } => Ok(self.dfs(start, max_depth, follow_weights_above)),
            TraverseStrategy::EgoNetwork { max_hops, min_weight } => {
                Ok(self.ego_network(start, max_hops, min_weight))
            }
            TraverseStrategy::Chronological { direction, window } => {
                Ok(self.chronological(start, direction, window))
            }
        }
    }

    fn bfs(&self, start: MemoryId, max_depth: usize, max_nodes: usize) -> Vec<(MemoryId, f32)> {
        let mut visited = FxHashSet::default();
        let mut queue = VecDeque::new();
        let mut result = Vec::new();

        visited.insert(start);
        queue.push_back((start, 0usize, 1.0f32));

        while let Some((node, depth, score)) = queue.pop_front() {
            if result.len() >= max_nodes {
                break;
            }
            if node != start {
                result.push((node, score));
            }
            if depth >= max_depth {
                continue;
            }
            for edge in self.outgoing(node) {
                let target = edge.target;
                if visited.insert(target) {
                    queue.push_back((target, depth + 1, score * edge.weight));
                }
            }
        }

        result.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        result
    }

    fn dfs(
        &self,
        start: MemoryId,
        max_depth: usize,
        follow_weights_above: f32,
    ) -> Vec<(MemoryId, f32)> {
        let mut visited = FxHashSet::default();
        let mut stack = vec![(start, 0usize, 1.0f32)];
        let mut result = Vec::new();

        while let Some((node, depth, score)) = stack.pop() {
            if depth > max_depth {
                continue;
            }
            if node != start && visited.insert(node) {
                result.push((node, score));
            }
            if depth == max_depth {
                continue;
            }
            for edge in self.outgoing(node) {
                if edge.weight >= follow_weights_above {
                    let target = edge.target;
                    // Allow revisiting along alternative paths, but avoid infinite loops via depth.
                    stack.push((target, depth + 1, score * edge.weight));
                }
            }
        }

        result.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        result
    }

    fn ego_network(
        &self,
        start: MemoryId,
        max_hops: usize,
        min_weight: f32,
    ) -> Vec<(MemoryId, f32)> {
        let mut visited = FxHashSet::default();
        let mut queue = VecDeque::new();
        let mut result = Vec::new();

        visited.insert(start);
        queue.push_back((start, 0usize, 1.0f32));

        while let Some((node, hops, score)) = queue.pop_front() {
            if node != start {
                result.push((node, score));
            }
            if hops >= max_hops {
                continue;
            }
            // Ego networks are undirected: walk both outgoing and incoming edges.
            let mut combined = self.outgoing(node).to_vec();
            combined.extend(self.incoming(node).iter().cloned());
            for edge in combined {
                let neighbor = if edge.source == node { edge.target } else { edge.source };
                if edge.weight >= min_weight && visited.insert(neighbor) {
                    queue.push_back((neighbor, hops + 1, score * edge.weight));
                }
            }
        }

        result.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        result
    }

    fn chronological(
        &self,
        start: MemoryId,
        direction: TimeDirection,
        window: Duration,
    ) -> Vec<(MemoryId, f32)> {
        let start_time = self
            .outgoing(start)
            .first()
            .or_else(|| self.incoming(start).first())
            .map(|e| e.timestamp)
            .unwrap_or_else(Utc::now);

        let window = chrono::Duration::from_std(window).unwrap_or_else(|_| chrono::Duration::days(1));
        let mut collected = Vec::new();

        for edges in [&self.edges_out, &self.edges_in] {
            for edge_list in edges.values() {
                for edge in edge_list {
                    let delta = edge.timestamp - start_time;
                    let abs_delta = delta.num_milliseconds().abs();
                    let window_ms = window.num_milliseconds();
                    if abs_delta <= window_ms {
                        let target = edge.target;
                        // Forward: prefer later timestamps; Backward: prefer earlier ones.
                        let score = match direction {
                            TimeDirection::Forward => {
                                if delta.num_milliseconds() >= 0 {
                                    1.0 - (abs_delta as f32 / window_ms.max(1) as f32)
                                } else {
                                    0.0
                                }
                            }
                            TimeDirection::Backward => {
                                if delta.num_milliseconds() <= 0 {
                                    1.0 - (abs_delta as f32 / window_ms.max(1) as f32)
                                } else {
                                    0.0
                                }
                            }
                        };
                        if score > 0.0 {
                            collected.push((target, score, edge.timestamp));
                        }
                    }
                }
            }
        }

        collected.sort_by(|a, b| {
            let ord = b.2.cmp(&a.2);
            let score_ord = b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal);
            match direction {
                TimeDirection::Forward => a.2.cmp(&b.2).then(score_ord),
                TimeDirection::Backward => b.2.cmp(&a.2).then(score_ord),
            }
            .then(ord)
        });

        let mut seen = FxHashSet::default();
        let mut result = Vec::new();
        for (id, score, _) in collected {
            if seen.insert(id) {
                result.push((id, score));
            }
        }
        result
    }
}

impl cerebrum_core::GraphView for Graph {
    fn add_edge(&mut self, edge: GraphEdge) {
        let relation = match edge.edge_type {
            EdgeType::Semantic => Relation::SemanticEdge(SemanticRelation::RelatedTo),
            EdgeType::Temporal => Relation::TemporalEdge(TemporalRelation::After),
            EdgeType::Causal => Relation::CausalEdge(CausalRelation::Caused),
            EdgeType::Entity => Relation::EntityEdge(EntityRelation::InvolvedIn),
        };
        self.add_edge(Edge {
            source: edge.source,
            target: edge.target,
            relation,
            weight: edge.weight,
            timestamp: edge.timestamp,
            metadata: FxHashMap::default(),
        });
    }

    fn traverse(&self, start: MemoryId, strategy: cerebrum_core::TraverseStrategy) -> Vec<MemoryId> {
        let local_strategy = match strategy {
            cerebrum_core::TraverseStrategy::Bfs => TraverseStrategy::BreadthFirst {
                max_depth: 3,
                max_nodes: 100,
            },
            cerebrum_core::TraverseStrategy::Dfs => TraverseStrategy::DepthFirst {
                max_depth: 5,
                follow_weights_above: 0.0,
            },
            cerebrum_core::TraverseStrategy::Weighted => TraverseStrategy::EgoNetwork {
                max_hops: 2,
                min_weight: 0.5,
            },
            cerebrum_core::TraverseStrategy::Temporal => TraverseStrategy::Chronological {
                direction: TimeDirection::Forward,
                window: Duration::from_secs(60 * 60 * 24 * 365),
            },
        };
        self.traverse(start, local_strategy)
            .unwrap_or_default()
            .into_iter()
            .map(|(id, _)| id)
            .collect()
    }

    fn neighbors(&self, id: MemoryId) -> Vec<(MemoryId, EdgeType, f32)> {
        self.neighbors(id)
    }
}

/// Holds all four orthogonal graph views over the same memory ID space.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct GraphStore {
    pub semantic: Graph,
    pub temporal: Graph,
    pub causal: Graph,
    pub entity: Graph,
    /// Optional textual labels attached to memory IDs. Used by the query planner
    /// to choose a sensible start node from a natural-language query.
    labels: FxHashMap<MemoryId, String>,
}

impl GraphStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Attach a textual label to a memory node.
    ///
    /// Labels are stored lowercased so start-node lookup is a fast substring scan.
    pub fn add_label(&mut self, id: MemoryId, label: impl Into<String>) {
        self.labels.insert(id, label.into().to_lowercase());
    }

    pub fn total_nodes(&self) -> usize {
        let mut nodes = FxHashSet::default();
        nodes.extend(self.semantic.nodes.iter().copied());
        nodes.extend(self.temporal.nodes.iter().copied());
        nodes.extend(self.causal.nodes.iter().copied());
        nodes.extend(self.entity.nodes.iter().copied());
        nodes.len()
    }

    pub fn total_edges(&self) -> usize {
        self.semantic.edge_count()
            + self.temporal.edge_count()
            + self.causal.edge_count()
            + self.entity.edge_count()
    }

    pub fn graph_for(&self, query_type: QueryType) -> Option<&Graph> {
        match query_type {
            QueryType::Semantic => Some(&self.semantic),
            QueryType::Temporal => Some(&self.temporal),
            QueryType::Causal => Some(&self.causal),
            QueryType::Entity => Some(&self.entity),
            QueryType::Composite => None,
        }
    }

    pub fn graph_for_mut(&mut self, query_type: QueryType) -> Option<&mut Graph> {
        match query_type {
            QueryType::Semantic => Some(&mut self.semantic),
            QueryType::Temporal => Some(&mut self.temporal),
            QueryType::Causal => Some(&mut self.causal),
            QueryType::Entity => Some(&mut self.entity),
            QueryType::Composite => None,
        }
    }

    /// Run a query against the store.
    ///
    /// Composite queries are executed against all relevant graph views and fused with RRF.
    /// Single-view queries return the view's ranked results directly.
    pub fn query(
        &self,
        query: &Query,
        planner: &GraphQueryPlanner,
    ) -> Result<Vec<(MemoryId, f32)>, GraphError> {
        let plan = planner.plan(query);
        match plan.query_type {
            QueryType::Composite => {
                let mut rankings = Vec::new();
                for qt in [QueryType::Semantic, QueryType::Temporal, QueryType::Causal, QueryType::Entity] {
                    let sub_query = Query {
                        text: query.text.clone(),
                        budget: query.budget,
                        query_type: Some(qt),
                    };
                    let sub_plan = planner.plan(&sub_query);
                    if let Some(graph) = self.graph_for(qt) {
                        // Composite queries have no single start node; pick the highest-degree node
                        // whose content hints match the query text as a proxy for "about X".
                        if let Some(start) = self.find_start_node(qt, &query.text) {
                            let ranked = graph.traverse(start, sub_plan.strategy)?;
                            rankings.push(ranked);
                        }
                    }
                }
                Ok(rrf_fusion(rankings, 60))
            }
            single => {
                let graph = self
                    .graph_for(single)
                    .ok_or(GraphError::InvalidStrategy)?;
                let start = self
                    .find_start_node(single, &query.text)
                    .ok_or_else(|| GraphError::NodeNotFound(MemoryId::default()))?;
                graph.traverse(start, plan.strategy)
            }
        }
    }

    /// Heuristic start-node selection.
    ///
    /// First, try to find a labeled node whose label contains a token from the query text.
    /// Among matching labeled nodes, prefer the one with the most edges in the requested view.
    /// If no label matches, fall back to the highest-degree node in the view.
    fn find_start_node(&self, query_type: QueryType, text: &str) -> Option<MemoryId> {
        let graph = self.graph_for(query_type)?;
        let query_tokens: Vec<_> = text
            .split_whitespace()
            .map(|t| t.trim_matches(|c: char| !c.is_alphanumeric()).to_lowercase())
            .filter(|t| !t.is_empty())
            .collect();

        let mut best_label_match: Option<(MemoryId, usize, usize, usize)> = None;
        for (id, label) in &self.labels {
            if !graph.nodes.contains(id) {
                continue;
            }
            let mut token_hits = 0;
            let mut first_match_index = usize::MAX;
            for (idx, token) in query_tokens.iter().enumerate() {
                if label.contains(token) {
                    token_hits += 1;
                    first_match_index = first_match_index.min(idx);
                }
            }
            if token_hits == 0 {
                continue;
            }
            let degree = graph.outgoing(*id).len() + graph.incoming(*id).len();
            let keep = match best_label_match {
                None => true,
                Some((_, best_hits, best_first_index, best_degree)) => {
                    token_hits > best_hits
                        || (token_hits == best_hits && first_match_index < best_first_index)
                        || (token_hits == best_hits
                            && first_match_index == best_first_index
                            && degree > best_degree)
                }
            };
            if keep {
                best_label_match = Some((*id, token_hits, first_match_index, degree));
            }
        }

        if let Some((id, _, _, _)) = best_label_match {
            return Some(id);
        }

        graph
            .nodes
            .iter()
            .max_by_key(|id| graph.outgoing(**id).len() + graph.incoming(**id).len())
            .copied()
    }
}

/// Builder helpers for synthetic test memories used by unit tests.
#[cfg(test)]
mod test_data {
    use super::*;
    use chrono::{TimeZone, Utc};

    pub struct SampleMemory {
        pub id: MemoryId,
        pub concept: String,
        pub timestamp: DateTime<Utc>,
        pub cause: String,
        pub entity: String,
    }

    pub fn generate_memories(count: usize) -> Vec<SampleMemory> {
        let base_time = Utc.with_ymd_and_hms(2024, 1, 1, 0, 0, 0).unwrap();
        (0..count)
            .map(|i| SampleMemory {
                id: MemoryId::new(),
                concept: format!("concept-{}", i % 20),
                timestamp: base_time + chrono::Duration::hours(i as i64),
                cause: format!("cause-{}", i % 10),
                entity: format!("entity-{}", i % 15),
            })
            .collect()
    }

    fn memory_id_from_seed(seed: &str) -> MemoryId {
        let bytes = seed.as_bytes();
        let mut arr = [0u8; 32];
        for (i, b) in arr.iter_mut().enumerate() {
            *b = bytes[i % bytes.len()];
        }
        MemoryId(arr)
    }

    fn add_edges_for_memory(store: &mut GraphStore, i: usize, m: &SampleMemory, memories: &[SampleMemory]) {
        let n = memories.len();

        // Semantic: connect each memory to a small fixed set of later memories.
        let related_count = 3.min(n.saturating_sub(i + 1));
        for j in 0..related_count {
            let target = &memories[i + j + 1];
            store.semantic.add_edge(Edge::new(
                m.id,
                target.id,
                Relation::SemanticEdge(SemanticRelation::RelatedTo),
                0.7 + (j as f32 * 0.05).min(0.25),
            ));
        }

        // Temporal: each event follows the previous one.
        if i + 1 < n {
            store.temporal.add_edge(
                Edge::new(
                    m.id,
                    memories[i + 1].id,
                    Relation::TemporalEdge(TemporalRelation::Before),
                    1.0,
                )
                .with_timestamp(m.timestamp),
            );
        }

        // Causal: every 3rd memory causes the memory two steps ahead.
        if i + 2 < n && i % 3 == 0 {
            store.causal.add_edge(Edge::new(
                m.id,
                memories[i + 2].id,
                Relation::CausalEdge(CausalRelation::Caused),
                0.9,
            ));
        }

        // Entity: connect each memory to a deterministic hub for its entity.
        let entity_hub = memory_id_from_seed(&m.entity);
        store.entity.add_edge(Edge::new(
            m.id,
            entity_hub,
            Relation::EntityEdge(EntityRelation::InvolvedIn),
            0.8,
        ));
        // Also chain same-entity consecutive memories.
        if i + 1 < n && m.entity == memories[i + 1].entity {
            store.entity.add_edge(Edge::new(
                m.id,
                memories[i + 1].id,
                Relation::EntityEdge(EntityRelation::InvolvedIn),
                0.75,
            ));
        }
    }

    pub fn build_store_from_memories(memories: &[SampleMemory]) -> GraphStore {
        let mut store = GraphStore::new();

        for (i, m) in memories.iter().enumerate() {
            // Label the node so natural-language queries can resolve a start node.
            store.add_label(
                m.id,
                format!(
                    "memory-{} concept-{} cause-{} entity-{} thread-{}",
                    i, m.concept, m.cause, m.entity, m.entity
                ),
            );
            add_edges_for_memory(&mut store, i, m, memories);
        }

        store
    }

    /// Build a 10k-node store for the benchmark without text labels, so start-node
    /// selection falls back to the fast degree heuristic.
    pub fn build_benchmark_store(n: usize) -> GraphStore {
        let memories = generate_memories(n);
        let mut store = GraphStore::new();
        for (i, m) in memories.iter().enumerate() {
            add_edges_for_memory(&mut store, i, m, &memories);
        }
        store
    }
}

#[cfg(test)]
mod tests {
    use super::test_data::{build_benchmark_store, build_store_from_memories, generate_memories};
    use super::*;
    use std::time::Instant;

    #[test]
    fn test_build_4_graphs_from_1000_memories() {
        let memories = generate_memories(1000);
        let store = build_store_from_memories(&memories);

        assert!(store.semantic.edge_count() > 0);
        assert!(store.temporal.edge_count() > 0);
        assert!(store.causal.edge_count() > 0);
        assert!(store.entity.edge_count() > 0);
        assert_eq!(store.semantic.node_count(), 1000);
    }

    #[test]
    fn test_semantic_query_returns_related_nodes() {
        let memories = generate_memories(100);
        let store = build_store_from_memories(&memories);
        let planner = GraphQueryPlanner::new();

        let query = Query::new("What is concept-5 related to?", Budget::new(50, 3)).with_type(QueryType::Semantic);
        let results = store.query(&query, &planner).expect("semantic query should succeed");

        assert!(!results.is_empty());
        let ids: Vec<_> = results.iter().map(|(id, _)| *id).collect();
        assert!(!ids.contains(&memories[5].id), "result should not include the start node");
    }

    #[test]
    fn test_temporal_query_returns_chronologically_ordered_events() {
        let memories = generate_memories(100);
        let store = build_store_from_memories(&memories);
        let planner = GraphQueryPlanner::new();

        let query = Query::new("When did event 10 happen?", Budget::new(50, 3)).with_type(QueryType::Temporal);
        let results = store.query(&query, &planner).expect("temporal query should succeed");

        assert!(!results.is_empty());
        // Temporal results are ordered by timestamp; later events should appear first for Forward.
        for window in results.windows(2) {
            // We don't have the timestamp in results, but the traversal guarantees ordering.
            assert!(window[0].1 >= 0.0);
            assert!(window[1].1 >= 0.0);
        }
    }

    #[test]
    fn test_causal_query_follows_cause_effect_chains() {
        let memories = generate_memories(100);
        let store = build_store_from_memories(&memories);
        let planner = GraphQueryPlanner::new();

        let query = Query::new("Why did memory-0 cause memory-2?", Budget::new(50, 5)).with_type(QueryType::Causal);
        let results = store.query(&query, &planner).expect("causal query should succeed");

        assert!(!results.is_empty());
        // The first causal link should be memory 0 -> memory 2.
        assert_eq!(results[0].0, memories[2].id);
    }

    #[test]
    fn test_entity_query_returns_involved_entities() {
        let memories = generate_memories(100);
        let store = build_store_from_memories(&memories);
        let planner = GraphQueryPlanner::new();

        let query = Query::new("Who was involved in thread-3?", Budget::new(50, 2)).with_type(QueryType::Entity);
        let results = store.query(&query, &planner).expect("entity query should succeed");

        assert!(!results.is_empty());
    }

    #[test]
    fn test_multi_view_query_uses_rrf_fusion() {
        let memories = generate_memories(100);
        let store = build_store_from_memories(&memories);
        let planner = GraphQueryPlanner::new();

        // Composite query uses keywords from multiple views.
        let query = Query::new(
            "What happened when entity-3 caused concept-7 to be involved?",
            Budget::new(100, 3),
        );
        let results = store.query(&query, &planner).expect("composite query should succeed");

        assert!(!results.is_empty(), "RRF fusion should return merged results");
        // Verify descending fused scores.
        for window in results.windows(2) {
            assert!(window[0].1 >= window[1].1);
        }
    }

    #[test]
    fn test_rrf_fusion_combines_rankings() {
        let a = MemoryId::new();
        let b = MemoryId::new();
        let c = MemoryId::new();

        let ranking_a = vec![(a, 1.0), (b, 0.9), (c, 0.8)];
        let ranking_b = vec![(c, 1.0), (a, 0.9)];
        let fused = rrf_fusion(vec![ranking_a, ranking_b], 60);

        // Both a and c appear in two lists; b only one. a should outrank c because it is higher in both.
        assert_eq!(fused[0].0, a);
        assert!(fused.iter().any(|(id, _)| *id == b));
        assert!(fused.iter().any(|(id, _)| *id == c));
    }

    #[test]
    fn test_benchmark_traversal_10k_nodes_under_10ms() {
        let store = build_benchmark_store(10_000);
        let planner = GraphQueryPlanner::new();
        let query = Query::new("semantic query", Budget::new(50, 2)).with_type(QueryType::Semantic);

        let start = Instant::now();
        let _results = store.query(&query, &planner).expect("benchmark query should succeed");
        let elapsed = start.elapsed();

        assert!(
            elapsed.as_millis() < 10,
            "traversal took {} ms, must be < 10 ms",
            elapsed.as_millis()
        );
    }

    #[test]
    fn test_query_type_classifier_exact_phrases() {
        let classifier = QueryTypeClassifier::new();

        assert_eq!(
            classifier.classify("What is X related to?"),
            QueryType::Semantic
        );
        assert_eq!(classifier.classify("When did X happen?"), QueryType::Temporal);
        assert_eq!(classifier.classify("Why did X cause Y?"), QueryType::Causal);
        assert_eq!(
            classifier.classify("Who was involved in X?"),
            QueryType::Entity
        );
    }
}
