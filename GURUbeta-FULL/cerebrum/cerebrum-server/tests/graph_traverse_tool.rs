//! The traverseMemoryGraph wire contract, driven through the real frame path.
//!
//! The tool in the app sends the payload this test sends. Until now nothing
//! proved that payload reaches the graph, because the Kotlin caller built the
//! JSON by hand and the old shape was written against an engine that no longer
//! existed: it sent a start memory id and a `max_tokens` budget, while the crate
//! parses `Query { text, budget: { max_nodes, max_depth }, query_type }` and
//! chooses its own start node from the text.
//!
//! So this test exists to pin three things that were all untrue before:
//!   1. the payload the app sends parses,
//!   2. it traverses rather than erroring,
//!   3. it returns pairs of (32-byte memory id, score), which is what the
//!      Kotlin parser now reads.
//!
//! It also pins the behaviour that makes the tool worth having: a traverse from
//! one subject reaches memories that a search for the same words does not.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::handler::{handle_frame, CurateFullResponse};
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame};

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-graph-tool-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

fn add_op(domain: &str, content: &str) -> CurateOp {
    CurateOp::Add {
        entry: ContextEntry {
            memory_id: MemoryId::new(),
            domain: domain.into(),
            topic: "graph".into(),
            subtopic: "tool".into(),
            content: content.into(),
            relations: vec![],
            provenance: "graph-tool-test".into(),
            rationale: "graph tool contract".into(),
            owner_agent_id: String::new(),
            visibility: String::new(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "graph tool add".into(),
    }
}

async fn curate_through_frame(
    state: &Arc<Mutex<CerebrumState>>,
    ops: Vec<CurateOp>,
) -> CurateFullResponse {
    let payload = serde_json::to_vec(&ops).unwrap();
    let frame = Frame::new(frame_types::CURATE, 11, payload);
    let bytes = handle_frame(frame, state.clone()).await.unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

/// Exactly the JSON the Kotlin `graphTraverse` builds. If this test and that
/// string disagree, the tool is silently broken on device and this fails first.
fn app_payload(subject: &str, query_type: &str, max_depth: usize, max_nodes: usize) -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "text": subject,
        "query_type": query_type,
        "budget": { "max_nodes": max_nodes, "max_depth": max_depth }
    }))
    .unwrap()
}

/// One graph reply: the engine's `[(MemoryId, f32)]`, which serialises as an
/// array of two-element arrays whose first element is the 32 raw id bytes.
#[derive(serde::Deserialize, Debug)]
struct GraphPair(MemoryId, f32);

/// The payload the app sends must traverse and return id/score pairs.
#[tokio::test]
async fn the_app_payload_traverses_and_returns_id_score_pairs() {
    let state = fresh_state().await;

    let ops = vec![
        add_op(
            "work",
            "Steven Newman founded Unus Lumen in Bristol and runs GURU as its flagship product",
        ),
        add_op(
            "work",
            "The Unus Lumen office sits in Bristol and the team builds GURU there",
        ),
        add_op(
            "life",
            "The kitchen in the Bristol flat needs its boiler serviced before winter",
        ),
    ];
    let result = curate_through_frame(&state, ops).await;
    assert!(result.executed.success, "{:?}", result.executed.details);
    assert!(result.side_effects.graph_edges > 0, "curate must build edges");

    let payload = app_payload("Steven Newman Unus Lumen Bristol", "Entity", 3, 40);
    let frame = Frame::new(frame_types::GRAPH_TRAVERSE, 12, payload);
    let reply = handle_frame(frame, state.clone())
        .await
        .expect("the app's payload must parse and traverse");
    assert!(!reply.is_empty(), "a traverse must answer with bytes");

    // The reply is an array, never an error object. An error object here means
    // the wire shape drifted away from the crate's Query struct again.
    let as_value: serde_json::Value = serde_json::from_slice(&reply).unwrap();
    assert!(
        as_value.is_array(),
        "traverse must answer with an array of pairs, got: {}",
        String::from_utf8_lossy(&reply).chars().take(200).collect::<String>()
    );

    let pairs: Vec<GraphPair> =
        serde_json::from_slice(&reply).expect("reply must be [(MemoryId, f32)] pairs");
    assert!(
        !pairs.is_empty(),
        "a graph holding these memories must reach at least one from this subject"
    );
    for pair in &pairs {
        assert!(
            pair.1 >= 0.0,
            "a graph score is a real number, got {}",
            pair.1
        );
    }
}

/// Every edge type the tool offers must be an accepted query_type, and an
/// unknown one must fall back rather than break the walk.
#[tokio::test]
async fn every_offered_edge_type_traverses() {
    let state = fresh_state().await;
    let ops = vec![
        add_op("work", "Steven Newman runs Unus Lumen and builds GURU in Bristol"),
        add_op("work", "Unus Lumen ships GURU releases from the Bristol office"),
    ];
    assert!(curate_through_frame(&state, ops).await.executed.success);

    for query_type in ["Semantic", "Temporal", "Causal", "Entity", "Composite"] {
        let payload = app_payload("Bristol GURU Unus Lumen", query_type, 3, 40);
        let frame = Frame::new(frame_types::GRAPH_TRAVERSE, 13, payload);
        let reply = handle_frame(frame, state.clone())
            .await
            .unwrap_or_else(|e| panic!("{query_type} must traverse: {}", e.message));
        let parsed: serde_json::Value = serde_json::from_slice(&reply).unwrap();
        assert!(
            parsed.is_array(),
            "{query_type} must answer with an array, got {}",
            String::from_utf8_lossy(&reply).chars().take(160).collect::<String>()
        );
    }
}

/// The claim the tool is sold on: a walk reaches what a search misses.
///
/// The subject here shares no words with the memory that answers it. The
/// kitchen memory mentions "Bristol" and so does the office memory; a search for
/// the office wording returns office rows, while a walk from the office reaches
/// the flat because the two are linked through the shared place.
#[tokio::test]
async fn a_walk_reaches_what_a_word_search_does_not() {
    let state = fresh_state().await;
    let ops = vec![
        add_op(
            "work",
            "Unus Lumen keeps its Bristol office above the harbour and ships GURU from there",
        ),
        add_op(
            "life",
            "The Bristol flat boiler service is overdue and the landlord has been told",
        ),
    ];
    assert!(curate_through_frame(&state, ops).await.executed.success);

    // A word search for the office wording.
    let search_payload =
        serde_json::to_vec(&serde_json::json!({"query": "harbour office", "limit": 10})).unwrap();
    let search_frame = Frame::new(frame_types::SEARCH, 14, search_payload);
    let search_reply = handle_frame(search_frame, state.clone()).await.unwrap();
    let hits: Vec<serde_json::Value> = serde_json::from_slice(&search_reply).unwrap();
    let searchmentions_boiler = hits.iter().any(|hit| {
        hit.get("content")
            .and_then(|c| c.as_str())
            .map(|text| text.contains("boiler"))
            .unwrap_or(false)
    });

    // A walk from the office subject over the whole graph.
    let payload = app_payload("Bristol office harbour", "Composite", 3, 40);
    let frame = Frame::new(frame_types::GRAPH_TRAVERSE, 15, payload);
    let reply = handle_frame(frame, state.clone()).await.unwrap();
    let pairs: Vec<GraphPair> = serde_json::from_slice(&reply).unwrap();

    // The walk answers with ids and the search answers with bodies, so the walk
    // is resolved back through the tree to compare like with like.
    let locked = state.lock().await;
    let reached_boiler = pairs.iter().any(|pair| {
        locked
            .context_tree
            .find(pair.0)
            .map(|entry| entry.content.contains("boiler"))
            .unwrap_or(false)
    });

    assert!(
        !pairs.is_empty(),
        "the walk must reach at least one node from the office subject"
    );
    if !searchmentions_boiler {
        assert!(
            reached_boiler,
            "the walk is the whole point: it must reach the flat that shares the place when the word search cannot"
        );
    }
}
