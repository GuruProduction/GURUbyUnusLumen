//! Fact identity: the brain's contract for the id a caller holds.
//!
//! Issue 201 reported three separate breaks, and each one is a promise this
//! file holds the brain to:
//!
//! 1. `addMemoryFact` reported an id that `deleteMemoryFact` could not use,
//!    because the id was a random UUID invented on the Kotlin side while the
//!    brain stored the fact under the sha256 of its text.
//! 2. A search id went stale, because consolidation minted a fresh id for every
//!    merged record and retired the ids the caller was holding.
//! 3. A delete reported "Fact not found" for a fact the brain held, because the
//!    tool lane proved existence with a relevance search rather than a lookup.
//!
//! These tests exercise the brain's half of the contract, through the same
//! frames the app sends: an id the caller wrote is an id the caller can read
//! and delete, consolidation preserves it, and a listing enumerates everything
//! rather than whatever happened to match a keyword.

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
        "cerebrum-fact-identity-{}",
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

/// A curate Upsert with the exact wire shape the app's storeFact emits: a
/// deterministic content id, the caller's category in the rationale, and the
/// caller's confidence in the provenance source label.
fn upsert_op(id: MemoryId, content: &str, category: &str, confidence: f32) -> CurateOp {
    CurateOp::Upsert {
        entry: ContextEntry {
            memory_id: id,
            domain: "memory".into(),
            topic: "recall".into(),
            subtopic: "fact".into(),
            content: content.to_string(),
            relations: vec![],
            provenance: format!("guru.confidence={confidence}"),
            rationale: format!("guru storeFact: {category}"),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "fact identity test".into(),
    }
}

fn delete_op(id: MemoryId) -> CurateOp {
    CurateOp::Delete {
        memory_id: id,
        reason: "fact identity test delete".into(),
    }
}

/// Retrieve a memory and return the decoded body, or None when the brain
/// refuses the id.
///
/// Shallow is the existence lookup (it is the cheapest level and carries the
/// metadata but no body); Deep adds the content. Both answer not-found for a
/// retired memory, which is what the tool lane's delete and update rely on.
async fn retrieve_body(
    state: &Arc<Mutex<CerebrumState>>,
    id: MemoryId,
    deep: bool,
) -> Option<serde_json::Value> {
    let level = if deep { "Deep" } else { "Shallow" };
    let payload = serde_json::to_vec(&serde_json::json!({
        "memory_id": id.0.to_vec(),
        "level": level
    }))
    .unwrap();
    let bytes = call_frame(state, frame_types::RETRIEVE, &payload).await;
    let root: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    if root.get("error").is_some() {
        return None;
    }
    // The body rides as JSON bytes inside a `data` array of numbers.
    let data_array = root.get("data")?.as_array()?;
    let body_bytes: Vec<u8> = data_array
        .iter()
        .filter_map(|value| value.as_u64().map(|n| n as u8))
        .collect();
    serde_json::from_slice(&body_bytes).ok()
}

/// The existence lookup used by delete and update.
async fn retrieve_shallow(
    state: &Arc<Mutex<CerebrumState>>,
    id: MemoryId,
) -> Option<serde_json::Value> {
    retrieve_body(state, id, false).await
}

async fn list_all(state: &Arc<Mutex<CerebrumState>>) -> serde_json::Value {
    let bytes = call_frame(state, frame_types::LIST, b"{}").await;
    serde_json::from_slice(&bytes).expect("list always returns a report")
}

/// Acceptance case one: write a fact, then delete the id the write reported,
/// with no search in between.
#[tokio::test]
async fn a_written_id_can_be_deleted_without_a_search() {
    let state = fresh_state().await;
    let id = MemoryId([7u8; 32]);
    let content = "GURU-DELETE-REPRO-ONE the kettle is in the kitchen";

    let bytes = call_frame(
        &state,
        frame_types::CURATE,
        &serde_json::to_vec(&vec![upsert_op(id, content, "knowledge", 0.1)]).unwrap(),
    )
    .await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true, "the write must land: {ack}");

    // The id resolves straight away, with no search in between. This is the
    // lookup the tool lane's delete is built on.
    let before = retrieve_shallow(&state, id).await;
    assert!(
        before.is_some(),
        "the id the write reported must resolve through RETRIEVE"
    );

    let bytes = call_frame(
        &state,
        frame_types::CURATE,
        &serde_json::to_vec(&vec![delete_op(id)]).unwrap(),
    )
    .await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true, "the delete must land: {ack}");

    // Immediately gone from every read surface.
    assert!(
        retrieve_shallow(&state, id).await.is_none(),
        "a deleted memory must answer not-found on RETRIEVE"
    );

    let hits = {
        let payload =
            serde_json::to_vec(&serde_json::json!({ "query": "kettle kitchen", "limit": 10 })).unwrap();
        let bytes = call_frame(&state, frame_types::SEARCH, &payload).await;
        serde_json::from_slice::<Vec<serde_json::Value>>(&bytes).unwrap_or_default()
    };
    assert!(
        hits.is_empty(),
        "a deleted memory must not come back out of search; got {} hits",
        hits.len()
    );

    let listing = list_all(&state).await;
    assert_eq!(
        listing["total_live"].as_u64().unwrap_or(999),
        0,
        "a deleted memory must not appear in a listing: {listing}"
    );
}

/// Acceptance case two: an id survives a consolidation pass.
#[tokio::test]
async fn a_search_id_still_resolves_after_consolidation() {
    let state = fresh_state().await;
    let content = "the landlord still has not fixed the boiler in the flat";
    let id_a = MemoryId([11u8; 32]);
    let id_b = MemoryId([12u8; 32]);

    // Two rows with identical content, written as Adds under distinct ids, so
    // consolidation has a genuine duplicate to fold.
    let add_ops: Vec<CurateOp> = [id_a, id_b]
        .iter()
        .map(|id| CurateOp::Add {
            entry: ContextEntry {
                memory_id: *id,
                domain: "memory".into(),
                topic: "recall".into(),
                subtopic: "fact".into(),
                content: content.to_string(),
                relations: vec![],
                provenance: "guru.confidence=0.9".into(),
                rationale: "guru storeFact: knowledge".into(),
                created_at: Utc::now(),
                modified_at: Utc::now(),
            },
            reason: "identity under consolidation".into(),
        })
        .collect();
    let bytes = call_frame(
        &state,
        frame_types::CURATE,
        &serde_json::to_vec(&add_ops).unwrap(),
    )
    .await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true, "both adds must land: {ack}");

    // The consolidation pass under test.
    let report_bytes = call_frame(&state, frame_types::CONSOLIDATE, b"{}").await;
    let report: serde_json::Value = serde_json::from_slice(&report_bytes).unwrap();

    // Exactly one of the two held ids must survive: the duplicate is absorbed
    // into the row the caller already holds a handle for, not swapped for a
    // stranger.
    let (resolves_a, resolves_b) = (
        retrieve_shallow(&state, id_a).await.is_some(),
        retrieve_shallow(&state, id_b).await.is_some(),
    );
    assert!(
        resolves_a ^ resolves_b,
        "exactly one held id must still resolve after consolidation \
         (a={resolves_a} b={resolves_b}); report said {report}"
    );

    // And the survivor is the row carrying the merged content.
    let survivor = if resolves_a { id_a } else { id_b };
    let body = retrieve_body(&state, survivor, true)
        .await
        .expect("survivor body");
    assert_eq!(
        body["content"].as_str().unwrap_or(""),
        content,
        "the surviving id must still describe the same fact"
    );
}

/// The listing is an enumeration, not a relevance query. Every live memory is
/// present regardless of its wording, which is what makes the tool lane's
/// listing and its delete existence check honest.
#[tokio::test]
async fn a_listing_enumerates_every_live_memory() {
    let state = fresh_state().await;

    let contents = [
        "the kettle is in the kitchen",
        "my sister is flying to lisbon on thursday",
        "the quarterly invoice arrived this morning",
    ];
    let ops: Vec<CurateOp> = contents
        .iter()
        .enumerate()
        .map(|(index, text)| {
            upsert_op(
                MemoryId([(index as u8) + 1; 32]),
                text,
                "knowledge",
                0.7,
            )
        })
        .collect();
    let bytes = call_frame(&state, frame_types::CURATE, &serde_json::to_vec(&ops).unwrap()).await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true);

    let listing = list_all(&state).await;
    let rows = listing["memories"].as_array().expect("memories array");
    assert_eq!(
        rows.len(),
        3,
        "a listing must carry every live memory, not a keyword match: {listing}"
    );
    assert_eq!(listing["total_live"].as_u64().unwrap_or(0), 3);
    assert_eq!(listing["total_matching"].as_u64().unwrap_or(0), 3);

    // Every memory is represented, whichever words it happens to use.
    for expected in contents {
        assert!(
            rows.iter().any(|row| row["content"].as_str() == Some(expected)),
            "listing omitted {expected:?}: {listing}"
        );
    }
}

/// The caller's category and confidence survive a round trip, because the tool
/// lane has nowhere else to read them from.
#[tokio::test]
async fn category_and_confidence_round_trip_through_retrieve() {
    let state = fresh_state().await;
    let id = MemoryId([31u8; 32]);

    let bytes = call_frame(
        &state,
        frame_types::CURATE,
        &serde_json::to_vec(&vec![upsert_op(
            id,
            "the boiler service is booked for tuesday",
            "home",
            0.42,
        )])
        .unwrap(),
    )
    .await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true);

    let body = retrieve_shallow(&state, id).await.expect("body after write");
    assert_eq!(
        body["rationale"].as_str().unwrap_or(""),
        "guru storeFact: home",
        "the caller's category must round-trip; got {body}"
    );
    let confidence = body["provenance"]["confidence"].as_f64().unwrap_or(-1.0);
    assert!(
        (confidence - 0.42).abs() < 0.001,
        "the caller's confidence must round-trip; got {confidence} from {body}"
    );
}

/// Listing honours a domain filter and reports the true totals separately from
/// the page it returns, so a capped listing cannot claim the brain is emptier
/// than it is.
#[tokio::test]
async fn a_limited_listing_reports_the_true_total() {
    let state = fresh_state().await;

    let mut ops: Vec<CurateOp> = Vec::new();
    for index in 0..5u8 {
        ops.push(upsert_op(
            MemoryId([(index as u8) + 40; 32]),
            &format!("numbered memory {index}"),
            "knowledge",
            0.5,
        ));
    }
    let bytes = call_frame(&state, frame_types::CURATE, &serde_json::to_vec(&ops).unwrap()).await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(ack["executed"]["success"], true);

    let payload = serde_json::to_vec(&serde_json::json!({ "limit": 2 })).unwrap();
    let bytes = call_frame(&state, frame_types::LIST, &payload).await;
    let listing: serde_json::Value = serde_json::from_slice(&bytes).unwrap();

    assert_eq!(
        listing["memories"].as_array().map(|rows| rows.len()).unwrap_or(0),
        2,
        "the page must respect the limit: {listing}"
    );
    assert_eq!(
        listing["total_matching"].as_u64().unwrap_or(0),
        5,
        "the total must report every match, not the page length: {listing}"
    );
}
