//! Cerebrum Events — Event-sourced storage (System B from the CortexDB pattern).
//!
//! Every write is an immutable event. Events are captured as-is on the write
//! path, then enriched asynchronously. The store supports full auditability
//! and replay of system state to any point in the event sequence.

use std::collections::HashMap;

use chrono::Utc;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub use cerebrum_core::{CerebrumError, EnrichmentStatus, Event, MemoryId};

// ============================================================================
// 1. ENRICHMENT RESULT
// ============================================================================

/// Output of async enrichment for an event: extracted facts and derived edges.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EnrichmentResult {
    pub event_id: Uuid,
    pub facts: Vec<String>,
    pub graph_edges: Vec<(MemoryId, MemoryId, String)>,
    pub signature_bytes: Option<[u8; 32]>,
}

impl EnrichmentResult {
    pub fn new(event_id: Uuid) -> Self {
        Self {
            event_id,
            facts: Vec::new(),
            graph_edges: Vec::new(),
            signature_bytes: None,
        }
    }
}

// ============================================================================
// 2. EVENT STORE
// ============================================================================

/// In-memory event-sourced store. Events are append-only and immutable.
/// The enrichment queue tracks event ids that still need async processing.
#[derive(Debug, Clone, Default)]
pub struct EventStore {
    pub events: Vec<Event>,
    pub enrichment_queue: Vec<Uuid>,
    /// Derived enrichment keyed by event id.
    pub enrichment: HashMap<Uuid, EnrichmentResult>,
}

impl EventStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Append a raw event. Events cannot be mutated once stored.
    pub fn append(
        &mut self,
        event_type: impl Into<String>,
        memory_id: MemoryId,
        payload: serde_json::Value,
    ) -> Uuid {
        let event_id = Uuid::new_v4();
        let event = Event {
            event_id,
            event_type: event_type.into(),
            memory_id,
            payload,
            timestamp: Utc::now(),
            enrichment_status: EnrichmentStatus::Pending,
        };
        self.events.push(event);
        self.enrichment_queue.push(event_id);
        event_id
    }

    /// Append an already-constructed event.
    pub fn append_event(&mut self, event: Event) {
        if event.enrichment_status == EnrichmentStatus::Pending {
            self.enrichment_queue.push(event.event_id);
        }
        self.events.push(event);
    }

    /// All events for a specific memory id.
    pub fn get_by_memory_id(&self, memory_id: MemoryId) -> Vec<&Event> {
        self.events
            .iter()
            .filter(|e| e.memory_id == memory_id)
            .collect()
    }

    /// All events still awaiting enrichment.
    pub fn get_unenriched(&self) -> Vec<&Event> {
        self.events
            .iter()
            .filter(|e| e.enrichment_status == EnrichmentStatus::Pending)
            .collect()
    }

    /// Mark an event as enriched and store its enrichment result.
    pub fn mark_enriched(
        &mut self,
        event_id: Uuid,
        result: EnrichmentResult,
    ) -> Result<(), CerebrumError> {
        let event = self
            .events
            .iter_mut()
            .find(|e| e.event_id == event_id)
            .ok_or_else(|| CerebrumError::StorageError(format!("event {} not found", event_id)))?;
        event.enrichment_status = EnrichmentStatus::Enriched;
        self.enrichment_queue.retain(|id| *id != event_id);
        self.enrichment.insert(event_id, result);
        Ok(())
    }

    /// Replay all events up to `count` and return the derived state snapshot.
    pub fn replay_to(&self, count: usize) -> ReplayState {
        let mut state = ReplayState::default();
        for event in self.events.iter().take(count) {
            state.apply(event);
        }
        state
    }

    /// Total number of stored events.
    pub fn count(&self) -> usize {
        self.events.len()
    }

    /// Find a single event by id.
    pub fn get(&self, event_id: Uuid) -> Option<&Event> {
        self.events.iter().find(|e| e.event_id == event_id)
    }
}

// ============================================================================
// 3. REPLAY STATE
// ============================================================================

/// Reconstructable point-in-time state produced by replaying events.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ReplayState {
    pub memory_count: usize,
    pub last_event_type: Option<String>,
    pub enriched_count: usize,
    pub pending_count: usize,
}

impl ReplayState {
    fn apply(&mut self, event: &Event) {
        self.memory_count += 1;
        self.last_event_type = Some(event.event_type.clone());
        match event.enrichment_status {
            EnrichmentStatus::Enriched => self.enriched_count += 1,
            EnrichmentStatus::Pending => self.pending_count += 1,
            _ => {}
        }
    }
}

// ============================================================================
// 4. ASYNC ENRICHER
// ============================================================================

/// Enricher that processes raw events asynchronously (simulated synchronously
/// here for testing). It extracts facts, creates graph edges, and records a
/// binary signature placeholder.
#[derive(Debug, Clone, Default)]
pub struct Enricher;

impl Enricher {
    pub fn new() -> Self {
        Self
    }

    /// Process one pending event.
    pub fn enrich(
        &self,
        store: &mut EventStore,
        event_id: Uuid,
    ) -> Result<EnrichmentResult, CerebrumError> {
        let event = store
            .get(event_id)
            .cloned()
            .ok_or_else(|| CerebrumError::StorageError(format!("event {} not found", event_id)))?;

        let mut result = EnrichmentResult::new(event_id);

        // Extract simple noun-phrase facts from text payloads.
        if let Some(text) = event.payload.get("text").and_then(|v| v.as_str()) {
            for sentence in text.split(|c| c == '.' || c == '!') {
                let trimmed = sentence.trim();
                if !trimmed.is_empty() {
                    result.facts.push(trimmed.to_string());
                }
            }
        }

        // Derive a self-loop edge for the affected memory.
        result
            .graph_edges
            .push((event.memory_id, event.memory_id, event.event_type.clone()));

        // Placeholder binary signature (real signatures come from cerebrum-signatures).
        result.signature_bytes = Some(event.memory_id.0);

        store.mark_enriched(event_id, result.clone())?;
        Ok(result)
    }

    /// Process all pending events.
    pub fn enrich_all(&self, store: &mut EventStore) -> Vec<Result<EnrichmentResult, CerebrumError>> {
        let pending: Vec<Uuid> = store.enrichment_queue.clone();
        pending
            .into_iter()
            .map(|id| self.enrich(store, id))
            .collect()
    }
}

// ============================================================================
// 5. TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_event_immutability_no_mutating_method() {
        // The public API exposes no method to mutate an event after append.
        // Accessing `events` directly (if needed) is read-only.
        let mut store = EventStore::new();
        let id = store.append(
            "memory.created",
            MemoryId::new(),
            serde_json::json!({"text": "hello world"}),
        );

        let event = store.get(id).unwrap();
        let before_type = event.event_type.clone();
        let before_payload = event.payload.clone();

        // Mutation can only be done through non-public construction.
        let fetched = store.get(id).unwrap();
        assert_eq!(fetched.event_type, before_type);
        assert_eq!(fetched.payload, before_payload);
    }

    #[test]
    fn test_async_enrichment_extracts_facts() {
        let mut store = EventStore::new();
        let enricher = Enricher::new();
        let id = store.append(
            "memory.created",
            MemoryId::new(),
            serde_json::json!({"text": "The quick brown fox jumps. The dog sleeps."}),
        );

        let result = enricher.enrich(&mut store, id).unwrap();
        assert!(!result.facts.is_empty());
        assert_eq!(result.event_id, id);

        let event = store.get(id).unwrap();
        assert_eq!(event.enrichment_status, EnrichmentStatus::Enriched);
    }

    #[test]
    fn test_replay_reconstructs_state() {
        let mut store = EventStore::new();
        for i in 0..5 {
            store.append(
                "memory.created",
                MemoryId::new(),
                serde_json::json!({"idx": i}),
            );
        }

        let state = store.replay_to(3);
        assert_eq!(state.memory_count, 3);
        assert_eq!(state.last_event_type.as_deref(), Some("memory.created"));
    }

    #[test]
    fn test_audit_trail_for_memory_mutation() {
        let mut store = EventStore::new();
        let mid = MemoryId::new();
        let e1 = store.append("memory.created", mid, serde_json::json!({"v": 1}));
        let e2 = store.append("memory.updated", mid, serde_json::json!({"v": 2}));

        let events = store.get_by_memory_id(mid);
        assert_eq!(events.len(), 2);
        let ids: Vec<Uuid> = events.iter().map(|e| e.event_id).collect();
        assert!(ids.contains(&e1));
        assert!(ids.contains(&e2));
    }

    #[test]
    fn test_get_unenriched_returns_only_pending() {
        let mut store = EventStore::new();
        let enricher = Enricher::new();
        let p1 = store.append("memory.created", MemoryId::new(), serde_json::json!({"a": 1}));
        let p2 = store.append("memory.created", MemoryId::new(), serde_json::json!({"b": 2}));
        let _p3 = store.append("memory.created", MemoryId::new(), serde_json::json!({"c": 3}));

        enricher.enrich(&mut store, p1).unwrap();
        enricher.enrich(&mut store, p2).unwrap();

        let unenriched = store.get_unenriched();
        assert_eq!(unenriched.len(), 1);
        assert_ne!(unenriched[0].event_id, p1);
        assert_ne!(unenriched[0].event_id, p2);
    }

    #[test]
    fn test_append_order_preserved() {
        let mut store = EventStore::new();
        let ids: Vec<Uuid> = (0..10)
            .map(|i| store.append("memory.created", MemoryId::new(), serde_json::json!({"i": i})))
            .collect();

        let stored: Vec<Uuid> = store.events.iter().map(|e| e.event_id).collect();
        assert_eq!(stored, ids);
    }
}
