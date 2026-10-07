//! CerebrumState — in-memory aggregator for every Cerebrum subsystem.
//!
//! This struct is the single source of truth that request handlers mutate and
//! query. It is intended to live inside an `Arc<Mutex<CerebrumState>>` so it
//! can be shared safely across concurrent connection tasks.
//!
//! Phase B (full wiring): holds every engine, including the five previously
//! orphaned ones (SignatureEngine, DynamicWaveletMatrix, EventStore,
//! EmbeddingEngine, IndexedExperienceStore) plus `last_curated_id` which
//! feeds the curate pipeline's temporal edge pass.

use std::collections::HashMap;
use std::path::PathBuf;

use cerebrum_core::MemoryId;
use cerebrum_curate::{ContextTree, CrossReferenceEngine};
use cerebrum_decay::DecayEngine;
use cerebrum_dream::DreamEngine;
use cerebrum_dwm::DynamicWaveletMatrix;
use cerebrum_embeddings::EmbeddingEngine;
use cerebrum_events::EventStore;
use cerebrum_episodic::EpisodicStore;
use cerebrum_graph::{GraphQueryPlanner, GraphStore};
use cerebrum_policy::IndexedExperienceStore;
use cerebrum_prefetch::PrefetchEngine;
use cerebrum_query::{QueryEngine, QueryTypeClassifier};
use cerebrum_signatures::SignatureEngine;
use cerebrum_storage::{InMemoryStorage, SnapshotManager, WriteAheadLog};

/// Holds all subsystems that the server routes to.
#[derive(Debug)]
pub struct CerebrumState {
    /// Query subsystem.
    pub query_classifier: QueryTypeClassifier,
    pub query_engine: QueryEngine,
    pub hot_cache: HashMap<MemoryId, Vec<u8>>,

    /// Curation subsystem.
    pub context_tree: ContextTree,
    pub cross_ref: CrossReferenceEngine,

    /// Storage subsystem (engine-local buffers; durable persistence runs on
    /// top of the encrypted vault in phase C).
    pub storage: InMemoryStorage,
    pub wal: WriteAheadLog,
    pub snapshots: SnapshotManager,

    /// Decay subsystem.
    pub decay_engine: DecayEngine,

    /// Graph subsystem — all four orthogonal views in one store.
    pub graph_store: GraphStore,
    pub graph_planner: GraphQueryPlanner,

    /// Dream subsystem.
    pub dream_engine: DreamEngine,

    /// Prefetch subsystem.
    pub prefetch_engine: PrefetchEngine,

    /// Episodic subsystem.
    pub episodic_store: EpisodicStore,

    /// Signature subsystem: tokenizer + random indexing + Hamming searcher.
    /// Manual Debug comes from the signatures crate (boxed tokenizer blocks a
    /// derive there), re-exported as the field type.
    pub signature_engine: SignatureEngine,

    /// Compressed co-index of signatures and token sequences.
    pub dwm: DynamicWaveletMatrix,

    /// Event-sourced audit trail of every memory mutation.
    pub event_store: EventStore,

    /// TF-IDF embeddings fallback for the query pipeline's deepest tier.
    pub embedding_engine: EmbeddingEngine,

    /// Indexed experience summaries (policy layer) for shallow dereferences.
    pub experience_store: IndexedExperienceStore,

    /// The id of the most recently curated memory, for temporal edges.
    pub last_curated_id: Option<MemoryId>,

    /// Persistent data directory.
    pub data_dir: PathBuf,
}

impl CerebrumState {
    /// Remove id from every hot cache surface (used on delete/merge).
    pub fn hot_cache_forget(&mut self, id: &MemoryId) {
        self.hot_cache.remove(id);
        self.query_engine.hot_cache.remove(id);
    }

    /// Insert into both hot-cache surfaces (they share warm/fresh data).
    pub fn hot_cache_insert_wide(&mut self, id: MemoryId, content: String) {
        self.hot_cache.insert(id, content.clone().into_bytes());
        self.query_engine.hot_cache.insert(id, content);
    }

    /// Build a fresh `CerebrumState` with default subsystem instances.
    pub fn new(data_dir: PathBuf) -> Self {
        Self {
            query_classifier: QueryTypeClassifier::new(),
            query_engine: QueryEngine::new(),
            hot_cache: HashMap::new(),
            context_tree: ContextTree::new(),
            cross_ref: CrossReferenceEngine::new(),
            storage: InMemoryStorage::new(),
            wal: WriteAheadLog::new(),
            snapshots: SnapshotManager::with_default_config(),
            decay_engine: DecayEngine::default(),
            graph_store: GraphStore::new(),
            graph_planner: GraphQueryPlanner::new(),
            dream_engine: DreamEngine::new(),
            prefetch_engine: PrefetchEngine::new(),
            episodic_store: EpisodicStore::new(),
            signature_engine: SignatureEngine::new()
                .expect("default signature configuration is valid"),
            dwm: DynamicWaveletMatrix::new(),
            event_store: EventStore::new(),
            embedding_engine: EmbeddingEngine::with_default_config(),
            experience_store: IndexedExperienceStore::new(),
            last_curated_id: None,
            data_dir,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_data_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "cerebrum-state-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn state_holds_all_engines() {
        let state = CerebrumState::new(tmp_data_dir());
        assert_eq!(state.dwm.count(), 0);
        assert_eq!(state.event_store.count(), 0);
        assert_eq!(state.experience_store.count(), 0);
        assert_eq!(state.embedding_engine.doc_count(), 0);
        assert_eq!(state.signature_engine.index_len(), 0);
        assert!(state.last_curated_id.is_none());
    }
}