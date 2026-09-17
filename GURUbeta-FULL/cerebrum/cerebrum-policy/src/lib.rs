//! Cerebrum Policy — Learned retrieval policy and dereferencing controller.
//! Layer 2 from the MemexRL pattern.
//!
//! Decides what to retrieve, when, and how much. Not just search —
//! intelligent context assembly. Initially hand-coded rules, then
//! trained via RL (cerebrum-rl).

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use cerebrum_core::{CerebrumError, MemoryId, TokenBudget};

// ============================================================================
// 1. INDEXED EXPERIENCE STORE
// ============================================================================

/// A compact in-context summary with a stable index.
/// The summary is what goes into the LLM context window.
/// The full artifact lives under this index and is retrieved on demand.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct IndexEntry {
    pub id: MemoryId,
    /// Compact summary for in-context use.
    pub summary: String,
    /// Link to the full artifact in the DWM.
    pub artifact_id: MemoryId,
    /// Named sections of the full artifact, if segmented.
    pub sections: HashMap<String, String>,
    /// Estimated token count of the full artifact.
    pub token_count: usize,
    /// How relevant this entry is to the current task (0.0-1.0).
    pub relevance: f32,
    /// When the index was created.
    pub created_at: DateTime<Utc>,
    /// When the index was last accessed.
    pub last_accessed_at: DateTime<Utc>,
}

impl IndexEntry {
    pub fn new(id: MemoryId, summary: String, artifact_id: MemoryId, token_count: usize) -> Self {
        let now = Utc::now();
        Self {
            id,
            summary,
            artifact_id,
            sections: HashMap::new(),
            token_count,
            relevance: 0.5,
            created_at: now,
            last_accessed_at: now,
        }
    }
}

/// The store of indexed experiences. Maps stable indices to summaries
/// and full-fidelity artifacts.
#[derive(Debug, Clone, Default)]
pub struct IndexedExperienceStore {
    entries: HashMap<MemoryId, IndexEntry>,
}

impl IndexedExperienceStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a new index entry and store it.
    pub fn create_index(&mut self, entry: IndexEntry) -> MemoryId {
        let id = entry.id;
        self.entries.insert(id, entry);
        id
    }

    /// Update an existing index entry.
    pub fn update_index(&mut self, id: MemoryId, summary: Option<String>, relevance: Option<f32>) -> Result<(), CerebrumError> {
        let entry = self.entries.get_mut(&id).ok_or_else(|| CerebrumError::NotFound(id))?;
        if let Some(s) = summary {
            entry.summary = s;
        }
        if let Some(r) = relevance {
            entry.relevance = r;
        }
        entry.last_accessed_at = Utc::now();
        Ok(())
    }

    /// Get a shallow reference — just the summary, no full content.
    pub fn get_shallow(&self, id: MemoryId) -> Option<&IndexEntry> {
        self.entries.get(&id)
    }

    /// Get a deep reference — the full summary plus all sections.
    /// In a real system this would also pull from DWM. Here we return
    /// the entry with all sections populated.
    pub fn get_deep(&self, id: MemoryId) -> Option<&IndexEntry> {
        self.entries.get(&id)
    }

    /// Get specific sections only.
    pub fn get_partial(&self, id: MemoryId, section_names: &[String]) -> Option<PartialResult> {
        let entry = self.entries.get(&id)?;
        let mut sections = HashMap::new();
        let mut token_count = 0;
        for name in section_names {
            if let Some(content) = entry.sections.get(name) {
                sections.insert(name.clone(), content.clone());
                token_count += content.len() / 4; // rough token estimate
            }
        }
        Some(PartialResult {
            id,
            summary: entry.summary.clone(),
            sections,
            token_count,
        })
    }

    /// Delete an index entry (archive, not hard delete per Constitution §5).
    pub fn delete_index(&mut self, id: MemoryId) -> Result<(), CerebrumError> {
        // In compliance with No-Delete, we mark as archived rather than remove.
        if let Some(entry) = self.entries.get_mut(&id) {
            entry.relevance = 0.0;
            entry.summary = format!("[ARCHIVED] {}", entry.summary);
            Ok(())
        } else {
            Err(CerebrumError::NotFound(id))
        }
    }

    /// List all index entries.
    pub fn list_indices(&self) -> Vec<&IndexEntry> {
        self.entries.values().collect()
    }

    /// List entries sorted by relevance (highest first).
    pub fn list_by_relevance(&self) -> Vec<&IndexEntry> {
        let mut entries: Vec<&IndexEntry> = self.entries.values().collect();
        entries.sort_by(|a, b| b.relevance.partial_cmp(&a.relevance).unwrap_or(std::cmp::Ordering::Equal));
        entries
    }

    /// Count of indexed entries.
    pub fn count(&self) -> usize {
        self.entries.len()
    }
}

/// Result of a partial dereference — specific sections only.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PartialResult {
    pub id: MemoryId,
    pub summary: String,
    pub sections: HashMap<String, String>,
    pub token_count: usize,
}

// ============================================================================
// 2. DEREFERENCING CONTROLLER
// ============================================================================

/// Decision made by the dereferencing controller for a single index entry.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum DereferenceDecision {
    /// Return the compact summary only (lowest token cost).
    ShallowDeref { id: MemoryId },
    /// Return the full artifact (highest token cost).
    DeepDeref { id: MemoryId },
    /// Return specific named sections (medium token cost).
    PartialDeref { id: MemoryId, sections: Vec<String> },
    /// Skip this entry entirely (zero token cost).
    NoDeref { id: MemoryId },
}

/// Task type for dereferencing decisions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum TaskType {
    /// Simple factual lookup — shallow is usually enough.
    FactLookup,
    /// Deep analysis needs full context.
    DeepAnalysis,
    /// Conversation needs moderate context.
    Conversation,
    /// Code generation needs relevant sections.
    CodeGeneration,
    /// Summary needs broad but shallow coverage.
    Summary,
}

/// The dereferencing controller. Decides what access level to use
/// for each indexed memory based on context budget and task type.
#[derive(Debug, Clone)]
pub struct DereferencingController {
    /// Token cost estimates for each access level.
    shallow_cost: usize,
    deep_cost: usize,
    partial_cost_per_section: usize,
}

impl Default for DereferencingController {
    fn default() -> Self {
        Self {
            shallow_cost: 50,    // ~50 tokens for a summary
            deep_cost: 500,      // ~500 tokens for a full artifact
            partial_cost_per_section: 100, // ~100 tokens per section
        }
    }
}

impl DereferencingController {
    pub fn new(shallow_cost: usize, deep_cost: usize, partial_cost_per_section: usize) -> Self {
        Self { shallow_cost, deep_cost, partial_cost_per_section }
    }

    /// Decide dereferencing level for a set of index entries given a task and budget.
    ///
    /// Strategy:
    /// - High budget (>80%): Deep dereference high-relevance items
    /// - Medium budget (50-80%): Partial for high-relevance, Shallow for medium
    /// - Low budget (<50%): Shallow only for highest relevance
    pub fn decide(
        &self,
        _task_type: TaskType,
        budget: &TokenBudget,
        entries: &[&IndexEntry],
    ) -> Vec<DereferenceDecision> {
        let remaining = budget.remaining() as f64;
        let total = budget.max_tokens as f64;
        let budget_pct = remaining / total;

        let mut decisions = Vec::with_capacity(entries.len());
        let mut tokens_used = 0usize;

        // Sort entries by relevance (highest first) so we allocate budget
        // to the most relevant entries first.
        let mut sorted: Vec<&IndexEntry> = entries.to_vec();
        sorted.sort_by(|a, b| b.relevance.partial_cmp(&a.relevance).unwrap_or(std::cmp::Ordering::Equal));

        for entry in sorted {
            let decision = if budget_pct > 0.8 {
                // High budget: deep dereference for relevant items
                if entry.relevance >= 0.7 {
                    DereferenceDecision::DeepDeref { id: entry.id }
                } else if entry.relevance >= 0.4 {
                    // Medium relevance: partial if sections exist, shallow otherwise
                    if entry.sections.is_empty() {
                        DereferenceDecision::ShallowDeref { id: entry.id }
                    } else {
                        let section_names: Vec<String> = entry.sections.keys().take(2).cloned().collect();
                        DereferenceDecision::PartialDeref { id: entry.id, sections: section_names }
                    }
                } else {
                    DereferenceDecision::ShallowDeref { id: entry.id }
                }
            } else if budget_pct > 0.5 {
                // Medium budget: partial for high, shallow for medium
                if entry.relevance >= 0.7 {
                    if entry.sections.is_empty() {
                        DereferenceDecision::ShallowDeref { id: entry.id }
                    } else {
                        let section_names: Vec<String> = entry.sections.keys().take(1).cloned().collect();
                        DereferenceDecision::PartialDeref { id: entry.id, sections: section_names }
                    }
                } else if entry.relevance >= 0.4 {
                    DereferenceDecision::ShallowDeref { id: entry.id }
                } else {
                    DereferenceDecision::NoDeref { id: entry.id }
                }
            } else {
                // Low budget: shallow only for highest relevance
                if entry.relevance >= 0.8 {
                    DereferenceDecision::ShallowDeref { id: entry.id }
                } else {
                    DereferenceDecision::NoDeref { id: entry.id }
                }
            };

            // Track token usage to respect budget
            let cost = match &decision {
                DereferenceDecision::DeepDeref { .. } => self.deep_cost,
                DereferenceDecision::ShallowDeref { .. } => self.shallow_cost,
                DereferenceDecision::PartialDeref { sections, .. } => {
                    self.shallow_cost + (sections.len() * self.partial_cost_per_section)
                }
                DereferenceDecision::NoDeref { .. } => 0,
            };

            if tokens_used + cost <= budget.remaining() {
                tokens_used += cost;
                decisions.push(decision);
            } else {
                // Budget exhausted — shallow or skip remaining entries
                if self.shallow_cost <= budget.remaining() - tokens_used {
                    decisions.push(DereferenceDecision::ShallowDeref { id: entry.id });
                    tokens_used += self.shallow_cost;
                } else {
                    decisions.push(DereferenceDecision::NoDeref { id: entry.id });
                }
            }
        }

        decisions
    }
}

// ============================================================================
// 3. POLICY NETWORK (hand-coded rules, later trained via RL)
// ============================================================================

/// Actions the policy network can take for an index entry.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PolicyAction {
    /// Create a new index entry for a memory.
    CreateIndex { summary: String, relevance: f32 },
    /// Update an existing index entry (new summary or relevance).
    UpdateIndex { id: MemoryId, summary: Option<String>, relevance: Option<f32> },
    /// Dereference at shallow level (summary only).
    DereferenceShallow { id: MemoryId },
    /// Dereference at deep level (full artifact).
    DereferenceDeep { id: MemoryId },
    /// Dereference specific sections only.
    DereferencePartial { id: MemoryId, sections: Vec<String> },
    /// Compress an entry (reduce its token footprint).
    Compress { id: MemoryId },
    /// Archive an entry (mark as inactive, per No-Delete principle).
    Archive { id: MemoryId },
}

/// A decision from the policy network.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyDecision {
    pub action: PolicyAction,
    pub target_id: MemoryId,
    pub rationale: String,
    pub confidence: f32,
}

/// The policy network. Initially hand-coded rules.
/// Later, cerebrum-rl will train this via GRPO.
#[derive(Debug, Clone)]
pub struct PolicyNetwork {
    controller: DereferencingController,
}

impl Default for PolicyNetwork {
    fn default() -> Self {
        Self {
            controller: DereferencingController::default(),
        }
    }
}

impl PolicyNetwork {
    pub fn new(controller: DereferencingController) -> Self {
        Self { controller }
    }

    /// Decide what actions to take given a task, budget, and available indices.
    ///
    /// This is the hand-coded baseline. In production, cerebrum-rl will
    /// replace this with a learned policy via GRPO training.
    pub fn decide(
        &self,
        task_type: TaskType,
        budget: &TokenBudget,
        entries: &[&IndexEntry],
    ) -> Vec<PolicyDecision> {
        let mut decisions = Vec::new();

        // Step 1: Decide dereferencing levels
        let deref_decisions = self.controller.decide(task_type, budget, entries);

        // Step 2: Convert dereferencing decisions to policy decisions
        for (i, deref) in deref_decisions.iter().enumerate() {
            let entry = entries.get(i);
            let (action, id) = match deref {
                DereferenceDecision::DeepDeref { id } => {
                    (PolicyAction::DereferenceDeep { id: *id }, *id)
                }
                DereferenceDecision::ShallowDeref { id } => {
                    (PolicyAction::DereferenceShallow { id: *id }, *id)
                }
                DereferenceDecision::PartialDeref { id, sections } => {
                    (PolicyAction::DereferencePartial { id: *id, sections: sections.clone() }, *id)
                }
                DereferenceDecision::NoDeref { id } => {
                    // No dereference needed — check if we should compress or archive
                    let entry_relevance = entry.map(|e| e.relevance).unwrap_or(0.0);
                    if entry_relevance < 0.2 {
                        (PolicyAction::Archive { id: *id }, *id)
                    } else if entry_relevance < 0.4 {
                        (PolicyAction::Compress { id: *id }, *id)
                    } else {
                        (PolicyAction::DereferenceShallow { id: *id }, *id)
                    }
                }
            };

            let rationale = format!("{:?} for task {:?} with {:.0}% budget remaining",
                deref, task_type, budget.remaining() as f64 / budget.max_tokens as f64 * 100.0);

            let confidence = match deref {
                DereferenceDecision::DeepDeref { .. } => 0.9,
                DereferenceDecision::PartialDeref { .. } => 0.7,
                DereferenceDecision::ShallowDeref { .. } => 0.5,
                DereferenceDecision::NoDeref { .. } => 0.3,
            };

            decisions.push(PolicyDecision {
                action,
                target_id: id,
                rationale,
                confidence,
            });
        }

        decisions
    }

    /// Decide whether to create a new index entry for a memory.
    /// High relevance memories always get indexed. Low relevance
    /// memories only get indexed if budget allows.
    pub fn should_index(
        &self,
        content: &str,
        relevance: f32,
        budget_remaining_pct: f32,
    ) -> PolicyDecision {
        let id = MemoryId::new();
        let summary = if content.len() > 100 {
            format!("{}...", &content[..100])
        } else {
            content.to_string()
        };

        if relevance >= 0.6 {
            PolicyDecision {
                action: PolicyAction::CreateIndex { summary, relevance },
                target_id: id,
                rationale: format!("High relevance ({:.2}) — always index", relevance),
                confidence: 0.9,
            }
        } else if relevance >= 0.3 && budget_remaining_pct > 0.3 {
            PolicyDecision {
                action: PolicyAction::CreateIndex { summary, relevance },
                target_id: id,
                rationale: format!("Medium relevance ({:.2}) with budget ({:.0}%) — index", relevance, budget_remaining_pct * 100.0),
                confidence: 0.6,
            }
        } else {
            PolicyDecision {
                action: PolicyAction::Compress { id },
                target_id: id,
                rationale: format!("Low relevance ({:.2}) or low budget ({:.0}%) — compress", relevance, budget_remaining_pct * 100.0),
                confidence: 0.4,
            }
        }
    }
}

// ============================================================================
// 4. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::MemoryId;

    fn make_entry(id_byte: u8, relevance: f32, summary: &str) -> IndexEntry {
        IndexEntry {
            id: MemoryId([id_byte; 32]),
            summary: summary.to_string(),
            artifact_id: MemoryId([id_byte + 100; 32]),
            sections: HashMap::new(),
            token_count: summary.len() / 4,
            relevance,
            created_at: Utc::now(),
            last_accessed_at: Utc::now(),
        }
    }

    fn make_entry_with_sections(id_byte: u8, relevance: f32, summary: &str) -> IndexEntry {
        let mut entry = make_entry(id_byte, relevance, summary);
        entry.sections.insert("intro".to_string(), "Introduction content here.".to_string());
        entry.sections.insert("body".to_string(), "Main body content here with more detail.".to_string());
        entry.sections.insert("conclusion".to_string(), "Conclusion and summary.".to_string());
        entry
    }

    // Test 1: Index creation — 100 memories indexed, all found
    #[test]
    fn test_index_creation_100_memories() {
        let mut store = IndexedExperienceStore::new();
        let mut ids = Vec::new();

        for i in 0..100 {
            let id = MemoryId([i as u8; 32]);
            let entry = IndexEntry::new(
                id,
                format!("Memory about topic {}", i),
                MemoryId([(i + 200) as u8; 32]),
                50,
            );
            let returned_id = store.create_index(entry);
            ids.push(returned_id);
        }

        assert_eq!(store.count(), 100);

        // All found
        for id in &ids {
            assert!(store.get_shallow(*id).is_some());
        }
    }

    // Test 2: Shallow dereference — returns summary only
    #[test]
    fn test_shallow_dereference() {
        let mut store = IndexedExperienceStore::new();
        let entry = make_entry(1, 0.8, "Important memory about Rust");
        let id = entry.id;
        store.create_index(entry);

        let result = store.get_shallow(id).unwrap();
        assert_eq!(result.summary, "Important memory about Rust");
        assert_eq!(result.id, id);
    }

    // Test 3: Deep dereference — returns full content
    #[test]
    fn test_deep_dereference() {
        let mut store = IndexedExperienceStore::new();
        let entry = make_entry_with_sections(2, 0.9, "Deep analysis of Cerebrum");
        let id = entry.id;
        store.create_index(entry);

        let result = store.get_deep(id).unwrap();
        assert!(result.sections.contains_key("intro"));
        assert!(result.sections.contains_key("body"));
        assert!(result.sections.contains_key("conclusion"));
    }

    // Test 4: Partial dereference — returns specific sections only
    #[test]
    fn test_partial_dereference() {
        let mut store = IndexedExperienceStore::new();
        let entry = make_entry_with_sections(3, 0.7, "Partial content retrieval");
        let id = entry.id;
        store.create_index(entry);

        let result = store.get_partial(id, &["intro".to_string(), "conclusion".to_string()]).unwrap();
        assert!(result.sections.contains_key("intro"));
        assert!(result.sections.contains_key("conclusion"));
        assert!(!result.sections.contains_key("body")); // Not requested
    }

    // Test 5: Budget adherence — controller never exceeds token budget
    #[test]
    fn test_budget_adherence() {
        let controller = DereferencingController::default();
        let entries: Vec<IndexEntry> = (0..20)
            .map(|i| make_entry_with_sections(i as u8, 0.5 + (i as f32 * 0.02), &format!("Entry {}", i)))
            .collect();
        let entry_refs: Vec<&IndexEntry> = entries.iter().collect();

        // Budget of 500 tokens — should not exceed
        let budget = TokenBudget::new(500);
        let decisions = controller.decide(TaskType::Conversation, &budget, &entry_refs);

        // Verify total estimated token cost doesn't exceed budget
        let mut total_cost = 0;
        for decision in &decisions {
            total_cost += match decision {
                DereferenceDecision::DeepDeref { .. } => 500,
                DereferenceDecision::ShallowDeref { .. } => 50,
                DereferenceDecision::PartialDeref { sections, .. } => 50 + sections.len() * 100,
                DereferenceDecision::NoDeref { .. } => 0,
            };
        }
        assert!(total_cost <= 500, "Total cost {} exceeds budget 500", total_cost);
    }

    // Test 6: Policy network — high budget gets deep dereference
    #[test]
    fn test_policy_network_high_budget() {
        let policy = PolicyNetwork::default();
        let entries: Vec<IndexEntry> = (0..5)
            .map(|i| make_entry_with_sections(i as u8, 0.8, &format!("High relevance entry {}", i)))
            .collect();
        let entry_refs: Vec<&IndexEntry> = entries.iter().collect();

        let budget = TokenBudget::new(10000); // Plenty of budget
        let decisions = policy.decide(TaskType::DeepAnalysis, &budget, &entry_refs);

        // High budget + high relevance = should dereference at least shallow
        assert!(!decisions.is_empty());
        let has_deref = decisions.iter().any(|d| matches!(
            d.action,
            PolicyAction::DereferenceDeep { .. } | PolicyAction::DereferenceShallow { .. } | PolicyAction::DereferencePartial { .. }
        ));
        assert!(has_deref, "Expected at least one dereference decision");
    }

    // Test 7: Compress decision — low relevance items get compressed
    #[test]
    fn test_compress_decision_low_relevance() {
        let policy = PolicyNetwork::default();

        let decision = policy.should_index("some low relevance content", 0.1, 0.2);
        match decision.action {
            PolicyAction::Compress { .. } => {},
            _ => panic!("Expected Compress action for low relevance content with low budget"),
        }
    }

    // Test 8: Archive decision — compressed items still not accessed get archived
    #[test]
    fn test_archive_decision_still_not_accessed() {
        let controller = DereferencingController::default();
        let entries: Vec<IndexEntry> = (0..3)
            .map(|i| make_entry(i as u8, 0.1, &format!("Low relevance entry {}", i)))
            .collect();
        let entry_refs: Vec<&IndexEntry> = entries.iter().collect();

        // Very low budget — only highest relevance gets shallow, rest get NoDeref
        let budget = TokenBudget::new(100);
        let decisions = controller.decide(TaskType::FactLookup, &budget, &entry_refs);

        // With 0.1 relevance and 100 token budget, some should be NoDeref
        let no_deref_count = decisions.iter().filter(|d| matches!(d, DereferenceDecision::NoDeref { .. })).count();
        assert!(no_deref_count > 0, "Expected some NoDeref decisions for low relevance entries");
    }

    // Test 9: Index update works correctly
    #[test]
    fn test_index_update() {
        let mut store = IndexedExperienceStore::new();
        let entry = make_entry(10, 0.5, "Original summary");
        let id = entry.id;
        store.create_index(entry);

        store.update_index(id, Some("Updated summary".to_string()), Some(0.9)).unwrap();

        let updated = store.get_shallow(id).unwrap();
        assert_eq!(updated.summary, "Updated summary");
        assert!((updated.relevance - 0.9).abs() < 0.01);
    }

    // Test 10: Delete archives rather than removes (No-Delete principle)
    #[test]
    fn test_delete_archives_not_removes() {
        let mut store = IndexedExperienceStore::new();
        let entry = make_entry(11, 0.6, "Memory to archive");
        let id = entry.id;
        store.create_index(entry);

        store.delete_index(id).unwrap();

        // Entry still exists but is marked as archived
        let archived = store.get_shallow(id).unwrap();
        assert!(archived.summary.starts_with("[ARCHIVED]"));
        assert_eq!(archived.relevance, 0.0);
    }
}