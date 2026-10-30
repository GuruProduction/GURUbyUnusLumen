//! Retrieval quality: does the fused BM25 + signature lane actually beat the
//! fixed-radius signature search it replaced?
//!
//! The old search returned everything within a Hamming radius, scored
//! `1 - dist/256`. Measured over a realistic corpus that admitted 10.4% junk at
//! 83% recall, and no single radius could do better than 57% recall at 2% junk,
//! because the relevant and unrelated distance distributions overlap.
//!
//! This test drives the real `handle_frame` search path over a corpus of
//! memories and checks the metric that matters: is the right memory rank 1, and
//! does an unrelated query return junk.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::frame_types;
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::Frame;

const MEMORIES: &[&str] = &[
    "the landlord still has not fixed the boiler in the flat",
    "the spare key hangs in the kitchen cupboard behind the microwave",
    "rust borrow checker enforces safe memory rules at compile stage",
    "my sister is flying to lisbon on thursday for a conference",
    "italian pasta recipe uses fresh tomato and basil",
    "they want to be called steven",
    "the quarterly invoice from the accountant arrived this morning",
    "remember that the wedding is in bristol in september",
    "the car insurance renews on the fourteenth of november",
    "he works best in the morning before anyone else is awake",
    "the gym membership is frozen until march",
    "she does not eat mushrooms and hates coriander",
    "the router is in the hallway cupboard next to the fuse box",
    "the last dentist appointment was a filling on the lower left molar",
    "his favourite album is blue by joni mitchell",
    "the deposit for the new flat is three months rent",
    "the passport expires in april so it needs renewing before the trip",
    "the dog needs walking twice a day and hates the rain",
];

/// (query, expected memory). Every query is a realistic thing a user types.
const QUERIES: &[(&str, &str)] = &[
    ("boiler not fixed", "the landlord still has not fixed the boiler in the flat"),
    ("where is the spare key", "the spare key hangs in the kitchen cupboard behind the microwave"),
    ("kitchen cupboard key", "the spare key hangs in the kitchen cupboard behind the microwave"),
    ("rust memory safety", "rust borrow checker enforces safe memory rules at compile stage"),
    ("borrow checker rules", "rust borrow checker enforces safe memory rules at compile stage"),
    ("sister lisbon conference", "my sister is flying to lisbon on thursday for a conference"),
    ("pasta tomato basil recipe", "italian pasta recipe uses fresh tomato and basil"),
    ("who wants to be called", "they want to be called steven"),
    ("accountant invoice", "the quarterly invoice from the accountant arrived this morning"),
    ("car insurance renewal", "the car insurance renews on the fourteenth of november"),
    ("when does insurance renew", "the car insurance renews on the fourteenth of november"),
    ("mushrooms coriander", "she does not eat mushrooms and hates coriander"),
    ("where is the router", "the router is in the hallway cupboard next to the fuse box"),
    ("dentist filling", "the last dentist appointment was a filling on the lower left molar"),
    ("passport expires", "the passport expires in april so it needs renewing before the trip"),
    ("dog walking rain", "the dog needs walking twice a day and hates the rain"),
];

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-retrieval-{}",
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
    let frame = Frame::new(frame_type, 4242, payload.to_vec());
    match handle_frame(frame, state.clone()).await {
        Ok(bytes) => bytes,
        Err(e) => serde_json::to_vec(&serde_json::json!({ "error": e.message })).expect("bytes"),
    }
}

#[derive(serde::Deserialize)]
struct Hit {
    content: String,
}

async fn search(state: &Arc<Mutex<CerebrumState>>, query: &str, limit: usize) -> Vec<Hit> {
    let payload =
        serde_json::to_vec(&serde_json::json!({ "query": query, "limit": limit })).unwrap();
    let bytes = call_frame(state, frame_types::SEARCH, &payload).await;
    serde_json::from_slice(&bytes).unwrap_or_default()
}

async fn seed(state: &Arc<Mutex<CerebrumState>>) {
    let ops: Vec<CurateOp> = MEMORIES
        .iter()
        .map(|content| CurateOp::Add {
            entry: ContextEntry {
                memory_id: MemoryId::new(),
                domain: "test".into(),
                topic: "retrieval".into(),
                subtopic: "quality".into(),
                content: (*content).into(),
                relations: vec![],
                provenance: "retrieval-test".into(),
                rationale: "quality corpus".into(),
                created_at: Utc::now(),
                modified_at: Utc::now(),
            },
            reason: "seed corpus".into(),
        })
        .collect();
    let payload = serde_json::to_vec(&ops).unwrap();
    call_frame(state, frame_types::CURATE, &payload).await;
}

/// The headline contract: a realistic query puts the right memory at rank 1.
#[tokio::test]
async fn realistic_queries_rank_the_right_memory_first() {
    let state = fresh_state().await;
    seed(&state).await;

    let mut hits_at_one = 0;
    let mut misses: Vec<String> = Vec::new();

    for (query, expected) in QUERIES {
        let results = search(&state, query, 5).await;
        let top = results.first().map(|h| h.content.as_str()).unwrap_or("");
        if top == *expected {
            hits_at_one += 1;
        } else {
            misses.push(format!(
                "  {:<32} expected {:?}\n  {:<32} got      {:?}",
                query, expected, "", top
            ));
        }
    }

    let rate = hits_at_one as f64 / QUERIES.len() as f64;
    println!(
        "\n=== TOP-1 ACCURACY: {}/{} = {:.0}% ===",
        hits_at_one,
        QUERIES.len(),
        rate * 100.0
    );
    if !misses.is_empty() {
        println!("misses:");
        for m in &misses {
            println!("{}", m);
        }
    }
    println!("=====================================\n");

    assert!(
        rate >= 0.75,
        "top-1 accuracy is only {:.0}% ({} of {}). The fused lane is not \
         finding the right memory for a realistic query.\n{}",
        rate * 100.0,
        hits_at_one,
        QUERIES.len(),
        misses.join("\n")
    );
}

/// The failure mode the old radius had: an unrelated query returning junk.
#[tokio::test]
async fn unrelated_query_returns_no_junk() {
    let state = fresh_state().await;
    seed(&state).await;

    for query in [
        "quantum particles behave probabilistically under measurement",
        "the history of the roman empire",
        "how do I bake sourdough bread",
        "stock market volatility index",
    ] {
        let results = search(&state, query, 5).await;
        println!("query {:?} -> {} hits", query, results.len());
        assert!(
            results.is_empty(),
            "query {:?} shares no content with the corpus but returned {} hits, \
             first: {:?}",
            query,
            results.len(),
            results.first().map(|h| h.content.as_str())
        );
    }
}

/// Rank must survive corpus growth: the old cutoff admitted more junk as the
/// corpus grew, because it matched on absolute distance rather than rank.
#[tokio::test]
async fn rank_is_stable_as_the_corpus_grows() {
    let state = fresh_state().await;
    seed(&state).await;

    let query = "where is the spare key";
    let expected = "the spare key hangs in the kitchen cupboard behind the microwave";

    let before = search(&state, query, 5).await;
    assert_eq!(before.first().map(|h| h.content.as_str()), Some(expected));

    // Add 60 unrelated memories.
    let filler: Vec<CurateOp> = (0..60)
        .map(|i| CurateOp::Add {
            entry: ContextEntry {
                memory_id: MemoryId::new(),
                domain: "filler".into(),
                topic: "noise".into(),
                subtopic: "bulk".into(),
                content: format!(
                    "unrelated filler memory number {} about assorted topics entirely",
                    i
                ),
                relations: vec![],
                provenance: "retrieval-test".into(),
                rationale: "bulk".into(),
                created_at: Utc::now(),
                modified_at: Utc::now(),
            },
            reason: "bulk fill".into(),
        })
        .collect();
    let payload = serde_json::to_vec(&filler).unwrap();
    call_frame(&state, frame_types::CURATE, &payload).await;

    let after = search(&state, query, 5).await;
    println!(
        "\nafter growth: {} hits, top = {:?}",
        after.len(),
        after.first().map(|h| h.content.as_str())
    );
    assert_eq!(
        after.first().map(|h| h.content.as_str()),
        Some(expected),
        "rank-1 memory changed after the corpus grew; rank must be stable under \
         growth"
    );
}
