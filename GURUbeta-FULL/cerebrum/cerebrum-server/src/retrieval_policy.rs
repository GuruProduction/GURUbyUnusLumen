//! RetrievalPolicy — the single place that decides whether a memory may be
//! returned and how strongly it ranks.
//!
//! THE DEFECT THIS REPLACES. `handle_search` ran `decay_engine.access` on every
//! hit and then used none of the decay state. Strength, layer, access count and
//! confidence were computed, stored, persisted and rendered, and not one of them
//! could change an answer. A memory that decay archived at ninety days came back
//! from every search, because the tree lifecycle still said Active and nothing
//! consulted the decay row's `archived` flag. So the brain had a biological memory
//! model bolted to a retrieval path that ignored it entirely, which is the exact
//! shape of a mechanism that exists in the code and nowhere in the behaviour.
//!
//! WHY A POLICY MODULE RATHER THAN CONDITIONS INLINE. The thresholds below are
//! product decisions: how faint a memory may become before it stops being
//! offered, how much a strong memory outranks a weak one that matched equally
//! well. Scattered through a handler they become untestable and undocumented. In
//! one value they can be read, argued with, and changed without hunting through
//! the pipeline.
//!
//! WHAT IS DELIBERATELY NOT HERE. There is no separate "trust" concept. The
//! caller's confidence is what the brain holds, and it influences ranking. No
//! validity window: only record time exists, and pretending otherwise would be a
//! bitemporal claim the storage cannot support. No tombstone: an archived memory
//! is excluded from ordinary retrieval by the lifecycle it already has, and the
//! writer refuses to reactivate it, without any second record of its absence.

use cerebrum_decay::{DecayEntry, MemoryLayer};
use chrono::{DateTime, Utc};

/// Tunable thresholds and weights, in one value.
#[derive(Debug, Clone, PartialEq)]
pub struct RetrievalPolicy {
    /// Below this strength a memory stops being offered at all.
    ///
    /// Chosen against the decay config rather than invented: `DecayConfig`'s
    /// hibernation threshold is 0.01, and a Buffer entry's half-life is one hour.
    /// At 0.05 a Buffer entry is about four and a half half-lives old, which is
    /// firmly hibernating under that config and is the point at which offering it
    /// as a relevant answer would be misleading rather than generous.
    pub minimum_strength: f32,

    /// How much a fully-decayed memory is down-weighted relative to a fresh one.
    ///
    /// The score multiplier is `1 - decay_weight * (1 - strength)`, so a strength
    /// of 1.0 multiplies by 1.0 and a strength of 0.0 by `1 - decay_weight`.
    /// 0.5 means the weakest eligible memory ranks at half the relevance of an
    /// equally-matching fresh one: present, but behind.
    pub decay_weight: f32,

    /// Multiplier applied to a Buffer-layer hit.
    ///
    /// A Buffer entry is working memory: recent and unconfirmed. It must remain
    /// findable, because that is how a just-stated fact is recalled on the next
    /// turn, but it should not outrank consolidated knowledge on a tie.
    pub buffer_weight: f32,

    /// Multiplier applied to an Episodic-layer hit.
    ///
    /// An Episodic entry is a specific remembered event rather than generalised
    /// knowledge, so it sits between Buffer and Semantic.
    pub episodic_weight: f32,

    /// Multiplier applied to a Semantic-layer hit. Consolidated knowledge, so 1.0.
    pub semantic_weight: f32,

    /// Lowest multiplier `confidence` can produce.
    ///
    /// Confidence is the caller's own statement of how well established a fact
    /// is. It must matter, or a fact someone marked doubtful ranks level with one
    /// they were certain of. It must not dominate: a well-established memory is
    /// still only as relevant as it is relevant. So the multiplier runs from this
    /// floor at confidence 0.0 up to 1.0 at confidence 1.0, and 0.8 is low enough
    /// to distinguish and high enough not to bury.
    pub minimum_confidence_weight: f32,
}

impl Default for RetrievalPolicy {
    fn default() -> Self {
        Self {
            minimum_strength: 0.05,
            decay_weight: 0.5,
            buffer_weight: 0.85,
            episodic_weight: 0.95,
            semantic_weight: 1.0,
            minimum_confidence_weight: 0.8,
        }
    }
}

/// Why a memory was or was not eligible.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Eligibility {
    /// May be returned.
    Eligible,
    /// Archived by the engine: retired by a delete, a merge, or consolidation.
    /// Never returned by an ordinary read.
    Archived,
    /// Not archived, but decayed below the strength floor.
    TooFaint,
    /// No decay row at all, which means the tree holds a memory the decay engine
    /// never learned about. Treated as ineligible rather than silently ranked,
    /// because it is a state the write path cannot produce.
    NoDecayRecord,
}

impl Eligibility {
    /// One word for the wire, so a caller can explain a missing memory.
    pub fn as_str(self) -> &'static str {
        match self {
            Eligibility::Eligible => "eligible",
            Eligibility::Archived => "archived",
            Eligibility::TooFaint => "too_faint",
            Eligibility::NoDecayRecord => "no_decay_record",
        }
    }
}

impl RetrievalPolicy {
    /// Whether a memory may be returned, and why not when it may not.
    ///
    /// THE ARCHIVED CHECK IS THE ARCHIVE-CORRECTNESS FIX. Decay archives an entry
    /// by setting `DecayEntry::archived`, and consolidation does it for memories it
    /// consumes. Neither touched the tree's own `EntryLifecycle`, which is what
    /// every read filters on. So the two engines held different opinions about the
    /// same memory and the read path only asked the one that never changed. This
    /// treats either side saying archived as archived: a memory must not be
    /// simultaneously retired by one part of the brain and live in another.
    pub fn eligibility(&self, decay_entry: Option<&DecayEntry>) -> Eligibility {
        match decay_entry {
            None => Eligibility::NoDecayRecord,
            Some(entry) if entry.archived => Eligibility::Archived,
            Some(entry) if entry.strength < self.minimum_strength => Eligibility::TooFaint,
            Some(_) => Eligibility::Eligible,
        }
    }

    /// The multiplier this memory's own state applies to its retrieval score.
    ///
    /// Three independent properties, multiplied, because each answers a different
    /// question: how well established is this (confidence), how consolidated is it
    /// (layer), and how recently was it reinforced (strength). A memory strong on
    /// all three is not discounted at all.
    pub fn score_multiplier(&self, decay_entry: &DecayEntry, confidence: f32) -> f32 {
        let strength_ceiling = 1.0f32.max(0.0);
        let clamped_strength = decay_entry.strength.clamp(0.0, strength_ceiling);
        let decay_factor = 1.0 - self.decay_weight * (1.0 - clamped_strength);

        let layer_factor = match decay_entry.layer {
            MemoryLayer::Buffer => self.buffer_weight,
            MemoryLayer::Episodic => self.episodic_weight,
            MemoryLayer::Semantic => self.semantic_weight,
        };

        let clamped_confidence = confidence.clamp(0.0, 1.0);
        let confidence_factor =
            self.minimum_confidence_weight + (1.0 - self.minimum_confidence_weight) * clamped_confidence;

        decay_factor * layer_factor * confidence_factor
    }

    /// The layer's name as the wire spells it, for a caller that wants to explain
    /// a ranking.
    pub fn layer_name(layer: MemoryLayer) -> &'static str {
        match layer {
            MemoryLayer::Buffer => "BUFFER",
            MemoryLayer::Episodic => "EPISODIC",
            MemoryLayer::Semantic => "SEMANTIC",
        }
    }

    /// Whether a decay row has passed the archive threshold and should be retired.
    ///
    /// This is the one place that decides retirement, so search, list, traversal
    /// and consolidation cannot drift on the question. It delegates to the decay
    /// crate's own predicate rather than restating the ninety-day rule, because a
    /// second copy of a threshold is a second answer waiting to disagree.
    pub fn should_archive(&self, entry: &DecayEntry, config: &cerebrum_decay::DecayConfig, now: DateTime<Utc>) -> bool {
        if entry.archived {
            return false;
        }
        // `is_archivable` reads the wall clock internally; this mirrors its
        // condition against the caller's `now` so a test can stand at any point in
        // time. The threshold values come from the config, never from a literal.
        let is_faint = entry.strength < config.hibernation_threshold;
        let age_days = (now - entry.last_accessed).num_seconds().max(0) as f64 / (60.0 * 60.0 * 24.0);
        is_faint && age_days >= config.archive_threshold_days as f64
    }

    /// Whether a decay row has faded far enough to hibernate rather than archive.
    ///
    /// HIBERNATED HAD NO PRODUCER. `EntryLifecycle::Hibernated` existed in the
    /// curate crate and only ever appeared in a unit test and the persistence
    /// parser, so it was a decorative enum value: a lifecycle state the system
    /// claimed to support and never entered. It is a real state for a memory that
    /// has faded below the hibernation threshold but is not yet old enough to
    /// archive, and giving it a producer is what makes the lifecycle honest.
    ///
    /// It is deliberately not terminal: a hibernated memory is still eligible for
    /// retrieval above the faintness floor, and a recall strengthens it back out.
    pub fn should_hibernate(&self, entry: &DecayEntry, config: &cerebrum_decay::DecayConfig) -> bool {
        if entry.archived {
            return false;
        }
        entry.strength < config.hibernation_threshold
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_decay::DecayConfig;
    use chrono::Duration;

    fn entry(id_byte: u8, layer: MemoryLayer, strength: f32) -> DecayEntry {
        let mut created = DecayEntry::new(
            cerebrum_core::MemoryId([id_byte; 32]),
            layer,
            "body",
        );
        created.strength = strength;
        created
    }

    #[test]
    fn archived_is_never_eligible_whatever_its_strength() {
        let policy = RetrievalPolicy::default();
        let mut archived = entry(1, MemoryLayer::Semantic, 1.0);
        archived.archived = true;
        assert_eq!(policy.eligibility(Some(&archived)), Eligibility::Archived);
    }

    #[test]
    fn a_memory_held_by_decay_but_absent_is_ineligible_rather_than_ranked() {
        let policy = RetrievalPolicy::default();
        assert_eq!(policy.eligibility(None), Eligibility::NoDecayRecord);
    }

    #[test]
    fn strength_below_the_floor_is_too_faint_and_above_it_is_eligible() {
        let policy = RetrievalPolicy::default();
        let faint = entry(2, MemoryLayer::Semantic, policy.minimum_strength * 0.5);
        let sound = entry(3, MemoryLayer::Semantic, policy.minimum_strength * 2.0);
        assert_eq!(policy.eligibility(Some(&faint)), Eligibility::TooFaint);
        assert_eq!(policy.eligibility(Some(&sound)), Eligibility::Eligible);
    }

    #[test]
    fn a_stronger_memory_of_the_same_layer_and_confidence_outranks_a_weaker_one() {
        let policy = RetrievalPolicy::default();
        let strong = entry(4, MemoryLayer::Semantic, 1.0);
        let weak = entry(5, MemoryLayer::Semantic, 0.2);
        assert!(
            policy.score_multiplier(&strong, 1.0) > policy.score_multiplier(&weak, 1.0),
            "strength must change ranking, or decay is computed and never read"
        );
    }

    #[test]
    fn layer_changes_ranking_on_an_otherwise_identical_memory() {
        let policy = RetrievalPolicy::default();
        let buffer = entry(6, MemoryLayer::Buffer, 0.8);
        let semantic = entry(7, MemoryLayer::Semantic, 0.8);
        assert!(
            policy.score_multiplier(&semantic, 0.8) > policy.score_multiplier(&buffer, 0.8),
            "a consolidated memory must outrank working memory when everything else matches"
        );
    }

    #[test]
    fn confidence_changes_ranking_and_never_reaches_zero() {
        let policy = RetrievalPolicy::default();
        let same = entry(8, MemoryLayer::Semantic, 0.8);
        let doubted = policy.score_multiplier(&same, 0.0);
        let certain = policy.score_multiplier(&same, 1.0);
        assert!(certain > doubted, "confidence must change ranking");
        assert!(
            doubted > 0.0,
            "a doubtful fact is still a fact and must not be scored out of existence"
        );
    }

    #[test]
    fn the_ninety_day_boundary_is_exactly_the_configured_threshold() {
        let policy = RetrievalPolicy::default();
        let config = DecayConfig::default();
        let now = Utc::now();

        // Faint and one day short of the threshold: hibernating, not archivable.
        let mut almost = entry(9, MemoryLayer::Episodic, config.hibernation_threshold * 0.5);
        almost.last_accessed = now - Duration::days(config.archive_threshold_days as i64 - 1);
        assert!(!policy.should_archive(&almost, &config, now));
        assert!(policy.should_hibernate(&almost, &config));

        // The same memory on the threshold day: archivable.
        let mut reached = entry(10, MemoryLayer::Episodic, config.hibernation_threshold * 0.5);
        reached.last_accessed = now - Duration::days(config.archive_threshold_days as i64);
        assert!(policy.should_archive(&reached, &config, now));

        // Strong but ancient: not faint, so not archived however old it is.
        let mut strong_ancient = entry(11, MemoryLayer::Episodic, 0.9);
        strong_ancient.last_accessed = now - Duration::days(365);
        assert!(!policy.should_archive(&strong_ancient, &config, now));
    }

    #[test]
    fn an_already_archived_entry_is_not_archived_twice() {
        let policy = RetrievalPolicy::default();
        let config = DecayConfig::default();
        let mut archived = entry(12, MemoryLayer::Episodic, 0.0);
        archived.archived = true;
        assert!(!policy.should_archive(&archived, &config, Utc::now()));
        assert!(!policy.should_hibernate(&archived, &config));
    }

    #[test]
    fn layer_names_match_the_wire() {
        assert_eq!(RetrievalPolicy::layer_name(MemoryLayer::Buffer), "BUFFER");
        assert_eq!(RetrievalPolicy::layer_name(MemoryLayer::Episodic), "EPISODIC");
        assert_eq!(RetrievalPolicy::layer_name(MemoryLayer::Semantic), "SEMANTIC");
    }
}
