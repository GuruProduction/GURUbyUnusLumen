//! Cerebrum Decay — Biological memory model (System A from the Hippo Memory pattern).
//!
//! Memories live in one of three layers: Buffer (working memory), Episodic
//! (specific events), and Semantic (generalized knowledge). Strength decays
//! with time but strengthens on access. Hibernation and archival keep the
//! No-Delete principle: memories are never deleted, only retired.

use std::collections::HashMap;
use std::time::Duration;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

pub use cerebrum_core::MemoryId;

// ============================================================================
// 1. MEMORY LAYER
// ============================================================================

/// Biological memory layer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum MemoryLayer {
    /// Working memory — fast access, high turnover, default half-life 1 hour.
    Buffer,
    /// Specific events with full context, default half-life 30 days.
    Episodic,
    /// Generalized knowledge, permanent until archived.
    Semantic,
}

// ============================================================================
// 2. CONFIGURATION
// ============================================================================

/// Tunable parameters for decay and consolidation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DecayConfig {
    pub buffer_halflife: Duration,
    pub episodic_halflife: Duration,
    pub hibernation_threshold: f32,
    pub archive_threshold_days: u32,
    pub consolidation_trigger: usize,
}

impl Default for DecayConfig {
    fn default() -> Self {
        Self {
            buffer_halflife: Duration::from_secs(60 * 60),             // 1 hour
            episodic_halflife: Duration::from_secs(60 * 60 * 24 * 30), // 30 days
            hibernation_threshold: 0.01,
            archive_threshold_days: 90,
            consolidation_trigger: 100,
        }
    }
}

// ============================================================================
// 3. DECAY ENTRY
// ============================================================================

/// A single memory tracked by the decay engine.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DecayEntry {
    pub id: MemoryId,
    pub layer: MemoryLayer,
    pub content: String,
    pub strength: f32,
    pub last_accessed: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub access_count: u32,
    /// True once the memory has been moved to long-term cold storage.
    pub archived: bool,
}

impl DecayEntry {
    pub fn new(id: MemoryId, layer: MemoryLayer, content: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            id,
            layer,
            content: content.into(),
            strength: 1.0,
            last_accessed: now,
            created_at: now,
            access_count: 0,
            archived: false,
        }
    }

    /// Whether the entry is currently hibernating.
    pub fn is_hibernating(&self, threshold: f32) -> bool {
        self.strength < threshold
    }

    /// Whether the entry can be archived based on age and hibernation.
    pub fn is_archivable(&self, threshold_days: u32, hibernation_threshold: f32) -> bool {
        self.is_hibernating(hibernation_threshold)
            && days_since(self.last_accessed) >= threshold_days as f64
    }
}

// ============================================================================
// 4. DECAY ENGINE
// ============================================================================

/// Owns all decay entries and runs decay/consolidation cycles.
#[derive(Debug, Clone, Default)]
pub struct DecayEngine {
    pub entries: HashMap<MemoryId, DecayEntry>,
    pub config: DecayConfig,
}

impl DecayEngine {
    pub fn new(config: DecayConfig) -> Self {
        Self {
            entries: HashMap::new(),
            config,
        }
    }

    /// Insert a new memory entry into the given layer.
    pub fn insert(
        &mut self,
        id: MemoryId,
        layer: MemoryLayer,
        content: impl Into<String>,
    ) -> &DecayEntry {
        let entry = DecayEntry::new(id, layer, content);
        self.entries.insert(id, entry);
        self.entries.get(&id).unwrap()
    }

    /// Access a memory: strengthen it, bump last_accessed, and increment count.
    pub fn access(&mut self, id: MemoryId) -> Option<&DecayEntry> {
        let entry = self.entries.get_mut(&id)?;
        let boost = 0.1;
        entry.strength = (entry.strength + boost).min(1.0);
        entry.last_accessed = Utc::now();
        entry.access_count += 1;
        Some(entry)
    }

    /// Apply exponential decay to every non-archived entry based on elapsed time.
    pub fn decay_all(&mut self, now: DateTime<Utc>) {
        for entry in self.entries.values_mut() {
            if entry.archived {
                continue;
            }
            let elapsed = now - entry.last_accessed;
            let elapsed_secs = elapsed.num_seconds().max(0) as f64;
            let halflife_secs = match entry.layer {
                MemoryLayer::Buffer => self.config.buffer_halflife.as_secs_f64(),
                MemoryLayer::Episodic => self.config.episodic_halflife.as_secs_f64(),
                MemoryLayer::Semantic => continue, // no half-life
            };
            if halflife_secs <= 0.0 {
                continue;
            }
            let factor = 0.5_f64.powf(elapsed_secs / halflife_secs) as f32;
            entry.strength *= factor;
        }
    }

    /// Promote or consolidate entries:
    /// - Buffer -> Episodic for related buffer memories
    /// - Episodic -> Semantic by abstracting common patterns
    /// - Archive hibernated entries older than the archival threshold
    pub fn consolidate(&mut self, now: DateTime<Utc>) -> ConsolidationReport {
        let mut report = ConsolidationReport::default();

        let buffer_ids: Vec<MemoryId> = self
            .entries
            .values()
            .filter(|e| e.layer == MemoryLayer::Buffer && !e.archived)
            .map(|e| e.id)
            .collect();

        for id in buffer_ids {
            if let Some(entry) = self.entries.get_mut(&id) {
                if entry.access_count > 0 && entry.strength >= self.config.hibernation_threshold {
                    entry.layer = MemoryLayer::Episodic;
                    entry.last_accessed = now;
                    report.buffer_to_episodic.push(id);
                }
            }
        }

        let episodic_ids: Vec<MemoryId> = self
            .entries
            .values()
            .filter(|e| e.layer == MemoryLayer::Episodic && !e.archived)
            .map(|e| e.id)
            .collect();

        for id in episodic_ids {
            if let Some(entry) = self.entries.get_mut(&id) {
                if entry.access_count >= 3 {
                    entry.layer = MemoryLayer::Semantic;
                    entry.strength = 1.0;
                    entry.last_accessed = now;
                    report.episodic_to_semantic.push(id);
                }
            }
        }

        let archivable: Vec<MemoryId> = self
            .entries
            .values()
            .filter(|e| {
                e.is_archivable(self.config.archive_threshold_days, self.config.hibernation_threshold)
            })
            .map(|e| e.id)
            .collect();

        for id in archivable {
            if let Some(entry) = self.entries.get_mut(&id) {
                entry.archived = true;
                report.archived.push(id);
            }
        }

        report
    }

    /// Entries in a specific layer.
    pub fn get_by_layer(&self, layer: MemoryLayer) -> Vec<&DecayEntry> {
        self.entries
            .values()
            .filter(|e| e.layer == layer && !e.archived)
            .collect()
    }

    /// Entries that have fallen below the hibernation threshold and are not archived.
    pub fn get_hibernated(&self) -> Vec<&DecayEntry> {
        self.entries
            .values()
            .filter(|e| e.is_hibernating(self.config.hibernation_threshold) && !e.archived)
            .collect()
    }

    /// Entries that meet archival criteria but are not yet archived.
    pub fn get_archivable(&self) -> Vec<&DecayEntry> {
        self.entries
            .values()
            .filter(|e| {
                e.is_archivable(self.config.archive_threshold_days, self.config.hibernation_threshold)
            })
            .collect()
    }

    /// Whether the buffer layer has exceeded the consolidation trigger size.
    pub fn buffer_over_capacity(&self) -> bool {
        self.get_by_layer(MemoryLayer::Buffer).len() > self.config.consolidation_trigger
    }
}

/// Summary produced by a consolidation cycle.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ConsolidationReport {
    pub buffer_to_episodic: Vec<MemoryId>,
    pub episodic_to_semantic: Vec<MemoryId>,
    pub archived: Vec<MemoryId>,
}

fn days_since(t: DateTime<Utc>) -> f64 {
    let now = Utc::now();
    let duration = now - t;
    duration.num_seconds() as f64 / (60.0 * 60.0 * 24.0)
}

// ============================================================================
// 5. TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config() -> DecayConfig {
        DecayConfig {
            buffer_halflife: Duration::from_secs(60 * 60),
            episodic_halflife: Duration::from_secs(60 * 60 * 24 * 30),
            hibernation_threshold: 0.01,
            archive_threshold_days: 90,
            consolidation_trigger: 100,
        }
    }

    #[test]
    fn test_decay_over_time() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Buffer, "buffer item");

        let now = Utc::now() + chrono::Duration::hours(2);
        engine.decay_all(now);

        let entry = engine.entries.get(&id).unwrap();
        assert!(
            entry.strength < 0.5 && entry.strength > 0.0,
            "buffer memory should decay after two half-lives, got {}",
            entry.strength
        );
    }

    #[test]
    fn test_access_strengthens() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Buffer, "buffer item");

        engine.decay_all(Utc::now() + chrono::Duration::hours(2));
        let after_decay = engine.entries.get(&id).unwrap().strength;

        engine.access(id);

        let entry = engine.entries.get(&id).unwrap();
        assert!(
            entry.strength > after_decay,
            "access should strengthen memory relative to its post-decay strength, got {} vs after_decay {}",
            entry.strength,
            after_decay
        );
        assert_eq!(entry.access_count, 1);
    }

    #[test]
    fn test_buffer_promoted_to_episodic() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Buffer, "event");
        engine.access(id);

        let report = engine.consolidate(Utc::now());
        assert!(report.buffer_to_episodic.contains(&id));
        assert_eq!(engine.entries.get(&id).unwrap().layer, MemoryLayer::Episodic);
    }

    #[test]
    fn test_episodic_abstracted_to_semantic() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Episodic, "recurring pattern");
        for _ in 0..3 {
            engine.access(id);
        }

        let report = engine.consolidate(Utc::now());
        assert!(report.episodic_to_semantic.contains(&id));
        assert_eq!(engine.entries.get(&id).unwrap().layer, MemoryLayer::Semantic);
    }

    #[test]
    fn test_hibernated_archived_after_90_days() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Episodic, "old memory");
        let entry = engine.entries.get_mut(&id).unwrap();
        entry.strength = 0.005;
        entry.last_accessed = Utc::now() - chrono::Duration::days(95);
        entry.access_count = 0;

        let report = engine.consolidate(Utc::now());
        assert!(report.archived.contains(&id));
        assert!(engine.entries.get(&id).unwrap().archived);
    }

    #[test]
    fn test_hibernation_not_deletion() {
        let mut engine = DecayEngine::new(test_config());
        let id = MemoryId::new();
        engine.insert(id, MemoryLayer::Buffer, "fading");
        let now = Utc::now() + chrono::Duration::hours(10);
        engine.decay_all(now);

        let hibernated = engine.get_hibernated();
        assert!(hibernated.iter().any(|e| e.id == id));
        assert!(engine.entries.contains_key(&id));
    }

    #[test]
    fn test_consolidation_trigger_when_buffer_exceeds_100() {
        let mut engine = DecayEngine::new(test_config());
        for i in 0..102 {
            let id = MemoryId::new();
            engine.insert(id, MemoryLayer::Buffer, format!("item {}", i));
            engine.access(id);
        }
        assert!(engine.buffer_over_capacity());

        let report = engine.consolidate(Utc::now());
        assert!(!report.buffer_to_episodic.is_empty());
    }
}
