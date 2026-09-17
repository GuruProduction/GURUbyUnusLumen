//! Cerebrum Episodic — Episodic reconstruction: the full context of discovery.
//!
//! Phase 0.2.4 — Track E. Stores and reconstructs episodes with source, trigger,
//! context, emotional valence, timeline, and related-episode links.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use cerebrum_core::{Episode, MemoryId};

// ============================================================================
// 1. EMOTIONAL VALENCE
// ============================================================================

/// Optional emotional valence attached to an episode from user feedback.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum EmotionalValence {
    /// Positive, energising discovery.
    Exciting,
    /// Worrisome or negative discovery.
    Concerning,
    /// Ordinary, unremarkable discovery.
    Routine,
    /// Unexpected discovery.
    Surprising,
}

// ============================================================================
// 2. EPISODIC CONFIG
// ============================================================================

/// Configuration for the episodic store.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct EpisodicConfig {
    /// Maximum number of episodes to retain (default 10000).
    pub max_episodes: usize,
    /// Whether emotional valence tracking is enabled (default true).
    pub valence_tracking: bool,
}

impl Default for EpisodicConfig {
    fn default() -> Self {
        Self {
            max_episodes: 10000,
            valence_tracking: true,
        }
    }
}

// ============================================================================
// 3. RICH EPISODE
// ============================================================================

/// A fully reconstructed episode with context, valence, and timeline links.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RichEpisode {
    pub episode: Episode,
    pub valence: EmotionalValence,
    pub before_context: String,
    pub after_context: String,
    pub related_episodes: Vec<MemoryId>,
}

// ============================================================================
// 4. EPISODIC STORE
// ============================================================================

/// Store of reconstructed episodic memories.
#[derive(Debug, Clone, Default)]
pub struct EpisodicStore {
    pub episodes: HashMap<MemoryId, RichEpisode>,
    pub config: EpisodicConfig,
}

impl EpisodicStore {
    /// Create an empty episodic store with default configuration.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create an episodic store with a specific configuration.
    pub fn with_config(config: EpisodicConfig) -> Self {
        Self {
            episodes: HashMap::new(),
            config,
        }
    }

    /// Record an episode and return its memory id.
    pub fn record(&mut self, episode: Episode, rich: RichEpisode) -> MemoryId {
        let id = episode.memory_id;
        if self.episodes.len() >= self.config.max_episodes {
            // Evict the oldest episode when at capacity.
            if let Some(oldest_id) = self
                .episodes
                .iter()
                .min_by(|a, b| a.1.episode.discovered_at.cmp(&b.1.episode.discovered_at))
                .map(|(id, _)| *id)
            {
                self.episodes.remove(&oldest_id);
            }
        }
        self.episodes.insert(id, rich);
        id
    }

    /// Reconstruct the full rich episode for a memory id.
    pub fn reconstruct(&self,
        memory_id: MemoryId,
    ) -> Option<&RichEpisode> {
        self.episodes.get(&memory_id)
    }

    /// Find all rich episodes whose source contains the query string.
    pub fn find_by_source(&self,
        source: &str,
    ) -> Vec<&RichEpisode> {
        let query = source.to_lowercase();
        self.episodes
            .values()
            .filter(|r| r.episode.source.to_lowercase().contains(&query))
            .collect()
    }

    /// Find all rich episodes whose trigger contains the query string.
    pub fn find_by_trigger(&self,
        trigger: &str,
    ) -> Vec<&RichEpisode> {
        let query = trigger.to_lowercase();
        self.episodes
            .values()
            .filter(|r| r.episode.trigger.to_lowercase().contains(&query))
            .collect()
    }

    /// Find all rich episodes matching a given emotional valence.
    pub fn find_by_valence(&self,
        valence: EmotionalValence,
    ) -> Vec<&RichEpisode> {
        self.episodes
            .values()
            .filter(|r| r.valence == valence)
            .collect()
    }
}

// ============================================================================
// 5. EPISODIC ENGINE
// ============================================================================

/// Higher-level episodic memory engine wrapping the store.
#[derive(Debug, Clone, Default)]
pub struct EpisodicEngine {
    pub store: EpisodicStore,
}

impl EpisodicEngine {
    /// Create a new episodic engine.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create an episodic engine with a specific configuration.
    pub fn with_config(config: EpisodicConfig) -> Self {
        Self {
            store: EpisodicStore::with_config(config),
        }
    }

    /// Record an episode and return its memory id.
    pub fn record(
        &mut self,
        episode: Episode,
        before_context: String,
        after_context: String,
        related_episodes: Vec<MemoryId>,
    ) -> MemoryId {
        let valence = if self.store.config.valence_tracking {
            infer_valence(&episode.context)
        } else {
            EmotionalValence::Routine
        };
        let rich = RichEpisode {
            episode: episode.clone(),
            valence,
            before_context,
            after_context,
            related_episodes,
        };
        self.store.record(episode, rich)
    }

    /// Reconstruct the full story for a memory id.
    pub fn reconstruct(
        &self,
        memory_id: MemoryId,
    ) -> Option<&RichEpisode> {
        self.store.reconstruct(memory_id)
    }

    /// Find episodes by source string.
    pub fn find_by_source(
        &self,
        source: &str,
    ) -> Vec<&RichEpisode> {
        self.store.find_by_source(source)
    }

    /// Find episodes by trigger string.
    pub fn find_by_trigger(
        &self,
        trigger: &str,
    ) -> Vec<&RichEpisode> {
        self.store.find_by_trigger(trigger)
    }

    /// Find episodes by emotional valence.
    pub fn find_by_valence(
        &self,
        valence: EmotionalValence,
    ) -> Vec<&RichEpisode> {
        self.store.find_by_valence(valence)
    }
}

/// Infer emotional valence from episode context.
///
/// This baseline uses simple keyword heuristics. Production would use user
/// feedback or a learned model.
fn infer_valence(context: &str) -> EmotionalValence {
    let lower = context.to_lowercase();
    if lower.contains("error") || lower.contains("crash") || lower.contains("fail") || lower.contains("urgent") {
        return EmotionalValence::Concerning;
    }
    if lower.contains("amazing") || lower.contains("exciting") || lower.contains("great") || lower.contains("breakthrough") {
        return EmotionalValence::Exciting;
    }
    if lower.contains("surprising") || lower.contains("unexpected") || lower.contains("shocking") {
        return EmotionalValence::Surprising;
    }
    EmotionalValence::Routine
}

// ============================================================================
// 6. UNIT TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::{Episode, MemoryId};
    use chrono::Utc;

    fn make_episode(memory_id_byte: u8, source: &str, trigger: &str, context: &str) -> Episode {
        Episode {
            memory_id: MemoryId([memory_id_byte; 32]),
            source: source.to_string(),
            trigger: trigger.to_string(),
            context: context.to_string(),
            discovered_at: Utc::now(),
        }
    }

    #[test]
    fn test_record_and_reconstruct() {
        let mut engine = EpisodicEngine::new();
        let episode = make_episode(
            1,
            "conversation://chat-42",
            "user asked about Rust",
            "We were discussing systems programming and memory safety",
        );
        let id = episode.memory_id;
        engine.record(
            episode.clone(),
            "Earlier: general programming languages".to_string(),
            "Later: Rust ownership model".to_string(),
            vec![MemoryId([2u8; 32])],
        );

        let rich = engine.reconstruct(id).expect("episode should exist");
        assert_eq!(rich.episode.memory_id, id);
        assert_eq!(rich.before_context, "Earlier: general programming languages");
        assert_eq!(rich.after_context, "Later: Rust ownership model");
        assert!(rich.related_episodes.contains(&MemoryId([2u8; 32])));
    }

    #[test]
    fn test_find_by_source() {
        let mut engine = EpisodicEngine::new();
        let episode = make_episode(
            3,
            "file:///design_notes.md",
            "opened architecture doc",
            "Architecture review in progress",
        );
        let id = engine.record(
            episode,
            "Opened file browser".to_string(),
            "Read design section".to_string(),
            vec![],
        );

        let matches = engine.find_by_source("design_notes");
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].episode.memory_id, id);
    }

    #[test]
    fn test_find_by_trigger() {
        let mut engine = EpisodicEngine::new();
        let episode = make_episode(
            4,
            "notification://slack-123",
            "deploy failed alert",
            "Production deployment failed unexpectedly",
        );
        let id = engine.record(
            episode,
            "CI pipeline running".to_string(),
            "Rollback initiated".to_string(),
            vec![],
        );

        let matches = engine.find_by_trigger("deploy failed");
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].episode.memory_id, id);
    }

    #[test]
    fn test_find_by_valence() {
        let mut engine = EpisodicEngine::new();
        let exciting = make_episode(
            5,
            "conversation://chat-99",
            "user announced launch",
            "This is an exciting breakthrough for the product",
        );
        engine.record(exciting, "".to_string(), "".to_string(), vec![]);

        let concerning = make_episode(
            6,
            "notification://pagerduty",
            "server error spike",
            "We are seeing urgent errors in the payment service",
        );
        engine.record(concerning, "".to_string(), "".to_string(), vec![]);

        let exciting_matches = engine.find_by_valence(EmotionalValence::Exciting);
        let concerning_matches = engine.find_by_valence(EmotionalValence::Concerning);
        assert_eq!(exciting_matches.len(), 1);
        assert_eq!(concerning_matches.len(), 1);
    }

    #[test]
    fn test_related_episodes() {
        let mut engine = EpisodicEngine::new();
        let related_id = MemoryId([7u8; 32]);
        let episode = make_episode(
            8,
            "conversation://chat-7",
            "follow-up question",
            "User returned to the topic",
        );
        engine.record(
            episode,
            "Previous discussion".to_string(),
            "Continued discussion".to_string(),
            vec![related_id],
        );

        let rich = engine.reconstruct(MemoryId([8u8; 32])).unwrap();
        assert!(rich.related_episodes.contains(&related_id));
    }
}
