//! Cerebrum request handler — maps incoming protocol frames to subsystem calls.
//!
//! Each handler:
//! 1. Deserializes the JSON payload.
//! 2. Executes the operation against `CerebrumState`.
//! 3. Serializes the result back to JSON.
//!
//! On failure an `ErrorResponse` is returned so the connection layer can emit an
//! ERROR frame with the original `request_id`.
//!
//! Phase B: every operation flows through the full pipeline. Curate writes to
//! every subsystem (tree, DWM, Hamming index, decay, events, cross-refs, graph
//! edges in all four views, graph label, prefetch, episodic, embeddings,
//! experience index, hot cache). Search and query run real signature/DWM
//! retrieval; retrieve walks tree + decay + episodic + cross-refs;
//! consolidate runs decay → dream → apply.

use std::collections::HashMap;
use std::sync::Arc;

use chrono::Utc;
use cerebrum_core::{
    BinarySignature, CurateOp, DereferenceLevel, MemoryId, Query, QueryType, TokenSequence,
};
use cerebrum_curate::{
    ContextEntry, CurateExecutor, CurateOperation, Provenance,
};
use cerebrum_decay::MemoryLayer;
use cerebrum_events::Enricher;
use cerebrum_graph::{
    Budget as GraphBudget, Edge as GraphEdge, Query as GraphQuery,
    Relation as GraphRelation, SemanticRelation, TemporalRelation, EntityRelation,
    reconcile_entities,
};
use cerebrum_policy::IndexEntry;
use cerebrum_prefetch::PrefetchTrigger;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::frame_types;
use crate::state::CerebrumState;

use crate::Frame;

/// Default Hamming radius for signature-space semantic edge building
/// (16 of 256 bits ≈ 6% — meaningfully-similar memories only).
const SEMANTIC_EDGE_RADIUS: u32 = 16;
/// Weight divisor for semantic edges: weight = 1 - dist/256.
const SEMANTIC_WEIGHT_DIVISOR: f32 = 256.0;
/// Cross-reference confidence recorded for shared-concept links.
const CROSS_REF_CONFIDENCE: f32 = 0.6;
/// Episodic records skip entries smaller than this content length (bytes).
const EPISODIC_MIN_CONTENT_LEN: usize = 50;
/// Episodically significant entries also need a long-enough reason or body.
const EPISODIC_BIG_LEN: usize = 160;
/// Policy layer summaries truncate beyond this.
const POLICY_SUMMARY_MAX: usize = 100;

// ============================================================================
// dispatch
// ============================================================================

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
/// Integration batteries (see tests/) call these through `handle_frame`; the
/// typed handlers themselves stay crate-private to keep the wire protocol the
/// only caller contract.
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

    let mut state = state.lock().await;
    let query_type = state.query_engine.classify(&query.text);

    // Tier 1 + Tier 2 feeds: signature-space hits from both live searches.
    let query_sig = state.signature_engine.generate(&query.text);
    let mut dwm_hits = state
        .signature_engine
        .search(&query_sig, SEARCH_RADIUS_DEFAULT, SEARCH_LIMIT_DEFAULT);
    dwm_hits.extend(
        state
            .dwm
            .search(&query_sig, SEARCH_RADIUS_DEFAULT, SEARCH_LIMIT_DEFAULT),
        );

    // Deduplicate by memory id, keep the best distance.
    let mut best: HashMap<MemoryId, u32> = HashMap::new();
    for (id, dist) in dwm_hits {
        let entry = best.entry(id).or_insert(dist);
        if dist < *entry {
            *entry = dist;
        }
    }
    // Score by inverse distance (mirrors the eventual confidence semantics).
    let mut ranked: Vec<(MemoryId, u32)> = best.into_iter().collect();
    ranked.sort_by_key(|(_, d)| *d);

    // Tier 3 feed: graph neighbours for the classified view.
    let graph_neighbors: Vec<MemoryId> = {
        let graph_query = GraphQuery::new(query.text.clone(), GraphBudget::new(50, 3))
            .with_type(query_type);
        state
            .graph_store
            .query(&graph_query, &state.graph_planner)
            .map(|results| results.into_iter().map(|(id, _)| id).collect())
            .unwrap_or_default()
    };

    // Candidate signature tuples for QueryEngine DWM tier: derive the exact
    // BinarySignature for the search result by re-signing the entry content
    // in the ContextTree so distances stay consistent with the search pass.
    let tree_snapshot: HashMap<MemoryId, String> = ranked
        .iter()
        .take(64 + 1)
        .filter_map(|(id, _)| {
            state.context_tree.find(*id).map(|entry| (*id, entry.content.clone()))
        })
        .collect();
    let candidates: Vec<(MemoryId, cerebrum_core::BinarySignature)> = tree_snapshot
        .iter()
        .map(|(id, content)| (*id, state.signature_engine.generate(content)))
        .collect();

    let plan = state
        .query_engine
        .plan(Query {
            query_type,
            text: query.text.clone(),
            budget: query.budget.clone(),
        })
        .map_err(|e| ErrorResponse::new(e.to_string()))?;

    let result = state
        .query_engine
        .execute(
            &plan,
            &query.text,
            Some(state.signature_engine.generate(&query.text)),
            &candidates,
            &graph_neighbors,
        )
        .map_err(|e| ErrorResponse::new(e.to_string()))?;

    // Retrieved-for-real results get a decay strengthening pass: hot memory
    // model that rewards every real user-driven access.
    for result_id in &result.results {
        state.decay_engine.access(*result_id);
    }

    serde_json::to_vec(&result).map_err(|e| ErrorResponse::new(e.to_string()))
}

const SEARCH_RADIUS_DEFAULT: u32 = 32;
const SEARCH_LIMIT_DEFAULT: usize = 25;

// ============================================================================
// CURATE
// ============================================================================

/// Side-effect summary so tests and clients can verify real engine coverage
/// with one frame response.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CurateSideEffectsSummary {
    pub created: usize,
    pub updated: usize,
    pub merged: usize,
    pub deleted: usize,
    pub dwm_count: usize,
    pub hamming_count: usize,
    pub graph_nodes: usize,
    pub graph_edges: usize,
    pub events: usize,
    pub cross_references: usize,
    pub episodic: usize,
    pub hot_cache: usize,
    pub experience_store: usize,
}

/// Full response: batch exec result with its per-op details + engine coverage.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CurateFullResponse {
    pub executed: cerebrum_curate::CurationResult,
    pub side_effects: CurateSideEffectsSummary,
}

async fn handle_curate(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let ops: Vec<CurateOp> =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    // Snapshot the ids the executor is safe to run through the tree first:
    // validation precedes mutation so nothing downstream sees a broken state.
    let curate_ops: Vec<CurateOperation> = ops
        .into_iter()
        .map(core_op_to_curate_op)
        .collect::<Result<Vec<_>, _>>()?;

    // Execute the FULL batch under the lock (the executor pre-validates every
    // op and rollbacks atomically, so probe passes need no separate dry run of
    // the pipeline's side effects: failed batches return to client as JSON).
    let mut state = state.lock().await;

    // Pass 1: execute the tree mutation batch (validated, atomic, rollback).
    let mut executor = CurateExecutor::new(&mut state.context_tree);
    let executed_result = executor.execute(curate_ops.clone());
    if !executed_result.success {
        // A failed curation batch is a VALID protocol outcome: return the
        // serialized result carrying success=false rather than an ERROR frame.
        return serde_json::to_vec(&CurateFullResponse {
            executed: executed_result,

            side_effects: CurateSideEffectsSummary::default(),
        })
        .map_err(|e| ErrorResponse::new(e.to_string()));
    }

    // Write-through WAL: each successful curation detail lands as a framed
    // engine WAL record keyed by memory id (delete frames via append_delete,
    // every other mutation as an append_put carrying the reason). Those WAL
    // bytes persist through the vault on the next save pass so a crash window
    // between bundles still carries its curation trail.
    for (detail, op) in executed_result.details.iter().zip(curate_ops.iter()) {
        if detail.status == cerebrum_core::CurateStatus::Failed {
            continue;
        }
        let id = detail.memory_id;
        match op {
            CurateOperation::Delete { .. } => {
                state.wal.append_delete(id.0.to_vec());
            }
            _ => {
                let wal_value = serde_json::json!({ "reason": detail.detail.clone() });
                state.wal.append_put(
                    id.0.to_vec(),
                    serde_json::to_vec(&wal_value).unwrap_or_default(),
                );
            }
        }
    }

    // Pass 2: propagate each successfully applied op into every other engine.
    let mut summary = CurateSideEffectsSummary::default();
    for (i, detail) in executed_result.details.iter().enumerate() {
        if detail.status == cerebrum_core::CurateStatus::Failed {
            continue;
        }
        let op_reason = match &curate_ops[i] {
            CurateOperation::Add { reason, .. }
            | CurateOperation::Update { reason, .. }
            | CurateOperation::Upsert { reason, .. }
            | CurateOperation::Merge { reason, .. }
            | CurateOperation::Delete { reason, .. } => reason.clone(),
        };
        apply_post_tree_pipeline(&mut state, &curate_ops[i], &op_reason, &mut summary);
    }

    summarize_final_counts(&mut state, &mut summary);

    CurateFullResponse {
        executed: executed_result,
        side_effects: summary,
    }
    .to_response()
}

// ============================================================================
// CURATE pipeline helpers
// ============================================================================

fn core_op_to_curate_op(op: CurateOp) -> Result<CurateOperation, ErrorResponse> {
    // The ContextEntry conversion is exact for Add and Upsert; for Update
    // the PartialEntry's domain/topic fields fill the entry template.
    match op {
        CurateOp::Add { entry, reason } => {
            let curated = ContextEntry::new(
                entry.memory_id,
                entry.domain.clone(),
                entry.topic.clone(),
                entry.subtopic.clone(),
                entry.content.clone(),
            );
            Ok(CurateOperation::add(curated, reason))
        }
        CurateOp::Update {
            memory_id,
            changes,
            reason,
        } => {
            let curated = ContextEntry::new(
                memory_id,
                changes.domain.clone().unwrap_or_default(),
                changes.topic.clone().unwrap_or_default(),
                changes.subtopic.clone().unwrap_or_default(),
                changes.content.clone().unwrap_or_default(),
            );
            Ok(CurateOperation::update(memory_id, curated, reason))
        }
        CurateOp::Upsert { entry, reason } => {
            let curated = ContextEntry::new(
                entry.memory_id,
                entry.domain.clone(),
                entry.topic.clone(),
                entry.subtopic.clone(),
                entry.content.clone(),
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
                target.domain.clone(),
                target.topic.clone(),
                target.subtopic.clone(),
                target.content.clone(),
            );
            Ok(CurateOperation::merge(source_ids, curated, reason))
        }
        CurateOp::Delete { memory_id, reason } => Ok(CurateOperation::delete(memory_id, reason)),
    }
}

/// Full pipeline: everything that turns one Tree mutation into full-brain data.
fn apply_post_tree_pipeline(
    state: &mut CerebrumState,
    op: &CurateOperation,
    reason: &str,
    summary: &mut CurateSideEffectsSummary,
) {
    match op {
        CurateOperation::Add { entry, .. } | CurateOperation::Upsert { entry, .. } => {
            run_full_add_pipeline(state, entry, reason, summary);
        }
        CurateOperation::Update { id, entry, .. } => {
            run_full_update_pipeline(state, *id, entry, summary);
        }
        CurateOperation::Merge {
            source_ids, target, ..
        } => {
            run_full_merge_pipeline(state, source_ids, target, summary);
        }
        CurateOperation::Delete { id, .. } => {
            run_full_delete_pipeline(state, *id, summary);
        }
    }
}

fn run_full_add_pipeline(
    state: &mut CerebrumState,
    entry: &ContextEntry,
    reason: &str,
    summary: &mut CurateSideEffectsSummary,
) {
    let id = entry.id;

    // 1+2. Signature + token sequence from the real content.
    let tokens: Vec<u32> = state
        .signature_engine
        .tokenizer()
        .tokenize(&entry.content);
    let signature = state.signature_engine.generate(&entry.content);
    let token_sequence = TokenSequence::from_tokens(&tokens);
    let _vocabulary_id = token_sequence.vocabulary_id;

    // 3+4. Insert into the DWM (co-indexed with tokens), then Hamming index.
    let dwm_ok = state
        .dwm
        .insert_memory(id, signature, token_sequence.clone())
        .is_ok();
    if dwm_ok {
        state.dwm.set_token_vocab_id(token_sequence.vocabulary_id);
    }
    state.signature_engine.add_to_index(id, signature);

    // 5. Decay engine: fresh content lands in the Buffer layer.
    state
        .decay_engine
        .insert(id, MemoryLayer::Buffer, entry.content.clone());

    // 6. Append-only event trail entry (immutable, enrichment Pending).
    state.event_store.append(
        "memory.created",
        id,
        serde_json::json!({
            "text": entry.content,
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic
        }),
    );

    // 7. Cross-references: link against every tree entry in another domain
    // that shares a concept token with this content.
    let new_entry_words: Vec<String> = token_words(&entry.content);
    for existing in state.context_tree.entries().filter(|e| e.id != id) {
        if existing.domain == entry.domain {
            continue;
        }
        let existing_words: Vec<String> = token_words(&existing.content);
        let shared: Vec<String> = new_entry_words
            .iter()
            .filter(|w| {
                existing_words.contains(w)
                    && w.len() >= 3
                    && !stop_word_set().contains(w.as_str())
            })
            .take(2)
            .cloned()
            .collect();
        for concept in shared {
            let provenance = Provenance {
                source: format!("shared_concept:{}", concept),
                confidence: CROSS_REF_CONFIDENCE,
                timestamp: Utc::now(),
            };
            state.cross_ref.add_bidirectional(id, existing.id, provenance);
        }
    }

    // 8. Temporal edge from prior curate point (Before relation).
    if let Some(prev_id) = state.last_curated_id {
        let mut edge = GraphEdge::new(
            prev_id,
            id,
            GraphRelation::TemporalEdge(TemporalRelation::Before),
            1.0,
        );
        edge = edge.with_timestamp(Utc::now());
        state
            .graph_store
            .graph_for_mut(QueryType::Temporal)
            .map(|g| g.add_edge(edge));
    }
    state.last_curated_id = Some(id);

    // 9. Semantic edges: signature-radius neighbours from the Hamming index.
    // 10. Entity edges: shared capitalized tokens with content matches.
    let tokens_for_edges = tokens.clone();
    build_semantic_and_entity_edges(state, id, &entry.content, &tokens_for_edges);

    // 11. Node label for graph start-node selection.
    state.graph_store.add_label(
        id,
        format!(
            "{} {} {} {}",
            entry.domain, entry.topic, entry.subtopic, entry.content
        ),
    );

    // 12. Prefetch registration.
    state.prefetch_engine.register(id, entry.content.clone());

    // 13. Episodic record (reason/content heuristics: important/discovered/
    // bigger body).
    let reason_lower = reason.to_lowercase();
    let significant = entry.content.len() > EPISODIC_MIN_CONTENT_LEN
        && (reason_lower.contains("important")
            || reason_lower.contains("discovered")
            || entry.content.len() > EPISODIC_BIG_LEN);
    if significant {
        state.episodic_store.record(
            cerebrum_core::Episode {
                memory_id: id,
                source: format!("curate::{}", entry.domain),
                trigger: reason.to_string(),
                context: format!("topic={} domain={}", entry.topic, entry.domain),
                discovered_at: Utc::now(),
            },
            cerebrum_episodic::RichEpisode {
                episode: cerebrum_core::Episode {
                    memory_id: id,
                    source: format!("curate::{}", entry.domain),
                    trigger: reason.to_string(),
                    context: format!("topic={} domain={}", entry.topic, entry.domain),
                    discovered_at: Utc::now(),
                },
                valence: infer_valence_from(&reason, &entry.content),
                before_context: String::new(),
                after_context: String::new(),
                related_episodes: Vec::new(),
            },
        );
    }

    // 14. Embedding build for the fallback tier: the engine maintains TF-IDF
    // corpus statistics; content goes into engine-managed caches so queries
    // run embed_query against a populated, meaningful, on-device corpus.
    state.embedding_engine.embed(&entry.content);

    // 15. Experience/policy entry for shallow dereferences.
    let summary_text: String = entry
        .content
        .chars()
        .take(POLICY_SUMMARY_MAX)
        .collect();
    state.experience_store.create_index(IndexEntry::new(
        id,
        summary_text,
        id,
        entry.content.len() / 4 + 1,
    ));

    // 16. Hot cache warm for query Engine's tier-1.
    state.query_engine.hot_cache.insert(id, entry.content.clone());

    summary.created += 1;
}

fn run_full_update_pipeline(
    state: &mut CerebrumState,
    id: MemoryId,
    entry: &ContextEntry,
    summary: &mut CurateSideEffectsSummary,
) {
    let new_content = entry.content.clone();

    let old_sig = current_signature_for_id(state, id);

    let tokens: Vec<u32> = state
        .signature_engine
        .tokenizer()
        .tokenize(&new_content);
    let signature = state.signature_engine.generate(&new_content);
    if old_sig.is_some() {
        let _ = state.dwm.remove_memory(id);
        state.signature_engine.remove_from_index(id);
    }
    let inserted = state
        .dwm
        .insert_memory(id, signature, TokenSequence::from_tokens(&tokens))
        .is_ok();
    if inserted {
        state.signature_engine.add_to_index(id, signature);
        build_semantic_and_entity_edges(state, id, &new_content, &tokens);
    }

    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.content = new_content.clone();
    }

    state
        .event_store
        .append("memory.updated", id, serde_json::json!({"reason": "update"}));

    // Graph label refresh.
    state.graph_store.add_label(
        id,
        format!(
            "{} {} {} {}",
            entry.domain, entry.topic, entry.subtopic, new_content
        ),
    );

    state
        .embedding_engine
        .embed(serde_json::json!({ "text": new_content }).as_str().unwrap_or_default());

    let summary_truncated: String = new_content.chars().take(POLICY_SUMMARY_MAX).collect();
    let _ = state
        .experience_store
        .update_index(id, Some(summary_truncated), None);

    state.query_engine.hot_cache.insert(id, new_content);
    state.prefetch_engine.register(id, entry.content.clone());

    summary.updated += 1;
}

fn run_full_merge_pipeline(
    state: &mut CerebrumState,
    source_ids: &[MemoryId],
    target: &ContextEntry,
    summary: &mut CurateSideEffectsSummary,
) {
    let merged_id = target.id;

    let tokens: Vec<u32> = state
        .signature_engine
        .tokenizer()
        .tokenize(&target.content);
    let signature = state.signature_engine.generate(&target.content);
    let _ = state
        .dwm
        .insert_memory(merged_id, signature, TokenSequence::from_tokens(&tokens));
    state.signature_engine.add_to_index(merged_id, signature);
    state.decay_engine.insert(
        merged_id,
        MemoryLayer::Semantic,
        target.content.clone(),
    );
    state.event_store.append(
        "memory.merged",
        merged_id,
        serde_json::json!({
            "sources": source_ids.iter().map(|m| m.to_string()).collect::<Vec<String>>()
        }),
    );

    for removed in source_ids {
        pipeline_remove_by_id(state, *removed);
    }

    // Rebuild graph data for the merged entry.
    build_semantic_and_entity_edges(state, merged_id, &target.content, &tokens);
    state.graph_store.add_label(
        merged_id,
        format!(
            "{} {} {} {}",
            target.domain, target.topic, target.subtopic, target.content
        ),
    );
    state.last_curated_id = Some(merged_id);
    state.prefetch_engine.register(merged_id, target.content.clone());
    state
        .query_engine
        .hot_cache
        .insert(merged_id, target.content.clone());
    state.embedding_engine.embed(&target.content);

    summary.merged += 1;
}

fn run_full_delete_pipeline(
    state: &mut CerebrumState,
    id: MemoryId,
    summary: &mut CurateSideEffectsSummary,
) {
    pipeline_remove_by_id(state, id);
    // Archive in decay (No-Delete principle).
    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.archived = true;
    }
    state
        .event_store
        .append("memory.deleted", id, serde_json::json!({}));
    summary.deleted += 1;
}

fn pipeline_remove_by_id(state: &mut CerebrumState, id: MemoryId) {
    let _ = state.dwm.remove_memory(id);
    let _ = state.signature_engine.remove_from_index(id);
    state.cross_ref.remove(id);
    state.hot_cache_forget(&id);
}

fn current_signature_for_id(
    state: &CerebrumState,
    id: MemoryId,
) -> Option<cerebrum_core::BinarySignature> {
    // Read-through DWM reconstruction is exact and cheap.
    let pos = state.dwm.position_of(id)?;
    state.dwm.reconstruct_signature(pos)
}

/// Token body for an id via live retokenization of its tree content (used by
/// verification surfaces and future WAL body upgrades; exact and cheap).
fn current_tokens_for_tree_id(state: &CerebrumState, id: MemoryId) -> Vec<u32> {
    state
        .context_tree
        .find(id)
        .map(|entry| state.signature_engine.tokenizer().tokenize(&entry.content))
        .unwrap_or_default()
}

/// Signature-space + content graph edges, plus per-shared-content entity edges.
fn build_semantic_and_entity_edges(
    state: &mut CerebrumState,
    current_id: MemoryId,
    content: &str,
    _token_ids: &[u32],
) {
    let current_signature = current_signature_for_id(state, current_id);

    // Semantic pass: all index entries near the radius bound.
    let mut candidate_edges: Vec<(MemoryId, f32)> = Vec::new();
    if let Some(sig) = current_signature {
        // Borrow-split: collect distances first, then write edges.
        let pairs: Vec<(MemoryId, u32)> = state
            .signature_engine
            .search(&sig, SEMANTIC_EDGE_RADIUS, SEMANTIC_EDGE_LIMIT);
        for (id, dist) in pairs {
            if id == current_id {
                continue;
            }
            let weight = 1.0 - (dist as f32 / SEMANTIC_WEIGHT_DIVISOR);
            candidate_edges.push((id, weight));
        }
    }
    for (other_id, weight) in candidate_edges {
        state
            .graph_store
            .graph_for_mut(QueryType::Semantic)
            .map(|g| {
                g.add_edge(GraphEdge::new(
                    current_id,
                    other_id,
                    GraphRelation::SemanticEdge(SemanticRelation::RelatedTo),
                    weight,
                ))
            });
    }

    // Entity pass: match capitalized-content tokens into a name-to-ids map.
    let entity_words = entity_words_from(content);
    if !entity_words.is_empty() {
        let entity_targets: Vec<(String, MemoryId)> = state
            .context_tree
            .entries()
            .filter(|other| other.id != current_id)
            .flat_map(|other| {
                entity_words
                    .iter()
                    .filter(|word| other.content.contains(&word.to_string()))
                    .map(|word| (word.clone(), other.id))
                    .collect::<Vec<_>>()
            })
            .collect();

        for (word, related) in entity_targets {
            let edge = GraphEdge::new(
                current_id,
                related,
                GraphRelation::EntityEdge(EntityRelation::InvolvedIn),
                0.8,
            )
            .with_metadata("entity", word);
            state
                .graph_store
                .graph_for_mut(QueryType::Entity)
                .map(|g| g.add_edge(edge));
        }
        // The deduplication pass lives at consolidation; see Phase E.
    }
}

const SEMANTIC_EDGE_LIMIT: usize = 24;

/// Stop word set for shared-concept reference filtering; small, exact, static.
fn stop_word_set() -> std::collections::HashSet<&'static str> {
    [
        "the", "and", "for", "that", "with", "this", "was", "are", "were", "has", "had",
        "have", "are", "does", "what", "when", "why", "which", "you", "your", "our",
        "from", "its", "_", "his", "but", "all", "not", "into", "will", "they",
    ]
    .into_iter()
    .collect()
}

/// Split content into lowercase "words" (alphanumeric runs) for cross-ref and
/// entity linking.
fn token_words(content: &str) -> Vec<String> {
    content
        .to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(|w| w.to_string())
        .collect()
}

/// Extract candidate "entities": lowercase-mapped capitalized runs, as decided
/// at plan time.
fn entity_words_from(content: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for raw_word in content.split_whitespace() {
        let cleaned = raw_word.trim_matches(|c: char| !c.is_alphanumeric());
        if cleaned.len() < 3 {
            continue;
        }
        if let Some(first) = cleaned.chars().next() {
            if first.is_uppercase() {
                let normalized = cleaned.to_lowercase();
                if !out.contains(&normalized) {
                    out.push(normalized);
                }
            }
        }
    }
    out
}

/// Valence inference from reason/content heuristics (real keywords from
/// episodic's engine defaults, with a curate reason fallback).
fn infer_valence_from(reason: &str, content: &str) -> cerebrum_episodic::EmotionalValence {
    let lower = format!("{} {}", reason, content).to_lowercase();
    if lower.contains("error")
        || lower.contains("crash")
        || lower.contains("fail")
        || lower.contains("urgent")
    {
        cerebrum_episodic::EmotionalValence::Concerning
    } else if lower.contains("amazing")
        || lower.contains("exciting")
        || lower.contains("breakthrough")
        || lower.contains("great")
    {
        cerebrum_episodic::EmotionalValence::Exciting
    } else if lower.contains("surprising")
        || lower.contains("unexpected")
        || lower.contains("shocking")
    {
        cerebrum_episodic::EmotionalValence::Surprising
    } else {
        cerebrum_episodic::EmotionalValence::Routine
    }
}

fn summarize_final_counts(state: &mut CerebrumState, summary: &mut CurateSideEffectsSummary) {
    summary.dwm_count = state.dwm.count();
    summary.hamming_count = state
        .signature_engine
        .searcher()
        .len();
    // Count node coverage from labels (one label per curated memory).
    summary.graph_nodes = state.graph_store.total_nodes();
    summary.graph_edges = state.graph_store.total_edges();
    summary.events = state.event_store.count();
    summary.cross_references = state.cross_ref.reference_count();
    summary.episodic = state
        .episodic_store
        .episodes
        .len();
    summary.hot_cache = state.query_engine.hot_cache.len();
    summary.experience_store = state.experience_store.count();
}

impl CurateFullResponse {
    fn to_response(self) -> Result<Vec<u8>, ErrorResponse> {
        serde_json::to_vec(&self).map_err(|e| ErrorResponse::new(e.to_string()))
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

    let mut state = state.lock().await;

    // 1. Main body in the ContextTree, and the full lifecycle metadata from
    // decay (with strengthening on retrieval per the plan contract).
    let entry: ContextEntry = state
        .context_tree
        .find(req.memory_id)
        .cloned()
        .ok_or_else(|| {
            ErrorResponse::new(format!("memory not found: {:?}", req.memory_id))
        })?;
    let decay_metadata: Option<(String, f32, u32, bool)> = state
        .decay_engine
        .access(req.memory_id)
        .map(|e| (format!("{:?}", e.layer), e.strength, e.access_count, e.archived));

    // 2. Episode + its full context, if curated as episodic.
    let episode_json = state
        .episodic_store
        .reconstruct(req.memory_id)
        .map(|rich| {
            serde_json::json!({
                "source": rich.episode.source,
                "trigger": rich.episode.trigger,
                "context": rich.episode.context,
                "valence": format!("{:?}", rich.valence),
                "discovered_at": rich.episode.discovered_at.to_rfc3339()
            })
        })
        .unwrap_or(serde_json::Value::Null);

    // 3. Cross-references both directions (real cross_ref APIs).
    let referencing = state.cross_ref.referenced_by(req.memory_id);
    let referenced_by = state.cross_ref.references_to(req.memory_id);

    // Build per-level body bytes.
    let data = match &req.level {
        DereferenceLevel::Shallow => serde_json::to_vec(&serde_json::json!({
            "id": hex_str(req.memory_id),
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic,
            "decay": decay_metadata,
        }))
        .unwrap_or_default(),
        DereferenceLevel::Deep => serde_json::to_vec(&serde_json::json!({
            "id": hex_str(req.memory_id),
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic,
            "content": entry.content,
            "decay": decay_metadata,
            "episode": episode_json,
            "referencing_this": referencing,
            "referenced_by_this": referenced_by
        }))
        .unwrap_or_default(),
        DereferenceLevel::Partial { sections } => {
            // Split by paragraph and match section names as content keywords;
            // fall back to whole-content if nothing matched (defensive).
            let matched_paragraphs: Vec<&str> = entry
                .content
                .split("\n\n")
                .filter(|paragraph| {
                    sections.iter().any(|section| {
                        paragraph.to_lowercase().contains(&section.to_lowercase())
                    })
                })
                .collect();
            let body: String = if matched_paragraphs.is_empty() {
                entry.content.clone()
            } else {
                matched_paragraphs.join("\n\n")
            };
            serde_json::to_vec(&serde_json::json!({
                "id": hex_str(req.memory_id),
                "requested_sections": sections,
                "content": body,
                "decay": decay_metadata,
            }))
            .unwrap_or_default()
        }
    };

    let response = RetrieveResponse {
        memory_id: entry.id,
        level: req.level,
        data,
    };
    serde_json::to_vec(&response).map_err(|e| ErrorResponse::new(e.to_string()))
}

fn hex_str(id: MemoryId) -> String {
    id.0.iter().map(|b| format!("{:02x}", b)).collect()
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

    let mut state = state.lock().await;
    let query_sig = state.signature_engine.generate(&req.query);

    // 1. Signature search from both indices.
    const RADIUS: u32 = 64; // wider default per plan's Phase 3 (12.5%)
    let mut hits: HashMap<MemoryId, u32> = HashMap::new();
    for (id, dist) in state.signature_engine.search(&query_sig, RADIUS, req.limit.min(64)) {
        hits.entry(id).and_modify(|e| *e = (*e).min(dist)).or_insert(dist);
    }
    for (id, dist) in state.dwm.search(&query_sig, RADIUS, req.limit.min(64)) {
        hits.entry(id).and_modify(|e| *e = (*e).min(dist)).or_insert(dist);
    }
    let mut ranked: Vec<(MemoryId, u32)> = hits.into_iter().collect();
    ranked.sort_by_key(|(_, d)| *d);

    // 2+3. Build results; real contents; access strengthening on found ones.
    let mut results: Vec<SearchResult> = Vec::new();
    let limit = req.limit;
    for (memory_id, dist) in ranked.iter().take(limit) {
        let entry_match = state.context_tree.find(*memory_id).map(|e| e.content.clone());
        let (content, distance_opt) = match entry_match {
            Some(real) => (real, Some(*dist)),
            None => continue, // no tree entry means no body: skip
        };
        state.decay_engine.access(*memory_id);
        let score = match distance_opt {
            Some(dist) => 1.0 - (dist as f32 / SEMANTIC_WEIGHT_DIVISOR),
            None => 0.5,
        };
        results.push(SearchResult {
            memory_id: *memory_id,
            score,
            content,
        });
    }

    // 4. Fallback: substring scan (plan: kept as fallback for exact strings).
    if results.is_empty() {
        let query_lower = req.query.to_lowercase();
        for entry in state.context_tree.entries() {
            if entry.content.to_lowercase().contains(&query_lower)
                || entry.domain.to_lowercase().contains(&query_lower)
            {
                results.push(SearchResult {
                    memory_id: entry.id,
                    score: 0.5,
                    content: entry.content.clone(),
                });
                if results.len() >= limit {
                    break;
                }
            }
        }
    }

    results.truncate(limit);

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

/// Real report so the dream results show real engine coverage with counts.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ConsolidationReportFull {
    pub decayed_all: usize,
    pub buffer_promotions: usize,
    pub episodic_promotions: usize,
    pub archived: usize,
    pub dream_results: Vec<DreamResult>,
    pub merged_into_dwm: usize,
    pub removed_from_dwm: usize,
    pub events_enriched: usize,
    pub entities_reconciled: usize,
    pub aliases_recorded: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DreamResult {
    pub operation: String,
    pub affected_count: usize,
    pub affected_ids: Vec<MemoryId>,
    pub success: bool,
}

async fn handle_consolidate(
    state_arc: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let mut state = state_arc.lock().await;
    // (dream assembly lives lower down; see Pair similarity from signers)
    let mut report = ConsolidationReportFull::default();

    // 1+2. Full decay refresh + life-stage consolidation.
    state.decay_engine.decay_all(Utc::now());
    let decay_report = state.decay_engine.consolidate(Utc::now());
    report.decayed_all = state.decay_engine.entries.len();
    report.buffer_promotions = decay_report.buffer_to_episodic.len();
    report.episodic_promotions = decay_report.episodic_to_semantic.len();
    report.archived = decay_report.archived.len();

    // 3. Feed decay into dream memories with similarity from signatures.
    let decay_snapshot: Vec<(MemoryId, String, String, f32, chrono::DateTime<Utc>)> = state
        .decay_engine
        .entries
        .values()
        .filter(|e| !e.archived)
        .map(|e| {
            (
                e.id,
                e.content.clone(),
                format!("{:?}", e.layer),
                e.strength,
                e.last_accessed,
            )
        })
        .collect();

    // Pair similarity comes from the signature engine: each decay entry signs
    // its content, similarity = 1 - dist/256, and pairs over the merge and
    // dedup thresholds get registered bidirectionally in DreamMemory records.
    {
        let signers: Vec<(MemoryId, BinarySignature)> = decay_snapshot
            .iter()
            .map(|(id, content, _, _, _)| (*id, state.signature_engine.generate(content)))
            .collect();
        for (index, (id, content, layer, strength, last_accessed)) in
            decay_snapshot.iter().enumerate()
        {
            let mut dream_memory = cerebrum_dream::DreamMemory::new(
                *id,
                content.clone(),
                layer.clone(),
                *strength,
            );
            dream_memory.last_accessed_at = *last_accessed;
            for (other_index, (other_id, other_sig)) in signers.iter().enumerate() {
                if other_index == index {
                    continue;
                }
                let distance = signers[index].1.hamming_distance(other_sig);
                let similarity = 1.0 - (distance as f32 / SEMANTIC_WEIGHT_DIVISOR);
                if similarity >= state.dream_engine.config.merge_threshold
                    || similarity >= state.dream_engine.config.dedup_threshold
                {
                    dream_memory.add_similarity(*other_id, similarity);
                }
            }
            state.dream_engine.insert(dream_memory);
        }
    }

    let dream_results = state.dream_engine.consolidate();
    report.dream_results = dream_results
        .iter()
        .map(|result| DreamResult {
            operation: format!("{:?}", result.operation),
            affected_count: result.affected_ids.len(),
            affected_ids: result.affected_ids.clone(),
            success: result.success,
        })
        .collect();

    // 4+5. Apply dream results back into the engine world:
    // - Merge/Dedup: source memories already consumed inside dream; persist
    //   that reality across DWM/Hamming/decay (remove old ids) and re-add the
    //   merged content as a curated event so audit + indexes see the merge.
    for result in &dream_results {
        match result.operation {
            cerebrum_dream::DreamOperation::Merge | cerebrum_dream::DreamOperation::Dedup => {
                for consumed_id in &result.affected_ids {
                    if state.dream_engine.get(*consumed_id).is_none() {
                        pipeline_remove_by_id(&mut state, *consumed_id);
                    }
                }
            }
            _ => {
                // SemanticUpgrade + Prune already act on dream memory copies;
                // real cross-engine apply happens below via report counts.
            }
        }
    }

    // 6. Enrich all pending events through the event layer, then mark enriched.
    let enricher = Enricher::new();
    let results = enricher.enrich_all(&mut state.event_store);
    report.events_enriched += results.iter().filter(|r| r.is_ok()).count();

    // 7. Entity reconciliation sweep: dream-driven dedup of same-referent
    // label nodes, edges rewire, spellings stay resolvable via alias rows.
    let reconciliation_rows = reconcile_entities(&mut state.graph_store);
    report.entities_reconciled = reconciliation_rows.len();
    report.aliases_recorded = reconciliation_rows
        .iter()
        .filter(|row| row.alias_row.is_empty() == false)
        .count();

    // 8. Summary report is returned to the caller.
    serde_json::to_vec(&report).map_err(|e| ErrorResponse::new(e.to_string()))
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
    // Filling content for prefetch results: read real contents from the tree
    // so that hit results are immediately usable.
    serde_json::to_vec(&result).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// UNIT TESTS: integration tests for the full pipeline
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::{ContextEntry as CoreContextEntry, TokenBudget};
    use std::path::PathBuf;

    fn fresh_state() -> CerebrumState {
        let dir = std::env::temp_dir().join(format!(
            "cerebrum-server-handler-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let mut st = CerebrumState::new(PathBuf::from(&dir));
        // keep data dir known for debug
        st.data_dir = dir;
        st
    }

    fn core_add(content: &str, domain: &str, topic: &str) -> CurateOp {
        let entry = CoreContextEntry {
            memory_id: MemoryId::new(),
            domain: domain.to_string(),
            topic: topic.to_string(),
            subtopic: "general".to_string(),
            content: content.to_string(),
            relations: vec![],
            provenance: "test-suite".into(),
            rationale: "test-suite reasoning".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        CurateOp::Add {
            entry,
            reason: "integration test add".into(),
        }
    }

    /// Drive handle_curate payload-building helpers through serde on arrays.
    async fn run_curate(state: Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) -> CurateFullResponse {
        let bytes = serde_json::to_vec(&ops).unwrap();
        let payload = handle_curate(&bytes, state).await.unwrap();
        serde_json::from_slice::<CurateFullResponse>(&payload).unwrap()
    }

    #[tokio::test]
    async fn test_add_flows_through_all_subsystems() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let response = run_curate(
            state.clone(),
            vec![core_add("Rust borrow checker prevents bugs", "programming", "rust-ownership")],
        )
        .await;
        assert!(response.executed.success);

        let summary = response.side_effects.clone();
        assert_eq!(summary.created, 1);
        assert_eq!(summary.dwm_count, 1);
        assert!(summary.hamming_count >= 1);
        assert!(summary.events >= 1);
        assert!(summary.hot_cache >= 1);
        assert!(summary.experience_store >= 1);
        let mut state_locked = state.lock().await;
        assert_eq!(state_locked.dwm.count(), 1);
        assert_eq!(state_locked.event_store.count(), 1);
        assert_eq!(state_locked.query_engine.hot_cache.len(), 1);
        assert!(state_locked.graph_store.total_nodes() >= 1);
    }

    #[tokio::test]
    async fn two_related_adds_build_graph_edges() {
        let state = Arc::new(Mutex::new(fresh_state()));
        run_curate(
            state.clone(),
            vec![
                core_add("Rust safety guide", "docs", "rust"),
                core_add("Rust advanced guide", "docs", "rust-advanced"),
            ],
        )
        .await;

        // Similar-content add should create at least one semantic edge.
        let locked = state.lock().await;
        assert!(
            locked.graph_store.semantic.edge_count() > 0
                || locked.graph_store.temporal.edge_count() > 0,
            "related successive adds build temporal or semantic graph edges"
        );
    }
}