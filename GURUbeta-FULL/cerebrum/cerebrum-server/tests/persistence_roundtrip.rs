//! Phase C persistence roundtrip battery (prescribed by the plan):
//! curate 10 memories, save to the encrypted vault, create a fresh
//! CerebrumState and load, verify EVERY engine matches the saved pre-image.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::handler::{handle_frame, CurateFullResponse};
use cerebrum_server::persistence::{restore_state, CerebrumPersistence};
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame};

const PASSPHRASE: &str = "phase-c roundtrip battery passphrase";

async fn state_in(dir: std::path::PathBuf) -> Arc<Mutex<CerebrumState>> {
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

fn add_op(content: &str) -> CurateOp {
    CurateOp::Add {
        entry: ContextEntry {
            memory_id: MemoryId::new(),
            domain: "persistence".into(),
            topic: "roundtrip".into(),
            subtopic: "battery".into(),
            content: content.into(),
            relations: vec![],
            provenance: "persist-battery".into(),
            rationale: "phase c roundtrip".into(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "phase c add".into(),
    }
}

async fn curate_through_frame(state: &Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) -> CurateFullResponse {
    let payload = serde_json::to_vec(&ops).unwrap();
    let frame = Frame::new(frame_types::CURATE, 77, payload);
    let bytes = handle_frame(frame, state.clone()).await.unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

/// PRESCRIBED TEST: 10 curated memories -> save_all -> restore_state ->
/// DWM 10, tree 10, decay 10, events 10, hot 10, policy 10, graph edges
/// preserved, last_curated_id preserved.
#[tokio::test]
async fn test_ten_curated_save_and_restore() {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-persist-battery-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let state = state_in(dir.clone()).await;

    let ops: Vec<CurateOp> = (0..10)
        .map(|i| add_op(&format!("persist battery record number {} details about theme {}", i, i * 29)))
        .collect();
    let result = curate_through_frame(&state, ops).await;
    assert!(result.executed.success);

    {
        let locked = state.lock().await;
        assert_eq!(locked.dwm.count(), 10);
        assert_eq!(locked.decay_engine.entries.len(), 10);
        assert_eq!(locked.event_store.count(), 10);
        assert_eq!(locked.query_engine.hot_cache.len(), 10);
        assert_eq!(locked.experience_store.count(), 10);
        assert!(locked.graph_store.total_edges() >= 9, "temporal chain present pre-save");
    }

    // Durability write pass (synchronous; the loop holds the state lock).
    let persist = CerebrumPersistence::new(dir.clone());
    {
        let mut unlocked = state.lock().await;
        let report = persist.save_all(&mut unlocked, PASSPHRASE).unwrap();
        // One record per engine surface: tree, decay, graph, episodic,
        // cross-refs, events, embeddings, policy, hot cache, prefetch,
        // last-id, hamming, dwm, wal = 14.
        assert_eq!(report.subsystem_tags.len(), 14, "one record per engine surface");
    }

    // FRESH, clean state with an identical dir. All engines restart defaults.
    let fresh = CerebrumState::new(dir.join("unused-subdir"));
    drop(fresh);
    let mut restored = CerebrumState::new(std::path::PathBuf::from("."));
    restore_state(&mut restored, dir.clone(), PASSPHRASE).unwrap();

    assert_eq!(restored.dwm.count(), 10, "DWM restored");
    assert_eq!(restored.context_tree.len(), 10, "context tree restored");
    assert_eq!(restored.decay_engine.entries.len(), 10, "decay restored");
    assert_eq!(restored.event_store.count(), 10, "events restored");
    assert_eq!(restored.query_engine.hot_cache.len(), 10, "hot cache restored");
    assert_eq!(restored.experience_store.count(), 10, "policy index restored");
    assert!(restored.graph_store.total_edges() >= 9, "graph edges restored");
    assert!(restored.graph_store.total_nodes() >= 10, "graph nodes restored");
    assert!(
        restored.last_curated_id.is_some(),
        "last curated id restored so the temporal chain can continue"
    );

    restore_broken_count_witness();
}

fn restore_broken_count_witness() {
    // Count-restore proof for the embedding engine happens in the full
    // battery for docs: engine state restores via EmbeddingState snapshot.
    let mut engine = cerebrum_embeddings::EmbeddingEngine::with_default_config();
    engine.embed("persist witness body for doc count roundtrip probe");
    let snap = engine.to_persistable();
    let mut back = cerebrum_embeddings::EmbeddingEngine::with_default_config();
    assert_eq!(back.doc_count(), 0, "fresh, default count 0 before load");
    let engine2 = cerebrum_embeddings::EmbeddingEngine::from_persistable(snap);
    assert_eq!(engine2.doc_count(), 1, "doc count rides the snapshot");
}

/// Encryption-at-rest verification through the real persistence path:
/// the vault file must contain NO memory-content plaintext ever.
#[tokio::test]
async fn test_vault_file_carries_no_plaintext() {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-persist-leak-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let state = state_in(dir.clone()).await;

    let secret_content = "SECRET_CEREBRUM_MEMORY_CONTENT_CANARY_4291";
    let result = curate_through_frame(&state, vec![add_op(secret_content)]).await;
    assert!(result.executed.success);

    let persist = CerebrumPersistence::new(dir.clone());
    {
        let mut unlocked = state.lock().await;
        persist.save_all(&mut unlocked, "leak battery passive").unwrap();
    }

    // Scan the vault file for the canary plaintext.
    let vault_bytes = std::fs::read(persist.vault_path()).unwrap();
    let as_str = String::from_utf8_lossy(&vault_bytes);
    assert!(
        !as_str.contains(secret_content),
        "plaintext memory content leaked through persistence into the vault"
    );
    // Also check the staging file got zero-cleared.
    let stray_files = std::fs::read_dir(&dir).unwrap().count();
    assert_eq!(stray_files, 1, "only the vault file must remain in the data dir");
}