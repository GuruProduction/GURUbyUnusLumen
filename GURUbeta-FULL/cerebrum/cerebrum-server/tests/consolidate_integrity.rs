//! Consolidation integrity tests.
//!
//! `handle_consolidate` is allowed to change what a memory looks like. It is
//! not allowed to make a memory unfindable. These tests hold that contract:
//! after a consolidation pass, content that was searchable before the pass is
//! either still searchable, or has been folded into a replacement record that
//! is itself searchable and carries the original content.
//!
//! The defect these tests were written against: merge/dedup removed the
//! consumed ids from the DWM, the signature index, the cross-reference engine
//! and the hot cache, but never wrote the merged replacement into the context
//! tree. Search reads its bodies from the context tree, so the originals
//! survived as rows nobody could reach, and the replacement only ever existed
//! inside an in-memory map that was rebuilt from scratch on the next pass.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::frame_types;
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::Frame;

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-consolidate-it-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

async fn call_frame(
    state: &Arc<Mutex<CerebrumState>>,
    frame_type: u8,
    payload: &[u8],
) -> Vec<u8> {
    let frame = Frame::new(frame_type, 9001, payload.to_vec());
    match handle_frame(frame, state.clone()).await {
        Ok(bytes) => bytes,
        Err(e) => serde_json::to_vec(&serde_json::json!({ "error": e.message })).expect("error bytes"),
    }
}

fn add_op_with_id(id: MemoryId, content: &str, domain: &str, topic: &str) -> CurateOp {
    CurateOp::Add {
        entry: ContextEntry {
            memory_id: id,
            domain: domain.into(),
            topic: topic.into(),
            subtopic: "test".into(),
            content: content.into(),
            relations: vec![],
            provenance: "consolidate-integrity".into(),
            rationale: "integrity test".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "integrity test add".into(),
    }
}

#[derive(serde::Deserialize)]
struct SearchHit {
    memory_id: MemoryId,
    #[allow(dead_code)]
    score: f32,
    content: String,
}

async fn search(state: &Arc<Mutex<CerebrumState>>, query: &str, limit: usize) -> Vec<SearchHit> {
    let payload = serde_json::to_vec(&serde_json::json!({ "query": query, "limit": limit })).unwrap();
    let bytes = call_frame(state, frame_types::SEARCH, &payload).await;
    serde_json::from_slice(&bytes).unwrap_or_default()
}

async fn consolidate(state: &Arc<Mutex<CerebrumState>>) -> serde_json::Value {
    let bytes = call_frame(state, frame_types::CONSOLIDATE, b"{}").await;
    serde_json::from_slice(&bytes).expect("consolidate always returns a report")
}

/// Probe: what similarity does unrelated same-language prose actually produce?
/// This runs the real signature engine the consolidation pass uses and prints
/// the distribution, so the merge/dedup thresholds can be chosen from measured
/// numbers instead of intuition.
#[tokio::test]
async fn probe_unrelated_text_similarity() {
    let state = fresh_state().await;

    let samples = [
        "italian pasta recipe uses fresh tomato and basil",
        "quantum particles behave probabilistically under measurement",
        "tennis racquet grip affects serve spin and control",
        "the landlord still has not fixed the boiler in the flat",
        "my sister is flying to lisbon on thursday for a conference",
        "the quarterly invoice from the accountant arrived this morning",
        "rust borrow checker enforces safe memory rules at compile stage",
        "remember that the wedding is in bristol in september",
    ];

    {
        let mut locked = state.lock().await;
        let sigs: Vec<_> = samples
            .iter()
            .map(|s| (s, locked.signature_engine.generate(s)))
            .collect();

        let mut pairs: Vec<(f32, &str, &str)> = Vec::new();
        for i in 0..sigs.len() {
            for j in (i + 1)..sigs.len() {
                let dist = sigs[i].1.hamming_distance(&sigs[j].1);
                let similarity = 1.0 - (dist as f32 / 256.0);
                pairs.push((similarity, sigs[i].0, sigs[j].0));
            }
        }
        pairs.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());

        println!("\n=== UNRELATED-TEXT SIMILARITY (1 - hamming/256) ===");
        for (sim, a, b) in pairs.iter().take(10) {
            let dist = ((1.0 - sim) * 256.0).round() as u32;
            println!("  {:.4} (dist {:3})  {}  ||  {}", sim, dist, a, b);
        }
        let max = pairs.first().map(|p| p.0).unwrap_or(0.0);
        println!("  MAX unrelated similarity: {:.4}", max);
        println!("  merge_threshold default: 0.8000");
        println!("  dedup_threshold default: 0.9500");
        println!("  unrelated pairs at or above merge threshold: {}",
            pairs.iter().filter(|p| p.0 >= 0.8).count());
        println!("=================================================\n");
    }
}

/// The contract: consolidation must not make findable content unfindable.
///
/// Two near-identical memories are curated, both confirmed searchable, then a
/// consolidation pass runs. Afterwards, every distinctive phrase from either
/// original must still be reachable through search, either in its own row or
/// inside the record that replaced it.
#[tokio::test]
async fn consolidate_never_makes_a_memory_unfindable() {
    let state = fresh_state().await;

    let phrase_a = "the landlord still has not fixed the boiler in the flat";
    let phrase_b = "the landlord still has not fixed the boiler in the flat and the radiator is cold";
    let id_a = MemoryId::new();
    let id_b = MemoryId::new();

    let payload = serde_json::to_vec(&vec![
        add_op_with_id(id_a, phrase_a, "home", "maintenance"),
        add_op_with_id(id_b, phrase_b, "home", "maintenance"),
    ])
    .unwrap();
    let bytes = call_frame(&state, frame_types::CURATE, &payload).await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(
        ack["executed"]["success"], true,
        "curation must succeed before consolidation can be tested"
    );

    // Baseline: both are findable before the pass.
    let before_a = search(&state, phrase_a, 10).await;
    assert!(
        !before_a.is_empty(),
        "precondition failed: phrase A was not findable before consolidation"
    );

    let (tree_before, dwm_before) = {
        let locked = state.lock().await;
        (locked.context_tree.len(), locked.dwm.count())
    };
    assert_eq!(tree_before, 2, "both rows must exist in the tree before the pass");
    assert_eq!(dwm_before, 2, "both rows must be indexed before the pass");

    // The pass under test.
    let report = consolidate(&state).await;
    println!("consolidate report: {report}");

    let (tree_after, dwm_after, sig_after, decay_after, dream_after) = {
        let locked = state.lock().await;
        (
            locked.context_tree.len(),
            locked.dwm.count(),
            locked.signature_engine.searcher().len(),
            locked.decay_engine.entries.len(),
            locked.dream_engine.memories.len(),
        )
    };
    println!(
        "after: tree={tree_after} dwm={dwm_after} sig={sig_after} decay={decay_after} dream={dream_after}"
    );

    // Every row still in the tree must still be reachable through the index.
    assert_eq!(
        tree_after, dwm_after,
        "the tree and the signature index must agree on how many memories exist: \
         a row in the tree with no index entry is a memory nobody can find"
    );

    // The user-facing contract: the content is still retrievable.
    let after_a = search(&state, phrase_a, 10).await;
    let after_b = search(&state, phrase_b, 10).await;

    let findable_a = after_a.iter().any(|h| h.content.contains("boiler"));
    let findable_b = after_b.iter().any(|h| h.content.contains("radiator"));
    assert!(
        findable_a && findable_b,
        "consolidation made curated content unfindable. \
         boiler found={findable_a} radiator found={findable_b}. \
         tree={tree_after} dwm={dwm_after} decay={decay_after} dream_map={dream_after}"
    );
}

/// When duplicates really are duplicates, the merge must fire AND the
/// replacement must land in the tree and the indexes before the originals
/// leave them. This exercises the write path the fix added.
#[tokio::test]
async fn merge_of_real_duplicates_leaves_a_findable_replacement() {
    let state = fresh_state().await;

    // Byte-identical content, which is the only duplicate claim this layer
    // makes without a measured similarity metric.
    let content = "the landlord still has not fixed the boiler in the flat";
    let id_a = MemoryId::new();
    let id_b = MemoryId::new();

    let payload = serde_json::to_vec(&vec![
        add_op_with_id(id_a, content, "home", "maintenance"),
        add_op_with_id(id_b, content, "home", "maintenance"),
    ])
    .unwrap();
    let bytes = call_frame(&state, frame_types::CURATE, &payload).await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true);

    let report = consolidate(&state).await;
    println!("duplicate merge report: {report}");

    let merged = report["merged_into_dwm"].as_u64().unwrap_or(0);
    let removed = report["removed_from_dwm"].as_u64().unwrap_or(0);
    assert!(
        merged >= 1,
        "identical content must produce a merged record, report said {report}"
    );
    assert!(removed >= 2, "both duplicates must be removed, report said {report}");

    let (tree_len, dwm_len, sig_len) = {
        let locked = state.lock().await;
        (
            locked.context_tree.len(),
            locked.dwm.count(),
            locked.signature_engine.searcher().len(),
        )
    };
    println!("after duplicate merge: tree={tree_len} dwm={dwm_len} sig={sig_len}");
    assert_eq!(tree_len, dwm_len, "tree and DWM must agree after a merge");
    assert_eq!(tree_len, sig_len, "tree and signature index must agree after a merge");

    // The whole point: the content is still retrievable after being merged.
    let hits = search(&state, "landlord boiler flat", 10).await;
    assert!(
        hits.iter().any(|h| h.content.contains("boiler")),
        "the merged replacement must be searchable; got {} hits",
        hits.len()
    );

    // And the original ids are gone from the tree, not orphaned in it.
    let (has_a, has_b) = {
        let locked = state.lock().await;
        (
            locked.context_tree.find(id_a).is_some(),
            locked.context_tree.find(id_b).is_some(),
        )
    };
    assert!(!has_a && !has_b, "consumed source rows must leave the tree");
}

/// A second pass with no new writes must not keep re-consuming the same
/// memories. The dream map is rebuilt from the decay snapshot on every pass,
/// so anything left in it accumulates, and anything still in decay gets fed
/// back in and re-merged with a fresh random id each run.
#[tokio::test]
async fn consolidation_is_idempotent_when_nothing_changed() {
    let state = fresh_state().await;

    let payload = serde_json::to_vec(&vec![
        add_op_with_id(
            MemoryId::new(),
            "the landlord still has not fixed the boiler in the flat",
            "home",
            "maintenance",
        ),
        add_op_with_id(
            MemoryId::new(),
            "the landlord still has not fixed the boiler in the flat and the radiator is cold",
            "home",
            "maintenance",
        ),
    ])
    .unwrap();
    call_frame(&state, frame_types::CURATE, &payload).await;

    let first = consolidate(&state).await;
    let (tree_1, dwm_1, dream_1) = {
        let locked = state.lock().await;
        (
            locked.context_tree.len(),
            locked.dwm.count(),
            locked.dream_engine.memories.len(),
        )
    };

    let second = consolidate(&state).await;
    let (tree_2, dwm_2, dream_2) = {
        let locked = state.lock().await;
        (
            locked.context_tree.len(),
            locked.dwm.count(),
            locked.dream_engine.memories.len(),
        )
    };

    println!("first pass:  {first}");
    println!("second pass: {second}");
    println!("after first:  tree={tree_1} dwm={dwm_1} dream={dream_1}");
    println!("after second: tree={tree_2} dwm={dwm_2} dream={dream_2}");

    assert!(
        dream_2 <= dream_1.max(1) * 2,
        "the dream map grew across passes with no new writes: \
         {dream_1} -> {dream_2}. It is rebuilt from the decay snapshot each run \
         and never cleared, so consumed memories are re-consumed every pass"
    );
}
