//! Cerebrum HTTP full battery:
//! (1) Legacy route/request/response contracts.
//! (2) Real-dispatch: store + search through the shared brain via
//!     handle_frame; the search hits the curated REAL signature shapes.
//! (3) get-memory route reads back a real entry id.

use serde_json::json;
use std::sync::Arc;
use tokio::sync::Mutex;

use cerebrum_core::{CurateOp, MemoryId};
use cerebrum_http::{
    decode_memory_id, dispatch_request, encode_memory_id, ConsolidateRequest, HttpConfig,
    HttpRequest, HttpResponse, Route, SharedHttpState, StoreMemoryRequest,
};
use cerebrum_server::state::CerebrumState;

fn http_state() -> SharedHttpState {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-http-battery-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

// ---------------------------------------------------------------------------
// Legacy contract battery
// ---------------------------------------------------------------------------

#[test]
fn legacy_route_definitions() {
    let route_shapes = [
        ("POST", "/memory/store", Route::StoreMemory),
        ("POST", "/memory/search", Route::SearchMemories),
        ("GET", "/memory/010203", Route::NotFound),
        ("POST", "/memory/curate", Route::Curate),
        ("POST", "/graph/traverse", Route::GraphTraverse),
        ("POST", "/consolidate", Route::Consolidate),
    ];
    for (method, path, expected) in &route_shapes {
        assert_eq!(&Route::parse(method, path), expected, "{} {}", method, path);
    }
}

#[test]
fn legacy_get_memory_route_full_hex() {
    let memory_id = MemoryId([1u8; 32]);
    let hex = encode_memory_id(&memory_id);
    match Route::parse("GET", &format!("/memory/{}", hex)) {
        Route::GetMemory { id: parsed } => assert_eq!(parsed, memory_id),
        _ => panic!("full 32-byte hex always decodes into GetMemory"),
    }
    assert!(decode_memory_id("0101").is_none());
    assert!(decode_memory_id(&"aa".repeat(32)).is_some());
}

#[test]
fn legacy_request_and_op_shapes() {
    let store = serde_json::from_str::<StoreMemoryRequest>(r#"{"content":"hello"}"#).unwrap();
    assert_eq!(store.content, "hello");

    let search: cerebrum_http::SearchMemoriesRequest = serde_json::from_str(
        r#"{"query":"rust","radius":10,"limit":5}"#,
    )
    .unwrap();
    assert_eq!(search.query, "rust");
    assert_eq!(search.radius, Some(10));

    let consolidate: ConsolidateRequest =
        serde_json::from_str(r#"{"domain":"code"}"#).unwrap();
    assert_eq!(consolidate.domain, Some("code".to_string()));

    // CurateOp round-trips its real enum shape via serde on bytes.
    let delete_op = CurateOp::Delete {
        memory_id: MemoryId([9u8; 32]),
        reason: "battery".to_string(),
    };
    let parsed_op: CurateOp = serde_json::to_value(&delete_op)
        .and_then(|value| serde_json::from_value(value))
        .unwrap();
    match parsed_op {
        CurateOp::Delete { memory_id, .. } => assert_eq!(memory_id, MemoryId([9u8; 32])),
        _ => panic!("delete op round-trip failed"),
    }
}

#[test]
fn legacy_response_and_config_defaults() {
    let response = HttpResponse::ok(json!({ "status": "ok" }));
    assert_eq!(response.status, 200);

    let config = HttpConfig::default();
    assert_eq!(config.bind_address, "127.0.0.1:4223");
    assert!(config.cors_enabled);
}

// ---------------------------------------------------------------------------
// Real-dispatch batteries
// ---------------------------------------------------------------------------

#[tokio::test]
async fn dispatch_store_then_search_and_get() {
    let state = http_state();

    let stored = dispatch_request(
        HttpRequest {
            method: "POST".to_string(),
            path: "/memory/store".to_string(),
            body: Some(json!({
                "content": "rust is memorable here for battery tests only",
                "domain": "http-battery",
                "topic": "real",
                "subtopic": "flow",
            })),
        },
        state.clone(),
    )
    .await;
    assert_eq!(stored.status, 200, "{:?}", stored.body);

    {
        let locked = state.lock().await;
        assert_eq!(locked.context_tree.len(), 1);
        assert_eq!(locked.dwm.count(), 1);
        assert_eq!(locked.event_store.count(), 1);
        assert_eq!(locked.query_engine.hot_cache.len(), 1);
        assert_eq!(locked.experience_store.count(), 1);
        assert!(locked.decay_engine.entries.len() >= 1);
    }

    let tree_entries = { state.lock().await.context_tree.clone_entries() };
    let memory_id = tree_entries
        .first()
        .map(|entry| entry.id)
        .expect("one curated memory");

    let found = dispatch_request(
        HttpRequest {
            method: "POST".to_string(),
            path: "/memory/search".to_string(),
            body: Some(json!({ "query": "rust memorable battery", "limit": 5 })),
        },
        state.clone(),
    )
    .await;
    assert_eq!(found.status, 200);
    let contents: Vec<String> = found
        .body
        .as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .filter_map(|hit| hit.get("content").and_then(|c| c.as_str()).map(|c| c.to_string()))
        .collect();
    assert!(
        contents.iter().any(|text_value| text_value.contains("rust is memorable")),
        "search must return real curated content; got {:?}",
        contents
    );

    let got = dispatch_request(
        HttpRequest { method: "GET".to_string(), path: format!("/memory/{}", encode_memory_id(&memory_id)), body: None },
        state.clone(),
    )
    .await;
    assert_eq!(got.status, 200, "{:?}", got.body);
}

#[tokio::test]
async fn dispatch_unknown_route_carries_404() {
    let state = http_state();
    let response = dispatch_request(
        HttpRequest { method: "POST".to_string(), path: "/no/such/route".to_string(), body: None },
        state,
    )
    .await;
    assert_eq!(response.status, 404);
}