//! Cerebrum Storage — persistence layer abstraction.
//!
//! Provides a trait-based storage engine so the concrete backend (RocksDB,
//! in-memory, etc.) can be swapped without changing callers. Also provides an
//! in-memory engine for development and an in-process WAL / snapshot manager
//! for crash-recovery semantics.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// Abstraction over a key/value persistence engine.
pub trait StorageEngine: Send + Sync {
    /// Store a key/value pair.
    fn put(&mut self, key: Vec<u8>, value: Vec<u8>);

    /// Retrieve a value by key, if present.
    fn get(&self, key: &[u8]) -> Option<Vec<u8>>;

    /// Remove a key from the store.
    fn delete(&mut self, key: &[u8]);

    /// Return all key/value pairs whose key starts with `prefix`.
    fn scan(&self, prefix: &[u8]) -> Vec<(Vec<u8>, Vec<u8>)>;

    /// Return the number of entries in the store.
    fn count(&self) -> usize;

    /// Ensure all pending writes are durable.
    fn flush(&mut self);
}

/// A single operation recorded in the write-ahead log.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WalEntry {
    pub sequence: u64,
    pub operation: WalOp,
    pub key: Vec<u8>,
    pub value: Vec<u8>,
    pub timestamp: DateTime<Utc>,
}

impl WalEntry {
    pub fn new(sequence: u64, operation: WalOp, key: Vec<u8>, value: Vec<u8>) -> Self {
        Self {
            sequence,
            operation,
            key,
            value,
            timestamp: Utc::now(),
        }
    }
}

/// Operation kind for a WAL entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum WalOp {
    Put,
    Delete,
}

/// In-memory write-ahead log.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct WriteAheadLog {
    entries: Vec<WalEntry>,
    sequence_counter: u64,
}

impl WriteAheadLog {
    pub fn new() -> Self {
        Self::default()
    }

    /// Append a `Put` operation and return its sequence number.
    pub fn append_put(&mut self, key: Vec<u8>, value: Vec<u8>) -> u64 {
        let seq = self.next_sequence();
        self.entries.push(WalEntry::new(seq, WalOp::Put, key, value));
        seq
    }

    /// Append a `Delete` operation and return its sequence number.
    pub fn append_delete(&mut self, key: Vec<u8>) -> u64 {
        let seq = self.next_sequence();
        self.entries.push(WalEntry::new(seq, WalOp::Delete, key, Vec::new()));
        seq
    }

    /// Replay all entries against a storage engine, reconstructing state.
    pub fn replay<E: StorageEngine>(&self, engine: &mut E) {
        for entry in &self.entries {
            match entry.operation {
                WalOp::Put => engine.put(entry.key.clone(), entry.value.clone()),
                WalOp::Delete => engine.delete(&entry.key),
            }
        }
    }

    /// Truncate the WAL after creating a checkpoint.
    pub fn checkpoint(&mut self) -> Vec<WalEntry> {
        let taken = std::mem::take(&mut self.entries);
        taken
    }

    /// Recover from a serialized snapshot plus any WAL entries that occurred
    /// after the snapshot was taken.
    pub fn recover<E: StorageEngine>(
        &self,
        engine: &mut E,
        snapshot_data: &HashMap<Vec<u8>, Vec<u8>>,
    ) {
        for (key, value) in snapshot_data {
            engine.put(key.clone(), value.clone());
        }
        self.replay(engine);
    }

    /// Number of entries in the WAL.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    fn next_sequence(&mut self) -> u64 {
        self.sequence_counter += 1;
        self.sequence_counter
    }
}

/// A point-in-time snapshot of storage contents.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub data: Vec<u8>,
    pub metadata: HashMap<String, String>,
}

impl Snapshot {
    pub fn new<S: Into<String>>(snapshot_id: S, data: Vec<u8>) -> Self {
        Self {
            snapshot_id: snapshot_id.into(),
            timestamp: Utc::now(),
            data,
            metadata: HashMap::new(),
        }
    }
}

/// Configuration for snapshot retention.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SnapshotConfig {
    pub max_snapshots: usize,
    pub snapshot_interval_secs: u64,
}

impl Default for SnapshotConfig {
    fn default() -> Self {
        Self {
            max_snapshots: 5,
            snapshot_interval_secs: 3600,
        }
    }
}

/// Manages a bounded list of snapshots.
#[derive(Debug, Clone, Default)]
pub struct SnapshotManager {
    snapshots: Vec<Snapshot>,
    config: SnapshotConfig,
}

impl SnapshotManager {
    pub fn new(config: SnapshotConfig) -> Self {
        Self {
            snapshots: Vec::new(),
            config,
        }
    }

    pub fn with_default_config() -> Self {
        Self::new(SnapshotConfig::default())
    }

    /// Add a snapshot, removing the oldest one if the limit is exceeded.
    pub fn add_snapshot(&mut self, snapshot: Snapshot) {
        self.snapshots.push(snapshot);
        if self.snapshots.len() > self.config.max_snapshots {
            self.snapshots.remove(0);
        }
    }

    /// Return all retained snapshots, oldest first.
    pub fn snapshots(&self) -> &[Snapshot] {
        &self.snapshots
    }

    pub fn config(&self) -> SnapshotConfig {
        self.config
    }
}

/// In-memory key/value store implementing `StorageEngine`.
#[derive(Debug, Clone, Default)]
pub struct InMemoryStorage {
    data: HashMap<Vec<u8>, Vec<u8>>,
}

impl InMemoryStorage {
    pub fn new() -> Self {
        Self::default()
    }

    /// Return a copy of the entire dataset for snapshotting.
    pub fn snapshot_data(&self) -> HashMap<Vec<u8>, Vec<u8>> {
        self.data.clone()
    }
}

impl StorageEngine for InMemoryStorage {
    fn put(&mut self, key: Vec<u8>, value: Vec<u8>) {
        self.data.insert(key, value);
    }

    fn get(&self, key: &[u8]) -> Option<Vec<u8>> {
        self.data.get(key).cloned()
    }

    fn delete(&mut self, key: &[u8]) {
        self.data.remove(key);
    }

    fn scan(&self, prefix: &[u8]) -> Vec<(Vec<u8>, Vec<u8>)> {
        self.data
            .iter()
            .filter(|(k, _)| k.starts_with(prefix))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect()
    }

    fn count(&self) -> usize {
        self.data.len()
    }

    fn flush(&mut self) {
        // In-memory store is always consistent.
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_put_and_get() {
        let mut store = InMemoryStorage::new();
        store.put(b"key1".to_vec(), b"value1".to_vec());
        assert_eq!(store.get(b"key1"), Some(b"value1".to_vec()));
    }

    #[test]
    fn test_delete() {
        let mut store = InMemoryStorage::new();
        store.put(b"key1".to_vec(), b"value1".to_vec());
        store.delete(b"key1");
        assert_eq!(store.get(b"key1"), None);
        assert_eq!(store.count(), 0);
    }

    #[test]
    fn test_scan_prefix() {
        let mut store = InMemoryStorage::new();
        store.put(b"prefix:a".to_vec(), b"1".to_vec());
        store.put(b"prefix:b".to_vec(), b"2".to_vec());
        store.put(b"other:c".to_vec(), b"3".to_vec());

        let mut results = store.scan(b"prefix:");
        results.sort_by(|a, b| a.0.cmp(&b.0));

        assert_eq!(results.len(), 2);
        assert_eq!(results[0].0, b"prefix:a".to_vec());
        assert_eq!(results[0].1, b"1".to_vec());
        assert_eq!(results[1].0, b"prefix:b".to_vec());
        assert_eq!(results[1].1, b"2".to_vec());
    }

    #[test]
    fn test_wal_append_and_replay() {
        let mut wal = WriteAheadLog::new();
        wal.append_put(b"k1".to_vec(), b"v1".to_vec());
        wal.append_put(b"k2".to_vec(), b"v2".to_vec());
        wal.append_delete(b"k1".to_vec());

        let mut store = InMemoryStorage::new();
        wal.replay(&mut store);

        assert_eq!(store.get(b"k1"), None);
        assert_eq!(store.get(b"k2"), Some(b"v2".to_vec()));
    }

    #[test]
    fn test_wal_checkpoint_and_recover() {
        let mut wal = WriteAheadLog::new();
        wal.append_put(b"k1".to_vec(), b"v1".to_vec());

        let mut store = InMemoryStorage::new();
        wal.replay(&mut store);
        let snapshot_data = store.snapshot_data();

        wal.checkpoint();
        wal.append_put(b"k2".to_vec(), b"v2".to_vec());

        let mut recovered = InMemoryStorage::new();
        wal.recover(&mut recovered, &snapshot_data);

        assert_eq!(recovered.get(b"k1"), Some(b"v1".to_vec()));
        assert_eq!(recovered.get(b"k2"), Some(b"v2".to_vec()));
    }

    #[test]
    fn test_snapshot_rotation() {
        let config = SnapshotConfig {
            max_snapshots: 3,
            snapshot_interval_secs: 3600,
        };
        let mut manager = SnapshotManager::new(config);

        for i in 0..5 {
            manager.add_snapshot(Snapshot::new(format!("snap-{}", i), vec![i as u8]));
        }

        assert_eq!(manager.snapshots().len(), 3);
        assert_eq!(manager.snapshots()[0].snapshot_id, "snap-2");
        assert_eq!(manager.snapshots()[2].snapshot_id, "snap-4");
    }
}
