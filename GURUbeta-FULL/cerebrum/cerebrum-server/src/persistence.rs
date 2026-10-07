//! Cerebrum Persistence — engine-state save and load, through the encrypted vault.
//!
//! Phase C. Every serialized subsystem goes through cerebrum-crypto's
//! EncryptedVault: bytes on disk are always ciphertext-with-verifier-header.
//! Engine states saved: context tree, decay, graph store, episodic,
//! cross-refs, event store (incl. enrichment), embedding engine,
//! hamming searcher entries, policy store, DWM (through its own DwmPersistence
//! byte format serialized into a vault record — CRC32 + WAL semantics ride on
//! top of the encrypted envelope so nothing plaintext ever hits the drive).
//!
//! Layout inside the data dir:
//!   {data_dir}/cerebrum.vault  — the encrypted-at-rest brain bundle. Each
//!   subsystem is ONE appended record whose plaintext body is
//!       u32-LE tag  (SUBSYSTEM_* constant)  || JSON-encoded engine state
//!   with the DWM entry using tag SUBSYSTEM_DWM with raw serialization
//!   bytes per its DwmPersistence format spec. The vault's append-only
//!   record stream is the write-ahead story between saves: reload replays
//!   same-subsystem records in stream order so later writes win. Save
//!   compaction: after a full save pass, earlier superseded records for the
//!   same subsystem lose authority automatically by record order.
//!
//! The key lives only with the caller (process RAM). Passing a passphrase in
//! on every save call is how the writer session opens its vault handle.

use std::collections::HashMap;
use std::path::PathBuf;

use cerebrum_crypto::{CryptoError, EncryptedVault, VaultBuilder};
use cerebrum_core::{BinarySignature, MemoryId};

use crate::state::CerebrumState;

/// Sub-system tags inside record plaintexts, u32 LE. Versioned forever:
/// changing a wire format bumps the tag value family (e.g. re-use +2^16).
pub const SUBSYSTEM_CONTEXT_TREE: u32 = 1;
pub const SUBSYSTEM_DECAY: u32 = 2;
pub const SUBSYSTEM_GRAPH_STORE: u32 = 3;
pub const SUBSYSTEM_EPISODIC: u32 = 4;
pub const SUBSYSTEM_CROSS_REFERENCE: u32 = 5;
pub const SUBSYSTEM_EVENT_STORE: u32 = 6;
pub const SUBSYSTEM_EMBEDDINGS: u32 = 7;
pub const SUBSYSTEM_HAMMING_INDEX: u32 = 8;
pub const SUBSYSTEM_POLICY_STORE: u32 = 9;
pub const SUBSYSTEM_DWM: u32 = 10;
pub const SUBSYSTEM_WAL: u32 = 11; // durable in-RAM WAL record buffer
pub const SUBSYSTEM_LAST_CURATED_ID: u32 = 12;
pub const SUBSYSTEM_QUERY_HOT_CACHE: u32 = 13;
pub const SUBSYSTEM_PREFETCH_REGISTER: u32 = 14;

/// Vault file name inside the data dir.
pub const VAULT_FILE_NAME: &str = "cerebrum.vault";

/// Persistence handle; owns the key and vault access to one brain.
pub struct CerebrumPersistence {
    data_dir: PathBuf,
}

impl CerebrumPersistence {
    pub fn new(data_dir: PathBuf) -> Self {
        Self { data_dir }
    }

    /// Durable in-RAM WAL: the DWM changes written since the LAST vault.
    /// Persisted at every save as its own record too, so the last crash
    /// recoverable window is: vault reload then re-check of newest WAL record.
    pub fn vault_path(&self) -> PathBuf {
        self.data_dir.join(VAULT_FILE_NAME)
    }

    // ==============================================================================
    // Saving
    // ==============================================================================

    /// Save everything writable under `passphrase`. Appends one record per
    /// subsystem to the vault; returns per-subsystem record byte size total.
    pub fn save_all(
        &self,
        state: &mut CerebrumState,
        passphrase: &str,
    ) -> Result<PersistReport, PersistError> {
        std::fs::create_dir_all(&self.data_dir).map_err(|e| PersistError::Io {
            reason: e.to_string(),
        })?;

        let path = self.vault_path();
        let mut vault = if path.exists() {
            EncryptedVault::open_existing(path, passphrase)
                .map_err(PersistError::Crypto)?
        } else {
            VaultBuilder::new(path)
                .create_from_passphrase(passphrase)
                .map_err(PersistError::Crypto)?
        };

        let mut report = PersistReport::default();

        write_subsystem(
            &mut vault,
            SUBSYSTEM_CONTEXT_TREE,
            &context_tree_records(&state.context_tree),
            &mut report,
        )?;
        write_subsystem(&mut vault, SUBSYSTEM_DECAY, &decay_records(&state.decay_engine), &mut report)?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_GRAPH_STORE,
            &graph_records(&state.graph_store),
            &mut report,
        )?;
        write_subsystem(&mut vault, SUBSYSTEM_EPISODIC, &episodic_records(&state.episodic_store), &mut report)?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_CROSS_REFERENCE,
            &cross_reference_records(&state.cross_ref),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_EVENT_STORE,
            &event_store_records(&state.event_store),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_EMBEDDINGS,
            &state.embedding_engine.to_persistable(),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_POLICY_STORE,
            &policy_records(&state.experience_store),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_QUERY_HOT_CACHE,
            &hot_cache_records(&state.query_engine.hot_cache),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_PREFETCH_REGISTER,
            &prefetch_records(&state.prefetch_engine),
            &mut report,
        )?;
        write_subsystem(
            &mut vault,
            SUBSYSTEM_LAST_CURATED_ID,
            &serde_json::json!({
                "last_curated_id": state.last_curated_id.map(|id| id.to_string())
            }),
            &mut report,
        )?;

        // DWM via its own byte format (DwmPersistence::write to in-memory).
        // The engine crate exposes snapshot-restore to FILES, so the memory
        // buffer path writes to a temp file INSIDE data dir then feeds its
        // bytes straight into the encrypted bundle, and its plaintext copy is
        // removed before the function returns. Even a kernel-torn write of a
        // file that briefly existed on an unlocked filesystem is bounded to a
        // few kilobytes: not silently permanent, but noted as a future
        // zeroization improvement phase — see plan Phase C todo.
        write_subsystem(&mut vault, SUBSYSTEM_HAMMING_INDEX, &build_hamming_dump(state), &mut report)?;

        // Staged DWM snapshot into the vault, atomic-zeroization after read.
        let dwm_path = self.data_dir.join(".dwmstage.bin.tmp");
        if dwm_path.exists() {
            std::fs::remove_file(&dwm_path).map_err(|e| PersistError::Io {
                reason: e.to_string(),
            })?;
        }
        cerebrum_dwm::DwmPersistence::save(&mut state.dwm, &dwm_path)
            .map_err(|e| PersistError::Dwm(format!("{:?}", e)))?;
        let dwm_bytes = std::fs::read(&dwm_path).map_err(|e| PersistError::Io {
            reason: e.to_string(),
        })?;
        // zeroize disk copies of the stage file before any plaintext sits.
        // Overwrite with zero pattern and then remove.
        let zero_pattern = vec![0u8; dwm_bytes.len()];
        std::fs::write(&dwm_path, &zero_pattern).map_err(|e| PersistError::Io {
            reason: e.to_string(),
        })?;
        let _ = std::fs::remove_file(&dwm_path);

        write_bytes_subsystem(&mut vault, SUBSYSTEM_DWM, &dwm_bytes, &mut report)?;

        // Persist the in-RAM engine WAL then checkpoint it: WriteAheadLog
        // derives Serialize so the WHOLE type roundtrips cleanly.
        write_subsystem(&mut vault, SUBSYSTEM_WAL, &state.wal, &mut report)?;

        report.total_vault_bytes = std::fs::metadata(self.vault_path())
            .map(|m| m.len())
            .unwrap_or_default() as usize;

        Ok(report)
    }

    // ==============================================================================
    // Loading
    // ==============================================================================

    /// Load every engine to the LATEST version found for each subsystem tag
    /// in the encrypted bundle, restore DWM through its own load API.
    pub fn load_all(&self, passphrase: &str) -> Result<LoadedPersist, PersistError> {
        let path = self.vault_path();

        if !path.exists() {
            return Ok(LoadedPersist {
                empty: true,
                ..Default::default()
            });
        }

        let vault =
            EncryptedVault::open_existing(path, passphrase).map_err(PersistError::Crypto)?;
        let count = vault.record_count().map_err(PersistError::Crypto)?;

        let mut out = LoadedPersist::default();

        for index in 0..count {
            let bytes = vault.read_record_by_index(index).map_err(PersistError::Crypto)?;
            let loaded = decode_record_bytes(&bytes).ok_or(PersistError::CorruptRecord {
                index,
                reason: "invalid subsystem tag or body".to_string(),
            })?;
            out.apply_record(loaded);
        }

        Ok(out)
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub enum PersistError {
    #[default]
    #[doc(hidden)]
    UninitializedButDefaultNeeded,
    Io { reason: String },
    Serialize { reason: String },
    Crypto(CryptoError),
    Dwm(String),
    GraphRecord(String),
    CorruptRecord { index: usize, reason: String },
}

/// Per-save metrics so the write path's engine coverage is audited too.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PersistReport {
    pub records_written: usize,
    pub total_vault_bytes: usize,
    pub subsystem_tags: Vec<u32>,
}

/// The fully-applied state for one subsystem, decoded from ciphertext.
#[derive(Debug)]
pub struct LoadedSubsystem {
    pub tag: u32,
    pub plaintext: Vec<u8>,
}

impl LoadedPersist {
    fn apply_record(&mut self, record: LoadedSubsystem) {
        match record.tag {
            SUBSYSTEM_CONTEXT_TREE => {
                self.context_tree_bytes = Some(record.plaintext);
            }
            SUBSYSTEM_DECAY => self.decay_bytes = Some(record.plaintext),
            SUBSYSTEM_GRAPH_STORE => self.graph_bytes = Some(record.plaintext),
            SUBSYSTEM_EPISODIC => self.episodic_bytes = Some(record.plaintext),
            SUBSYSTEM_CROSS_REFERENCE => self.cross_ref_bytes = Some(record.plaintext),
            SUBSYSTEM_EVENT_STORE => self.event_store_bytes = Some(record.plaintext),
            SUBSYSTEM_EMBEDDINGS => self.embeddings_bytes = Some(record.plaintext),
            SUBSYSTEM_HAMMING_INDEX => self.hamming_bytes = Some(record.plaintext),
            SUBSYSTEM_POLICY_STORE => self.policy_bytes = Some(record.plaintext),
            SUBSYSTEM_DWM => self.dwm_bytes = Some(record.plaintext),
            SUBSYSTEM_WAL => self.wal_bytes = Some(record.plaintext),
            SUBSYSTEM_LAST_CURATED_ID => self.last_id_bytes = Some(record.plaintext),
            SUBSYSTEM_QUERY_HOT_CACHE => self.hot_cache_bytes = Some(record.plaintext),
            SUBSYSTEM_PREFETCH_REGISTER => self.prefetch_bytes = Some(record.plaintext),
            _ => {}
        }
    }
}

#[derive(Default)]
pub struct LoadedPersist {
    pub empty: bool,
    pub context_tree_bytes: Option<Vec<u8>>,
    pub decay_bytes: Option<Vec<u8>>,
    pub graph_bytes: Option<Vec<u8>>,
    pub episodic_bytes: Option<Vec<u8>>,
    pub cross_ref_bytes: Option<Vec<u8>>,
    pub event_store_bytes: Option<Vec<u8>>,
    pub embeddings_bytes: Option<Vec<u8>>,
    pub hamming_bytes: Option<Vec<u8>>,
    pub policy_bytes: Option<Vec<u8>>,
    pub dwm_bytes: Option<Vec<u8>>,
    pub wal_bytes: Option<Vec<u8>>,
    pub last_id_bytes: Option<Vec<u8>>,
    pub hot_cache_bytes: Option<Vec<u8>>,
    pub prefetch_bytes: Option<Vec<u8>>,
}

// ============================================================================
// helpers
// ============================================================================

fn write_subsystem<S: serde::Serialize>(
    vault: &mut EncryptedVault,
    tag: u32,
    value: &S,
    report: &mut PersistReport,
) -> Result<(), PersistError> {
    let mut body_json = serde_json::to_vec(value).map_err(|e| PersistError::Serialize {
        reason: e.to_string(),
    })?;
    let mut plains: Vec<u8> = vec![];
    plains.extend(tag.to_le_bytes());
    plains.append(&mut body_json);
    vault
        .append_record(&plains)
        .map_err(PersistError::Crypto)?;
    report.records_written += 1;
    report.subsystem_tags.push(tag);
    Ok(())
}

fn write_bytes_subsystem(
    vault: &mut EncryptedVault,
    tag: u32,
    value: &[u8],
    report: &mut PersistReport,
) -> Result<(), PersistError> {
    let mut plains: Vec<u8> = vec![];
    plains.extend(tag.to_le_bytes());
    plains.extend_from_slice(value);
    vault
        .append_record(&plains)
        .map_err(PersistError::Crypto)?;
    report.records_written += 1;
    report.subsystem_tags.push(tag);
    Ok(())
}

fn decode_record_bytes(bytes: &[u8]) -> Option<LoadedSubsystem> {
    if bytes.len() < 4 {
        return None;
    }
    let tag = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    Some(LoadedSubsystem {
        tag,
        plaintext: bytes[4..].to_vec(),
    })
}

/// Restore a graph-store record written by graph_records.
fn restore_graph_state(
    state: &mut CerebrumState,
    bytes: &[u8],
) -> Result<(), String> {
    let value: serde_json::Value = serde_json::from_slice(bytes)
        .map_err(|e| e.to_string())?;

    let mut graph_store = cerebrum_graph::GraphStore::new();

    // Per-view restored first (their node sets + edges).
    for (json_name, view_type) in [
        ("semantic", cerebrum_core::QueryType::Semantic),
        ("temporal", cerebrum_core::QueryType::Temporal),
        ("causal", cerebrum_core::QueryType::Causal),
        ("entity", cerebrum_core::QueryType::Entity),
    ] {
        if let Some(edges) = value.get(json_name).and_then(|v| v.as_array()) {
            let view = graph_store.graph_for_mut(view_type).ok_or("view missing")?;
            for edge in edges {
                let source_json = edge.get("source").and_then(|v| v.as_str()).unwrap_or("");
                let target_json = edge.get("target").and_then(|v| v.as_str()).unwrap_or("");
                let ordinal = edge
                    .get("relation_ordinal")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(rust_ordinal_of_default(&view_type));
                let weight = edge.get("weight").and_then(|v| v.as_f64()).unwrap_or(0.5) as f32;
                let timestamp = edge
                    .get("timestamp")
                    .and_then(|v| v.as_str())
                    .and_then(parse_rfc3339);
                let relation_match = cerebrum_graph::Relation::from_relation_ordinal(ordinal as u32);
                if let (Some(source), Some(target), Some(relation)) = (
                    memory_id_from_hex(source_json),
                    memory_id_from_hex(target_json),
                    relation_match,
                ) {
                    let mut built = cerebrum_graph::Edge::new(source, target, relation, weight);
                    if let Some(marked) = timestamp {
                        built = built.with_timestamp(marked);
                    }
                    view.add_edge(built);
                }
            }
        }
    }

    // Labels last (their node registration effect rides add_label).
    let label_list = value
        .get("labels")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    for entry in label_list {
        let id_json = entry.get("id").and_then(|v| v.as_str()).unwrap_or("");
        let label = entry.get("label").and_then(|v| v.as_str()).unwrap_or("");
        if let Some(id) = memory_id_from_hex(id_json) {
            graph_store.add_label(id, label);
        }
    }

    // Alias groups restore as plain map inserts (no side effect needed).
    let alias_list = value
        .get("aliases")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let alias_pairs: Vec<(cerebrum_core::MemoryId, Vec<String>)> = alias_list
        .iter()
        .filter_map(|entry| {
            let id = entry
                .get("id")
                .and_then(|v| v.as_str())
                .and_then(memory_id_from_hex)?;
            let rows: Vec<String> = entry
                .get("aliases")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|row| row.as_str().map(|row_text| row_text.to_string()))
                        .collect()
                })
                .unwrap_or_default();
            Some((id, rows))
        })
        .collect();
    graph_store.restore_aliases(alias_pairs);

    state.graph_store = graph_store;
    Ok(())
}

fn rust_ordinal_of_default(view: &cerebrum_core::QueryType) -> u64 {
    match view {
        cerebrum_core::QueryType::Semantic => 0,
        cerebrum_core::QueryType::Temporal => 10,
        cerebrum_core::QueryType::Causal => 20,
        _ => 30,
    }
}

fn parse_rfc3339(raw_value: &str) -> Option<chrono::DateTime<chrono::Utc>> {
    chrono::DateTime::parse_from_rfc3339(raw_value)
        .map(|parsed| parsed.with_timezone(&chrono::Utc))
        .ok()
}

/// Context tree in entries-array form (entries map, hierarchy as text paths).
/// The tree re-derives its hierarchy on restore from every entry's
/// domain/topic/subtopic, which ContextTree::insert does natively.
fn context_tree_records(tree: &cerebrum_curate::ContextTree) -> serde_json::Value {
    let entry_json = tree
        .clone_entries()
        .iter()
        .map(|entry| serde_json::json!({
            "id": entry.id.to_string(),
            "domain": entry.domain,
            "topic": entry.topic,
            "subtopic": entry.subtopic,
            "content": entry.content,
            "relations": entry.relations.iter().map(|rel| serde_json::json!({
                "source": rel.source.to_string(),
                "target": rel.target.to_string(),
                "relation_type": rel.relation_type,
                "weight": rel.weight,
            })).collect::<Vec<_>>(),
            "provenance": {
                "source": entry.provenance.source,
                "confidence": entry.provenance.confidence,
                "timestamp": entry.provenance.timestamp.to_rfc3339(),
            },
            "rationale": entry.rationale,
            "lifecycle": format!("{:?}", entry.lifecycle),
            "created_at": entry.created_at.to_rfc3339(),
            "updated_at": entry.updated_at.to_rfc3339(),
        }))
        .collect::<Vec<_>>();

    serde_json::json!({ "entries": entry_json })
}

/// Map-safe decay engine records.
fn decay_records(engine: &cerebrum_decay::DecayEngine) -> serde_json::Value {
    serde_json::json!({
        "config": {
            "buffer_halflife_secs": engine.config.buffer_halflife.as_secs(),
            "episodic_halflife_secs": engine.config.episodic_halflife.as_secs(),
            "hibernation_threshold": engine.config.hibernation_threshold,
            "archive_threshold_days": engine.config.archive_threshold_days,
            "consolidation_trigger": engine.config.consolidation_trigger,
        },
        "entries": engine.entries.values().map(|e| serde_json::json!({
            "id": e.id.to_string(),
            "layer": format!("{:?}", e.layer),
            "content": e.content,
            "strength": e.strength,
            "last_accessed": e.last_accessed.to_rfc3339(),
            "created_at": e.created_at.to_rfc3339(),
            "access_count": e.access_count,
            "archived": e.archived,
        }))
        .collect::<Vec<_>>()
    })
}

/// Episodic store in string-entry form (episodes + related ids).
fn episodic_records(store: &cerebrum_episodic::EpisodicStore) -> serde_json::Value {
    serde_json::json!({
        "config": {
            "max_episodes": store.config.max_episodes,
            "valence_tracking": store.config.valence_tracking,
        },
        "episodes": store.episodes.values().map(|rich| serde_json::json!({
            "memory_id": rich.episode.memory_id.to_string(),
            "source": rich.episode.source,
            "trigger": rich.episode.trigger,
            "context": rich.episode.context,
            "discovered_at": rich.episode.discovered_at.to_rfc3339(),
            "valence": format!("{:?}", rich.valence),
            "before_context": rich.before_context,
            "after_context": rich.after_context,
            "related_episodes": rich.related_episodes.iter()
                .map(|id| id.to_string())
                .collect::<Vec<_>>(),
        }))
        .collect::<Vec<_>>()
    })
}

/// Cross-referencing engine state as entry records.
fn cross_reference_records(engine: &cerebrum_curate::CrossReferenceEngine) -> serde_json::Value {
    serde_json::json!({
        "reference_pairs": engine
            .all_forward_pairs()
            .iter()
            .map(|(source, target, confidence, source_tag, timestamp)| serde_json::json!({
                "source": source,
                "target": target,
                "confidence": confidence,
                "provenance_source": source_tag,
                "timestamp": timestamp.to_rfc3339(),
            }))
            .collect::<Vec<_>>()
    })
}

/// Event store state as string-key records, including enrichment map rows
/// (UUIDs stay strings throughout).
fn event_store_records(store: &cerebrum_events::EventStore) -> serde_json::Value {
    serde_json::json!({
        "events": store.events.iter().map(|event| serde_json::json!({
            "event_id": event.event_id.to_string(),
            "event_type": event.event_type,
            "memory_id": event.memory_id.to_string(),
            "payload": event.payload,
            "timestamp": event.timestamp.to_rfc3339(),
            "enrichment_status": format!("{:?}", event.enrichment_status),
        }))
        .collect::<Vec<_>>()
    })
}

/// Policy store as entry records (MemoryId keys as safe json strings).
fn policy_records(store: &cerebrum_policy::IndexedExperienceStore) -> serde_json::Value {
    serde_json::json!({
        "indices": store
            .list_indices()
            .iter()
            .map(|entry| serde_json::json!({
                "id": entry.id.to_string(),
                "summary": entry.summary,
                "artifact_id": entry.artifact_id.to_string(),
                "sections": entry.sections.iter()
                    .map(|(k, v)| serde_json::json!({ "name": k, "body": v }))
                    .collect::<Vec<_>>(),
                "token_count": entry.token_count,
                "relevance": entry.relevance,
                "created_at": entry.created_at.to_rfc3339(),
                // last_access is an update-time surface on restore.
                "updated_at": entry.last_accessed_at.to_rfc3339(),
            }))
            .collect::<Vec<_>>()
    })
}

/// Hot-cache JSON in an entries-array form (JSON map keys must be strings
/// while MemoryId keys serialize as byte arrays).
fn hot_cache_records(cache: &HashMap<MemoryId, String>) -> serde_json::Value {
    serde_json::json!({
        "entries": cache.iter().map(|(id, content)| serde_json::json!({
            "id": id.to_string(),
            "content": content
        })).collect::<Vec<_>>()
    })
}

/// Prefetch engine record shape, over its entry list.
fn graph_records(store: &cerebrum_graph::GraphStore) -> serde_json::Value {
    // Labels are (MemoryId, String); emit as string-keyed safe entries.
    let labels = store
        .labels_entries()
        .map(|(id, label)| {
            serde_json::json!({ "id": id.to_string(), "label": label })
        })
        .collect::<Vec<_>>();
    // Alias groups carry (canonical id hex, [alternate spelling rows]).
    let aliases = store
        .aliases_all()
        .into_iter()
        .map(|(id, rows)| serde_json::json!({ "id": id.to_string(), "aliases": rows }))
        .collect::<Vec<_>>();
    let views = serde_json::json!({
        "labels": labels,
        "aliases": aliases,
        // Per-view edge tuples: (source hex, target hex, u32 edge-type ordinal,
        // relation name, weight, rfc3339 timestamp, metadata kv rows).
        "semantic": view_edges(&store.semantic),
        "temporal": view_edges(&store.temporal),
        "causal": view_edges(&store.causal),
        "entity": view_edges(&store.entity),
    });
    views
}

fn view_edges(view: &cerebrum_graph::Graph) -> serde_json::Value {
    let list = view
        .all_edges()
        .map(|edge| {
            serde_json::json!({
                "source": edge.source.to_string(),
                "target": edge.target.to_string(),
                "relation": edge.relation.relation_name(),
                "relation_ordinal": edge.relation.relation_ordinal(),
                "weight": edge.weight,
                "timestamp": edge.timestamp.to_rfc3339(),
                "metadata": edge.metadata.iter()
                    .map(|(k, v)| serde_json::json!({"key": k, "value": v}))
                    .collect::<Vec<_>>(),
            })
        })
        .collect::<Vec<serde_json::Value>>();
    serde_json::Value::Array(list)
}

/// Prefetch engine state in entries-array form.
fn prefetch_records(engine: &cerebrum_prefetch::PrefetchEngine) -> serde_json::Value {
    serde_json::json!({
        "entries": engine.cache.iter().map(|(id, content)| serde_json::json!({
            "id": id.to_string(),
            "content": content
        })).collect::<Vec<_>>(),
        "hit_count": engine.hit_count,
        "miss_count": engine.miss_count
    })
}

// ============================================================================
// Server-side main wiring
// ============================================================================

/// Load into CerebrumState. Engine fields that need no restoration stay at
/// defaults (hot cache etc.) only when their record types don't exist.
pub fn restore_state(
    state: &mut CerebrumState,
    data_dir: PathBuf,
    passphrase: &str,
) -> Result<(), PersistError> {
    let persist = CerebrumPersistence::new(data_dir.clone());
    let loaded = persist.load_all(passphrase)?;
    if loaded.empty {
        state.data_dir = data_dir;
        return Ok(());
    }

    if let Some(bytes) = &loaded.context_tree_bytes {
        state.context_tree = restore_context_tree_state(bytes)?;
    }
    if let Some(bytes) = &loaded.decay_bytes {
        state.decay_engine = restore_decay_state(bytes)?;
    }
    if let Some(bytes) = &loaded.graph_bytes {
        restore_graph_state(state, bytes).map_err(PersistError::GraphRecord)?;
    }
    if let Some(bytes) = &loaded.episodic_bytes {
        state.episodic_store = restore_episodic_state(bytes)?;
    }
    if let Some(bytes) = &loaded.cross_ref_bytes {
        state.cross_ref = restore_cross_reference_state(bytes)?;
    }
    if let Some(bytes) = &loaded.event_store_bytes {
        state.event_store = restore_event_store_state(bytes)?;
    }
    if let Some(bytes) = &loaded.embeddings_bytes {
        let state_snapshot: cerebrum_embeddings::EmbeddingState = serde_json::from_slice(bytes)
            .map_err(|e| PersistError::Serialize { reason: e.to_string() })?;
        state.embedding_engine = cerebrum_embeddings::EmbeddingEngine::from_persistable(state_snapshot);
    }
    if let Some(bytes) = &loaded.policy_bytes {
        state.experience_store = restore_policy_state(bytes)?;
    }
    if let Some(bytes) = &loaded.hamming_bytes {
        restore_hamming_state(state, bytes)?;
    }
    if let Some(bytes) = &loaded.dwm_bytes {
        // DWM loads through DwmPersistence's byte parser, staged via a hidden
        // temp file: write the vault-decrypted bytes, load, then zeroize the
        // staged copy before removal (no plaintext residue beyond the window).
        let tmp = data_dir.join(".dwmrestore.bin.tmp");
        std::fs::write(&tmp, bytes)
            .map_err(|e| PersistError::Io { reason: e.to_string() })?;
        let dwm = cerebrum_dwm::DwmPersistence::load(&tmp)
            .map_err(|e| PersistError::Dwm(format!("{:?}", e)))?;
        let size = bytes.len();
        std::fs::write(&tmp, vec![0u8; size])
            .map_err(|e| PersistError::Io { reason: e.to_string() })?;
        let _ = std::fs::remove_file(&tmp);
        state.dwm = dwm;
    }
    if let Some(bytes) = &loaded.hot_cache_bytes {
        let json_value: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|e| PersistError::Serialize { reason: e.to_string() })?;
        let entry_list = json_value
            .get("entries")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        for entry in entry_list {
            let id = entry.get("id").and_then(|v| v.as_str()).and_then(memory_id_from_hex);
            let content = entry.get("content").and_then(|v| v.as_str());
            if let (Some(id), Some(content)) = (id, content) {
                state.query_engine.hot_cache.insert(id, content.to_string());
                state.hot_cache_insert_wide(id, content.to_string());
            }
        }
    }
    if let Some(bytes) = &loaded.prefetch_bytes {
        let json_value: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|e| PersistError::Serialize { reason: e.to_string() })?;
        let entry_list = json_value
            .get("entries")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        for entry in entry_list {
            let id = entry.get("id").and_then(|v| v.as_str()).and_then(memory_id_from_hex);
            let content = entry.get("content").and_then(|v| v.as_str());
            if let (Some(id), Some(content)) = (id, content) {
                state.prefetch_engine.register(id, content.to_string());
            }
        }
        if let Some(hit_count) = json_value.get("hit_count").and_then(|v| v.as_u64()) {
            state.prefetch_engine.hit_count = hit_count;
        }
        if let Some(miss_count) = json_value.get("miss_count").and_then(|v| v.as_u64()) {
            state.prefetch_engine.miss_count = miss_count;
        }
    }
    if let Some(bytes) = &loaded.last_id_bytes {
        let json_value: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|e| PersistError::Serialize { reason: e.to_string() })?;
        if let Some(id_text) = json_value.get("last_curated_id").and_then(|x| x.as_str()) {
            state.last_curated_id = memory_id_from_hex(id_text);
        }
    }
    if let Some(bytes) = &loaded.wal_bytes {
        let wal_restored: cerebrum_storage::WriteAheadLog = serde_json::from_slice(bytes)
            .map_err(|e| PersistError::Serialize { reason: e.to_string() })?;
        state.wal = wal_restored;
    }
    state.data_dir = data_dir;

    Ok(())
}

fn restore_hamming_state(state: &mut CerebrumState, bytes: &[u8]) -> Result<(), PersistError> {
    let value: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|e| PersistError::Serialize {
            reason: e.to_string(),
        })?;
    let entries = value
        .get("entries")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    for entry in entries {
        let id: Option<MemoryId> =
            entry
                .get("id")
                .and_then(|v| v.as_str())
                .and_then(memory_id_from_hex);
        let sig: Option<BinarySignature> = entry
            .get("signature")
            .and_then(|v| v.as_array())
            .and_then(|arr| {
                let bytes_result: Vec<u8> = arr
                    .iter()
                    .filter_map(|v| v.as_u64().map(|x| x as u8))
                    .collect();
                arrays_match_32(&bytes_result).map(BinarySignature)
            });
        if let (Some(id), Some(sig)) = (id, sig) {
            state.signature_engine.add_to_index(id, sig);
        }
    }
    Ok(())
}

/// Restore the ContextTree from its entries-array record; the tree rebuilds
/// hierarchy on insert, exactly like a fresh curation session.
fn restore_context_tree_state(
    bytes: &[u8],
) -> Result<cerebrum_curate::ContextTree, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize { reason: e.to_string() }
    })?;
    let entries_value = value
        .get("entries")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();

    let mut tree = cerebrum_curate::ContextTree::new();
    for entry in entries_value {
        let memory_id = entry
            .get("id")
            .and_then(|v| v.as_str())
            .and_then(memory_id_from_hex);
        let domain = entry
            .get("domain")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let topic = entry
            .get("topic")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let subtopic = entry
            .get("subtopic")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let content = entry
            .get("content")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let relations: Vec<cerebrum_curate::Relation> = entry
            .get("relations")
            .and_then(|v| v.as_array())
            .map(|list| {
                let mapped_results: Vec<Option<cerebrum_curate::Relation>> = list
                    .iter()
                    .map(|rel_json| build_rel_opt(rel_json))
                    .collect();
                mapped_results.into_iter().flatten().collect()
            })
            .unwrap_or_default();

        if memory_id.is_none() {
            continue;
        }
        let mut rebuilt_entry = cerebrum_curate::ContextEntry::new(
            memory_id.unwrap(),
            domain,
            topic,
            subtopic,
            content,
        );
        rebuilt_entry.relations = relations;
        rebuilt_entry.provenance = deserialize_provenance(&entry);
        rebuilt_entry.rationale = entry
            .get("rationale")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        if let Some(lifecycle_from) = deserialize_lifecycle(&entry) {
            rebuilt_entry.lifecycle = lifecycle_from;
        }
        if let Some(bound_created) = deserialize_bound(&entry, "created_at") {
            rebuilt_entry.created_at = bound_created;
        }
        tree.insert(rebuilt_entry);
    }
    Ok(tree)
}

fn build_rel_opt(rel_json: &serde_json::Value) -> Option<cerebrum_curate::Relation> {
    Some(cerebrum_curate::Relation {
        source: memory_id_from_hex(rel_json.get("source")?.as_str()?)?,
        target: memory_id_from_hex(rel_json.get("target")?.as_str()?)?,
        relation_type: rel_json.get("relation_type")?.as_str()?.to_string(),
        weight: rel_json.get("weight")?.as_f64()? as f32,
    })
}

fn deserialize_provenance(entry: &serde_json::Value) -> cerebrum_curate::Provenance {
    cerebrum_curate::Provenance {
        source: entry
            .get("provenance")
            .and_then(|x| x.get("source"))
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .to_string(),
        confidence: entry
            .get("provenance")
            .and_then(|x| x.get("confidence"))
            .and_then(|x| x.as_f64())
            .unwrap_or(0.0) as f32,
        timestamp: entry
            .get("provenance")
            .and_then(|x| x.get("timestamp"))
            .and_then(|x| x.as_str())
            .and_then(parse_rfc3339)
            .unwrap_or_else(chrono::Utc::now),
    }
}

fn deserialize_lifecycle(
    entry: &serde_json::Value,
) -> Option<cerebrum_curate::EntryLifecycle> {
    let word = entry.get("lifecycle").and_then(|x| x.as_str())?;
    Some(match word {
        "Active" => cerebrum_curate::EntryLifecycle::Active,
        "Hibernated" => cerebrum_curate::EntryLifecycle::Hibernated,
        "Archived" => cerebrum_curate::EntryLifecycle::Archived,
        _ => return None,
    })
}

fn deserialize_bound(entry: &serde_json::Value, key: &str) -> Option<chrono::DateTime<chrono::Utc>> {
    entry
        .get(key)
        .and_then(|x| x.as_str())
        .and_then(parse_rfc3339)
}

/// Restore the DecayEngine from its JSON record.
fn restore_decay_state(bytes: &[u8]) -> Result<cerebrum_decay::DecayEngine, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize { reason: e.to_string() }
    })?;
    let mut engine = cerebrum_decay::DecayEngine::default();
    if let Some(config_section) = value.get("config") {
        engine.config.hibernation_threshold = config_section
            .get("hibernation_threshold")
            .and_then(|x| x.as_f64())
            .unwrap_or(0.01) as f32;
        engine.config.archive_threshold_days = config_section
            .get("archive_threshold_days")
            .and_then(|x| x.as_u64())
            .unwrap_or(90) as u32;
        engine.config.consolidation_trigger = config_section
            .get("consolidation_trigger")
            .and_then(|x| x.as_u64())
            .unwrap_or(100) as usize;
        engine.config.buffer_halflife =
            std::time::Duration::from_secs(config_section
                .get("buffer_halflife_secs")
                .and_then(|x| x.as_u64())
                .unwrap_or(3600));
        engine.config.episodic_halflife =
            std::time::Duration::from_secs(
                config_section
                    .get("episodic_halflife_secs")
                    .and_then(|x| x.as_u64())
                    .unwrap_or(60 * 60 * 24 * 30),
            );
    }
    if let Some(entries_value) = value.get("entries").and_then(|x| x.as_array()) {
        for e in entries_value {
            // Each engine layer comes back as DecayEntry with lifecycle kept.
            let id = e.get("id").and_then(|x| x.as_str()).and_then(memory_id_from_hex);
            if id.is_none() {
                continue;
            }
            let layer = deserialize_memory_layer(e.get("layer"));
            let content = e.get("content").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let strength = e.get("strength").and_then(|x| x.as_f64()).unwrap_or(1.0) as f32;
            let created_at = deserialize_bound(e, "created_at").unwrap_or_else(chrono::Utc::now);
            let last_accessed = deserialize_bound(e, "last_accessed").unwrap_or_else(chrono::Utc::now);
            let access_count = e
                .get("access_count")
                .and_then(|x| x.as_u64())
                .unwrap_or(0) as u32;
            let archived_status = deserialize_decay_archived(e.get("archived"));

            let restored = build_decay_entry_restored(
                id.unwrap(),
                layer,
                content,
                strength,
                last_accessed,
                created_at,
                access_count,
                archived_status,
            );
            engine.entries.insert(restored.id, restored);
        }
    }
    Ok(engine)
}

fn deserialize_memory_layer(layer_value: Option<&serde_json::Value>) -> cerebrum_decay::MemoryLayer {
    match layer_value.and_then(|x| x.as_str()) {
        Some("Semantic") => cerebrum_decay::MemoryLayer::Semantic,
        Some("Episodic") => cerebrum_decay::MemoryLayer::Episodic,
        Some(_) | None => cerebrum_decay::MemoryLayer::Buffer,
    }
}

fn deserialize_decay_archived(marker: Option<&serde_json::Value>) -> bool {
    marker.and_then(|x| x.as_bool()).unwrap_or(false)
}

/// Build a decay engine's fully-populated entries store row.
fn build_decay_entry_restored(
    id: MemoryId,
    layer: cerebrum_decay::MemoryLayer,
    content: String,
    strength: f32,
    last_accessed: chrono::DateTime<chrono::Utc>,
    created_at: chrono::DateTime<chrono::Utc>,
    access_count: u32,
    archived: bool,
) -> cerebrum_decay::DecayEntry {
    let mut entry = cerebrum_decay::DecayEntry::new(id, layer, content);
    entry.strength = strength;
    entry.last_accessed = last_accessed;
    entry.created_at = created_at;
    entry.access_count = access_count;
    entry.archived = archived;
    entry
}

/// Restore the episodic state from its JSON record.
fn restore_episodic_state(
    bytes: &[u8],
) -> Result<cerebrum_episodic::EpisodicStore, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize { reason: e.to_string() }
    })?;
    let mut store = cerebrum_episodic::EpisodicStore::default();
    if let Some(max) = value.get("config").and_then(|v| v.get("max_episodes")).and_then(|x| x.as_u64()) {
        store.config.max_episodes = max as usize;
    }
    if let Some(valence) = value.get("config").and_then(|v| v.get("valence_tracking")).and_then(|x| x.as_bool()) {
        store.config.valence_tracking = valence;
    }
    if let Some(episode_rows) = value.get("episodes").and_then(|x| x.as_array()) {
        for episode_row in episode_rows {
            let memory_id_opt = episode_row
                .get("memory_id")
                .and_then(|x| x.as_str())
                .and_then(memory_id_from_hex);
            if memory_id_opt.is_none() {
                continue;
            }
            let memory_id = memory_id_opt.unwrap();
            let source = episode_row
                .get("source")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let trigger = episode_row
                .get("trigger")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let context = episode_row
                .get("context")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let discovered_at =
                deserialize_bound(episode_row, "discovered_at").unwrap_or_else(chrono::Utc::now);
            let before_context = episode_row
                .get("before_context")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let after_context = episode_row
                .get("after_context")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            let related = episode_row
                .get("related_episodes")
                .and_then(|x| x.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|id_json| {
                            id_json.as_str().and_then(memory_id_from_hex)
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            let valence_text = episode_row
                .get("valence")
                .and_then(|x| x.as_str())
                .unwrap_or("Routine");
            let reconstructed_episode = cerebrum_core::Episode {
                memory_id,
                source,
                trigger,
                context,
                discovered_at,
            };
            serde_insert_rich_episode(
                &mut store,
                reconstructed_episode.clone(),
                rebuild_rich_episode(
                    reconstructed_episode,
                    before_context,
                    after_context,
                    related,
                    episodic_valence_of(valence_text),
                ),
            );
        }
    }
    Ok(store)
}

/// The engine's exact rich-episode restore surface.
fn episodic_valence_of(
    as_lower: &str,
) -> cerebrum_episodic::EmotionalValence {
    match as_lower {
        "Exciting" => cerebrum_episodic::EmotionalValence::Exciting,
        "Concerning" => cerebrum_episodic::EmotionalValence::Concerning,
        "Surprising" => cerebrum_episodic::EmotionalValence::Surprising,
        _ => cerebrum_episodic::EmotionalValence::Routine,
    }
}

fn serde_insert_rich_episode(
    store: &mut cerebrum_episodic::EpisodicStore,
    episode: cerebrum_core::Episode,
    rich: cerebrum_episodic::RichEpisode,
) {
    // The API stores (Episode, RichEpisode) as its record/restore channel
    // (matches its `record` API signature).
    store.record(episode, rich);
}

/// Build the fully-restored rich episode row (carries its own episode).
fn rebuild_rich_episode(
    reconstituted: cerebrum_core::Episode,
    before_context: String,
    after_context: String,
    related_episodes: Vec<MemoryId>,
    valence: cerebrum_episodic::EmotionalValence,
) -> cerebrum_episodic::RichEpisode {
    cerebrum_episodic::RichEpisode {
        episode: reconstituted,
        valence,
        before_context,
        after_context,
        related_episodes,
    }
}

/// Restore the event store state from its string-shaped event records.
fn restore_event_store_state(
    bytes: &[u8],
) -> Result<cerebrum_events::EventStore, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize { reason: e.to_string() }
    })?;
    let mut store = cerebrum_events::EventStore::default();
    if let Some(events) = value.get("events").and_then(|x| x.as_array()) {
        for event_row in events {
            let event_id = event_row
                .get("event_id")
                .and_then(|x| x.as_str())
                .and_then(|s| uuid::Uuid::parse_str(s).ok());
            let memory_id_opt = event_row
                .get("memory_id")
                .and_then(|x| x.as_str())
                .and_then(memory_id_from_hex);
            if event_id.is_none() || memory_id_opt.is_none() {
                continue;
            }
            let payload = event_row
                .get("payload")
                .cloned()
                .unwrap_or(serde_json::json!({}));
            store.append_event(cerebrum_core::Event {
                event_id: event_id.unwrap(),
                event_type: event_row
                    .get("event_type")
                    .and_then(|x| x.as_str())
                    .unwrap_or("memory.created")
                    .to_string(),
                memory_id: memory_id_opt.unwrap(),
                payload,
                timestamp: deserialize_bound(event_row, "timestamp")
                    .unwrap_or_else(chrono::Utc::now),
                enrichment_status: restore_enrichment_status(
                    event_row.get("enrichment_status"),
                ),
            });
        }
    }
    Ok(store)
}

fn restore_enrichment_status(
    status_value: Option<&serde_json::Value>,
) -> cerebrum_core::EnrichmentStatus {
    match status_value.map(|x| x.as_str()).flatten() {
        Some("Enriched") => cerebrum_core::EnrichmentStatus::Enriched,
        Some("Failed") => cerebrum_core::EnrichmentStatus::Failed,
        Some("DeadLetter") => cerebrum_core::EnrichmentStatus::DeadLetter,
        _ => cerebrum_core::EnrichmentStatus::Pending,
    }
}

/// Restore the policy store from its index entry records.
fn restore_policy_state(
    bytes: &[u8],
) -> Result<cerebrum_policy::IndexedExperienceStore, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize
        {
            reason: e.to_string(),
        }
    })?;
    let mut store = cerebrum_policy::IndexedExperienceStore::new();
    if let Some(relevance_value) = get_policy_sections(&value) {
        for row in relevance_value {
            let id = row
                .get("id")
                .and_then(|x| x.as_str())
                .and_then(memory_id_from_hex);
            let artifact = row
                .get("artifact_id")
                .and_then(|x| x.as_str())
                .and_then(memory_id_from_hex);
            let summary = row
                .get("summary")
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            if id.is_none() || artifact.is_none() {
                continue;
            }
            let mut built = cerebrum_policy::IndexEntry::new(
                id.unwrap(),
                summary,
                artifact.unwrap(),
                row.get("token_count")
                    .and_then(|x| x.as_u64())
                    .unwrap_or(0) as usize,
            );
            built.relevance = row
                .get("relevance")
                .and_then(|x| x.as_f64())
                .unwrap_or(0.5) as f32;
            if let Some(created) = deserialize_bound(row, "created_at") {
                built.created_at = created;
            }
            if let Some(last_used) = deserialize_bound(row, "updated_at") {
                built.last_accessed_at = last_used;
            }
            if let Some(section_list) = row.get("sections").and_then(|x| x.as_array()) {
                for section_row in section_list {
                    let name = section_row.get("name").and_then(|s| s.as_str()).unwrap_or("");
                    let content = section_row.get("body").and_then(|s| s.as_str()).unwrap_or("");
                    built.sections.insert(name.to_string(), content.to_string());
                }
            }
            store.create_index(built);
        }
    }
    Ok(store)
}

/// The policy rows list access.
fn get_policy_sections(value: &serde_json::Value) -> Option<&Vec<serde_json::Value>> {
    value.get("indices").and_then(|x| x.as_array())
}

/// Restore cross-references from entry records.
fn restore_cross_reference_state(
    bytes: &[u8],
) -> Result<cerebrum_curate::CrossReferenceEngine, PersistError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|e| {
        PersistError::Serialize { reason: e.to_string() }
    })?;
    let mut engine = cerebrum_curate::CrossReferenceEngine::new();
    if let Some(pairs) = value.get("reference_pairs").and_then(|x| x.as_array()) {
        for pair in pairs {
            let source = pair.get("source").and_then(|v| v.as_str()).and_then(memory_id_from_hex);
            let target = pair.get("target").and_then(|v| v.as_str()).and_then(memory_id_from_hex);
            if source.is_none() || target.is_none() {
                continue;
            }
            let confidence = pair
                .get("confidence")
                .and_then(|x| x.as_f64())
                .unwrap_or(CROSS_REF_RESTORE_CONFIDENCE) as f32;
            let provenance_tag = pair
                .get("provenance_source")
                .and_then(|x| x.as_str())
                .unwrap_or("restored");
            engine.add_bidirectional(
                source.unwrap(),
                target.unwrap(),
                cerebrum_curate::Provenance {
                    source: provenance_tag.to_string(),
                    confidence,
                    timestamp: deserialize_bound(pair, "timestamp")
                        .unwrap_or_else(chrono::Utc::now),
                },
            );
        }
    }
    Ok(engine)
}

const CROSS_REF_RESTORE_CONFIDENCE: f64 = 0.6;

fn arrays_match_32(bytes: &[u8]) -> Option<[u8; 32]> {
    if bytes.len() != 32 {
        return None;
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    Some(out)
}

/// Hamming index dump in string-id entries array form.
fn build_hamming_dump(state: &CerebrumState) -> serde_json::Value {
    let pairs = state
        .signature_engine
        .searcher()
        .entries()
        .iter()
        .map(|(id, sig)| serde_json::json!({ "id": id.to_string(), "signature": sig.0.to_vec() }))
        .collect::<Vec<_>>();
    serde_json::json!({ "entries": pairs })
}

fn memory_id_from_hex(hex_value: &str) -> Option<MemoryId> {
    let hex_lower = hex_value.to_lowercase();
    if hex_lower.len() != 64 {
        return None;
    }
    let mut bytes = [0u8; 32];
    for (index, byte_pair) in hex_lower.as_bytes().chunks(2).enumerate() {
        if byte_pair.len() != 2 {
            return None;
        }
        let high = (byte_pair[0] as char).to_digit(16)?;
        let low = (byte_pair[1] as char).to_digit(16)?;
        bytes[index] = (high as u8) * 16 + low as u8;
    }
    Some(MemoryId(bytes))
}