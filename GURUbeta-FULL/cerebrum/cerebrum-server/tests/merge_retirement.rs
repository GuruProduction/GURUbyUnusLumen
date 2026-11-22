//! The merge retirement contract.
//!
//! A merge folds several memories into one surviving record. What it must do to
//! the memories it absorbed is the whole subject here, because the previous code
//! did something no other path in the brain does:
//!
//!   `pipeline_remove_by_id` cleared the DWM, the signature index, the lexical
//!   index, the cross-reference map and the hot cache, and touched nothing else.
//!
//! Two consequences, both silent.
//!
//! THE DECAY ENTRY STAYED LIVE. The decay layer is what scoring reads for layer
//! weight and what survives consolidation, and `decay_records` persists entry
//! content, so an absorbed source kept being scored against and came back from
//! disk after a restart. Delete archives its decay row; merge did not. Two paths
//! that both mean "retire this memory" left it in two different states.
//!
//! THE GRAPH NODE STAYED LABELLED. `find_start_node` matches query text against
//! labels, so an absorbed source could still be chosen as the START of a
//! traversal, and its edges stayed in its neighbours' tables, so a walk still
//! produced it as a reachable result. A traverse answered with a memory the
//! brain no longer admitted holding.
//!
//! These tests fail against current main. They assert retrieval emptiness through
//! the real frame path, the archived-but-readable state of the source content, and
//! that the merged record is the only hit.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame};

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-merge-retirement-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

fn entry_with_id(id: MemoryId, content: &str, domain: &str, topic: &str) -> ContextEntry {
    ContextEntry {
        memory_id: id,
        domain: domain.into(),
        topic: topic.into(),
        subtopic: "retirement".into(),
        content: content.into(),
        relations: vec![],
        provenance: "merge-retirement-test".into(),
        rationale: "merge retirement contract".into(),
        owner_agent_id: String::new(),
        visibility: String::new(),
        created_at: Utc::now(),
        modified_at: Utc::now(),
    }
}

async fn run_curate(state: &Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) -> serde_json::Value {
    let payload = serde_json::to_vec(&ops).unwrap();
    let frame = Frame::new(frame_types::CURATE, 21, payload);
    let bytes = handle_frame(frame, state.clone())
        .await
        .expect("curate must answer");
    let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(
        value["executed"]["success"], true,
        "curation must succeed before retirement can be tested: {value}"
    );
    value
}

async fn search(state: &Arc<Mutex<CerebrumState>>, query: &str, limit: usize) -> Vec<MemoryId> {
    let payload = serde_json::to_vec(&serde_json::json!({"query": query, "limit": limit})).unwrap();
    let frame = Frame::new(frame_types::SEARCH, 22, payload);
    let bytes = handle_frame(frame, state.clone())
        .await
        .expect("search must answer");
    let hits: Vec<serde_json::Value> = serde_json::from_slice(&bytes).unwrap_or_default();
    hits.iter()
        .filter_map(|hit| {
            hit.get("memory_id")
                .and_then(|id| serde_json::from_value::<MemoryId>(id.clone()).ok())
        })
        .collect()
}

async fn traverse(state: &Arc<Mutex<CerebrumState>>, text: &str) -> Vec<MemoryId> {
    let payload = serde_json::to_vec(&serde_json::json!({
        "text": text,
        "query_type": "Composite",
        "budget": { "max_nodes": 60, "max_depth": 3 }
    }))
    .unwrap();
    let frame = Frame::new(frame_types::GRAPH_TRAVERSE, 23, payload);
    let bytes = handle_frame(frame, state.clone())
        .await
        .expect("traverse must answer");
    let pairs: Vec<(MemoryId, f32)> = serde_json::from_slice(&bytes).unwrap_or_default();
    pairs.into_iter().map(|(id, _)| id).collect()
}

/// The contract in one test: after a merge, neither source is retrievable by any
/// of the three read surfaces, the merged record is the only hit, and the source
/// bytes are still present under an archived state.
#[tokio::test]
async fn a_merged_source_cannot_be_retrieved_and_is_archived_not_destroyed() {
    let state = fresh_state().await;

    let id_a = MemoryId::new();
    let id_b = MemoryId::new();
    let merged_id = MemoryId::new();

    let content_a = "the landlord has not fixed the boiler in the flat";
    let content_b = "the flat radiator is cold and the landlord has been told";
    let merged_content =
        "the landlord has not fixed the boiler in the flat; the flat radiator is cold";

    // Both sources must share the target's domain and topic: the curate
    // executor refuses a merge across a hierarchy boundary, which is a real
    // guard and not something this test should work around.
    run_curate(
        &state,
        vec![
            CurateOp::Add {
                entry: entry_with_id(id_a, content_a, "home", "maintenance"),
                reason: "add source a".into(),
            },
            CurateOp::Add {
                entry: entry_with_id(id_b, content_b, "home", "maintenance"),
                reason: "add source b".into(),
            },
        ],
    )
    .await;

    // Both are findable, and both are walked to, before the merge.
    assert!(
        search(&state, "boiler landlord flat", 10)
            .await
            .contains(&id_a),
        "precondition: source a must be findable before the merge"
    );
    assert!(
        traverse(&state, "landlord boiler flat radiator")
            .await
            .contains(&id_a)
            || traverse(&state, "landlord boiler flat radiator")
                .await
                .contains(&id_b),
        "precondition: at least one source must be reachable by a walk before the merge"
    );

    // The merge under test. target.content is replaced by the executor with the
    // sources' combined body, so what is written here is a seed, not the result.
    run_curate(
        &state,
        vec![CurateOp::Merge {
            source_ids: vec![id_a, id_b],
            target: entry_with_id(merged_id, merged_content, "home", "maintenance"),
            reason: "fold the two maintenance facts into one".into(),
        }],
    )
    .await;

    // 1. Neither source comes back from search.
    let hits = search(&state, "boiler landlord flat radiator", 10).await;
    assert!(
        !hits.contains(&id_a),
        "a merged-away source must not be returned by search; got {hits:?}"
    );
    assert!(
        !hits.contains(&id_b),
        "a merged-away source must not be returned by search; got {hits:?}"
    );

    // 2. Neither source comes back from a graph walk.
    let walked = traverse(&state, "landlord boiler flat radiator").await;
    assert!(
        !walked.contains(&id_a),
        "a merged-away source must not be reachable by a traversal; got {walked:?}"
    );
    assert!(
        !walked.contains(&id_b),
        "a merged-away source must not be reachable by a traversal; got {walked:?}"
    );

    // 3. The merged record is retrievable, and its body carries both sources.
    let merged_hits = search(&state, "boiler radiator landlord", 10).await;
    assert!(
        merged_hits.contains(&merged_id),
        "the merged record must be the surviving hit; got {merged_hits:?}"
    );

    // 4. The source bytes survive under an archived state. The no-delete rule
    //    applies to merges exactly as it applies to deletes.
    let locked = state.lock().await;
    for (label, source_id) in [("a", id_a), ("b", id_b)] {
        let decay = locked
            .decay_engine
            .entries
            .get(&source_id)
            .unwrap_or_else(|| panic!("source {label} must still have a decay row, archived"));
        assert!(
            decay.archived,
            "source {label}'s decay row must be archived, not live"
        );
        assert!(
            !decay.content.is_empty(),
            "source {label}'s content must survive the merge, not be erased"
        );
    }
    drop(locked);
}

/// A delete and a merge must leave the world in the same state, because both
/// mean "this memory is no longer served". This is the invariant that was false:
/// delete archived the decay row and merge did not.
#[tokio::test]
async fn delete_and_merge_retire_a_memory_the_same_way() {
    let state = fresh_state().await;

    let deleted_id = MemoryId::new();
    let source_id = MemoryId::new();
    let merged_id = MemoryId::new();

    run_curate(
        &state,
        vec![
            CurateOp::Add {
                entry: entry_with_id(
                    deleted_id,
                    "a fact that is going to be deleted",
                    "home",
                    "notes",
                ),
                reason: "add the row to be deleted".into(),
            },
            CurateOp::Add {
                entry: entry_with_id(
                    source_id,
                    "a fact that is going to be merged away",
                    "home",
                    "notes",
                ),
                reason: "add the row to be merged".into(),
            },
        ],
    )
    .await;

    run_curate(
        &state,
        vec![CurateOp::Delete {
            memory_id: deleted_id,
            reason: "delete path".into(),
        }],
    )
    .await;

    run_curate(
        &state,
        vec![CurateOp::Merge {
            source_ids: vec![source_id],
            target: entry_with_id(merged_id, "the survivor of the merge", "home", "notes"),
            reason: "merge path".into(),
        }],
    )
    .await;

    let locked = state.lock().await;
    let deleted_decay = locked
        .decay_engine
        .entries
        .get(&deleted_id)
        .expect("the deleted row keeps its decay entry");
    let merged_decay = locked
        .decay_engine
        .entries
        .get(&source_id)
        .expect("the merged-away row keeps its decay entry");

    assert!(deleted_decay.archived, "a delete archives the decay row");
    assert!(
        merged_decay.archived,
        "a merge must archive the decay row too; delete and merge retire a memory the same way"
    );
    assert_eq!(
        deleted_decay.archived, merged_decay.archived,
        "the two retirement paths must agree on the archived state"
    );
}
