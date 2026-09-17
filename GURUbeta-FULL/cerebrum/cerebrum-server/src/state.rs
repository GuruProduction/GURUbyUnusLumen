//! CerebrumState — in-memory aggregator for every Cerebrum subsystem.
//!
//! This struct is the single source of truth that request handlers mutate and
//! query. It is intended to live inside an `Arc<Mutex<CerebrumState>>` so it
//! can be shared safely across concurrent connection tasks.

use std::collections::HashMap;
use std::path::PathBuf;

use cerebrum_core::MemoryId;
use cerebrum_curate::{ContextTree, CrossReferenceEngine};
use cerebrum_decay::DecayEngine;
use cerebrum_dream::DreamEngine;
use cerebrum_episodic::EpisodicStore;
use cerebrum_graph::{GraphQueryPlanner, GraphStore};
use cerebrum_prefetch::PrefetchEngine;
use cerebrum_query::{QueryEngine, QueryTypeClassifier};
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

    /// Storage subsystem.
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

    /// Persistent data directory.
    pub data_dir: PathBuf,
}

impl CerebrumState {
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
            data_dir,
        }
    }
}