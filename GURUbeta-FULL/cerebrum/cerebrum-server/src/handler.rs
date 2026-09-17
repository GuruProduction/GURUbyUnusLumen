//! Cerebrum request handler — maps incoming protocol frames to subsystem calls.
//!
//! Each handler:
//! 1. Deserializes the JSON payload.
//! 2. Executes the operation against `CerebrumState`.
//! 3. Serializes the result back to JSON.
//!
//! On failure an `ErrorResponse` is returned so the connection layer can emit an
//! ERROR frame with the original `request_id`.

use std::sync::Arc;

use cerebrum_core::{CurateOp, DereferenceLevel, MemoryId, Query};
use cerebrum_curate::{ContextEntry, CurateExecutor, CurateOperation};
use cerebrum_graph::{Budget as GraphBudget, Query as GraphQuery};
use cerebrum_prefetch::PrefetchTrigger;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::frame_types;
use crate::state::CerebrumState;
use crate::Frame;

/// Error value returned from a handler to signal an ERROR frame response.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub message: String,
}

impl ErrorResponse {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

/// Dispatch a request `Frame` to the appropriate subsystem handler.
///
/// Returns a JSON payload on success, or an `ErrorResponse` on failure.
pub async fn handle_frame(
    request: Frame,
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let frame_type = request.header.frame_type;
    let payload = request.payload;

    match frame_type {
        frame_types::QUERY => handle_query(&payload, state).await,
        frame_types::CURATE => handle_curate(&payload, state).await,
        frame_types::RETRIEVE => handle_retrieve(&payload, state).await,
        frame_types::SEARCH => handle_search(&payload, state).await,
        frame_types::GRAPH_TRAVERSE => handle_graph_traverse(&payload, state).await,
        frame_types::CONSOLIDATE => handle_consolidate(state).await,
        frame_types::PREFETCH => handle_prefetch(&payload, state).await,
        _ => Err(ErrorResponse::new(format!(
            "unknown frame type: 0x{:02x}",
            frame_type
        ))),
    }
}

// ============================================================================
// QUERY
// ============================================================================

async fn handle_query(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let query: Query =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let state = state.lock().await;

    // Use the state's query engine with its hot cache already populated.
    let plan = state
        .query_engine
        .plan(query.clone())
        .map_err(|e| ErrorResponse::new(e.to_string()))?;

    let query_type = state.query_classifier.classify(&query.text);

    // Pull graph neighbours as an additional signal for the query engine.
    let graph_query = GraphQuery::new(query.text.clone(), GraphBudget::new(50, 3)).with_type(query_type);
    let graph_neighbors: Vec<MemoryId> = state
        .graph_store
        .query(&graph_query, &state.graph_planner)
        .map(|results| results.into_iter().map(|(id, _)| id).collect())
        .unwrap_or_default();

    let result = state
        .query_engine
        .execute(&plan, &query.text, None, &[], &graph_neighbors)
        .map_err(|e| ErrorResponse::new(e.to_string()))?;

    serde_json::to_vec(&result).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// CURATE
// ============================================================================

async fn handle_curate(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    // Wire format: an array of core CurateOps, converted to curate-internal CurateOperations.
    let ops: Vec<CurateOp> =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let curate_ops: Vec<CurateOperation> = ops
        .into_iter()
        .map(core_op_to_curate_op)
        .collect::<Result<Vec<_>, _>>()?;

    let mut state = state.lock().await;
    let mut executor = CurateExecutor::new(&mut state.context_tree);
    let result = executor.execute(curate_ops);

    serde_json::to_vec(&result).map_err(|e| ErrorResponse::new(e.to_string()))
}

/// Convert a core CurateOp (wire format) into a curate-internal CurateOperation.
fn core_op_to_curate_op(op: CurateOp) -> Result<CurateOperation, ErrorResponse> {
    match op {
        CurateOp::Add { entry, reason } => {
            let curated = ContextEntry::new(
                entry.memory_id,
                entry.domain,
                entry.topic,
                entry.subtopic,
                entry.content,
            );
            Ok(CurateOperation::add(curated, reason))
        }
        CurateOp::Update {
            memory_id,
            changes,
            reason,
        } => {
            let updated = ContextEntry::new(
                memory_id,
                changes.domain.unwrap_or_default(),
                changes.topic.unwrap_or_default(),
                changes.subtopic.unwrap_or_default(),
                changes.content.unwrap_or_default(),
            );
            Ok(CurateOperation::update(memory_id, updated, reason))
        }
        CurateOp::Upsert { entry, reason } => {
            let curated = ContextEntry::new(
                entry.memory_id,
                entry.domain,
                entry.topic,
                entry.subtopic,
                entry.content,
            );
            Ok(CurateOperation::upsert(curated, reason))
        }
        CurateOp::Merge {
            source_ids,
            target,
            reason,
        } => {
            let curated = ContextEntry::new(
                target.memory_id,
                target.domain,
                target.topic,
                target.subtopic,
                target.content,
            );
            Ok(CurateOperation::merge(source_ids, curated, reason))
        }
        CurateOp::Delete { memory_id, reason } => Ok(CurateOperation::delete(memory_id, reason)),
    }
}

// ============================================================================
// RETRIEVE
// ============================================================================

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct RetrieveRequest {
    memory_id: MemoryId,
    level: DereferenceLevel,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct RetrieveResponse {
    memory_id: MemoryId,
    level: DereferenceLevel,
    data: Vec<u8>,
}

async fn handle_retrieve(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let req: RetrieveRequest =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let state = state.lock().await;
    let entry = state
        .decay_engine
        .entries
        .get(&req.memory_id)
        .cloned()
        .ok_or_else(|| ErrorResponse::new(format!("memory not found: {:?}", req.memory_id)))?;

    let data = match req.level {
        DereferenceLevel::Shallow => serde_json::to_vec(&serde_json::json!({
            "id": entry.id,
            "layer": format!("{:?}", entry.layer),
            "strength": entry.strength,
            "access_count": entry.access_count,
        }))
        .unwrap_or_default(),
        DereferenceLevel::Deep => entry.content.clone().into_bytes(),
        DereferenceLevel::Partial { .. } => serde_json::to_vec(&serde_json::json!({
            "id": entry.id,
            "content": entry.content,
        }))
        .unwrap_or_default(),
    };

    let response = RetrieveResponse {
        memory_id: entry.id,
        level: req.level,
        data,
    };
    serde_json::to_vec(&response).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// SEARCH
// ============================================================================

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct SearchRequest {
    query: String,
    limit: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct SearchResult {
    memory_id: MemoryId,
    score: f32,
    content: String,
}

async fn handle_search(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let req: SearchRequest =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let state = state.lock().await;
    let query_lower = req.query.to_lowercase();

    // Search the context tree entries.
    let mut results: Vec<SearchResult> = state
        .context_tree
        .entries()
        .filter(|entry| {
            entry.content.to_lowercase().contains(&query_lower)
                || entry.domain.to_lowercase().contains(&query_lower)
                || entry.topic.to_lowercase().contains(&query_lower)
                || entry.subtopic.to_lowercase().contains(&query_lower)
        })
        .map(|entry| SearchResult {
            memory_id: entry.id,
            score: 1.0,
            content: entry.content.clone(),
        })
        .collect();

    // Also search the hot cache.
    results.extend(
        state
            .hot_cache
            .iter()
            .filter(|(_, value)| {
                String::from_utf8_lossy(value)
                    .to_lowercase()
                    .contains(&query_lower)
            })
            .map(|(id, value)| SearchResult {
                memory_id: *id,
                score: 0.9,
                content: String::from_utf8_lossy(value).to_string(),
            }),
    );

    results.truncate(req.limit);

    serde_json::to_vec(&results).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// GRAPH_TRAVERSE
// ============================================================================

async fn handle_graph_traverse(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let query: GraphQuery =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let state = state.lock().await;
    let results = state
        .graph_store
        .query(&query, &state.graph_planner)
        .map_err(|e| ErrorResponse::new(e.to_string()))?;

    serde_json::to_vec(&results).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// CONSOLIDATE
// ============================================================================

async fn handle_consolidate(
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let mut state = state.lock().await;
    let results = state.dream_engine.consolidate();
    serde_json::to_vec(&results).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// PREFETCH
// ============================================================================

async fn handle_prefetch(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let trigger: PrefetchTrigger =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let mut state = state.lock().await;
    let result = state.prefetch_engine.prefetch(&trigger);
    serde_json::to_vec(&result).map_err(|e| ErrorResponse::new(e.to_string()))
}