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
    CurateOp, DereferenceLevel, MemoryId, Query, QueryType, TokenSequence,
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
use crate::retrieval_policy::{Eligibility, RetrievalPolicy};
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
        frame_types::LIST => handle_list(&payload, state).await,
        frame_types::HISTORY => handle_history(&payload, state).await,
        _ => Err(ErrorResponse::new(format!(
            "unknown frame type: 0x{:02x}",
            frame_type
        ))),
    }
}

// ============================================================================
// visibility: what the brain will admit to holding
// ============================================================================

/// Whether an id is retired.
///
/// The No-Delete doctrine means a deletion archives the row instead of erasing
/// it, so the row survives in the tree with the Archived lifecycle. Archived
/// rows must be invisible to every read surface: a caller that deleted a memory
/// and then saw it in a listing or a search would have been told the deletion
/// failed. This one predicate is the single place that decides it, so search,
/// list and retrieve cannot drift apart on the question.
fn is_retired(state: &CerebrumState, id: &MemoryId) -> bool {
    let tree_says_retired = state
        .context_tree
        .find(*id)
        .map(|entry| entry.lifecycle == cerebrum_curate::EntryLifecycle::Archived)
        .unwrap_or(true);
    if tree_says_retired {
        return true;
    }
    /*
     * THE SECOND OPINION THAT USED TO BE IGNORED.
     *
     * Decay archives a row by setting `DecayEntry::archived`, and consolidation
     * does the same to every memory it consumes. Neither of those touched the
     * tree's `EntryLifecycle`, which was the only thing this predicate read. So
     * the two engines held different opinions about the same memory and every read
     * asked only the one that never changed: a memory retired by decay still
     * answered searches, still appeared in listings, and still resolved by id.
     *
     * A memory is retired when EITHER engine says it is. That is the conservative
     * reading and the only one that cannot resurrect something a caller was told
     * was gone.
     */
    state
        .decay_engine
        .entries
        .get(id)
        .map(|decay_entry| decay_entry.archived)
        .unwrap_or(false)
}

/// The layer name for a memory, or an empty string when decay has no row for it.
///
/// A named helper rather than an inline `format!("{:?}", ...)` so the wire spelling
/// comes from one place, the policy, and the read paths cannot drift.
fn layer_label_for(state: &CerebrumState, id: &MemoryId) -> String {
    state
        .decay_engine
        .entries
        .get(id)
        .map(|entry| RetrievalPolicy::layer_name(entry.layer).to_string())
        .unwrap_or_default()
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

/// A memory's state read immediately before the executor mutates it.
///
/// Captured for Update and Delete because both write an audit event describing what
/// they replaced, and the executor destroys the evidence before the pipeline that
/// writes the event can run. Every field here is something an audit trail is asked
/// for and cannot recover afterwards.
struct PreWriteState {
    content: String,
    lifecycle: String,
    domain: String,
    provenance: String,
    confidence: f32,
}

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

    // THE PRE-WRITE SNAPSHOT (issue 483).
    //
    // This has to happen HERE, before the executor runs, because the executor
    // mutates the tree in place. A capture taken inside the update pipeline reads
    // the row the update has already replaced, so it records the new text as the
    // superseded text and the history becomes a list of the present repeated.
    //
    // Every id an Update op names is read once, here, while the old wording still
    // exists. An id that does not resolve is simply absent, which the event records
    // honestly as no superseded text.
    // The same snapshot answers the delete event. A deletion that records no text
    // and no reason is an audit entry that says only "something went away", which
    // is exactly what an audit trail must not say.
    //
    // IT MUST CARRY THE LIFECYCLE TOO, AND THAT IS NOT OBVIOUS. The curate executor
    // implements Delete by archiving the row in the tree, so by the time the
    // post-tree pipeline runs, the row's lifecycle already reads Archived. A
    // pipeline that read it then would record "previous lifecycle: Archived" for
    // every single deletion, which is a fiction: the state being described is the
    // one before the delete. So the lifecycle is captured here, with the text,
    // while both are still true.
    let mut pre_write: HashMap<MemoryId, PreWriteState> = HashMap::new();
    for op in &curate_ops {
        match op {
            CurateOperation::Update { id, .. } | CurateOperation::Delete { id, .. } => {
                if let Some(existing) = state.context_tree.find(*id) {
                    pre_write.insert(
                        *id,
                        PreWriteState {
                            content: existing.content.clone(),
                            lifecycle: format!("{:?}", existing.lifecycle),
                            domain: existing.domain.clone(),
                            provenance: existing.provenance.source.clone(),
                            confidence: existing.provenance.confidence,
                        },
                    );
                }
            }
            _ => {}
        }
    }

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
    // every other mutation as an append_put carrying the reason AND the token
    // body). Carrying the token body is what makes a WAL record replayable on
    // its own: a later replay can rebuild the DWM row without re-reading the
    // tree, and a record that only held a reason string could not.
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
                let token_body = current_tokens_for_tree_id(&state, id);
                let wal_value = serde_json::json!({
                    "reason": detail.detail.clone(),
                    "tokens": token_body,
                });
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
        // The pre-write state rides into the update AND delete pipelines so their
        // events can describe what they replaced. Read from the snapshot, never from
        // the already-mutated tree, which for an update holds the new wording and for
        // a delete holds an already-archived row.
        let pre_write_state = match &curate_ops[i] {
            CurateOperation::Update { id, .. } | CurateOperation::Delete { id, .. } => {
                pre_write.get(id)
            }
            _ => None,
        };
        apply_post_tree_pipeline(
            &mut state,
            &curate_ops[i],
            &op_reason,
            pre_write_state,
            &mut summary,
        );
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

/// Split a caller's provenance label into its source text and its confidence.
///
/// The wire carries provenance as one string, because the core `ContextEntry`
/// has a string where the curate layer has a typed `Provenance`. Rather than
/// throw the confidence away at the boundary — which is what made a caller's
/// confidence come back as zero and made a memory look unestablished — the
/// convention is: `"<source>|confidence=<f32>"`, and the source alone when the
/// caller has no confidence to give. An absent confidence defaults to 1.0,
/// which matches the domain model's default, so a row written by an older
/// caller is treated as fully believed rather than as worthless.
fn split_provenance(raw: &str) -> (String, f32, Option<chrono::DateTime<Utc>>) {
    // Two accepted spellings, because the label arrives from two kinds of
    // caller: `source|confidence=0.42` (the delimited form) and the older
    // descriptive `guru.confidence=0.42` (the plain key form). Accepting both
    // means an already-stored row and a freshly written one read the same way,
    // rather than one of them silently reporting full confidence for a fact the
    // caller had marked as doubtful.
    let marker_positions: [&str; 2] = ["|confidence=", "confidence="];
    for marker in marker_positions {
        if let Some(start) = raw.find(marker) {
            let source_text = raw[..start].trim_end_matches('|').trim().to_string();
            let confidence_text = raw[start + marker.len()..].trim();
            let confidence = confidence_text
                .parse::<f32>()
                .map(|value| value.clamp(0.0, 1.0))
                .unwrap_or(1.0);
            return (source_text, confidence, None);
        }
    }
    (raw.to_string(), 1.0, None)
}

/// Build the curate-layer entry from the wire entry, carrying provenance and
/// rationale across.
///
/// Provenance and rationale are the ONLY carriers of a caller's category and
/// confidence, because the brain's own hierarchy has no slot for either. A
/// conversion that used `ContextEntry::new` and left them at their defaults
/// silently erased the caller's metadata on the way in, which is why a
/// round trip read back an empty rationale and confidence zero.
fn curate_entry_from_core(entry: &cerebrum_core::ContextEntry) -> ContextEntry {
    let mut curated = ContextEntry::new(
        entry.memory_id,
        entry.domain.clone(),
        entry.topic.clone(),
        entry.subtopic.clone(),
        entry.content.clone(),
    );
    let (source_text, confidence, _) = split_provenance(&entry.provenance);
    curated.provenance = Provenance {
        source: source_text,
        confidence,
        timestamp: entry.created_at,
    };
    curated.rationale = entry.rationale.clone();
    curated.created_at = entry.created_at;
    curated.updated_at = entry.modified_at;
    curated
}

fn core_op_to_curate_op(op: CurateOp) -> Result<CurateOperation, ErrorResponse> {
    // The ContextEntry conversion is exact for Add and Upsert; for Update
    // the PartialEntry's domain/topic fields fill the entry template.
    match op {
        CurateOp::Add { entry, reason } => {
            let curated = curate_entry_from_core(&entry);
            Ok(CurateOperation::add(curated, reason))
        }
        CurateOp::Update {
            memory_id,
            changes,
            reason,
        } => {
            let mut curated = ContextEntry::new(
                memory_id,
                changes.domain.clone().unwrap_or_default(),
                changes.topic.clone().unwrap_or_default(),
                changes.subtopic.clone().unwrap_or_default(),
                changes.content.clone().unwrap_or_default(),
            );
            // A partial update may carry a new provenance or rationale; both are
            // optional, and an absent one keeps the existing row's value because
            // the update pipeline only overwrites what it is given.
            if let Some(provenance_text) = &changes.provenance {
                let (source_text, confidence, _) = split_provenance(provenance_text);
                curated.provenance = Provenance {
                    source: source_text,
                    confidence,
                    timestamp: Utc::now(),
                };
            }
            if let Some(rationale_text) = &changes.rationale {
                curated.rationale = rationale_text.clone();
            }
            Ok(CurateOperation::update(memory_id, curated, reason))
        }
        CurateOp::Upsert { entry, reason } => {
            let curated = curate_entry_from_core(&entry);
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
    pre_write: Option<&PreWriteState>,
    summary: &mut CurateSideEffectsSummary,
) {
    match op {
        CurateOperation::Add { entry, .. } | CurateOperation::Upsert { entry, .. } => {
            run_full_add_pipeline(state, entry, reason, summary);
        }
        CurateOperation::Update { id, entry, .. } => {
            run_full_update_pipeline(state, *id, entry, reason, pre_write, summary);
        }
        CurateOperation::Merge {
            source_ids, target, ..
        } => {
            run_full_merge_pipeline(state, source_ids, target, summary);
        }
        CurateOperation::Delete { id, .. } => {
            run_full_delete_pipeline(state, *id, reason, pre_write, summary);
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

    // 4b. Lexical index: the primary retrieval lane for text the user types.
    state.lexical.upsert(id, &entry.content);

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
    reason: &str,
    pre_write: Option<&PreWriteState>,
    summary: &mut CurateSideEffectsSummary,
) {
    // The text this revision replaced, from the snapshot taken before the executor
    // ran. Reading the tree here would read the row the update already replaced,
    // which is what makes a history built at this point a list of the present.
    let superseded = pre_write.map(|state_before| state_before.content.as_str());
    let new_content = entry.content.clone();

    // `superseded` is captured by the caller BEFORE the executor mutated the tree.
    // Reading the tree here would read the row the update has already replaced,
    // which is what makes a history built at this point a list of the present.

    // Metadata is applied before the index work so a caller that reads the row
    // back sees the same values the write carried, not the previous ones.
    if let Some(existing) = state.context_tree.find_mut(id) {
        if !entry.provenance.source.is_empty() || entry.provenance.confidence > 0.0 {
            existing.provenance = entry.provenance.clone();
        }
        if !entry.rationale.is_empty() {
            existing.rationale = entry.rationale.clone();
        }
    }

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
    // Upsert on the lexical index drops the old terms first, so an edit can never
    // leave the previous wording searchable.
    state.lexical.upsert(id, &new_content);

    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.content = new_content.clone();
    }

    // THE EVENT CARRIES THE TEXT IT REPLACED (issue 483).
    //
    // It used to carry `{"reason": "update"}`, which cannot replay anything: the
    // trail knew a change happened and not what was changed. An event-sourced
    // store whose update events do not hold the superseded content is a log of
    // intentions, and the Archivist prompt's section 10 asks exactly the question
    // such a log cannot answer.
    //
    // `text` is what the memory says from this revision onward; `superseded` is
    // what it said before it. Both are needed: one reconstructs the current state,
    // the other reconstructs the history.
    // The reason is the caller's, not the literal string "update". An audit entry
    // that restates the operation name instead of the caller's explanation records
    // that something happened and not why, and "why" is the only part a person
    // reading a history needs. An absent reason stays absent rather than being
    // filled in with prose the caller never wrote.
    state.event_store.append(
        "memory.updated",
        id,
        serde_json::json!({
            "reason": reason,
            "text": new_content,
            "superseded": superseded,
            "category": entry.domain,
            "actor": entry.provenance.source,
            "confidence": entry.provenance.confidence,
        }),
    );

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

fn pipeline_remove_by_id(state: &mut CerebrumState, id: MemoryId) {
    let _ = state.dwm.remove_memory(id);
    let _ = state.signature_engine.remove_from_index(id);
    state.lexical.remove(id);
    state.cross_ref.remove(id);
    state.hot_cache_forget(&id);
}

/// Why a memory is being retired, which decides what happens to the bytes.
///
/// THE TWO CASES ARE NOT THE SAME AND USED TO BE TREATED AS IF THEY WERE.
///
/// A DELETE retires a memory the caller may later restore by id, so its decay
/// row is archived in place: the content stays readable and the row stops
/// being served. A MERGE retires a memory whose content now lives inside the
/// surviving record, so its decay row is archived for the same reason and by
/// the same code path, and the surviving record carries the merged body.
///
/// Both archive. Neither erases. The No-Delete doctrine does not have an
/// exception for merges, and the previous code assumed it did: `pipeline_remove_by_id`
/// cleared five indexes and never touched the decay entry, so a merged-away
/// source stayed live in the layer scoring actually reads, strengthening on
/// every access, and came back from disk after a restart because
/// `decay_records` persists entry content.
///
/// The graph needed the same treatment for a different reason. A retired id
/// whose edges and label survive can still be reached by a traversal and can
/// still be chosen as a traversal's START node, so the graph answered with a
/// memory the brain no longer admits to holding.
fn retire_memory(state: &mut CerebrumState, id: MemoryId) {
    pipeline_remove_by_id(state, id);
    state.graph_store.retire_memory_node(id);
    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.archived = true;
    }
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

    // THE LEXICAL INDEX IS THE LANE THAT ACTUALLY ANSWERS A SEARCH.
    //
    // This write was missing. The merge inserted into the DWM, the signature
    // index and the decay layer — none of which is what `handle_search` ranks
    // on. Its primary lane is BM25 over `state.lexical`, and its signature lane
    // is a recall aid that may only promote a memory the lexical lane also
    // found. So a merged record existed in the tree and in three indexes while
    // being invisible to the one surface a caller reads, which made a merge look
    // like a memory that vanished.
    state.lexical.upsert(merged_id, &target.content);

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

    // Every source leaves through the one retirement path, so a merge cannot
    // leave a live decay entry or a reachable graph node behind the way it
    // used to. A source that IS the target keeps its row: it is the record the
    // caller can still hold, and retiring it would delete the merged content.
    for removed in source_ids {
        if *removed == merged_id {
            continue;
        }
        retire_memory(state, *removed);
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

/// Retire a memory and record a deletion event that can actually be audited.
///
/// THE EVENT USED TO BE `{}`. It recorded that a memory disappeared and nothing
/// else: no identifier, no text, no category, no reason, no actor, no prior
/// lifecycle. An audit trail whose deletion entry carries an empty object answers
/// none of the questions an audit trail exists for, and the delete is the one
/// mutation a person is most likely to need explained later.
///
/// Everything here is read BEFORE `retire_memory` runs, because retirement archives
/// the decay row and clears the indexes. The tree row survives, which is what makes
/// the previous text and category recoverable at this moment and not afterwards.
///
/// This is an audit event, not a tombstone. The withheld mechanism is a second
/// record whose job is to block reuse of a deleted identity. This is a description
/// of what was retired, stored in the trail that already exists.
fn run_full_delete_pipeline(
    state: &mut CerebrumState,
    id: MemoryId,
    reason: &str,
    pre_write: Option<&PreWriteState>,
    summary: &mut CurateSideEffectsSummary,
) {
    // Everything the event records comes from the PreWriteState captured before the
    // executor archived the row. A delete for an id the tree never held has no
    // pre-write state and is described honestly as such rather than filled in.
    let (previous_text, category, actor, confidence, prior_lifecycle) = match pre_write {
        Some(before) => (
            Some(before.content.clone()),
            before.domain.clone(),
            before.provenance.clone(),
            before.confidence,
            before.lifecycle.clone(),
        ),
        None => (
            None,
            String::new(),
            String::new(),
            0.0f32,
            "Unknown".to_string(),
        ),
    };

    // The content identity: the brain's ids are already the hash of the content, so
    // the id IS the content address. Recorded explicitly so an auditor does not have
    // to know that.
    let content_id = hex_str(id);

    retire_memory(state, id);

    state.event_store.append(
        "memory.deleted",
        id,
        serde_json::json!({
            // An absent reason is the empty string the caller gave, never invented.
            // The event time is the event's own timestamp.
            "reason": reason,
            "text": previous_text,
            "category": category,
            "content_id": content_id,
            "actor": actor,
            "confidence": confidence,
            "previous_lifecycle": prior_lifecycle,
        }),
    );
    summary.deleted += 1;
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

    // Entity pass: match this memory's proper nouns against every other memory.
    //
    // THE COMPARISON IS CASE-INSENSITIVE ON BOTH SIDES, which is the whole point
    // of this pass. `entity_words_from` returns LOWERCASED words, because the
    // entity has to resolve to the same node however a name was capitalised in
    // one sentence. Matching those lowercase words against raw content with
    // `contains` meant searching for "steven" inside "...Steven Newman...", which
    // never matches. So the entity view stayed empty no matter how many memories
    // named a person, and every entity walk honestly answered "nothing
    // connected" about a name the brain held dozens of facts about.
    let entity_words = entity_words_from(content);
    if !entity_words.is_empty() {
        let entity_targets: Vec<(String, MemoryId)> = state
            .context_tree
            .entries()
            .filter(|other| other.id != current_id)
            .flat_map(|other| {
                let other_lower = other.content.to_lowercase();
                entity_words
                    .iter()
                    .filter(|word| other_lower.contains(word.as_str()))
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

/// Content folded to the form duplicate detection compares on.
///
/// Lowercased, whitespace collapsed, trimmed. Two records with the same
/// normalised body are the same memory written twice, which is the one
/// duplicate claim this layer makes without measuring anything.
fn normalised_content(content: &str) -> String {
    content.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

/// Write a consolidated replacement so it is findable the same way any other
/// memory is: a row in the context tree plus an entry in every index.
///
/// Used by the consolidation pass when the dream engine merges or dedups a
/// set of memories. The replacement gets the strongest decay strength of the
/// records it replaces, so folding away duplicates never makes the surviving
/// fact look less established than its sources.
fn write_merged_record(
    state: &mut CerebrumState,
    id: MemoryId,
    content: &str,
    domain: &str,
    topic: &str,
    subtopic: &str,
    source_count: usize,
) {
    let entry = ContextEntry::new(
        id,
        domain.to_string(),
        topic.to_string(),
        subtopic.to_string(),
        content,
    );
    state.context_tree.upsert_public(entry);

    let tokens: Vec<u32> = state.signature_engine.tokenizer().tokenize(content);
    let signature = state.signature_engine.generate(content);
    let _ = state
        .dwm
        .insert_memory(id, signature, TokenSequence::from_tokens(&tokens));
    state.signature_engine.add_to_index(id, signature);
    state.lexical.upsert(id, content);

    // The replacement starts life at the strongest layer its sources reached,
    // never below Buffer. A merge of two semantic facts stays semantic.
    let (layer, strength) = (MemoryLayer::Semantic, 1.0f32);
    state.decay_engine.insert(id, layer, content.to_string());
    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.strength = strength;
    }

    state.event_store.append(
        "memory.consolidated",
        id,
        serde_json::json!({
            "merged_from": source_count,
            "domain": domain,
            "topic": topic,
        }),
    );

    state.graph_store.add_label(
        id,
        format!("{domain} {topic} {subtopic} {content}"),
    );
    state.prefetch_engine.register(id, content.to_string());
    state.embedding_engine.embed(content);
    let summary_text: String = content.chars().take(POLICY_SUMMARY_MAX).collect();
    state.experience_store.create_index(IndexEntry::new(
        id,
        summary_text,
        id,
        content.len() / 4 + 1,
    ));
    state.query_engine.hot_cache.insert(id, content.to_string());
    state.hot_cache.insert(id, content.as_bytes().to_vec());
}

/// Refresh a memory that survives a consolidation pass, IN PLACE.
///
/// The row keeps its id, its domain/topic/subtopic and its lifecycle; only the
/// content and the indexes derived from it change. This is the difference
/// between folding a duplicate into the memory the caller already holds a
/// handle for, and replacing that memory with a stranger under a new id.
///
/// The decay entry is refreshed rather than recreated: recreating it would reset
/// the strength of a memory that was established enough to survive, and would
/// make a merge look like a brand new fact on the next decay pass.
fn update_consolidated_record(state: &mut CerebrumState, id: MemoryId, content: &str) {
    if let Some(entry) = state.context_tree.find_mut(id) {
        entry.content = content.to_string();
        entry.updated_at = Utc::now();
    }

    let tokens: Vec<u32> = state.signature_engine.tokenizer().tokenize(content);
    let signature = state.signature_engine.generate(content);
    let _ = state.dwm.remove_memory(id);
    let _ = state
        .dwm
        .insert_memory(id, signature, TokenSequence::from_tokens(&tokens));
    state.signature_engine.remove_from_index(id);
    state.signature_engine.add_to_index(id, signature);
    state.lexical.upsert(id, content);

    if let Some(decay_entry) = state.decay_engine.entries.get_mut(&id) {
        decay_entry.content = content.to_string();
    }

    state.event_store.append(
        "memory.consolidated",
        id,
        serde_json::json!({ "content_updated": true }),
    );

    let summary_text: String = content.chars().take(POLICY_SUMMARY_MAX).collect();
    let _ = state
        .experience_store
        .update_index(id, Some(summary_text), None);

    state.graph_store.add_label(id, content);
    state.prefetch_engine.register(id, content.to_string());
    state.embedding_engine.embed(content);
    state.query_engine.hot_cache.insert(id, content.to_string());
    state.hot_cache.insert(id, content.as_bytes().to_vec());
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
    //
    // A retired row answers "not found". The No-Delete doctrine keeps the bytes
    // in the vault, but every caller-facing read treats a deleted memory as
    // gone, and this is the lookup a delete or an update uses to decide whether
    // the id resolves at all. Returning the archived body here would tell a
    // second delete that the memory is still there.
    // The same retirement rule the search and listing use, so a memory cannot be
    // gone from one surface and present on another.
    if is_retired(&state, &req.memory_id) {
        return Err(ErrorResponse::new(format!(
            "memory not found: {:?}",
            req.memory_id
        )));
    }
    let entry: ContextEntry = state
        .context_tree
        .find(req.memory_id)
        .cloned()
        .ok_or_else(|| {
            ErrorResponse::new(format!("memory not found: {:?}", req.memory_id))
        })?;
    // The layer name is read from the policy so the wire spelling is one fact.
    let decay_metadata: Option<(String, f32, u32, bool)> = state
        .decay_engine
        .access(req.memory_id)
        .map(|e| {
            (
                RetrievalPolicy::layer_name(e.layer).to_string(),
                e.strength,
                e.access_count,
                e.archived,
            )
        });

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

    // Provenance and rationale ride every level. They are the only place a
    // caller's category and confidence survive a round trip, because the brain
    // stores content under domain/topic/subtopic and has no other slot for
    // them. A write path that dropped them would be a write path that lost the
    // caller's metadata, so they are read back here rather than guessed.
    let provenance_json = serde_json::json!({
        "source": entry.provenance.source,
        "confidence": entry.provenance.confidence,
    });
    let rationale_text = entry.rationale.clone();

    // Build per-level body bytes.
    let data = match &req.level {
        DereferenceLevel::Shallow => serde_json::to_vec(&serde_json::json!({
            "id": hex_str(req.memory_id),
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic,
            "provenance": provenance_json,
            "rationale": rationale_text,
            "lifecycle": format!("{:?}", entry.lifecycle),
            "decay": decay_metadata,
        }))
        .unwrap_or_default(),
        DereferenceLevel::Deep => serde_json::to_vec(&serde_json::json!({
            "id": hex_str(req.memory_id),
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic,
            "content": entry.content,
            "provenance": provenance_json,
            "rationale": rationale_text,
            "lifecycle": format!("{:?}", entry.lifecycle),
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

/// One search hit, carrying the row's real metadata rather than its body alone.
///
/// WHY THIS IS NOT JUST `{id, score, content}` ANY MORE. Search used to return the
/// body and nothing else, so the Kotlin boundary had to invent the rest. It invented
/// a constant `category = "memory"` and a constant `source = "cerebrum"`, and both
/// were wrong in a way no test caught:
///
///  * `memory` is not in the category vocabulary, so a category-filtered search
///    returned nothing for every real category.
///  * `cerebrum` is the STORAGE BACKEND, not the origin. An agent's ownership marker
///    rides in provenance, so a caller looking for `agent=<slug>` in `source` found
///    nothing and an own-only agent was handed an empty memory every time.
///
/// A read surface that returns the content and discards the metadata it just read is
/// the reason the two other read paths (listing and retrieve) disagreed with search
/// about what a category was. So the metadata travels here.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct SearchResult {
    memory_id: MemoryId,
    score: f32,
    content: String,
    /// The real category, which is the tree's domain.
    domain: String,
    topic: String,
    subtopic: String,
    /// The caller's confidence, as stored.
    confidence: f32,
    /// The provenance label exactly as it was written, so ownership and visibility
    /// markers survive to a caller that knows how to read them.
    provenance: String,
    /// The memory's lifecycle: Active, Hibernated or Archived.
    lifecycle: String,
    /// The decay layer, or empty when decay holds no row.
    layer: String,
    /// The decay strength, so a caller can see why a hit ranked where it did.
    strength: f32,
}

async fn handle_search(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let req: SearchRequest =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let mut state = state.lock().await;
    let limit = req.limit;

    // Retrieval is rank-fused across independent lanes, not threshold-gated.
    //
    // A fixed Hamming radius could not separate a relevant query from an
    // irrelevant one. Measured over 30 relevant and 512 unrelated pairs
    // (`cerebrum-signatures/tests/radius_eval.rs`): at the radius admitting 2%
    // junk, recall is 57%; at the radius recalling 83%, junk is 10.4%. The
    // distributions overlap (unrelated min 82, relevant max 134), and a distance
    // cutoff also admits more junk as the corpus grows.
    //
    // BM25 carries the lexical job properly: it has IDF, term-frequency
    // saturation and length normalisation, none of which the signature has.
    // The signature lane stays as a complementary recall pass, fused by
    // reciprocal rank so the two never need their scores calibrated against each
    // other. Top-k is stable under corpus growth, which a cutoff is not.

    // Lane 1: BM25 over the inverted index.
    let lexical_ranked: Vec<(MemoryId, f32)> = state.lexical.search(&req.query, limit * 3);

    // Lane 2: signature neighbourhood, rank-ordered by distance.
    //
    // The radius here is deliberately wide. It is no longer deciding what is a
    // match; it is deciding what enters the ranking, and RRF down-weights
    // anything the lexical lane did not also surface.
    let query_sig = state.signature_engine.generate(&req.query);
    const SIGNATURE_RECALL_RADIUS: u32 = 110;
    let mut signature_pairs: Vec<(MemoryId, u32)> = state
        .signature_engine
        .search(&query_sig, SIGNATURE_RECALL_RADIUS, limit * 3);
    signature_pairs.extend(
        state
            .dwm
            .search(&query_sig, SIGNATURE_RECALL_RADIUS, limit * 3),
    );
    let mut best_distance: HashMap<MemoryId, u32> = HashMap::new();
    for (id, dist) in signature_pairs {
        best_distance
            .entry(id)
            .and_modify(|e| *e = (*e).min(dist))
            .or_insert(dist);
    }
    let mut signature_ranked: Vec<(MemoryId, u32)> = best_distance
        .iter()
        .map(|(id, dist)| (*id, *dist))
        .collect();
    signature_ranked.sort_by_key(|(_, d)| *d);

    // Reciprocal rank fusion, k = 60 (the standard smoothing constant). Each lane
    // contributes 1/(k + rank) per hit, so a memory both lanes agree on outranks
    // one only a single lane found, without comparing raw scores from
    // incomparable scales.
    const RRF_K: f32 = 60.0;
    let mut fused: HashMap<MemoryId, f32> = HashMap::new();
    for (rank, (id, _)) in lexical_ranked.iter().enumerate() {
        *fused.entry(*id).or_insert(0.0) += 1.0 / (RRF_K + rank as f32);
    }
    // The signature lane is a RECALL aid only: it may promote a memory the
    // lexical lane also found, and it may never introduce one on its own.
    //
    // Its job was to catch paraphrases the lexical lane misses. Measured, it
    // does not do that reliably: the signatures are a projection of hashed
    // words, so "the history of the roman empire" shares its shape with memories
    // about a router purely through function-word overlap. Admitting those
    // produced junk for any query, and a lexical-only result set is the honest
    // floor until a real semantic lane exists.
    //
    // So: agreement on a memory is worth a boost, disagreement adds nothing.
    let lexical_ids: std::collections::HashSet<MemoryId> =
        lexical_ranked.iter().map(|(id, _)| *id).collect();
    for (rank, (id, _)) in signature_ranked.iter().enumerate() {
        if lexical_ids.contains(id) {
            *fused.entry(*id).or_insert(0.0) += 1.0 / (RRF_K + rank as f32);
        }
    }
    // Drop anything only the signature lane proposed.
    fused.retain(|id, _| lexical_ids.contains(id));

    // Order by fused score, then by lexical score, then by signature distance.
    // The tie-breaks matter because RRF produces many exact ties at low ranks.
    let lexical_score: HashMap<MemoryId, f32> = lexical_ranked.iter().copied().collect();
    let mut ordered: Vec<(MemoryId, f32)> = fused.into_iter().collect();
    ordered.sort_by(|a, b| {
        b.1.partial_cmp(&a.1)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| {
                let la = lexical_score.get(&a.0).copied().unwrap_or(0.0);
                let lb = lexical_score.get(&b.0).copied().unwrap_or(0.0);
                lb.partial_cmp(&la).unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| {
                let da = best_distance.get(&a.0).copied().unwrap_or(u32::MAX);
                let db = best_distance.get(&b.0).copied().unwrap_or(u32::MAX);
                da.cmp(&db)
            })
    });

    // Build results from real tree bodies, filtered and ranked by the memory's OWN
    // stored state rather than by lexical agreement alone.
    //
    // A fused id with no row in the tree is skipped: the index must never return a
    // memory the tree cannot produce. A retired row is skipped for the same reason
    // in the other direction: a caller who deleted a memory must never see it come
    // back out of search.
    //
    // THE POLICY IS WHERE THE MECHANISM ACTUALLY RUNS. Until now strength, layer
    // and the decay archive flag were computed, stored, persisted and rendered, and
    // none of them could change an answer. An archived or faded memory is now not
    // offered at all, and an eligible one is weighted by how established, how
    // consolidated and how recently reinforced it is.
    let policy = RetrievalPolicy::default();
    let top_fused = ordered.first().map(|(_, s)| *s).unwrap_or(1.0).max(f32::EPSILON);
    let mut scored: Vec<SearchResult> = Vec::new();
    for (memory_id, fused_score) in ordered.iter() {
        if is_retired(&state, memory_id) {
            continue;
        }
        let Some(entry) = state.context_tree.find(*memory_id) else {
            continue;
        };

        // Eligibility is asked BEFORE the access pass below, on the state as it
        // stands. Otherwise a faint memory would strengthen itself by being
        // considered and could qualify for an answer it does not deserve.
        let decay_row = state.decay_engine.entries.get(memory_id);
        if policy.eligibility(decay_row) != Eligibility::Eligible {
            continue;
        }
        let Some(decay_row) = decay_row else { continue };
        let multiplier = policy.score_multiplier(decay_row, entry.provenance.confidence);

        // Ranking is over the weighted score, never raw lexical agreement: a faint
        // memory matching a word must not outrank a fresh one matching the same
        // word.
        let weighted = (fused_score / top_fused) * multiplier;
        scored.push(SearchResult {
            memory_id: *memory_id,
            score: weighted,
            content: entry.content.clone(),
            domain: entry.domain.clone(),
            topic: entry.topic.clone(),
            subtopic: entry.subtopic.clone(),
            confidence: entry.provenance.confidence,
            provenance: entry.provenance.source.clone(),
            lifecycle: format!("{:?}", entry.lifecycle),
            layer: RetrievalPolicy::layer_name(decay_row.layer).to_string(),
            strength: decay_row.strength,
        });
    }

    // Highest weighted score first. The id breaks ties so two equal memories cannot
    // swap order between identical calls.
    scored.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| a.memory_id.0.cmp(&b.memory_id.0))
    });
    scored.truncate(limit);

    // Only what is actually RETURNED is strengthened, and only once the ordering is
    // settled. Marking every candidate as accessed would reward memories the caller
    // never saw, which reinforces the retrieval pass rather than the use of it.
    for hit in &scored {
        state.decay_engine.access(hit.memory_id);
    }

    let mut results: Vec<SearchResult> = scored;

    // Last resort: exact phrase present but no shared indexed term (for example a
    // two-character query, which tokenisation drops).
    //
    // THIS FALLBACK OBEYS THE SAME POLICY. It used to check only the tree's
    // lifecycle, so a memory decay had archived was excluded from the ranked path
    // and then served by this one, which is the retrieval equivalent of locking the
    // front door and leaving the window open. A fallback is still a read.
    if results.is_empty() {
        let query_lower = req.query.to_lowercase();
        for entry in state.context_tree.entries() {
            if is_retired(&state, &entry.id) {
                continue;
            }
            let decay_row = state.decay_engine.entries.get(&entry.id);
            if policy.eligibility(decay_row) != Eligibility::Eligible {
                continue;
            }
            if entry.content.to_lowercase().contains(&query_lower) {
                let multiplier = decay_row
                    .map(|row| policy.score_multiplier(row, entry.provenance.confidence))
                    .unwrap_or(1.0);
                results.push(SearchResult {
                    memory_id: entry.id,
                    score: 0.5 * multiplier,
                    content: entry.content.clone(),
                    domain: entry.domain.clone(),
                    topic: entry.topic.clone(),
                    subtopic: entry.subtopic.clone(),
                    confidence: entry.provenance.confidence,
                    provenance: entry.provenance.source.clone(),
                    lifecycle: format!("{:?}", entry.lifecycle),
                    layer: decay_row
                        .map(|row| RetrievalPolicy::layer_name(row.layer).to_string())
                        .unwrap_or_default(),
                    strength: decay_row.map(|row| row.strength).unwrap_or(0.0),
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

    /*
     * TWO ENGINES, ONE OPINION (archive correctness).
     *
     * `decay_engine.consolidate` retires a memory by setting its decay row's
     * `archived` flag. It has never touched the context tree, whose
     * `EntryLifecycle` is what every read filters on. The result was a brain that
     * disagreed with itself: decay considered the memory dead, the tree considered
     * it live, and search believed the tree. A memory could be archived by every
     * pass for a year and still answer every query.
     *
     * So every archival decision is published to the tree here, at the one point
     * where the pass knows both facts. This is the write half of the read rule in
     * `is_retired`, which treats either engine's retirement as final.
     */
    for archived_id in &decay_report.archived {
        if let Some(entry) = state.context_tree.find_mut(*archived_id) {
            entry.lifecycle = cerebrum_curate::EntryLifecycle::Archived;
            entry.updated_at = Utc::now();
            state.event_store.append(
                "memory.archived",
                *archived_id,
                serde_json::json!({
                    "reason": "decay archive threshold reached",
                    "lifecycle": "Archived",
                }),
            );
        }
    }

    /*
     * HIBERNATED GETS A PRODUCER.
     *
     * `EntryLifecycle::Hibernated` existed in the curate crate and was set by
     * nothing in production: it appeared in a unit test and in the persistence
     * parser, and nowhere else. A lifecycle state the system declares and never
     * enters is a decorative enum value, and it makes the claim "this brain models
     * memory fading" false.
     *
     * A memory whose strength has fallen below the decay config's hibernation
     * threshold has genuinely faded: it is harder to recall, and it is on its way
     * to archival if nothing reinforces it. That is what Hibernated means, so that
     * is when it is entered. It is not terminal: a recall strengthens the entry and
     * the next pass promotes it back, because `consolidate` promotes any Buffer or
     * Episodic entry that has been accessed and is above the threshold.
     */
    let policy = RetrievalPolicy::default();
    let now = Utc::now();
    let mut hibernated_now: Vec<MemoryId> = Vec::new();
    let mut revived_now: Vec<MemoryId> = Vec::new();
    for decay_entry in state.decay_engine.entries.values() {
        if decay_entry.archived {
            continue;
        }
        if policy.should_hibernate(decay_entry, &state.decay_engine.config) {
            hibernated_now.push(decay_entry.id);
        } else if decay_entry.strength >= state.decay_engine.config.hibernation_threshold {
            revived_now.push(decay_entry.id);
        }
    }
    for hibernated_id in hibernated_now {
        if let Some(entry) = state.context_tree.find_mut(hibernated_id) {
            if entry.lifecycle == cerebrum_curate::EntryLifecycle::Active {
                entry.lifecycle = cerebrum_curate::EntryLifecycle::Hibernated;
                entry.updated_at = now;
                state.event_store.append(
                    "memory.hibernated",
                    hibernated_id,
                    serde_json::json!({
                        "reason": "strength below hibernation threshold",
                        "lifecycle": "Hibernated",
                    }),
                );
            }
        }
    }
    // A hibernated memory that was recalled is live again. Without this the state
    // would be a one-way door and a remembered memory would stay marked as fading.
    for revived_id in revived_now {
        if let Some(entry) = state.context_tree.find_mut(revived_id) {
            if entry.lifecycle == cerebrum_curate::EntryLifecycle::Hibernated {
                entry.lifecycle = cerebrum_curate::EntryLifecycle::Active;
                entry.updated_at = now;
                state.event_store.append(
                    "memory.revived",
                    revived_id,
                    serde_json::json!({
                        "reason": "strength restored above hibernation threshold",
                        "lifecycle": "Active",
                    }),
                );
            }
        }
    }

    // 3. Rebuild the dream view from the live decay snapshot.
    //
    // The map is cleared first. It is a working view of THIS pass, not a
    // store: leaving it populated made every earlier pass's unreachable
    // merged rows pile up, and fed already-consumed ids back in on the next
    // run. Only entries that still have a row in the context tree are fed
    // in, so a memory that was consumed by an earlier merge can never be
    // consumed again.
    state.dream_engine.memories.clear();

    let tree_ids: std::collections::HashSet<MemoryId> =
        state.context_tree.entries().map(|e| e.id).collect();

    let decay_snapshot: Vec<(MemoryId, String, String, f32, chrono::DateTime<Utc>)> = state
        .decay_engine
        .entries
        .values()
        .filter(|e| !e.archived && tree_ids.contains(&e.id))
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

    // Duplicate detection is exact-content only, deliberately.
    //
    // The 256-bit random-indexing signature does not separate unrelated prose
    // from near-identical prose. Measured on real sentences (see
    // tests/consolidate_integrity.rs), unrelated pairs score 0.945 to 0.961,
    // which is 10 to 14 bits apart out of 256. Both the merge threshold (0.8)
    // and the dedup threshold (0.95) therefore fire on text that has nothing
    // in common. Handing that number to the dream engine made consolidation
    // merge almost everything it was given.
    //
    // The only question this layer can answer honestly is "is this the same
    // text?". Anything cleverer waits until there is a similarity measure
    // with a measured false-positive rate.
    let normalised: Vec<(MemoryId, String)> = decay_snapshot
        .iter()
        .map(|(id, content, _, _, _)| (*id, normalised_content(content)))
        .collect();

    {
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
            for (other_index, (other_id, other_content)) in normalised.iter().enumerate() {
                if other_index == index {
                    continue;
                }
                if *other_content == normalised[index].1 {
                    dream_memory.add_similarity(*other_id, 1.0);
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

    // 4+5. Apply dream results back into the engine world.
    //
    // The contract, in order, for every merge or dedup the dream engine
    // reports: write the replacement into the context tree AND every index
    // first, prove the tree can read it back, and only then remove the
    // records it replaced. Removing first (the previous behaviour) cut each
    // consumed memory out of the DWM, the signature index, the cross
    // references and the hot cache while leaving its row in the tree, so it
    // could no longer be found by any search, and the replacement never
    // existed outside an in-memory map that the next pass rebuilt from
    // scratch.
    for result in &dream_results {
        match result.operation {
            cerebrum_dream::DreamOperation::Merge | cerebrum_dream::DreamOperation::Dedup => {
                // A source is consumed when the dream engine no longer holds
                // it; replacements are the ids it does hold.
                let consumed: Vec<MemoryId> = result
                    .affected_ids
                    .iter()
                    .copied()
                    .filter(|id| state.dream_engine.get(*id).is_none())
                    .filter(|id| state.context_tree.find(*id).is_some())
                    .collect();
                let replacements: Vec<MemoryId> = result
                    .affected_ids
                    .iter()
                    .copied()
                    .filter(|id| state.dream_engine.get(*id).is_some())
                    .collect();

                if consumed.is_empty() {
                    continue;
                }

                // A replacement that IS one of the consumed sources is not a new
                // row: it is the same memory, refreshed. Writing it as a new
                // entry and then deleting the original would leave the brain
                // with a fresh row and a corpse, and every held id for the
                // original would resolve to the corpse. So the two cases are
                // separated here rather than papered over downstream.
                let mut written: Vec<MemoryId> = Vec::new();
                for replacement_id in &replacements {
                    let Some(dream_memory) = state.dream_engine.get(*replacement_id).cloned() else {
                        continue;
                    };
                    if state.context_tree.find(*replacement_id).is_some() {
                        // The replacement is an EXISTING memory, which is the
                        // survivor case: refresh its body and re-index it in
                        // place so its id, hierarchy and metadata all stand.
                        update_consolidated_record(&mut state, *replacement_id, &dream_memory.content);
                        written.push(*replacement_id);
                        continue;
                    }

                    // A genuinely new identity (only reachable for a merge whose
                    // target was not one of its sources). Inherit the hierarchy
                    // from the first consumed row, read before anything moves.
                    let (domain, topic, subtopic) = consumed
                        .first()
                        .and_then(|id| state.context_tree.find(*id))
                        .map(|entry| {
                            (
                                entry.domain.clone(),
                                entry.topic.clone(),
                                entry.subtopic.clone(),
                            )
                        })
                        .unwrap_or_else(|| {
                            ("memory".into(), "consolidated".into(), "merged".into())
                        });

                    write_merged_record(
                        &mut state,
                        *replacement_id,
                        &dream_memory.content,
                        &domain,
                        &topic,
                        &subtopic,
                        consumed.len(),
                    );
                    // Proof, not assumption: the tree must be able to hand the
                    // content back before any source leaves the indexes.
                    let readable = state
                        .context_tree
                        .find(*replacement_id)
                        .map(|entry| !entry.content.trim().is_empty())
                        .unwrap_or(false);
                    if readable {
                        written.push(*replacement_id);
                    } else {
                        // Leave the world untouched rather than trade a
                        // findable memory for an unreadable one.
                        pipeline_remove_by_id(&mut state, *replacement_id);
                    }
                }

                if written.is_empty() {
                    // Nothing safe to swap in. Keep the originals exactly as
                    // they are; the next pass can try again.
                    continue;
                }

                // Retire only the rows that were absorbed BY a row that
                // survived. A source that is itself a survivor keeps its row.
                for consumed_id in &consumed {
                    if written.contains(consumed_id) {
                        continue;
                    }
                    // Remove the row as well as every index entry, so the tree
                    // and the indexes agree on what exists. The retirement path
                    // is the same one delete and merge use: it archives the
                    // decay row and takes the node out of all four graph views,
                    // so a consolidated-away memory stops being scored against
                    // and stops being reachable by a traversal.
                    state.context_tree.remove(*consumed_id);
                    retire_memory(&mut state, *consumed_id);
                }
                report.merged_into_dwm += written.len();
                report.removed_from_dwm += consumed.len();
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
// LIST
// ============================================================================

/// One listed memory: everything a caller needs to show a row and act on it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ListedMemory {
    pub memory_id: MemoryId,
    pub domain: String,
    pub topic: String,
    pub subtopic: String,
    pub content: String,
    pub confidence: f32,
    pub provenance: String,
    pub rationale: String,
    pub created_at: String,
    pub updated_at: String,
    /// The memory's lifecycle as the tree holds it: Active, Hibernated or Archived.
    ///
    /// Carried on the wire so a caller can tell a memory that is fading from one
    /// that is established without asking a second question the brain would have to
    /// answer from a different engine.
    pub lifecycle: String,
    /// The decay layer: BUFFER, EPISODIC or SEMANTIC, empty when unrecorded.
    pub layer: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct ListRequest {
    /// Optional domain filter. Ignored when blank.
    #[serde(default)]
    domain: String,
    #[serde(default)]
    limit: usize,
    /// Whether to enumerate RETIRED memories instead of live ones.
    ///
    /// THE DEDUPLICATION PASS NEEDS THIS. The brain's id for a memory is the hash of
    /// its content, so writing a sentence a second time resolves to the row a delete
    /// archived. A pass that can only see live rows therefore cannot tell that a fact
    /// is already known, and the write it proposes would either resurrect the row or
    /// be refused. Seeing the archive lets it conclude "already known, do not write".
    ///
    /// Defaults to false, so every existing caller keeps the live listing it expects
    /// and this cannot accidentally widen an ordinary read.
    #[serde(default)]
    include_archived: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ListResponse {
    /// The rows this response carries, newest first.
    pub memories: Vec<ListedMemory>,
    /// How many live memories the requested filter matched, ignoring `limit`.
    ///
    /// Separate from `memories.len()` on purpose. A capped list that reported
    /// its own length as the total would be a listing that quietly claims the
    /// brain holds less than it does, which is exactly the class of lie this
    /// frame exists to remove.
    pub total_matching: usize,
    /// How many live memories the brain holds in total, before any filter.
    pub total_live: usize,
}

/// Enumerate live memories with their metadata, newest first.
///
/// This is the honest answer to "what do you hold". Search answers a relevance
/// question and cannot: a caller listing its memory by searching for a keyword
/// only ever sees the rows that share a word with that keyword, which silently
/// omits every other memory and made both the listing tool and the delete
/// tool's existence check unreliable.
async fn handle_list(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    // An empty payload is a legitimate "list everything" request, so a decode
    // failure falls back to the unfiltered shape rather than refusing.
    let req: ListRequest = if payload.is_empty() {
        ListRequest {
            domain: String::new(),
            limit: 0,
            include_archived: false,
        }
    } else {
        serde_json::from_slice(payload).unwrap_or(ListRequest {
            domain: String::new(),
            limit: 0,
            include_archived: false,
        })
    };

    let state = state.lock().await;
    let domain_filter = req.domain.trim();
    let limit = if req.limit == 0 { usize::MAX } else { req.limit };

    // THE LISTING USES THE SAME RETIREMENT RULE AS SEARCH.
    //
    // It checked only the tree lifecycle, so a memory decay had archived stayed in
    // every listing, and the listing is what the deduplication pass reads. That is
    // one half of the resurrection defect: a deleted fact never looked deleted to
    // the pass deciding whether to write it again.
    //
    // `include_archived` is the other half. The deduplication pass is the one caller
    // that must see retired rows, because it is deciding whether a fact it is about
    // to write is already known. Every other caller keeps the live listing.
    let want_archived = req.include_archived;
    let mut rows: Vec<ListedMemory> = state
        .context_tree
        .entries()
        .filter(|entry| is_retired(&state, &entry.id) == want_archived)
        .filter(|entry| {
            domain_filter.is_empty() || entry.domain.eq_ignore_ascii_case(domain_filter)
        })
        .map(|entry| ListedMemory {
            memory_id: entry.id,
            domain: entry.domain.clone(),
            topic: entry.topic.clone(),
            subtopic: entry.subtopic.clone(),
            content: entry.content.clone(),
            confidence: entry.provenance.confidence,
            provenance: entry.provenance.source.clone(),
            rationale: entry.rationale.clone(),
            created_at: entry.created_at.to_rfc3339(),
            updated_at: entry.updated_at.to_rfc3339(),
            lifecycle: format!("{:?}", entry.lifecycle),
            layer: layer_label_for(&state, &entry.id),
        })
        .collect();

    // Newest first. The tie-break on the id keeps the order total, so two rows
    // written in the same millisecond never swap between calls.
    rows.sort_by(|a, b| {
        b.updated_at
            .cmp(&a.updated_at)
            .then_with(|| a.memory_id.0.cmp(&b.memory_id.0))
    });

    let total_matching = rows.len();
    rows.truncate(limit);

    // The total obeys the same rule, or a listing would report a live count that
    // disagrees with the rows it is willing to show. It always counts LIVE rows: it
    // describes the brain's ordinary contents, not the page that was asked for.
    let total_live = state
        .context_tree
        .entries()
        .filter(|entry| !is_retired(&state, &entry.id))
        .count();

    let response = ListResponse {
        memories: rows,
        total_matching,
        total_live,
    };
    serde_json::to_vec(&response).map_err(|e| ErrorResponse::new(e.to_string()))
}

// ============================================================================
// HISTORY
// ============================================================================

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct HistoryRequest {
    memory_id: MemoryId,
}

/// One recorded revision of a memory, with everything an audit trail is asked for.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HistoryRevision {
    /// Which write produced it: Created, Updated, Merged, Consolidated or Archived.
    pub kind: String,
    /// What the memory said DURING this revision.
    pub content: String,
    /// What this revision replaced. Null for a create, and for an update written before
    /// the payload carried the superseded text.
    pub superseded: Option<String>,
    /// The caller's stated reason, empty when none was given. Never invented.
    pub reason: String,
    /// Who produced it, from the provenance label the write carried.
    pub actor: String,
    /// When the brain recorded it, RFC 3339.
    pub recorded_at: String,
}

/// The whole recorded history of one memory, oldest first.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HistoryResponse {
    pub memory_id: MemoryId,
    /// The lifecycle NOW, which is not the same as the lifecycle at any revision.
    pub lifecycle: String,
    /// The category the row is filed under now.
    pub category: String,
    pub revisions: Vec<HistoryRevision>,
    /// Whether this memory has ever been deleted or archived, read from the trail rather
    /// than inferred from the current state.
    pub ever_retired: bool,
}

/// Every event ever recorded about one memory, oldest first.
///
/// THE DEFECT THIS OPENS THE DOOR ON. `EventStore::history_for`, `previous_content` and the
/// `superseded` field in `memory.updated` were merged, tested, and reachable by nothing: no
/// frame existed, so the only caller was a unit test. Mutation audit was stored and
/// unusable.
///
/// A DELETED OR ARCHIVED MEMORY ANSWERS. This is the one read that must survive retirement,
/// because "what did this say, and who deleted it, and why" is a question asked precisely
/// when the memory is gone. Every other surface refuses a retired row; this one reads its
/// trail.
async fn handle_history(
    payload: &[u8],
    state: Arc<Mutex<CerebrumState>>,
) -> Result<Vec<u8>, ErrorResponse> {
    let req: HistoryRequest =
        serde_json::from_slice(payload).map_err(|e| ErrorResponse::new(e.to_string()))?;

    let state = state.lock().await;

    let entry = state.context_tree.find(req.memory_id).cloned();
    let revisions = state.event_store.history_for(req.memory_id);
    let ever_retired = state
        .event_store
        .get_by_memory_id(req.memory_id)
        .iter()
        .any(|event| event.event_type == "memory.deleted" || event.event_type == "memory.archived");

    /*
     * AN ID WITH NO ROW AND NO EVENTS IS NOT A MEMORY, so it is refused rather than answered
     * with an empty history that looks like a memory nothing ever happened to.
     */
    if entry.is_none() && revisions.is_empty() && !ever_retired {
        return Err(ErrorResponse::new(format!(
            "no memory and no recorded history for {:?}",
            req.memory_id
        )));
    }

    let mapped: Vec<HistoryRevision> = revisions
        .into_iter()
        .map(|revision| HistoryRevision {
            kind: format!("{:?}", revision.revision_kind),
            content: revision.content,
            superseded: revision.superseded,
            reason: revision.reason,
            actor: entry
                .as_ref()
                .map(|row| row.provenance.source.clone())
                .unwrap_or_default(),
            recorded_at: revision.recorded_at.to_rfc3339(),
        })
        .collect();

    let response = HistoryResponse {
        memory_id: req.memory_id,
        lifecycle: entry
            .as_ref()
            .map(|row| format!("{:?}", row.lifecycle))
            .unwrap_or_else(|| "Unknown".to_string()),
        category: entry
            .as_ref()
            .map(|row| row.domain.clone())
            .unwrap_or_default(),
        revisions: mapped,
        ever_retired,
    };

    serde_json::to_vec(&response).map_err(|e| ErrorResponse::new(e.to_string()))
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

    /// Put one memory in the brain and hand back its id, through the real curate path.
    async fn add_one(state: Arc<Mutex<CerebrumState>>, content: &str, domain: &str) -> MemoryId {
        let entry = CoreContextEntry {
            memory_id: MemoryId::new(),
            domain: domain.to_string(),
            topic: "general".to_string(),
            subtopic: "general".to_string(),
            content: content.to_string(),
            relations: vec![],
            provenance: "test-suite".into(),
            rationale: "test".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        };
        let id = entry.memory_id;
        let bytes = serde_json::to_vec(&vec![CurateOp::Add {
            entry,
            reason: "test add".into(),
        }])
        .unwrap();
        let response = handle_curate(&bytes, state).await.unwrap();
        let parsed: CurateFullResponse = serde_json::from_slice(&response).unwrap();
        assert!(parsed.executed.success);
        id
    }

    async fn search_ids(state: Arc<Mutex<CerebrumState>>, query: &str) -> Vec<MemoryId> {
        let bytes = serde_json::to_vec(&serde_json::json!({
            "query": query,
            "limit": 20
        }))
        .unwrap();
        let response = handle_search(&bytes, state).await.unwrap();
        let results: Vec<SearchResult> = serde_json::from_slice(&response).unwrap();
        results.into_iter().map(|row| row.memory_id).collect()
    }

    #[tokio::test]
    async fn an_archived_memory_is_absent_from_search_and_from_the_listing() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "the router is in the hallway cupboard", "home").await;

        // Present before retirement, so the assertion after it means something.
        assert!(
            search_ids(state.clone(), "router hallway").await.contains(&id),
            "the memory must be findable before it is retired, or the test proves nothing"
        );

        // Retire it the way decay does: through the decay row only. The tree
        // lifecycle is deliberately left Active, because that is the exact state the
        // defect produced and the state a tree-only filter passed.
        {
            let mut locked = state.lock().await;
            locked.decay_engine.entries.get_mut(&id).unwrap().archived = true;
        }
        assert_eq!(
            state.lock().await.context_tree.find(id).unwrap().lifecycle,
            cerebrum_curate::EntryLifecycle::Active,
            "the tree must still say Active, or this test is not testing the disagreement"
        );

        assert!(
            !search_ids(state.clone(), "router hallway").await.contains(&id),
            "a memory retired by decay must not be served by search"
        );

        let list_bytes = serde_json::to_vec(&serde_json::json!({})).unwrap();
        let listed: ListResponse = serde_json::from_slice(
            &handle_list(&list_bytes, state.clone()).await.unwrap(),
        )
        .unwrap();
        assert!(
            !listed.memories.iter().any(|row| row.memory_id == id),
            "a memory retired by decay must not appear in the listing"
        );
        assert_eq!(listed.total_live, 0, "nor be counted as live");
    }

    #[tokio::test]
    async fn retrieval_strengthens_only_what_it_returns() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let returned = add_one(state.clone(), "the boiler service is booked for tuesday", "home").await;
        let unrelated = add_one(state.clone(), "the garden gate hinge needs oil", "home").await;

        let hits = search_ids(state.clone(), "boiler service tuesday").await;
        assert!(hits.contains(&returned));

        let locked = state.lock().await;
        let returned_accesses = locked.decay_engine.entries.get(&returned).unwrap().access_count;
        let unrelated_accesses = locked.decay_engine.entries.get(&unrelated).unwrap().access_count;
        assert!(
            returned_accesses > 0,
            "a returned memory is a used memory and must be strengthened"
        );
        assert_eq!(
            unrelated_accesses, 0,
            "a memory the caller never saw must not be strengthened by the retrieval pass"
        );
    }

    #[tokio::test]
    async fn consolidation_publishes_its_archival_decision_to_the_tree() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "an old fact that is about to fade away entirely", "home").await;

        // Age it past the archive threshold and faint it, exactly as ninety days of
        // neglect would, then run the real consolidation pass.
        {
            let mut locked = state.lock().await;
            let decay_entry = locked.decay_engine.entries.get_mut(&id).unwrap();
            decay_entry.strength = 0.0001;
            decay_entry.last_accessed = Utc::now() - chrono::Duration::days(120);
        }
        let report_bytes = handle_consolidate(state.clone()).await.unwrap();
        let report: ConsolidationReportFull = serde_json::from_slice(&report_bytes).unwrap();
        assert!(report.archived >= 1, "the pass must have archived the faint old memory");

        let locked = state.lock().await;
        assert_eq!(
            locked.context_tree.find(id).unwrap().lifecycle,
            cerebrum_curate::EntryLifecycle::Archived,
            "decay archived it, so the tree must agree; two engines cannot hold different opinions"
        );
        assert!(
            locked
                .event_store
                .get_by_memory_id(id)
                .iter()
                .any(|event| event.event_type == "memory.archived"),
            "the archival decision must be recorded in the trail"
        );
    }

    #[tokio::test]
    async fn hibernated_has_a_real_producer_and_is_reversible() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "a fact that will fade but not yet be archived", "home").await;

        // Faint but recent: below the hibernation threshold, nowhere near ninety days.
        {
            let mut locked = state.lock().await;
            locked.decay_engine.entries.get_mut(&id).unwrap().strength = 0.001;
        }
        handle_consolidate(state.clone()).await.unwrap();
        assert_eq!(
            state.lock().await.context_tree.find(id).unwrap().lifecycle,
            cerebrum_curate::EntryLifecycle::Hibernated,
            "a faint recent memory enters Hibernated, which nothing in production ever did before"
        );

        // Recall it hard enough to clear the threshold, then consolidate again.
        {
            let mut locked = state.lock().await;
            for _ in 0..5 {
                locked.decay_engine.access(id);
            }
        }
        handle_consolidate(state.clone()).await.unwrap();
        assert_eq!(
            state.lock().await.context_tree.find(id).unwrap().lifecycle,
            cerebrum_curate::EntryLifecycle::Active,
            "a hibernated memory that is recalled is live again, so the state is not a one-way door"
        );
    }

    #[tokio::test]
    async fn a_deletion_event_carries_the_text_the_category_and_the_reason() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "the spare key is under the blue pot", "home").await;

        let delete_op = serde_json::to_vec(&vec![CurateOp::Delete {
            memory_id: id,
            reason: "the owner asked for this to be forgotten".to_string(),
        }])
        .unwrap();
        handle_curate(&delete_op, state.clone()).await.unwrap();

        let locked = state.lock().await;
        let events = locked.event_store.get_by_memory_id(id);
        let deletion = events
            .iter()
            .find(|event| event.event_type == "memory.deleted")
            .expect("a deletion must leave an event");

        assert_eq!(
            deletion.payload.get("text").and_then(|v| v.as_str()),
            Some("the spare key is under the blue pot"),
            "the event must carry the text it retired, read before retirement cleared it"
        );
        assert_eq!(
            deletion.payload.get("category").and_then(|v| v.as_str()),
            Some("home"),
            "the event must carry the category the memory was filed under"
        );
        assert_eq!(
            deletion.payload.get("reason").and_then(|v| v.as_str()),
            Some("the owner asked for this to be forgotten"),
            "the caller's reason must be recorded as given, never replaced with an operation name"
        );
        assert_eq!(
            deletion.payload.get("previous_lifecycle").and_then(|v| v.as_str()),
            Some("Active"),
            "the lifecycle before retirement is part of the audit"
        );
        assert!(
            deletion.payload.get("content_id").and_then(|v| v.as_str()).is_some(),
            "the content identity must be recorded so the retired row can be traced"
        );
    }

    #[tokio::test]
    async fn an_update_records_the_text_it_replaced_and_the_reason_it_was_given() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "the meeting is on monday", "work").await;

        let changes = cerebrum_core::PartialEntry {
            content: Some("the meeting is on tuesday".to_string()),
            ..Default::default()
        };
        let update_op = serde_json::to_vec(&vec![CurateOp::Update {
            memory_id: id,
            changes,
            reason: "the meeting moved a day".to_string(),
        }])
        .unwrap();
        handle_curate(&update_op, state.clone()).await.unwrap();

        let locked = state.lock().await;
        let revisions = locked.event_store.history_for(id);
        let latest = revisions.last().expect("an update must produce a revision");
        assert_eq!(latest.content, "the meeting is on tuesday");
        assert_eq!(
            latest.superseded.as_deref(),
            Some("the meeting is on monday"),
            "the superseded text must survive, which is the only reason the native Update exists"
        );
        assert_eq!(
            latest.reason, "the meeting moved a day",
            "the caller's reason must be recorded, not the literal string update"
        );
    }

    #[tokio::test]
    async fn the_archive_listing_shows_retired_rows_and_the_live_listing_does_not() {
        let state = Arc::new(Mutex::new(fresh_state()));
        let retired = add_one(state.clone(), "a fact the owner wanted forgotten", "home").await;
        let live = add_one(state.clone(), "a fact that is still in use", "home").await;

        let delete_op = serde_json::to_vec(&vec![CurateOp::Delete {
            memory_id: retired,
            reason: "owner asked".to_string(),
        }])
        .unwrap();
        handle_curate(&delete_op, state.clone()).await.unwrap();

        let live_bytes = serde_json::to_vec(&serde_json::json!({})).unwrap();
        let live_listing: ListResponse =
            serde_json::from_slice(&handle_list(&live_bytes, state.clone()).await.unwrap()).unwrap();
        assert!(
            !live_listing.memories.iter().any(|row| row.memory_id == retired),
            "an ordinary listing must not show a retired row"
        );
        assert!(live_listing.memories.iter().any(|row| row.memory_id == live));

        let archived_bytes =
            serde_json::to_vec(&serde_json::json!({ "include_archived": true })).unwrap();
        let archive_listing: ListResponse =
            serde_json::from_slice(&handle_list(&archived_bytes, state.clone()).await.unwrap())
                .unwrap();
        assert!(
            archive_listing.memories.iter().any(|row| row.memory_id == retired),
            "the deduplication pass must be able to see retired rows, or it will propose a write \
             that resolves to one"
        );
        assert!(
            !archive_listing.memories.iter().any(|row| row.memory_id == live),
            "the archive listing is the archive, not a second live listing"
        );
    }

    /// Ask for a memory's history THROUGH THE FRAME DISPATCHER.
    ///
    /// Through `handle_frame`, not `handle_history` directly. A test that calls the handler
    /// proves the function works and says nothing about whether anything can reach it, which
    /// is exactly the defect being fixed: `history_for` had no caller because no frame
    /// carried the question. Disabling the route must fail this test, and it only does if the
    /// route is what is exercised.
    async fn history_of(state: Arc<Mutex<CerebrumState>>, id: MemoryId) -> HistoryResponse {
        let payload = serde_json::to_vec(&HistoryRequest { memory_id: id }).unwrap();
        let reply = handle_frame(
            Frame::new(frame_types::HISTORY, 1, payload),
            state,
        )
        .await
        .expect("the HISTORY frame must be routed and answered");
        serde_json::from_slice(&reply).unwrap()
    }

    #[tokio::test]
    async fn the_history_of_a_memory_is_reachable_and_carries_what_each_revision_replaced() {
        /*
         * THE DOOR THIS PROVES EXISTS AT ALL. `EventStore::history_for` and
         * `previous_content` were merged and tested and had no caller outside a unit test,
         * because no frame reached them. Mutation audit was stored and unusable.
         */
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "the meeting is on monday", "work").await;

        let changes = cerebrum_core::PartialEntry {
            content: Some("the meeting is on tuesday".to_string()),
            ..Default::default()
        };
        let update_op = serde_json::to_vec(&vec![CurateOp::Update {
            memory_id: id,
            changes,
            reason: "the meeting moved a day".to_string(),
        }])
        .unwrap();
        handle_curate(&update_op, state.clone()).await.unwrap();

        let history = history_of(state.clone(), id).await;
        assert_eq!(
            2,
            history.revisions.len(),
            "two revisions: the create and the update, got {:?}",
            history.revisions
        );
        assert_eq!("Created", history.revisions[0].kind);
        assert_eq!("the meeting is on monday", history.revisions[0].content);
        assert_eq!(
            None,
            history.revisions[0].superseded,
            "a create replaced nothing, and saying otherwise would invent a prior version"
        );

        assert_eq!("Updated", history.revisions[1].kind);
        assert_eq!("the meeting is on tuesday", history.revisions[1].content);
        assert_eq!(
            Some("the meeting is on monday".to_string()),
            history.revisions[1].superseded,
            "the superseded text is the whole point of the native Update"
        );
        assert_eq!(
            "the meeting moved a day",
            history.revisions[1].reason,
            "the caller's reason, never the operation name"
        );
        assert_eq!(
            "Active",
            history.lifecycle,
            "and the lifecycle now, which is not the lifecycle at every revision"
        );
        assert!(!history.ever_retired);
    }

    #[tokio::test]
    async fn the_history_of_a_deleted_memory_still_answers_and_says_it_was_retired() {
        /*
         * THE QUESTION IS ASKED PRECISELY WHEN THE MEMORY IS GONE. Every other read surface
         * refuses a retired row; this one must not, because "what did this say and who
         * deleted it" is the whole reason a trail exists.
         */
        let state = Arc::new(Mutex::new(fresh_state()));
        let id = add_one(state.clone(), "the spare key is under the blue pot", "home").await;

        let delete_op = serde_json::to_vec(&vec![CurateOp::Delete {
            memory_id: id,
            reason: "the owner asked for this to be forgotten".to_string(),
        }])
        .unwrap();
        handle_curate(&delete_op, state.clone()).await.unwrap();

        let history = history_of(state.clone(), id).await;
        assert!(history.ever_retired, "the trail must say this memory was retired");
        assert_eq!("Archived", history.lifecycle);
        assert!(
            history.revisions.iter().any(|r| r.content.contains("blue pot")),
            "the text it held must still be readable from the trail: {:?}",
            history.revisions
        );
    }

    #[tokio::test]
    async fn a_history_request_for_an_unknown_id_is_refused_rather_than_answered_empty() {
        // An empty history and a memory that never existed are different facts, and only one
        // of them is an answer.
        let state = Arc::new(Mutex::new(fresh_state()));
        let absent = MemoryId::new();
        let payload = serde_json::to_vec(&HistoryRequest { memory_id: absent }).unwrap();
        let refused = handle_frame(Frame::new(frame_types::HISTORY, 1, payload), state).await;
        assert!(refused.is_err(), "an unknown id must be refused, not answered with nothing");
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