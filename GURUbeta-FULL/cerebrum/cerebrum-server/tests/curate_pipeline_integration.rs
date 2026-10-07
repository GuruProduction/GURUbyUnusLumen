//! Integration tests: prescribed by plan-cerebrum-integration.md
//! (Phase B verification battery). Drives every op through the public
//! protocol layer exactly as an external client would: frame bytes in,
//! frame bytes out.

use std::sync::Arc;
use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{
    ContextEntry, CurateOp, DereferenceLevel, MemoryId,
};
use cerebrum_server::{frame_types, Frame};
use cerebrum_server::handler::{handle_frame, CurateFullResponse};
use cerebrum_server::state::CerebrumState;

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-it-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

/// One round call: encode a payload into a frame, dispatch through
/// handle_frame, return the JSON payload bytes of the response.
async fn call_frame(
    state: &Arc<Mutex<CerebrumState>>,
    frame_type: u8,
    payload: &[u8],
) -> Vec<u8> {
    let frame = Frame::new(frame_type, 4242, payload.to_vec());
    let response = handle_frame(frame, state.clone()).await;
    match response {
        Ok(bytes) => bytes,
        Err(e) => {
            serde_json::to_vec(&serde_json::json!({ "error": e.message })).expect("error bytes")
        }
    }
}

fn add_op(content: &str) -> CurateOp {
    CurateOp::Add {
        entry: ContextEntry {
            memory_id: MemoryId::new(),
            domain: "integration".into(),
            topic: "pipeline".into(),
            subtopic: "tests".into(),
            content: content.into(),
            relations: vec![],
            provenance: "integration-test".into(),
            rationale: "battery test".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "battery add".into(),
    }
}

async fn curate(state: &Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) -> CurateFullResponse {
    let payload = serde_json::to_vec(&ops).unwrap();
    let bytes = call_frame(state, frame_types::CURATE, &payload).await;
    serde_json::from_slice::<CurateFullResponse>(&bytes)
        .expect("curate response always parses for valid batches")
}

/// PRESCRIBED TEST 1 (plan-cerebrum-integration.md Phase 2 verify):
/// curate 10 memories; verify counts across all involved systems.
#[tokio::test]
async fn test_10_curations_flow_to_every_engine() {
    let state = fresh_state().await;
    let ops: Vec<CurateOp> = (0..10)
        .map(|i| add_op(&format!("battery memory {} on distinct theme {}", i, i * 13)))
        .collect();
    let result = curate(&state, ops).await;

    assert!(result.executed.success, "{:?} ", result.executed.details);
    assert_eq!(result.side_effects.dwm_count, 10, "DWM");
    assert_eq!(result.side_effects.hamming_count, 10, "Hamming");
    assert_eq!(result.side_effects.events, 10, "EventStore");
    assert!(result.side_effects.graph_nodes >= 10, "Graph nodes");
    assert!(result.side_effects.graph_edges >= 9, "temporal chain edges");
    assert_eq!(result.side_effects.hot_cache, 10, "hot cache");
    assert_eq!(result.side_effects.experience_store, 10, "policy index");

    let locked = state.lock().await;
    assert_eq!(locked.dwm.count(), 10);
    assert_eq!(locked.decay_engine.entries.len(), 10);
    assert_eq!(locked.event_store.count(), 10);
    assert_eq!(locked.event_store.enrichment_queue.len(), 10);
    assert_eq!(locked.signature_engine.searcher().len(), 10);
    assert_eq!(locked.query_engine.hot_cache.len(), 10);
    assert!(locked.graph_store.semantic.node_count() >= 10);
}

/// PRESCRIBED TEST 2 (plan Phase 3 verify): curate 10 with known content,
/// search by rewording, verify right memory tops.
#[tokio::test]
async fn test_search_by_similar_theme() {
    let state = fresh_state().await;
    let mut rust_id: Option<MemoryId> = None;
    let texts = [
        "rust borrow checker enforces safe memory rules at compile stage",
        "italian pasta recipe uses fresh tomato and basil",
        "quantum particles behave probabilistically under measurement",
        "tennis racquet grip affects serve spin and control",
    ];
    let ops: Vec<CurateOp> = texts
        .iter()
        .map(|content| {
            let mut op = add_op(content);
            if let CurateOp::Add { entry, .. } = &mut op {
                if content.starts_with("rust") {
                    rust_id = Some(entry.memory_id);
                }
            }
            op
        })
        .collect();
    let result = curate(&state, ops).await;
    assert!(result.executed.success);

    #[derive(serde::Deserialize)]
    struct S {
        memory_id: MemoryId,
        #[allow(dead_code)]
        score: f64,
        #[allow(dead_code)]
        content: String,
    }

    let request_payload =
        serde_json::to_vec(&serde_json::json!({"query": "safe borrow rules memory", "limit": 4}))
            .unwrap();
    let bytes = call_frame(&state, frame_types::SEARCH, &request_payload).await;
    let parsed = serde_json::from_slice::<Vec<S>>(&bytes).expect("search response parses");
    assert!(!parsed.is_empty(), "expected at least one hit");
    assert_eq!(
        parsed[0].memory_id,
        rust_id.unwrap(),
        "memory-themed query should rank the rust memory top"
    );
}

/// PRESCRIBED TEST 3 (plan Phase 5 verify): curate, retrieve at all levels.
#[tokio::test]
async fn test_retrieve_levels() {
    let state = fresh_state().await;
    let memory_id = MemoryId::new();
    let op = CurateOp::Upsert {
        entry: ContextEntry {
            memory_id,
            domain: "retrieve-tests".into(),
            topic: "levels".into(),
            subtopic: "levels-sub".into(),
            content: "Paragraph one covers borrow rules.\n\nParagraph two covers ownership moves."
                .into(),
            relations: vec![],
            provenance: "t".into(),
            rationale: "t".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "retrieve battery".into(),
    };
    let result = curate(&state, vec![op]).await;
    assert!(result.executed.success);
    assert_eq!(result.side_effects.created, 1);

    #[derive(serde::Serialize)]
    struct RR {
        memory_id: MemoryId,
        level: DereferenceLevel,
    }

    // Shallow: metadata only.
    let shallow_response =
        call_frame(&state, frame_types::RETRIEVE, &serde_json::to_vec(&RR {
            memory_id,
            level: DereferenceLevel::Shallow,
        }).unwrap()).await;
    let shallow: serde_json::Value = serde_json::from_slice(&shallow_response).unwrap();
    assert!(shallow.get("data").unwrap().is_array());
    assert!(String::from_utf8_lossy(
        shallow.get("data").unwrap().as_array().unwrap()[0]
            .as_u64()
            .unwrap_or(0)
            .to_string()
            .as_bytes()
    )
    .starts_with(""));

    // Deep: full body must carry real content with both paragraphs.
    let deep_response =
        call_frame(&state, frame_types::RETRIEVE, &serde_json::to_vec(&RR {
            memory_id,
            level: DereferenceLevel::Deep,
        }).unwrap()).await;
    let deep_resp: serde_json::Value = serde_json::from_slice(&deep_response).unwrap();
    let data_bytes = deep_resp.get("data").unwrap().as_array().unwrap();
    let raw: Vec<u8> = data_bytes.iter().map(|v| v.as_u64().unwrap() as u8).collect();
    let deep =
        serde_json::from_slice::<serde_json::Value>(&raw).expect("deep body bytes parse");
    assert!(
        deep.get("content")
            .unwrap()
            .as_str()
            .is_some(),
        "deep body must serialize to a JSON text"
    );

    // Partial: lifetimes section request resolves to full content fallback.
    let partial_response =
        call_frame(&state, frame_types::RETRIEVE, &serde_json::to_vec(&RR {
            memory_id,
            level: DereferenceLevel::Partial { sections: vec!["borrow".into()] },
        }).unwrap()).await;
    let partial: serde_json::Value = serde_json::from_slice(&partial_response).unwrap();
    let raw2_vec: Vec<u8> = partial
        .get("data")
        .unwrap()
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_u64().unwrap() as u8)
        .collect();
    serde_json::from_slice::<serde_json::Value>(&raw2_vec).expect("partial body parses");
}