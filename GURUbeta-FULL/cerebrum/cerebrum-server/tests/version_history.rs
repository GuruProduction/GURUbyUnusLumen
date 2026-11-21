//! An update must leave the previous version recoverable (issue 483).
//!
//! THE DEFECT. `updateMemoryFact` destroyed the previous content in three layers
//! at once. The context tree replaced the row. The decay copy was overwritten in
//! place before anything read it. And the event trail recorded
//! `{"reason": "update"}`, so the one store that is supposed to make replay
//! possible carried no content to replay.
//!
//! The consequence is not cosmetic. Steven's own Archivist prompt, section 10,
//! says a correction may only be made when "the original version remains
//! permanently recoverable through immutable version history", and section 11
//! requires that a changed preference preserve both the new state and the fact
//! that the previous state existed. Against the old write path a compliant agent
//! had to refuse every correction, and an agent that did correct silently destroyed
//! the record without knowing it.
//!
//! These tests fail against current main. They assert on the history the trail can
//! reconstruct, which is the only place the previous text can honestly come from.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId, PartialEntry};
use cerebrum_events::RevisionKind;
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame};

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-version-history-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

fn entry_with_id(id: MemoryId, content: &str) -> ContextEntry {
    ContextEntry {
        memory_id: id,
        domain: "memory".into(),
        topic: "facts".into(),
        subtopic: "history".into(),
        content: content.into(),
        relations: vec![],
        provenance: "version-history-test".into(),
        rationale: "the reason for the write".into(),
        owner_agent_id: String::new(),
        visibility: String::new(),
        created_at: Utc::now(),
        modified_at: Utc::now(),
    }
}

async fn curate(state: &Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) -> serde_json::Value {
    let payload = serde_json::to_vec(&ops).unwrap();
    let frame = Frame::new(frame_types::CURATE, 31, payload);
    let bytes = handle_frame(frame, state.clone()).await.expect("curate must answer");
    let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(value["executed"]["success"], true, "curation must succeed: {value}");
    value
}

/// The one behaviour the Archivist prompt depends on: after an edit, the old
/// wording is still readable.
#[tokio::test]
async fn an_update_leaves_the_previous_wording_recoverable() {
    let state = fresh_state().await;
    let id = MemoryId::new();

    curate(
        &state,
        vec![CurateOp::Add {
            entry: entry_with_id(id, "the deployment window is Tuesday at 14:00"),
            reason: "record the deployment window".into(),
        }],
    )
    .await;

    curate(
        &state,
        vec![CurateOp::Update {
            memory_id: id,
            changes: PartialEntry {
                content: Some("the deployment window is Thursday at 09:00".into()),
                ..Default::default()
            },
            reason: "the window moved".into(),
        }],
    )
    .await;

    let locked = state.lock().await;

    // The live row says the new thing.
    let live = locked
        .context_tree
        .find(id)
        .expect("the row must still exist");
    assert_eq!(live.content, "the deployment window is Thursday at 09:00");

    // And the trail can still produce the old thing.
    assert_eq!(
        locked.event_store.previous_content(id).as_deref(),
        Some("the deployment window is Tuesday at 14:00"),
        "the superseded wording must be recoverable from the event trail"
    );
    drop(locked);
}

/// The whole revision list, oldest first, with both kinds distinguished.
#[tokio::test]
async fn the_trail_records_every_revision_in_order() {
    let state = fresh_state().await;
    let id = MemoryId::new();

    curate(
        &state,
        vec![CurateOp::Add {
            entry: entry_with_id(id, "version one"),
            reason: "first".into(),
        }],
    )
    .await;
    for text in ["version two", "version three"] {
        curate(
            &state,
            vec![CurateOp::Update {
                memory_id: id,
                changes: PartialEntry {
                    content: Some(text.into()),
                    ..Default::default()
                },
                reason: "revision".into(),
            }],
        )
        .await;
    }

    let locked = state.lock().await;
    let history = locked.event_store.history_for(id);
    assert_eq!(history.len(), 3, "one create and two updates: {history:?}");

    assert_eq!(history[0].revision_kind, RevisionKind::Created);
    assert_eq!(history[0].content, "version one");
    assert_eq!(history[0].superseded, None);

    assert_eq!(history[1].revision_kind, RevisionKind::Updated);
    assert_eq!(history[1].content, "version two");
    assert_eq!(
        history[1].superseded.as_deref(),
        Some("version one"),
        "each update carries what it replaced, not merely that it happened"
    );

    assert_eq!(history[2].content, "version three");
    assert_eq!(history[2].superseded.as_deref(), Some("version two"));

    // The newest revision is the live one.
    assert_eq!(
        history.last().map(|r| r.content.as_str()),
        Some("version three")
    );
}

/// A create records no superseded text, because there was nothing to supersede.
#[tokio::test]
async fn a_create_has_no_superseded_text_and_previous_content_is_none() {
    let state = fresh_state().await;
    let id = MemoryId::new();

    curate(
        &state,
        vec![CurateOp::Add {
            entry: entry_with_id(id, "a fact that has never been revised"),
            reason: "first write".into(),
        }],
    )
    .await;

    let locked = state.lock().await;
    assert_eq!(
        locked.event_store.previous_content(id),
        None,
        "a memory that was never revised has no previous version, and inventing one \
         would be worse than the honest None"
    );
    let history = locked.event_store.history_for(id);
    assert_eq!(history.len(), 1);
    assert_eq!(history[0].superseded, None);
}

/// Revising a memory twice must not lose the first revision behind the second.
///
/// This is the property that makes it "immutable version history" rather than a
/// single previous-value slot. The store is append-only, so a third write cannot
/// overwrite the record of the first.
#[tokio::test]
async fn a_second_revision_does_not_erase_the_record_of_the_first() {
    let state = fresh_state().await;
    let id = MemoryId::new();

    curate(
        &state,
        vec![CurateOp::Add {
            entry: entry_with_id(id, "position A"),
            reason: "first".into(),
        }],
    )
    .await;

    for text in ["position B", "position C", "position D"] {
        curate(
            &state,
            vec![CurateOp::Update {
                memory_id: id,
                changes: PartialEntry {
                    content: Some(text.into()),
                    ..Default::default()
                },
                reason: "revise".into(),
            }],
        )
        .await;
    }

    let locked = state.lock().await;
    let history = locked.event_store.history_for(id);
    let superseded: Vec<&str> = history
        .iter()
        .filter_map(|revision| revision.superseded.as_deref())
        .collect();
    assert_eq!(
        superseded,
        vec!["position A", "position B", "position C"],
        "every intermediate version must still be readable, not just the last one"
    );
}

/// The event payload itself is what the persistence layer writes to disk, so the
/// history has to round-trip through serde unchanged.
#[tokio::test]
async fn the_history_survives_a_serde_round_trip() {
    let state = fresh_state().await;
    let id = MemoryId::new();

    curate(
        &state,
        vec![CurateOp::Add {
            entry: entry_with_id(id, "before the change"),
            reason: "first".into(),
        }],
    )
    .await;
    curate(
        &state,
        vec![CurateOp::Update {
            memory_id: id,
            changes: PartialEntry {
                content: Some("after the change".into()),
                ..Default::default()
            },
            reason: "the change".into(),
        }],
    )
    .await;

    let locked = state.lock().await;
    // The vault record is the serialised store, so this is the exact shape that
    // lands on disk and comes back after a restart.
    let serialised = serde_json::to_string(&locked.event_store).unwrap();
    let restored: cerebrum_events::EventStore = serde_json::from_str(&serialised).unwrap();
    assert_eq!(
        restored.previous_content(id).as_deref(),
        Some("before the change"),
        "the history must survive the round trip the vault performs"
    );
}
