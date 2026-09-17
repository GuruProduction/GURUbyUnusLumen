//! Cerebrum Curate — Agent-native curation engine (Layer 4 from the ByteRover pattern).
//!
//! The LLM itself decides what to remember, where to file it, and how to relate it.
//! This crate provides the Context Tree hierarchy, the curation operation set, an
//! atomic executor, and a cross-reference engine for bidirectional, cross-domain links.

use std::collections::HashMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

pub use cerebrum_core::{CurateOp as CoreCurateOp, CurateResult, CurateStatus, MemoryId};

// ============================================================================
// 1. CONTEXT TREE HIERARCHY
// ============================================================================

/// Root of the context tree: domains each own topics, topics own subtopics,
/// subtopics hold entry identifiers.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct ContextTree {
    root: DomainNode,
    entries: HashMap<MemoryId, ContextEntry>,
}

/// A domain is the top-level classification in the context tree.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct DomainNode {
    name: String,
    topics: HashMap<String, TopicNode>,
}

/// A topic lives inside a domain and groups subtopics.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct TopicNode {
    name: String,
    subtopics: HashMap<String, SubtopicNode>,
}

/// A subtopic lives inside a topic and holds references to context entries.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct SubtopicNode {
    name: String,
    entries: Vec<MemoryId>,
}

// ============================================================================
// 2. ENTRY METADATA
// ============================================================================

/// Lifecycle state of a context entry.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EntryLifecycle {
    Active,
    Hibernated,
    Archived,
}

impl Default for EntryLifecycle {
    fn default() -> Self {
        EntryLifecycle::Active
    }
}

/// A typed relation between two context entries, carrying a weight.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Relation {
    pub source: MemoryId,
    pub target: MemoryId,
    pub relation_type: String,
    pub weight: f32,
}

/// Provenance tracks where a piece of knowledge came from and how confident we are.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Provenance {
    pub source: String,
    pub confidence: f32,
    pub timestamp: DateTime<Utc>,
}

impl Default for Provenance {
    fn default() -> Self {
        Self {
            source: String::new(),
            confidence: 0.0,
            timestamp: Utc::now(),
        }
    }
}

/// A single curated memory entry in the Context Tree.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ContextEntry {
    pub id: MemoryId,
    pub domain: String,
    pub topic: String,
    pub subtopic: String,
    pub content: String,
    pub relations: Vec<Relation>,
    pub provenance: Provenance,
    pub rationale: String,
    pub lifecycle: EntryLifecycle,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl ContextEntry {
    /// Create a new active entry with the current timestamp for both creation and update.
    pub fn new(
        id: MemoryId,
        domain: impl Into<String>,
        topic: impl Into<String>,
        subtopic: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            id,
            domain: domain.into(),
            topic: topic.into(),
            subtopic: subtopic.into(),
            content: content.into(),
            relations: Vec::new(),
            provenance: Provenance::default(),
            rationale: String::new(),
            lifecycle: EntryLifecycle::Active,
            created_at: now,
            updated_at: now,
        }
    }

    /// Touch `updated_at` to the current time.
    fn touch(&mut self) {
        self.updated_at = Utc::now();
    }
}

// ============================================================================
// 3. CONTEXT TREE OPERATIONS
// ============================================================================

impl ContextTree {
    /// Build an empty context tree with the default unnamed root domain.
    pub fn new() -> Self {
        Self::default()
    }

    /// Insert a new context entry under its domain/topic/subtopic.
    /// Returns the previous entry if one already existed at the same location
    /// and location-id combination; otherwise returns `None`.
    pub fn insert(&mut self, entry: ContextEntry) -> Option<ContextEntry> {
        let domain = self
            .root
            .topics
            .entry(entry.domain.clone())
            .or_insert_with(|| TopicNode {
                name: entry.domain.clone(),
                subtopics: HashMap::new(),
            });

        let topic = domain
            .subtopics
            .entry(entry.topic.clone())
            .or_insert_with(|| SubtopicNode {
                name: entry.topic.clone(),
                entries: Vec::new(),
            });

        if !topic.entries.contains(&entry.id) {
            topic.entries.push(entry.id);
        }

        self.entries.insert(entry.id, entry)
    }

    /// Find a single entry by its memory id.
    pub fn find(&self, id: MemoryId) -> Option<&ContextEntry> {
        self.entries.get(&id)
    }

    /// Mutable access to a single entry by its memory id.
    pub fn find_mut(&mut self, id: MemoryId) -> Option<&mut ContextEntry> {
        self.entries.get_mut(&id)
    }

    /// Find all entries within a domain.
    pub fn find_by_domain(&self, domain: &str) -> Vec<&ContextEntry> {
        let mut out = Vec::new();
        if let Some(topic_node) = self.root.topics.get(domain) {
            for subtopic_node in topic_node.subtopics.values() {
                for id in &subtopic_node.entries {
                    if let Some(entry) = self.entries.get(id) {
                        out.push(entry);
                    }
                }
            }
        }
        out
    }

    /// Find all entries within a domain/topic pair.
    pub fn find_by_topic(&self, domain: &str, topic: &str) -> Vec<&ContextEntry> {
        let mut out = Vec::new();
        if let Some(topic_node) = self.root.topics.get(domain) {
            if let Some(subtopic_node) = topic_node.subtopics.get(topic) {
                for id in &subtopic_node.entries {
                    if let Some(entry) = self.entries.get(id) {
                        out.push(entry);
                    }
                }
            }
        }
        out
    }

    /// Update an existing entry in place. If the entry moves in the hierarchy,
    /// the tree structure is adjusted accordingly.
    pub fn update(&mut self, mut entry: ContextEntry) -> Result<(), CurationError> {
        let old = self
            .entries
            .get(&entry.id)
            .ok_or_else(|| CurationError::NotFound(entry.id))?
            .clone();

        if old.domain != entry.domain || old.topic != entry.topic || old.subtopic != entry.subtopic {
            self.remove_from_hierarchy(&old);
            let domain = self
                .root
                .topics
                .entry(entry.domain.clone())
                .or_insert_with(|| TopicNode {
                    name: entry.domain.clone(),
                    subtopics: HashMap::new(),
                });
            let topic = domain
                .subtopics
                .entry(entry.topic.clone())
                .or_insert_with(|| SubtopicNode {
                    name: entry.topic.clone(),
                    entries: Vec::new(),
                });
            if !topic.entries.contains(&entry.id) {
                topic.entries.push(entry.id);
            }
        }

        entry.touch();
        self.entries.insert(entry.id, entry);
        Ok(())
    }

    /// Remove an entry from the tree. The entry id is removed from the hierarchy
    /// and the entry map.
    pub fn remove(&mut self, id: MemoryId) -> Option<ContextEntry> {
        let removed = self.entries.remove(&id)?;
        self.remove_from_hierarchy(&removed);
        Some(removed)
    }

    fn remove_from_hierarchy(&mut self, entry: &ContextEntry) {
        if let Some(topic_node) = self.root.topics.get_mut(&entry.domain) {
            if let Some(subtopic_node) = topic_node.subtopics.get_mut(&entry.topic) {
                subtopic_node.entries.retain(|eid| *eid != entry.id);
                if subtopic_node.entries.is_empty() {
                    topic_node.subtopics.remove(&entry.topic);
                }
            }
            if topic_node.subtopics.is_empty() {
                self.root.topics.remove(&entry.domain);
            }
        }
    }

    /// Total number of curated entries.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether the tree is empty.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Immutable iterator over all entries.
    pub fn entries(&self) -> impl Iterator<Item = &ContextEntry> {
        self.entries.values()
    }
}

// ============================================================================
// 4. CURATION OPERATIONS
// ============================================================================

/// The five curation operations the agent can request.
/// Every variant carries a `reason` (why was this curated?) and a `timestamp`
/// recording when the operation was issued.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum CurateOperation {
    Add {
        entry: ContextEntry,
        reason: String,
        timestamp: DateTime<Utc>,
    },
    Update {
        id: MemoryId,
        entry: ContextEntry,
        reason: String,
        timestamp: DateTime<Utc>,
    },
    Upsert {
        entry: ContextEntry,
        reason: String,
        timestamp: DateTime<Utc>,
    },
    Merge {
        source_ids: Vec<MemoryId>,
        target: ContextEntry,
        reason: String,
        timestamp: DateTime<Utc>,
    },
    Delete {
        id: MemoryId,
        reason: String,
        timestamp: DateTime<Utc>,
    },
}

impl CurateOperation {
    /// Convenience constructor for `Add` with the current timestamp.
    pub fn add(entry: ContextEntry, reason: impl Into<String>) -> Self {
        Self::Add {
            entry,
            reason: reason.into(),
            timestamp: Utc::now(),
        }
    }

    /// Convenience constructor for `Update` with the current timestamp.
    pub fn update(id: MemoryId, entry: ContextEntry, reason: impl Into<String>) -> Self {
        Self::Update {
            id,
            entry,
            reason: reason.into(),
            timestamp: Utc::now(),
        }
    }

    /// Convenience constructor for `Upsert` with the current timestamp.
    pub fn upsert(entry: ContextEntry, reason: impl Into<String>) -> Self {
        Self::Upsert {
            entry,
            reason: reason.into(),
            timestamp: Utc::now(),
        }
    }

    /// Convenience constructor for `Merge` with the current timestamp.
    pub fn merge(
        source_ids: Vec<MemoryId>,
        target: ContextEntry,
        reason: impl Into<String>,
    ) -> Self {
        Self::Merge {
            source_ids,
            target,
            reason: reason.into(),
            timestamp: Utc::now(),
        }
    }

    /// Convenience constructor for `Delete` with the current timestamp.
    pub fn delete(id: MemoryId, reason: impl Into<String>) -> Self {
        Self::Delete {
            id,
            reason: reason.into(),
            timestamp: Utc::now(),
        }
    }
}

/// Result of executing a curation batch.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CurationResult {
    pub success: bool,
    pub status: CurateStatus,
    pub details: Vec<CurateResult>,
    pub timestamp: DateTime<Utc>,
}

impl CurationResult {
    /// Build a single-result curation response; useful for one-off operations.
    pub fn single(memory_id: MemoryId, status: CurateStatus, detail: impl Into<String>) -> Self {
        Self {
            success: status != CurateStatus::Failed,
            status,
            details: vec![CurateResult {
                memory_id,
                status,
                detail: detail.into(),
            }],
            timestamp: Utc::now(),
        }
    }
}

/// Errors that can occur while executing curation operations.
#[derive(Debug, Clone, thiserror::Error, PartialEq, Serialize, Deserialize)]
pub enum CurationError {
    #[error("entry not found: {0:?}")]
    NotFound(MemoryId),
    #[error("merge failed: source and target share no domain/topic overlap")]
    MergeFailed,
    #[error("invalid operation: {0}")]
    Invalid(String),
}

// ============================================================================
// 5. CURATE EXECUTOR
// ============================================================================

/// Executes a batch of `CurateOperation`s atomically against a `ContextTree`.
/// All operations are validated before any mutation is applied; if any operation
/// fails validation, the entire batch is rejected and the tree is left unchanged.
pub struct CurateExecutor<'a> {
    tree: &'a mut ContextTree,
}

impl<'a> CurateExecutor<'a> {
    pub fn new(tree: &'a mut ContextTree) -> Self {
        Self { tree }
    }

    /// Execute a batch of curation operations atomically.
    pub fn execute(&mut self, ops: Vec<CurateOperation>) -> CurationResult {
        let mut details = Vec::with_capacity(ops.len());
        let mut rollback = Vec::new();
        let mut failed = false;

        // Pre-validate every operation so we can abort before touching the tree.
        for op in &ops {
            if let Err(e) = self.validate(op) {
                return CurationResult {
                    success: false,
                    status: CurateStatus::Failed,
                    details: vec![CurateResult {
                        memory_id: MemoryId::default(),
                        status: CurateStatus::Failed,
                        detail: e.to_string(),
                    }],
                    timestamp: Utc::now(),
                };
            }
        }

        for op in ops {
            match self.apply(op, &mut rollback) {
                Ok(result) => details.push(result),
                Err(e) => {
                    failed = true;
                    details.push(CurateResult {
                        memory_id: MemoryId::default(),
                        status: CurateStatus::Failed,
                        detail: e.to_string(),
                    });
                    break;
                }
            }
        }

        if failed {
            self.rollback(rollback);
            CurationResult {
                success: false,
                status: CurateStatus::Failed,
                details,
                timestamp: Utc::now(),
            }
        } else {
            CurationResult {
                success: true,
                status: overall_status(&details),
                details,
                timestamp: Utc::now(),
            }
        }
    }

    fn validate(&self, op: &CurateOperation) -> Result<(), CurationError> {
        match op {
            CurateOperation::Add { entry, .. } => {
                if self.tree.find(entry.id).is_some() {
                    return Err(CurationError::Invalid(format!(
                        "entry {} already exists; use Upsert instead",
                        hex_id(&entry.id)
                    )));
                }
            }
            CurateOperation::Update { id, .. } | CurateOperation::Delete { id, .. } => {
                if self.tree.find(*id).is_none() {
                    return Err(CurationError::NotFound(*id));
                }
            }
            CurateOperation::Merge { source_ids, target, .. } => {
                if source_ids.is_empty() {
                    return Err(CurationError::Invalid(
                        "merge requires at least one source id".into(),
                    ));
                }
                for sid in source_ids {
                    if let Some(source) = self.tree.find(*sid) {
                        if source.domain != target.domain || source.topic != target.topic {
                            return Err(CurationError::MergeFailed);
                        }
                    } else {
                        return Err(CurationError::NotFound(*sid));
                    }
                }
            }
            CurateOperation::Upsert { .. } => {}
        }
        Ok(())
    }

    fn apply(
        &mut self,
        op: CurateOperation,
        rollback: &mut Vec<RollbackAction>,
    ) -> Result<CurateResult, CurationError> {
        match op {
            CurateOperation::Add { entry, reason, .. } => {
                let id = entry.id;
                rollback.push(RollbackAction::Remove(id));
                self.tree.insert(entry);
                Ok(CurateResult {
                    memory_id: id,
                    status: CurateStatus::Created,
                    detail: reason,
                })
            }
            CurateOperation::Update { id, entry, reason, .. } => {
                let old = self
                    .tree
                    .find(id)
                    .ok_or(CurationError::NotFound(id))?
                    .clone();
                rollback.push(RollbackAction::Restore(old));
                self.tree.update(entry)?;
                Ok(CurateResult {
                    memory_id: id,
                    status: CurateStatus::Updated,
                    detail: reason,
                })
            }
            CurateOperation::Upsert { entry, reason, .. } => {
                let id = entry.id;
                let status = if self.tree.find(id).is_some() {
                    let old = self.tree.find(id).unwrap().clone();
                    rollback.push(RollbackAction::Restore(old));
                    CurateStatus::Updated
                } else {
                    rollback.push(RollbackAction::Remove(id));
                    CurateStatus::Created
                };
                self.tree.upsert(entry);
                Ok(CurateResult {
                    memory_id: id,
                    status,
                    detail: reason,
                })
            }
            CurateOperation::Merge {
                source_ids,
                mut target,
                reason,
                ..
            } => {
                let mut merged_content = target.content.clone();
                let mut merged_relations: Vec<Relation> = target.relations.clone();
                let mut merged_rationale = target.rationale.clone();

                for sid in &source_ids {
                    let source = self.tree.find(*sid).ok_or(CurationError::NotFound(*sid))?;
                    if !merged_content.contains(&source.content) {
                        if !merged_content.is_empty() {
                            merged_content.push('\n');
                        }
                        merged_content.push_str(&source.content);
                    }
                    for rel in &source.relations {
                        if !merged_relations.iter().any(|r| {
                            r.source == rel.source
                                && r.target == rel.target
                                && r.relation_type == rel.relation_type
                        }) {
                            merged_relations.push(rel.clone());
                        }
                    }
                    if !merged_rationale.is_empty() {
                        merged_rationale.push_str("; ");
                    }
                    merged_rationale.push_str(&source.rationale);
                }

                target.content = merged_content;
                target.relations = merged_relations;
                target.rationale = merged_rationale;
                target.touch();

                // Atomically replace sources with the merged target.
                let removed_sources: Vec<ContextEntry> = source_ids
                    .iter()
                    .filter_map(|sid| self.tree.remove(*sid))
                    .collect();
                rollback.push(RollbackAction::RestoreMany(removed_sources));

                let old_target = self.tree.find(target.id).cloned();
                if let Some(old) = old_target {
                    rollback.push(RollbackAction::Restore(old));
                } else {
                    rollback.push(RollbackAction::Remove(target.id));
                }
                self.tree.upsert(target.clone());

                Ok(CurateResult {
                    memory_id: target.id,
                    status: CurateStatus::Merged,
                    detail: reason,
                })
            }
            CurateOperation::Delete { id, reason, .. } => {
                let old = self.tree.find(id).ok_or(CurationError::NotFound(id))?.clone();
                rollback.push(RollbackAction::Restore(old));
                let mut entry = self.tree.remove(id).ok_or(CurationError::NotFound(id))?;
                entry.lifecycle = EntryLifecycle::Archived;
                entry.touch();
                self.tree.insert(entry);
                Ok(CurateResult {
                    memory_id: id,
                    status: CurateStatus::Deleted,
                    detail: reason,
                })
            }
        }
    }

    fn rollback(&mut self, actions: Vec<RollbackAction>) {
        // Reverse the recorded actions and replay them to restore prior state.
        for action in actions.into_iter().rev() {
            match action {
                RollbackAction::Remove(id) => {
                    self.tree.remove(id);
                }
                RollbackAction::Restore(entry) => {
                    self.tree.upsert(entry);
                }
                RollbackAction::RestoreMany(entries) => {
                    for entry in entries {
                        self.tree.upsert(entry);
                    }
                }
            }
        }
    }
}

#[derive(Debug, Clone)]
enum RollbackAction {
    Remove(MemoryId),
    Restore(ContextEntry),
    RestoreMany(Vec<ContextEntry>),
}

fn overall_status(details: &[CurateResult]) -> CurateStatus {
    if details.iter().any(|d| d.status == CurateStatus::Merged) {
        CurateStatus::Merged
    } else if details.iter().any(|d| d.status == CurateStatus::Updated) {
        CurateStatus::Updated
    } else if details.iter().all(|d| d.status == CurateStatus::Created) {
        CurateStatus::Created
    } else {
        CurateStatus::Success
    }
}

fn hex_id(id: &MemoryId) -> String {
    id.0.iter().map(|b| format!("{:02x}", b)).collect()
}

// Extension methods used by the executor for atomic upsert and merge semantics.
impl ContextTree {
    /// Insert or replace an entry, updating the hierarchy if necessary.
    fn upsert(&mut self, entry: ContextEntry) {
        let needs_hierarchy_update = self
            .entries
            .get(&entry.id)
            .map(|old| {
                old.domain != entry.domain || old.topic != entry.topic || old.subtopic != entry.subtopic
            })
            .unwrap_or(true);

        if needs_hierarchy_update {
            let domain = self
                .root
                .topics
                .entry(entry.domain.clone())
                .or_insert_with(|| TopicNode {
                    name: entry.domain.clone(),
                    subtopics: HashMap::new(),
                });
            let topic = domain
                .subtopics
                .entry(entry.topic.clone())
                .or_insert_with(|| SubtopicNode {
                    name: entry.topic.clone(),
                    entries: Vec::new(),
                });
            if !topic.entries.contains(&entry.id) {
                topic.entries.push(entry.id);
            }
        }

        self.entries.insert(entry.id, entry);
    }
}

// ============================================================================
// 6. CROSS-REFERENCE ENGINE
// ============================================================================

/// Maintains explicit cross-references between context entries.
///
/// References are stored bidirectionally and support cross-domain links. The
/// engine tracks provenance so the system knows why a reference was created.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CrossReferenceEngine {
    /// Forward references from source id to target ids.
    references: HashMap<MemoryId, Vec<MemoryId>>,
    /// Reverse index: target id -> source ids that reference it.
    reverse: HashMap<MemoryId, Vec<MemoryId>>,
    /// Provenance record for each (source, target) pair.
    provenance: HashMap<(MemoryId, MemoryId), Provenance>,
}

impl CrossReferenceEngine {
    /// Create an empty cross-reference engine.
    pub fn new() -> Self {
        Self::default()
    }

    /// Add a unidirectional reference from `source` to `target`.
    pub fn add_reference(
        &mut self,
        source: MemoryId,
        target: MemoryId,
        provenance: Provenance,
    ) {
        if source == target {
            return;
        }

        let forward = self.references.entry(source).or_default();
        if !forward.contains(&target) {
            forward.push(target);
        }

        let reverse = self.reverse.entry(target).or_default();
        if !reverse.contains(&source) {
            reverse.push(source);
        }

        self.provenance.insert((source, target), provenance);
    }

    /// Add a bidirectional reference between `a` and `b`.
    pub fn add_bidirectional(
        &mut self,
        a: MemoryId,
        b: MemoryId,
        provenance: Provenance,
    ) {
        self.add_reference(a, b, provenance.clone());
        self.add_reference(b, a, provenance);
    }

    /// Scan two entries and create a bidirectional cross-domain link if they
    /// share a concept token in their content.
    pub fn link_shared_concept(
        &mut self,
        a: &ContextEntry,
        b: &ContextEntry,
        concept: impl Into<String>,
        confidence: f32,
    ) {
        let concept_str = concept.into();
        let a_contains = a.content.contains(&concept_str);
        let b_contains = b.content.contains(&concept_str);

        if a_contains && b_contains && a.domain != b.domain {
            let provenance = Provenance {
                source: format!("shared_concept:{}", concept_str),
                confidence,
                timestamp: Utc::now(),
            };
            self.add_bidirectional(a.id, b.id, provenance);
        }
    }

    /// Get the ids that `source` references.
    pub fn referenced_by(&self, source: MemoryId) -> Vec<MemoryId> {
        self.references.get(&source).cloned().unwrap_or_default()
    }

    /// Get the ids that reference `target` (reverse direction).
    pub fn references_to(&self, target: MemoryId) -> Vec<MemoryId> {
        self.reverse.get(&target).cloned().unwrap_or_default()
    }

    /// Remove all references involving `id` in either direction.
    pub fn remove(&mut self, id: MemoryId) {
        if let Some(targets) = self.references.remove(&id) {
            for target in &targets {
                self.reverse.entry(*target).and_modify(|v| v.retain(|x| *x != id));
                self.provenance.remove(&(id, *target));
            }
        }

        if let Some(sources) = self.reverse.remove(&id) {
            for source in &sources {
                self.references
                    .entry(*source)
                    .and_modify(|v| v.retain(|x| *x != id));
                self.provenance.remove(&(*source, id));
            }
        }
    }

    /// Total number of stored forward references.
    pub fn reference_count(&self) -> usize {
        self.references.values().map(|v| v.len()).sum()
    }
}

// ============================================================================
// 7. TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_entry(
        domain: &str,
        topic: &str,
        subtopic: &str,
        content: &str,
    ) -> ContextEntry {
        ContextEntry::new(
            MemoryId::new(),
            domain.to_string(),
            topic.to_string(),
            subtopic.to_string(),
            content.to_string(),
        )
    }

    #[test]
    fn test_curation_loop_100_facts() {
        let mut tree = ContextTree::new();
        let mut ops = Vec::new();

        for i in 0..100 {
            let entry = make_entry(
                "knowledge",
                "facts",
                "batch",
                &format!("fact number {}", i),
            );
            ops.push(CurateOperation::add(entry, format!("curate fact {}", i)));
        }

        let mut executor = CurateExecutor::new(&mut tree);
        let result = executor.execute(ops);
        assert!(result.success, "curation loop should succeed: {:?}", result);
        assert_eq!(tree.len(), 100);

        for i in 0..100 {
            let matches = tree
                .entries()
                .filter(|e| e.content == format!("fact number {}", i))
                .count();
            assert_eq!(matches, 1, "fact {} should be present exactly once", i);
        }
    }

    #[test]
    fn test_cross_reference_bidirectional_and_cross_domain() {
        let a = make_entry(
            "physics",
            "thermodynamics",
            "entropy",
            "entropy measures disorder in a closed system.",
        );
        let b = make_entry(
            "philosophy",
            "concepts",
            "chaos",
            "entropy as a metaphor for disorder in human affairs.",
        );

        let mut engine = CrossReferenceEngine::new();
        engine.link_shared_concept(&a, &b, "entropy", 0.95);

        assert!(engine.referenced_by(a.id).contains(&b.id));
        assert!(engine.referenced_by(b.id).contains(&a.id));
        assert_eq!(engine.reference_count(), 2);
    }

    #[test]
    fn test_atomicity_failure_no_partial_state() {
        let mut tree = ContextTree::new();
        let mut ops = Vec::new();

        for i in 0..100 {
            let entry = make_entry(
                "knowledge",
                "facts",
                "batch",
                &format!("fact number {}", i),
            );
            ops.push(CurateOperation::add(entry, format!("curate fact {}", i)));
        }

        // Force failure on item 50 by trying to update a non-existent id mid-batch.
        let bad_id = MemoryId::new();
        ops.insert(
            50,
            CurateOperation::Update {
                id: bad_id,
                entry: make_entry("knowledge", "facts", "batch", "intruder"),
                reason: "this should fail".into(),
                timestamp: Utc::now(),
            },
        );

        let mut executor = CurateExecutor::new(&mut tree);
        let result = executor.execute(ops);
        assert!(!result.success);
        assert_eq!(tree.len(), 0, "tree must remain empty after atomic rollback");

        // Retry with the failing operation removed.
        let mut retry_ops = Vec::new();
        for i in 0..100 {
            let entry = make_entry(
                "knowledge",
                "facts",
                "batch",
                &format!("fact number {}", i),
            );
            retry_ops.push(CurateOperation::add(entry, format!("retry fact {}", i)));
        }

        let mut executor2 = CurateExecutor::new(&mut tree);
        let retry_result = executor2.execute(retry_ops);
        assert!(retry_result.success);
        assert_eq!(tree.len(), 100);
    }

    #[test]
    fn test_lifecycle_transitions() {
        let mut tree = ContextTree::new();
        let entry = make_entry("life", "states", "hibernation", "entry to transition");
        let id = entry.id;

        {
            let mut executor = CurateExecutor::new(&mut tree);
            executor.execute(vec![CurateOperation::add(entry, "initial add")]);
        }

        assert_eq!(tree.find(id).unwrap().lifecycle, EntryLifecycle::Active);

        let mut hibernated = tree.find(id).unwrap().clone();
        hibernated.lifecycle = EntryLifecycle::Hibernated;
        {
            let mut executor = CurateExecutor::new(&mut tree);
            executor.execute(vec![CurateOperation::update(id, hibernated, "hibernate")]);
        }
        assert_eq!(tree.find(id).unwrap().lifecycle, EntryLifecycle::Hibernated);

        let mut archived = tree.find(id).unwrap().clone();
        archived.lifecycle = EntryLifecycle::Archived;
        {
            let mut executor = CurateExecutor::new(&mut tree);
            executor.execute(vec![CurateOperation::update(id, archived, "archive")]);
        }
        assert_eq!(tree.find(id).unwrap().lifecycle, EntryLifecycle::Archived);
    }

    #[test]
    fn test_upsert_no_duplication() {
        let mut tree = ContextTree::new();
        let entry = make_entry("domain", "topic", "subtopic", "original content");
        let id = entry.id;

        {
            let mut executor = CurateExecutor::new(&mut tree);
            executor.execute(vec![CurateOperation::upsert(entry, "first upsert")]);
        }
        assert_eq!(tree.len(), 1);

        let mut second = tree.find(id).unwrap().clone();
        second.content = "updated content".into();
        {
            let mut executor = CurateExecutor::new(&mut tree);
            executor.execute(vec![CurateOperation::upsert(second, "second upsert")]);
        }

        assert_eq!(tree.len(), 1);
        assert_eq!(tree.find(id).unwrap().content, "updated content");
    }

    #[test]
    fn test_merge_combines_content_and_relations() {
        let mut tree = ContextTree::new();

        let mut source_a = make_entry(
            "project",
            "api",
            "endpoints",
            "GET /users returns a list of users.",
        );
        let target_id = source_a.id;
        source_a.relations.push(Relation {
            source: target_id,
            target: MemoryId::new(),
            relation_type: "depends_on".into(),
            weight: 0.9,
        });

        let source_b = make_entry(
            "project",
            "api",
            "endpoints",
            "POST /users creates a new user.",
        );

        let mut executor = CurateExecutor::new(&mut tree);
        executor.execute(vec![
            CurateOperation::add(source_a.clone(), "add source a"),
            CurateOperation::add(source_b.clone(), "add source b"),
        ]);

        let target = make_entry(
            "project",
            "api",
            "endpoints",
            "Base API user routes.",
        );
        let merged_id = target.id;
        let merge_op = CurateOperation::merge(
            vec![source_a.id, source_b.id],
            target,
            "merge user endpoints",
        );

        let result = executor.execute(vec![merge_op]);
        assert!(result.success);

        let merged = tree.find(merged_id).unwrap();
        assert!(merged.content.contains("GET /users returns a list of users."));
        assert!(merged.content.contains("POST /users creates a new user."));
        assert_eq!(merged.relations.len(), 1);
        assert_eq!(tree.find(source_a.id), None);
        assert_eq!(tree.find(source_b.id), None);
    }
}
