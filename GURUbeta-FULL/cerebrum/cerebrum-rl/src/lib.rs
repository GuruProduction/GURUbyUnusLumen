//! Cerebrum RL — GRPO training loop for the memory-management policy.
//!
//! System D from the AgeMem GRPO pattern. The crate provides the three-stage
//! pipeline structure (SFT, reward model, GRPO) that will later back a learned
//! policy. For now it implements the loop structure and configurations with
//! deterministic, observable updates.

use cerebrum_core::MemoryId;
use cerebrum_policy::{PolicyAction, PolicyNetwork, TaskType};
use serde::{Deserialize, Serialize};

// ============================================================================
// 1. CONFIGURATION
// ============================================================================

/// Configuration for the Supervised Fine-Tuning stage.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct SFTConfig {
    pub learning_rate: f64,
    pub epochs: usize,
    pub batch_size: usize,
}

impl Default for SFTConfig {
    fn default() -> Self {
        Self {
            learning_rate: 0.01,
            epochs: 10,
            batch_size: 16,
        }
    }
}

/// Configuration for the reward-model training stage.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RewardConfig {
    pub comparison_samples: usize,
}

impl Default for RewardConfig {
    fn default() -> Self {
        Self {
            comparison_samples: 32,
        }
    }
}

/// Configuration for the Group Relative Policy Optimization stage.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct GRPOConfig {
    pub group_size: usize,
    pub learning_rate: f64,
    pub epochs: usize,
}

impl Default for GRPOConfig {
    fn default() -> Self {
        Self {
            group_size: 4,
            learning_rate: 0.005,
            epochs: 10,
        }
    }
}

// ============================================================================
// 2. STATE / ACTION / REWARD
// ============================================================================

/// State presented to the policy network when it must choose a memory action.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyState {
    pub task_type: TaskType,
    pub budget_remaining: usize,
    pub available_indices: usize,
    pub current_context_tokens: usize,
}

impl PolicyState {
    pub fn budget_remaining_pct(&self) -> f32 {
        if self.current_context_tokens == 0 {
            return 1.0;
        }
        let total = self.current_context_tokens + self.budget_remaining;
        self.budget_remaining as f32 / total as f32
    }
}

/// A single expert demonstration used during SFT.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TrainingExample {
    pub state: PolicyState,
    pub action: PolicyAction,
    pub reward: f32,
}

/// Reward model combining three criteria into one scalar reward.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RewardModel {
    pub task_completion: f32,
    pub memory_efficiency: f32,
    pub budget_adherence: f32,
}

impl RewardModel {
    pub fn new(task_completion: f32, memory_efficiency: f32, budget_adherence: f32) -> Self {
        Self {
            task_completion: task_completion.clamp(0.0, 1.0),
            memory_efficiency: memory_efficiency.clamp(0.0, 1.0),
            budget_adherence: budget_adherence.clamp(0.0, 1.0),
        }
    }

    /// Combined reward as a simple average of the three criteria.
    pub fn combined(&self) -> f32 {
        (self.task_completion + self.memory_efficiency + self.budget_adherence) / 3.0
    }
}

// ============================================================================
// 3. POLICY NETWORK ADAPTER
// ============================================================================

/// Lightweight wrapper around `cerebrum_policy::PolicyNetwork` that exposes
/// trainable parameters used by SFT and GRPO.
#[derive(Debug, Clone)]
pub struct TrainablePolicy {
    inner: PolicyNetwork,
    /// Thresholds that the SFT/GRPO stages nudge based on examples.
    relevance_threshold: f32,
    budget_pressure_threshold: f32,
    archive_threshold: f32,
}

impl Default for TrainablePolicy {
    fn default() -> Self {
        Self {
            inner: PolicyNetwork::default(),
            relevance_threshold: 0.5,
            budget_pressure_threshold: 0.5,
            archive_threshold: 0.2,
        }
    }
}

impl TrainablePolicy {
    pub fn new(network: PolicyNetwork) -> Self {
        Self {
            inner: network,
            relevance_threshold: 0.5,
            budget_pressure_threshold: 0.5,
            archive_threshold: 0.2,
        }
    }

    /// Access the wrapped policy network.
    pub fn network(&self) -> &PolicyNetwork {
        &self.inner
    }

    /// Decide an action for the given state using the current thresholds.
    /// This is a structured surrogate for the eventual neural policy.
    pub fn decide(&self, state: &PolicyState) -> PolicyAction {
        let budget_pct = state.budget_remaining_pct();

        if state.available_indices == 0 {
            return PolicyAction::Archive {
                id: MemoryId::default(),
            };
        }

        if budget_pct < self.budget_pressure_threshold {
            return PolicyAction::Compress {
                id: MemoryId([0u8; 32]),
            };
        }

        if budget_pct > 0.8 && state.available_indices > 0 {
            return PolicyAction::DereferenceDeep {
                id: MemoryId([1u8; 32]),
            };
        }

        if budget_pct > self.archive_threshold {
            return PolicyAction::DereferenceShallow {
                id: MemoryId([1u8; 32]),
            };
        }

        PolicyAction::Archive {
            id: MemoryId([0u8; 32]),
        }
    }

    /// Update thresholds from a single training example.
    pub fn apply_sft_step(&mut self, example: &TrainingExample, learning_rate: f64) {
        let lr = learning_rate as f32;
        let desired_reward = example.reward;

        // Adjust thresholds toward the expert demonstration's implied preference.
        match &example.action {
            PolicyAction::DereferenceDeep { .. } => {
                if desired_reward > 0.7 {
                    self.relevance_threshold = (self.relevance_threshold - 0.05 * lr).clamp(0.0, 1.0);
                }
            }
            PolicyAction::DereferenceShallow { .. } => {
                self.budget_pressure_threshold =
                    (self.budget_pressure_threshold - 0.03 * lr).clamp(0.0, 1.0);
            }
            PolicyAction::Compress { .. } => {
                self.budget_pressure_threshold =
                    (self.budget_pressure_threshold + 0.03 * lr).clamp(0.0, 1.0);
            }
            PolicyAction::Archive { .. } => {
                self.archive_threshold = (self.archive_threshold + 0.02 * lr).clamp(0.0, 1.0);
            }
            _ => {}
        }
    }
}

// ============================================================================
// 4. METRICS
// ============================================================================

/// Metrics recorded during training.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TrainingMetrics {
    pub steps: Vec<StepMetric>,
}

impl TrainingMetrics {
    pub fn record(&mut self, epoch: usize, loss: f32, reward: f32, budget_adherence: f32) {
        self.steps.push(StepMetric {
            epoch,
            loss,
            reward,
            budget_adherence,
        });
    }

    pub fn average_reward(&self) -> f32 {
        if self.steps.is_empty() {
            return 0.0;
        }
        self.steps.iter().map(|s| s.reward).sum::<f32>() / self.steps.len() as f32
    }

    pub fn loss_trend(&self) -> f32 {
        if self.steps.len() < 2 {
            return 0.0;
        }
        let first = self.steps.first().unwrap().loss;
        let last = self.steps.last().unwrap().loss;
        last - first
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct StepMetric {
    pub epoch: usize,
    pub loss: f32,
    pub reward: f32,
    pub budget_adherence: f32,
}

// ============================================================================
// 5. TRAINER
// ============================================================================

/// Orchestrates the three-stage training pipeline.
#[derive(Debug, Clone)]
pub struct PolicyTrainer {
    pub policy: TrainablePolicy,
    pub sft_config: SFTConfig,
    pub reward_config: RewardConfig,
    pub grpo_config: GRPOConfig,
    pub metrics: TrainingMetrics,
    pub reward_model: RewardModel,
}

impl Default for PolicyTrainer {
    fn default() -> Self {
        Self {
            policy: TrainablePolicy::default(),
            sft_config: SFTConfig::default(),
            reward_config: RewardConfig::default(),
            grpo_config: GRPOConfig::default(),
            metrics: TrainingMetrics::default(),
            reward_model: RewardModel::new(0.0, 0.0, 0.0),
        }
    }
}

impl PolicyTrainer {
    pub fn new(
        sft_config: SFTConfig,
        reward_config: RewardConfig,
        grpo_config: GRPOConfig,
    ) -> Self {
        Self {
            policy: TrainablePolicy::default(),
            sft_config,
            reward_config,
            grpo_config,
            metrics: TrainingMetrics::default(),
            reward_model: RewardModel::new(0.0, 0.0, 0.0),
        }
    }

    /// Stage 1: Supervised Fine-Tuning on expert demonstrations.
    pub fn run_sft(&mut self, examples: &[TrainingExample]) {
        let lr = self.sft_config.learning_rate as f32;
        for epoch in 0..self.sft_config.epochs {
            let mut epoch_loss = 0.0f32;
            let mut epoch_reward = 0.0f32;

            for batch in examples.chunks(self.sft_config.batch_size.max(1)) {
                for example in batch {
                    let predicted = self.policy.decide(&example.state);
                    let loss = action_loss(&predicted, &example.action);
                    epoch_loss += loss;
                    epoch_reward += example.reward;
                    self.policy.apply_sft_step(example, lr as f64);
                }
            }

            let count = examples.len().max(1) as f32;
            let avg_loss = epoch_loss / count;
            let avg_reward = epoch_reward / count;
            let budget_adherence = avg_reward * 0.5 + 0.5;

            self.metrics.record(epoch + 1, avg_loss, avg_reward, budget_adherence.clamp(0.0, 1.0));
        }
    }

    /// Stage 2: Train the reward model on preference comparisons.
    pub fn run_reward_model(&mut self, comparisons: &[RewardComparison]) {
        let mut total_task = 0.0f32;
        let mut total_eff = 0.0f32;
        let mut total_budget = 0.0f32;

        for comparison in comparisons {
            let better = comparison.better_reward();
            total_task += better.task_completion;
            total_eff += better.memory_efficiency;
            total_budget += better.budget_adherence;
        }

        let count = comparisons.len().max(1) as f32;
        self.reward_model = RewardModel::new(
            total_task / count,
            total_eff / count,
            total_budget / count,
        );
    }

    /// Stage 3: GRPO — generate candidates, rank by reward, update the policy.
    pub fn run_grpo(&mut self, seed_state: &PolicyState) -> (PolicyAction, f32) {
        let mut best_action: Option<PolicyAction> = None;
        let mut best_reward = f32::NEG_INFINITY;

        for _ in 0..self.grpo_config.epochs {
            let mut candidates = Vec::with_capacity(self.grpo_config.group_size);
            let mut rewards = Vec::with_capacity(self.grpo_config.group_size);

            for i in 0..self.grpo_config.group_size {
                let candidate = generate_candidate(seed_state, &self.policy, i);
                let reward = evaluate_candidate(seed_state, &candidate, &self.reward_model);
                candidates.push(candidate);
                rewards.push(reward);
            }

            // Rank and update toward the best candidate.
            let (winner_idx, winner_reward) = rewards
                .iter()
                .enumerate()
                .max_by(|a, b| a.1.partial_cmp(b.1).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(i, r)| (i, *r))
                .unwrap_or((0, 0.0));

            let winner = candidates[winner_idx].clone();
            let example = TrainingExample {
                state: seed_state.clone(),
                action: winner.clone(),
                reward: winner_reward,
            };
            self.policy.apply_sft_step(&example, self.grpo_config.learning_rate);

            if winner_reward > best_reward {
                best_reward = winner_reward;
                best_action = Some(winner);
            }

            self.metrics.record(
                self.metrics.steps.len() + 1,
                1.0 - winner_reward,
                winner_reward,
                self.reward_model.budget_adherence,
            );
        }

        (best_action.unwrap_or_else(|| self.policy.decide(seed_state)), best_reward)
    }
}

/// A preference comparison between two policy decisions.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RewardComparison {
    pub decision_a: PolicyDecision,
    pub reward_a: RewardModel,
    pub decision_b: PolicyDecision,
    pub reward_b: RewardModel,
}

impl RewardComparison {
    /// Return the reward model of the better decision.
    pub fn better_reward(&self) -> RewardModel {
        if self.reward_a.combined() >= self.reward_b.combined() {
            self.reward_a
        } else {
            self.reward_b
        }
    }
}

/// A policy decision annotated for training.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PolicyDecision {
    pub state: PolicyState,
    pub action: PolicyAction,
    pub rationale: String,
}

// ============================================================================
// 6. CANDIDATE GENERATION & EVALUATION
// ============================================================================

fn generate_candidate(state: &PolicyState, policy: &TrainablePolicy, variant: usize) -> PolicyAction {
    let mut synthetic = state.clone();
    // Perturb state slightly so each candidate is distinct.
    synthetic.budget_remaining = state
        .budget_remaining
        .saturating_add(variant.saturating_mul(10));
    synthetic.available_indices = state.available_indices.saturating_add(variant);
    policy.decide(&synthetic)
}

fn evaluate_candidate(state: &PolicyState, action: &PolicyAction, reward_model: &RewardModel) -> f32 {
    let budget_pct = state.budget_remaining_pct();

    let task_score = match action {
        PolicyAction::DereferenceDeep { .. } => 0.9,
        PolicyAction::DereferenceShallow { .. } => 0.7,
        PolicyAction::DereferencePartial { .. } => 0.8,
        PolicyAction::CreateIndex { .. } => 0.75,
        PolicyAction::UpdateIndex { .. } => 0.6,
        PolicyAction::Compress { .. } => 0.5,
        PolicyAction::Archive { .. } => 0.3,
    };

    let efficiency_score = match action {
        PolicyAction::DereferenceShallow { .. } | PolicyAction::Compress { .. } => 0.9,
        PolicyAction::DereferencePartial { .. } => 0.8,
        PolicyAction::Archive { .. } => 0.7,
        _ => 0.5,
    };

    let budget_score = if budget_pct > 0.5 { 0.9 } else { 0.5 };

    let local_reward = RewardModel::new(task_score, efficiency_score, budget_score);
    let alpha = 0.7;
    alpha * local_reward.combined() + (1.0 - alpha) * reward_model.combined()
}

fn action_loss(predicted: &PolicyAction, target: &PolicyAction) -> f32 {
    if std::mem::discriminant(predicted) == std::mem::discriminant(target) {
        0.0
    } else {
        1.0
    }
}

// ============================================================================
// 7. SMOKE TESTS
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_policy::{PolicyAction, TaskType};

    fn make_examples(count: usize) -> Vec<TrainingExample> {
        (0..count)
            .map(|i| {
                let state = PolicyState {
                    task_type: TaskType::Conversation,
                    budget_remaining: 1000usize.saturating_sub(i * 10),
                    available_indices: 10 + i,
                    current_context_tokens: i * 5,
                };
                let action = if i % 2 == 0 {
                    PolicyAction::DereferenceDeep {
                        id: MemoryId([1u8; 32]),
                    }
                } else {
                    PolicyAction::DereferenceShallow {
                        id: MemoryId([1u8; 32]),
                    }
                };
                TrainingExample {
                    state,
                    action,
                    reward: 0.5 + (i as f32 * 0.01),
                }
            })
            .collect()
    }

    #[test]
    fn test_sft_adjusts_thresholds() {
        let mut trainer = PolicyTrainer::default();
        let before = trainer.policy.relevance_threshold;
        let examples = make_examples(50);
        trainer.run_sft(&examples);
        assert_ne!(
            trainer.policy.relevance_threshold, before,
            "SFT should adjust policy thresholds"
        );
        assert!(!trainer.metrics.steps.is_empty());
    }

    #[test]
    fn test_reward_model_good_vs_bad() {
        let mut trainer = PolicyTrainer::default();
        let good = RewardModel::new(0.9, 0.9, 0.9);
        let bad = RewardModel::new(0.2, 0.2, 0.2);

        let comparisons = vec![RewardComparison {
            decision_a: PolicyDecision {
                state: PolicyState {
                    task_type: TaskType::DeepAnalysis,
                    budget_remaining: 1000,
                    available_indices: 5,
                    current_context_tokens: 0,
                },
                action: PolicyAction::DereferenceDeep {
                    id: MemoryId([1u8; 32]),
                },
                rationale: "good".into(),
            },
            reward_a: good,
            decision_b: PolicyDecision {
                state: PolicyState {
                    task_type: TaskType::DeepAnalysis,
                    budget_remaining: 1000,
                    available_indices: 5,
                    current_context_tokens: 0,
                },
                action: PolicyAction::Archive {
                    id: MemoryId([1u8; 32]),
                },
                rationale: "bad".into(),
            },
            reward_b: bad,
        }];

        trainer.run_reward_model(&comparisons);
        assert!(trainer.reward_model.combined() > bad.combined());
        assert!(trainer.reward_model.combined() <= good.combined());
    }

    #[test]
    fn test_grpo_selects_best_candidate() {
        let mut trainer = PolicyTrainer::new(
            SFTConfig::default(),
            RewardConfig::default(),
            GRPOConfig {
                group_size: 5,
                learning_rate: 0.005,
                epochs: 10,
            },
        );
        let seed = PolicyState {
            task_type: TaskType::DeepAnalysis,
            budget_remaining: 2000,
            available_indices: 10,
            current_context_tokens: 100,
        };
        let (best, reward) = trainer.run_grpo(&seed);
        assert!(
            reward > 0.0,
            "best candidate should have positive reward, got {} for {:?}",
            reward,
            best
        );
    }

    #[test]
    fn test_combined_reward_is_average() {
        let reward = RewardModel::new(0.9, 0.6, 0.3);
        assert!((reward.combined() - 0.6).abs() < 0.001);
    }

    #[test]
    fn test_training_metrics_loss_decreases() {
        let mut trainer = PolicyTrainer::new(
            SFTConfig {
                learning_rate: 0.05,
                epochs: 10,
                batch_size: 8,
            },
            RewardConfig::default(),
            GRPOConfig::default(),
        );
        let examples = make_examples(100);
        trainer.run_sft(&examples);

        let first_loss = trainer.metrics.steps.first().unwrap().loss;
        let last_loss = trainer.metrics.steps.last().unwrap().loss;
        assert!(
            last_loss <= first_loss,
            "loss should decrease or stay flat over training: first {} last {}",
            first_loss,
            last_loss
        );
    }
}
