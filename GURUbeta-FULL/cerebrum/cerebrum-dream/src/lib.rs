//! Cerebrum Dream — Consolidation engine (merge, prune, dedup, semantic upgrade).
//!
//! Phase 0.2.4 — Track E. Runs during low-activity periods and mimics sleep
//! consolidation by merging related memories, pruning hibernated entries,
//! deduplicating near-identical memories, and promoting episodic memories to
//! semantic abstractions.

use std::collections::HashMap;

use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};

use cerebrum_core::MemoryId;

// ============================================================================
// 1. DREAM OPERATION
// ============================================================================

/// Operations that the dream consolidation engine can perform.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum DreamOperation {
    /// Combine related memories into a single richer memory.
    Merge,
    /// Remove hibernated memories past the retention threshold.
    Prune,
    /// Detect and merge duplicate memories.
    Dedup,
    /// Promote episodic memories to semantic through abstraction.
    SemanticUpgrade,
}

// ============================================================================
// 2. DREAM RESULT
// ============================================================================

/// The outcome of a single dream operation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DreamResult {
    pub operation: DreamOperation,
    pub affected_ids: Vec<MemoryId>,
    pub success: bool,
}

// ============================================================================
// 3. DREAM MEMORY
// ============================================================================

/// A memory as seen by the dream consolidation engine.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DreamMemory {
    pub id: MemoryId,
    pub content: String,
    pub layer: String,
    pub strength: f32,
    /// Known similarities to other memories: (id, similarity).
    pub similarity: Vec<(MemoryId, f32)>,
    /// Timestamp used for age-based pruning.
    pub last_accessed_at: DateTime<Utc>,
}

impl DreamMemory {
    /// Create a new dream memory.
    pub fn new(id: MemoryId, content: String, layer: String, strength: f32) -> Self {
        Self {
            id,
            content,
            layer,
            strength,
            similarity: Vec::new(),
            last_accessed_at: Utc::now(),
        }
    }

    /// Mark a similarity relationship with another memory.
    pub fn add_similarity(&mut self, other: MemoryId, score: f32) {
        self.similarity.push((other, score));
    }
}

// ============================================================================
// 4. DREAM CONFIG
// ============================================================================

/// Configuration for the dream consolidation engine.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct DreamConfig {
    /// Minimum similarity for merging two memories (default 0.8).
    pub merge_threshold: f32,
    /// Minimum similarity for deduplicating memories (default 0.95).
    pub dedup_threshold: f32,
    /// Days after which hibernated memories can be pruned (default 90).
    pub prune_after_days: u32,
}

impl Default for DreamConfig {
    fn default() -> Self {
        Self {
            merge_threshold: 0.8,
            dedup_threshold: 0.95,
            prune_after_days: 90,
        }
    }
}

// ============================================================================
// 5. DREAM ENGINE
// ============================================================================

/// Consolidation engine over a collection of dream memories.
#[derive(Debug, Clone, Default)]
pub struct DreamEngine {
    pub memories: HashMap<MemoryId, DreamMemory>,
    pub config: DreamConfig,
}

impl DreamEngine {
    /// Create a new dream engine with default configuration.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a dream engine with a specific configuration.
    pub fn with_config(config: DreamConfig) -> Self {
        Self {
            memories: HashMap::new(),
            config,
        }
    }

    /// Insert a memory into the engine.
    pub fn insert(&mut self, memory: DreamMemory) -> MemoryId {
        let id = memory.id;
        self.memories.insert(id, memory);
        id
    }

    /// Get a memory by id.
    pub fn get(&self, id: MemoryId) -> Option<&DreamMemory> {
        self.memories.get(&id)
    }

    /// Run the merge operation: combine related memories into one richer memory.
    ///
    /// Returns the id of the merged memory and the list of source ids consumed.
    pub fn merge(&mut self) -> DreamResult {
        let threshold = self.config.merge_threshold;
        let mut merged = Vec::new();
        let mut consumed = Vec::new();

        // Find pairs to merge. A simple greedy single-pass merge.
        let ids: Vec<MemoryId> = self.memories.keys().copied().collect();
        for i in 0..ids.len() {
            let id_a = ids[i];
            if consumed.contains(&id_a) {
                continue;
            }
            let mem_a = match self.memories.get(&id_a) {
                Some(m) => m.clone(),
                None => continue,
            };

            let mut best_match: Option<(MemoryId, f32)> = None;
            for j in (i + 1)..ids.len() {
                let id_b = ids[j];
                if consumed.contains(&id_b) {
                    continue;
                }
                if let Some(sim) = mem_a.similarity.iter().find(|(id, _)| *id == id_b) {
                    if sim.1 >= threshold {
                        if best_match.map(|(_, s)| sim.1 > s).unwrap_or(true) {
                            best_match = Some((id_b, sim.1));
                        }
                    }
                }
            }

            if let Some((id_b, _)) = best_match {
                let mem_b = self.memories.get(&id_b).unwrap().clone();
                let merged_content = format!("{} | {}", mem_a.content, mem_b.content);
                let merged_id = MemoryId::new();
                let merged_memory = DreamMemory::new(
                    merged_id,
                    merged_content,
                    "semantic".to_string(),
                    (mem_a.strength + mem_b.strength) / 2.0,
                );
                self.memories.insert(merged_id, merged_memory);
                merged.push(merged_id);
                consumed.push(id_a);
                consumed.push(id_b);
            }
        }

        // Remove consumed memories.
        for id in &consumed {
            self.memories.remove(id);
        }

        let mut affected = consumed;
        affected.extend(merged);
        DreamResult {
            operation: DreamOperation::Merge,
            affected_ids: affected,
            success: true,
        }
    }

    /// Run the prune operation: remove hibernated memories older than the threshold.
    ///
    /// In this baseline, "hibernated" means strength < 0.2 and last accessed
    /// older than `prune_after_days`.
    pub fn prune(&mut self) -> DreamResult {
        let cutoff = Utc::now() - Duration::days(self.config.prune_after_days as i64);
        let to_prune: Vec<MemoryId> = self
            .memories
            .iter()
            .filter(|(_, m)| m.strength < 0.2 && m.last_accessed_at < cutoff)
            .map(|(id, _)| *id)
            .collect();

        for id in &to_prune {
            self.memories.remove(id);
        }

        DreamResult {
            operation: DreamOperation::Prune,
            affected_ids: to_prune,
            success: true,
        }
    }

    /// Run the dedup operation: merge duplicate memories (similarity >= dedup threshold).
    pub fn dedup(&mut self) -> DreamResult {
        let threshold = self.config.dedup_threshold;
        let mut merged = Vec::new();
        let mut consumed = Vec::new();

        let ids: Vec<MemoryId> = self.memories.keys().copied().collect();
        for i in 0..ids.len() {
            let id_a = ids[i];
            if consumed.contains(&id_a) {
                continue;
            }
            let mem_a = match self.memories.get(&id_a) {
                Some(m) => m.clone(),
                None => continue,
            };

            let mut duplicates = vec![id_a];
            for j in (i + 1)..ids.len() {
                let id_b = ids[j];
                if consumed.contains(&id_b) {
                    continue;
                }
                if let Some(sim) = mem_a.similarity.iter().find(|(id, _)| *id == id_b) {
                    if sim.1 >= threshold {
                        duplicates.push(id_b);
                    }
                }
            }

            if duplicates.len() > 1 {
                let content = duplicates
                    .iter()
                    .filter_map(|id| self.memories.get(id).map(|m| m.content.clone()))
                    .collect::<Vec<String>>()
                    .join(" | ");
                let merged_id = MemoryId::new();
                let merged_memory = DreamMemory::new(
                    merged_id,
                    content,
                    "semantic".to_string(),
                    duplicates
                        .iter()
                        .filter_map(|id| self.memories.get(id).map(|m| m.strength))
                        .sum::<f32>()
                        / duplicates.len() as f32,
                );
                self.memories.insert(merged_id, merged_memory);
                merged.push(merged_id);
                consumed.extend(duplicates);
            }
        }

        for id in &consumed {
            self.memories.remove(id);
        }

        let mut affected = consumed;
        affected.extend(merged);
        DreamResult {
            operation: DreamOperation::Dedup,
            affected_ids: affected,
            success: true,
        }
    }

    /// Run the semantic upgrade operation: promote episodic memories to semantic.
    pub fn semantic_upgrade(&mut self) -> DreamResult {
        let mut affected = Vec::new();
        for memory in self.memories.values_mut() {
            if memory.layer == "episodic" && memory.strength >= 0.7 {
                memory.layer = "semantic".to_string();
                affected.push(memory.id);
            }
        }
        DreamResult {
            operation: DreamOperation::SemanticUpgrade,
            affected_ids: affected,
            success: true,
        }
    }

    /// Run a full consolidation cycle: merge → dedup → semantic_upgrade → prune.
    pub fn consolidate(&mut self) -> Vec<DreamResult> {
        vec![
            self.merge(),
            self.dedup(),
            self.semantic_upgrade(),
            self.prune(),
        ]
    }
}

// ============================================================================
// 6. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::MemoryId;

    fn make_memory(id_byte: u8, content: &str, layer: &str, strength: f32) -> DreamMemory {
        DreamMemory::new(
            MemoryId([id_byte; 32]),
            content.to_string(),
            layer.to_string(),
            strength,
        )
    }

    #[test]
    fn test_merge_similar_memories() {
        let mut engine = DreamEngine::new();
        let mut a = make_memory(1, "Rust memory safety", "semantic", 0.9);
        let mut b = make_memory(2, "Rust ownership system", "semantic", 0.85);
        a.add_similarity(b.id, 0.85);
        b.add_similarity(a.id, 0.85);
        engine.insert(a);
        engine.insert(b);

        let result = engine.merge();
        assert_eq!(result.operation, DreamOperation::Merge);
        assert!(!result.affected_ids.is_empty());
        // Two source ids consumed and one merged id produced.
        assert_eq!(engine.memories.len(), 1);
    }

    #[test]
    fn test_prune_hibernated_memories() {
        let mut engine = DreamEngine::new();
        let mut old = make_memory(3, "Old forgotten memory", "episodic", 0.1);
        old.last_accessed_at = Utc::now() - Duration::days(91);
        engine.insert(old);

        let fresh = make_memory(4, "Recent memory", "episodic", 0.9);
        engine.insert(fresh);

        let result = engine.prune();
        assert_eq!(result.operation, DreamOperation::Prune);
        assert_eq!(result.affected_ids.len(), 1);
        assert_eq!(engine.memories.len(), 1);
    }

    #[test]
    fn test_dedup_duplicates() {
        let mut engine = DreamEngine::new();
        let mut a = make_memory(5, "Duplicate content", "episodic", 0.9);
        let mut b = make_memory(6, "Duplicate content", "episodic", 0.9);
        a.add_similarity(b.id, 0.98);
        b.add_similarity(a.id, 0.98);
        engine.insert(a);
        engine.insert(b);

        let result = engine.dedup();
        assert_eq!(result.operation, DreamOperation::Dedup);
        // Two duplicates consumed, one merged result produced.
        assert_eq!(engine.memories.len(), 1);
        let remaining = engine.memories.values().next().unwrap();
        assert!(remaining.content.contains("Duplicate content"));
    }

    #[test]
    fn test_semantic_upgrade() {
        let mut engine = DreamEngine::new();
        let episode = make_memory(7, "A specific coding session", "episodic", 0.85);
        engine.insert(episode);

        let result = engine.semantic_upgrade();
        assert_eq!(result.operation, DreamOperation::SemanticUpgrade);
        assert_eq!(result.affected_ids.len(), 1);
        assert_eq!(engine.memories.values().next().unwrap().layer, "semantic");
    }

    #[test]
    fn test_consolidation_cycle() {
        let mut engine = DreamEngine::new();
        let mut a = make_memory(8, "Rust borrow checker", "episodic", 0.85);
        let mut b = make_memory(9, "Rust borrow checker details", "episodic", 0.82);
        a.add_similarity(b.id, 0.88);
        b.add_similarity(a.id, 0.88);
        engine.insert(a);
        engine.insert(b);

        let results = engine.consolidate();
        assert_eq!(results.len(), 4);
        assert!(!engine.memories.is_empty());
    }
}
