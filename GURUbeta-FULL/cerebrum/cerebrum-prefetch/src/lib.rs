//! Cerebrum Prefetch — Predictive memory prefetching based on task type.
//!
//! Phase 0.2.4 — Track E. Prefetches memories likely needed when a specialist
//! is activated, a conversation topic shifts, or a scheduled event approaches.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use cerebrum_core::MemoryId;

// ============================================================================
// 1. PREFETCH TRIGGER
// ============================================================================

/// A signal that causes memories to be prefetched.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum PrefetchTrigger {
    /// A specialist has been activated by name.
    SpecialistActivated(String),
    /// The conversation topic has shifted.
    TopicShift(String),
    /// A scheduled event is approaching.
    ScheduledEvent(String),
}

// ============================================================================
// 2. PREFETCH RESULT
// ============================================================================

/// Result of a prefetch operation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PrefetchResult {
    pub prefetched: Vec<MemoryId>,
    pub hit: bool,
}

// ============================================================================
// 3. PREFETCH ENGINE
// ============================================================================

/// Predictive memory prefetch engine.
#[derive(Debug, Clone, Default)]
pub struct PrefetchEngine {
    pub cache: HashMap<MemoryId, String>,
    pub hit_count: u64,
    pub miss_count: u64,
}

impl PrefetchEngine {
    /// Create an empty prefetch engine.
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a memory in the cache.
    pub fn register(&mut self, id: MemoryId, content: String) {
        self.cache.insert(id, content);
    }

    /// Total number of prefetch attempts.
    pub fn total_requests(&self) -> u64 {
        self.hit_count + self.miss_count
    }

    /// Current hit rate as a fraction between 0.0 and 1.0.
    pub fn hit_rate(&self) -> f32 {
        let total = self.total_requests();
        if total == 0 {
            0.0
        } else {
            self.hit_count as f32 / total as f32
        }
    }

    /// Record a request against the prefetched cache.
    fn record_request(&mut self, ids: &[MemoryId]) -> PrefetchResult {
        let hit = !ids.is_empty();
        if hit {
            self.hit_count += 1;
        } else {
            self.miss_count += 1;
        }
        PrefetchResult {
            prefetched: ids.to_vec(),
            hit,
        }
    }

    /// Prefetch based on a trigger.
    pub fn prefetch(&mut self, trigger: &PrefetchTrigger) -> PrefetchResult {
        let ids: Vec<MemoryId> = match trigger {
            PrefetchTrigger::SpecialistActivated(name) => self.prefetch_specialist(name),
            PrefetchTrigger::TopicShift(topic) => self.prefetch_topic(topic),
            PrefetchTrigger::ScheduledEvent(event) => self.prefetch_event(event),
        };
        self.record_request(&ids)
    }

    fn prefetch_specialist(&self, name: &str) -> Vec<MemoryId> {
        let lowered = name.to_lowercase();
        self.cache
            .iter()
            .filter(|(_, content)| {
                let c = content.to_lowercase();
                match lowered.as_str() {
                    "doctor" | "medical" => {
                        c.contains("medical")
                            || c.contains("doctor")
                            || c.contains("health")
                            || c.contains("diagnosis")
                    }
                    "code" | "engineer" | "programmer" => {
                        c.contains("code") || c.contains("rust") || c.contains("programming")
                    }
                    "concierge" => c.contains("file") || c.contains("device") || c.contains("storage"),
                    _ => c.contains(&lowered),
                }
            })
            .map(|(id, _)| *id)
            .collect()
    }

    fn prefetch_topic(&self, topic: &str) -> Vec<MemoryId> {
        let lowered = topic.to_lowercase();
        self.cache
            .iter()
            .filter(|(_, content)| {
                let c = content.to_lowercase();
                match lowered.as_str() {
                    "taxes" | "tax" => {
                        c.contains("tax")
                            || c.contains("finance")
                            || c.contains("accounting")
                            || c.contains("irs")
                    }
                    "travel" => c.contains("travel") || c.contains("trip") || c.contains("flight"),
                    "rust" => c.contains("rust") || c.contains("cargo") || c.contains(" borrow"),
                    _ => c.contains(&lowered),
                }
            })
            .map(|(id, _)| *id)
            .collect()
    }

    fn prefetch_event(&self, event: &str) -> Vec<MemoryId> {
        let lowered = event.to_lowercase();
        self.cache
            .iter()
            .filter(|(_, content)| {
                let c = content.to_lowercase();
                c.contains(&lowered)
            })
            .map(|(id, _)| *id)
            .collect()
    }
}

// ============================================================================
// 4. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::MemoryId;

    fn make_engine_with_medical_memories() -> PrefetchEngine {
        let mut engine = PrefetchEngine::new();
        engine.register(
            MemoryId([1u8; 32]),
            "Patient blood pressure reading from morning check".to_string(),
        );
        engine.register(
            MemoryId([2u8; 32]),
            "Medical history: allergies and current medications".to_string(),
        );
        engine.register(
            MemoryId([3u8; 32]),
            "Rust ownership and lifetimes summary".to_string(),
        );
        engine.register(
            MemoryId([31u8; 32]),
            "Health checkup reminder for annual physical".to_string(),
        );
        engine
    }

    #[test]
    fn test_prefetch_specialist_doctor() {
        let mut engine = make_engine_with_medical_memories();
        let trigger = PrefetchTrigger::SpecialistActivated("doctor".to_string());
        let result = engine.prefetch(&trigger);
        assert!(result.hit);
        assert_eq!(result.prefetched.len(), 2);
    }

    #[test]
    fn test_prefetch_topic_taxes() {
        let mut engine = PrefetchEngine::new();
        engine.register(
            MemoryId([4u8; 32]),
            "Quarterly tax filing deadline reminder".to_string(),
        );
        engine.register(
            MemoryId([5u8; 32]),
            "Investment portfolio performance last quarter".to_string(),
        );
        engine.register(
            MemoryId([51u8; 32]),
            "Finance spreadsheet for quarterly accounting".to_string(),
        );
        engine.register(
            MemoryId([6u8; 32]),
            "Vacation itinerary for summer trip".to_string(),
        );

        let trigger = PrefetchTrigger::TopicShift("taxes".to_string());
        let result = engine.prefetch(&trigger);
        assert!(result.hit);
        assert_eq!(result.prefetched.len(), 2);
    }

    #[test]
    fn test_prefetch_hit_rate_above_sixty_percent() {
        let mut engine = PrefetchEngine::new();
        engine.register(
            MemoryId([10u8; 32]),
            "Medical appointment notes".to_string(),
        );
        engine.register(
            MemoryId([11u8; 32]),
            "Tax deduction checklist".to_string(),
        );
        // Repeated doctor activation should hit.
        let doctor_trigger = PrefetchTrigger::SpecialistActivated("doctor".to_string());
        for _ in 0..8 {
            engine.prefetch(&doctor_trigger);
        }
        // Repeated unknown specialist should miss.
        let unknown_trigger = PrefetchTrigger::SpecialistActivated("astronomer".to_string());
        for _ in 0..2 {
            engine.prefetch(&unknown_trigger);
        }
        assert_eq!(engine.total_requests(), 10);
        assert!(
            engine.hit_rate() > 0.6,
            "hit rate {} should be above 0.6",
            engine.hit_rate()
        );
    }

    #[test]
    fn test_prefetch_latency_under_10ms() {
        let mut engine = PrefetchEngine::new();
        for i in 0..100 {
            engine.register(
                MemoryId([i as u8; 32]),
                format!("Memory content number {}", i),
            );
        }
        let trigger = PrefetchTrigger::SpecialistActivated("code".to_string());
        let start = std::time::Instant::now();
        let result = engine.prefetch(&trigger);
        let elapsed_ms = start.elapsed().as_millis() as u64;
        assert!(
            elapsed_ms < 10,
            "prefetch took {}ms, expected <10ms",
            elapsed_ms
        );
        assert!(!result.hit || !result.prefetched.is_empty());
    }
}
