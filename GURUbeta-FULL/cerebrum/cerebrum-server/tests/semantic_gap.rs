//! The semantic gap, pinned as expected failures.
//!
//! Retrieval is currently BM25 (lexical) plus a signature lane that may only
//! promote what BM25 already found. That means a paraphrase sharing no words
//! with the stored memory returns nothing. This is not a bug to fix by tuning;
//! it is the boundary of a lexical retriever, and it is here so the boundary
//! stays visible instead of being forgotten.
//!
//! Every case below is a query a real user would plausibly type, paired with a
//! memory that means the same thing without sharing vocabulary. Each one
//! currently fails. When a semantic lane lands, this file flips from documenting
//! the gap to proving it closed, and the assertions invert.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::frame_types;
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::Frame;

/// (memory as stored, paraphrase that means the same thing with no shared
/// content words). The second element is what a human would type.
const PARAPHRASES: &[(&str, &str)] = &[
    ("the landlord still has not fixed the boiler in the flat", "the heating is broken"),
    ("the landlord still has not fixed the boiler in the flat", "nothing has been done about the hot water"),
    ("the spare key hangs in the kitchen cupboard behind the microwave", "where do I keep the thing that opens the door"),
    ("the spare key hangs in the kitchen cupboard behind the microwave", "how do I get in when I am locked out"),
    ("my sister is flying to lisbon on thursday for a conference", "what is happening at the end of the week abroad"),
    ("my sister is flying to lisbon on thursday for a conference", "when is she travelling to portugal"),
    ("italian pasta recipe uses fresh tomato and basil", "what do I cook for dinner tonight"),
    ("italian pasta recipe uses fresh tomato and basil", "something quick with herbs and sauce"),
    ("they want to be called steven", "what is their preferred name"),
    ("they want to be called steven", "how should I address them"),
    ("the car insurance renews on the fourteenth of november", "when does the policy expire"),
    ("the car insurance renews on the fourteenth of november", "motoring cover runs out soon"),
    ("she does not eat mushrooms and hates coriander", "what food should I avoid serving"),
    ("she does not eat mushrooms and hates coriander", "any dietary things I should know"),
    ("the router is in the hallway cupboard next to the fuse box", "where is the internet box"),
    ("the router is in the hallway cupboard next to the fuse box", "location of the broadband equipment"),
    ("the passport expires in april so it needs renewing before the trip", "travel document runs out soon"),
    ("the passport expires in april so it needs renewing before the trip", "will I be able to go abroad later this year"),
    ("the deposit for the new flat is three months rent", "how much do I need up front for the place"),
    ("the deposit for the new flat is three months rent", "upfront cost of moving"),
];

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-semantic-gap-{}",
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
    let frame = Frame::new(frame_type, 5150, payload.to_vec());
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

/// Every paraphrase below currently fails. The count of failures is the size of
/// the semantic gap, printed so it can be tracked as a number rather than a
/// feeling.
#[tokio::test]
async fn paraphrase_recall_is_currently_zero() {
    let state = fresh_state().await;

    // Store every distinct memory once.
    let mut seen: std::collections::HashSet<&str> = std::collections::HashSet::new();
    let ops: Vec<CurateOp> = PARAPHRASES
        .iter()
        .filter(|(memory, _)| seen.insert(memory))
        .map(|(memory, _)| CurateOp::Add {
            entry: ContextEntry {
                memory_id: MemoryId::new(),
                domain: "semantic".into(),
                topic: "gap".into(),
                subtopic: "paraphrase".into(),
                content: (*memory).into(),
                relations: vec![],
                provenance: "semantic-gap-test".into(),
                rationale: "gap corpus".into(),
                created_at: Utc::now(),
                modified_at: Utc::now(),
            },
            reason: "gap seed".into(),
        })
        .collect();
    let payload = serde_json::to_vec(&ops).unwrap();
    call_frame(&state, frame_types::CURATE, &payload).await;

    let mut found = 0usize;
    let mut missed: Vec<(&str, &str)> = Vec::new();

    for (memory, paraphrase) in PARAPHRASES {
        let results = search(&state, paraphrase, 5).await;
        if results.iter().any(|h| h.content == *memory) {
            found += 1;
        } else {
            missed.push((memory, paraphrase));
        }
    }

    let total = PARAPHRASES.len();
    println!(
        "\n=== PARAPHRASE RECALL: {}/{} ({}% of the semantic gap is still open) ===",
        found,
        total,
        ((total - found) as f64 / total as f64 * 100.0).round()
    );
    for (memory, paraphrase) in &missed {
        println!("  MISS  {:?}\n        should find {:?}", paraphrase, memory);
    }
    println!("====================================================================\n");

    // Pinned as a measurement, not a wish.
    //
    // The baseline is 1 of 20, and that one is an accident: "where is the
    // internet box" matches "the router is in the hallway cupboard next to the
    // fuse box" on the shared word "box". It is not understanding, and it should
    // not be counted as recall.
    //
    // This assertion is a tripwire in both directions. If the number rises, a
    // semantic lane probably landed and this file should record the new floor.
    // If it falls, the lexical lane regressed. Either way it should not move
    // silently.
    assert_eq!(
        found, 1,
        "paraphrase recall moved from the pinned baseline of 1/20 to {}/20. \
         If a semantic lane landed, update this test to assert the new floor \
         and record the measurement. If it fell, retrieval regressed. If it \
         rose without a semantic lane, the lexical lane is matching vocabulary \
         it should not be.",
        found
    );
}
