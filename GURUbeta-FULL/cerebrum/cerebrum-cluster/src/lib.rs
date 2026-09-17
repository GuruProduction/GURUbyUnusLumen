//! Cerebrum Cluster — distributed consensus simulation.
//!
//! This crate provides an in-process Raft consensus simulation. It is not a
//! production Raft implementation (that requires a real network layer), but it
//! captures the core state machine, log replication, and election semantics.

use serde::{Deserialize, Serialize};

/// Cluster membership and timing configuration.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClusterConfig {
    pub node_id: String,
    pub peers: Vec<String>,
    pub election_timeout_ms: u64,
    pub heartbeat_ms: u64,
}

impl Default for ClusterConfig {
    fn default() -> Self {
        Self {
            node_id: "node-1".to_string(),
            peers: Vec::new(),
            election_timeout_ms: 150,
            heartbeat_ms: 50,
        }
    }
}

/// Role of a Raft node.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NodeState {
    Follower,
    Candidate,
    Leader,
}

/// A single entry in the replicated log.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LogEntry {
    pub term: u64,
    pub index: u64,
    pub command: Vec<u8>,
}

impl LogEntry {
    pub fn new(term: u64, index: u64, command: Vec<u8>) -> Self {
        Self {
            term,
            index,
            command,
        }
    }
}

/// Simulated Raft node.
#[derive(Debug, Clone)]
pub struct RaftNode {
    pub id: String,
    pub state: NodeState,
    pub current_term: u64,
    pub voted_for: Option<String>,
    pub log: Vec<LogEntry>,
    pub commit_index: u64,
    pub last_applied: u64,
    pub config: ClusterConfig,
    /// Simulated votes received during an election.
    votes_received: Vec<String>,
}

impl RaftNode {
    pub fn new(config: ClusterConfig) -> Self {
        Self {
            id: config.node_id.clone(),
            state: NodeState::Follower,
            current_term: 0,
            voted_for: None,
            log: Vec::new(),
            commit_index: 0,
            last_applied: 0,
            config,
            votes_received: Vec::new(),
        }
    }

    /// Transition to candidate and request votes from peers.
    pub fn start_election(&mut self) {
        self.state = NodeState::Candidate;
        self.current_term += 1;
        self.voted_for = Some(self.id.clone());
        self.votes_received = vec![self.id.clone()];
    }

    /// Handle an incoming RequestVote RPC.
    ///
    /// Returns true if the vote is granted.
    pub fn request_vote(&mut self, candidate_id: String, candidate_term: u64) -> bool {
        if candidate_term > self.current_term {
            self.current_term = candidate_term;
            self.state = NodeState::Follower;
            self.voted_for = None;
        }

        if candidate_term < self.current_term {
            return false;
        }

        if self.voted_for.is_none() || self.voted_for.as_ref() == Some(&candidate_id) {
            self.voted_for = Some(candidate_id.clone());
            true
        } else {
            false
        }
    }

    /// Record a vote from another node during an election.
    pub fn record_vote(&mut self, voter_id: String) {
        if !self.votes_received.contains(&voter_id) {
            self.votes_received.push(voter_id);
        }
    }

    /// Return true if this candidate has won a majority.
    pub fn has_majority(&self) -> bool {
        let cluster_size = self.config.peers.len() + 1;
        self.votes_received.len() * 2 > cluster_size
    }

    /// Promote this candidate to leader if it has a majority.
    pub fn become_leader_if_won(&mut self) {
        if matches!(self.state, NodeState::Candidate) && self.has_majority() {
            self.state = NodeState::Leader;
        }
    }

    /// Append entries from the leader.
    ///
    /// Returns true if the entries were accepted.
    pub fn append_entries(&mut self, term: u64, entries: &[LogEntry]) -> bool {
        if term < self.current_term {
            return false;
        }

        self.current_term = term;
        self.state = NodeState::Follower;
        self.voted_for = None;

        for entry in entries {
            // Simple append-only log for the simulation.
            if !self.log.iter().any(|e| e.index == entry.index) {
                self.log.push(entry.clone());
            }
        }

        true
    }

    /// Commit all entries up to `new_commit_index`.
    pub fn commit(&mut self, new_commit_index: u64) {
        self.commit_index = self.commit_index.max(new_commit_index);
    }

    /// Apply all committed entries that have not yet been applied.
    pub fn apply_committed(&mut self) -> Vec<LogEntry> {
        let mut applied = Vec::new();
        while self.last_applied < self.commit_index {
            self.last_applied += 1;
            if let Some(entry) = self.log.iter().find(|e| e.index == self.last_applied) {
                applied.push(entry.clone());
            }
        }
        applied
    }

    /// Propose a new command as leader, appending it to the local log.
    pub fn propose(&mut self, command: Vec<u8>) -> LogEntry {
        let index = self.log.len() as u64 + 1;
        let entry = LogEntry::new(self.current_term, index, command);
        self.log.push(entry.clone());
        entry
    }

    /// Simulate a leader death by forcing this node to start a new election.
    pub fn trigger_failover(&mut self) {
        self.start_election();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_node(id: &str, peers: &[&str]) -> RaftNode {
        let config = ClusterConfig {
            node_id: id.to_string(),
            peers: peers.iter().map(|s| s.to_string()).collect(),
            election_timeout_ms: 150,
            heartbeat_ms: 50,
        };
        RaftNode::new(config)
    }

    #[test]
    fn test_election_three_nodes_one_becomes_leader() {
        let mut a = make_node("a", &["b", "c"]);
        let mut b = make_node("b", &["a", "c"]);
        let mut c = make_node("c", &["a", "b"]);

        a.start_election();
        assert!(b.request_vote("a".to_string(), a.current_term));
        assert!(c.request_vote("a".to_string(), a.current_term));

        a.record_vote("b".to_string());
        a.record_vote("c".to_string());
        a.become_leader_if_won();

        assert_eq!(a.state, NodeState::Leader);
        assert_eq!(a.current_term, 1);
    }

    #[test]
    fn test_log_replication() {
        let mut leader = make_node("leader", &["f1", "f2"]);
        leader.start_election();
        leader.record_vote("f1".to_string());
        leader.record_vote("f2".to_string());
        leader.become_leader_if_won();

        let entry = leader.propose(b"cmd1".to_vec());

        let mut follower = make_node("f1", &["leader", "f2"]);
        assert!(follower.append_entries(leader.current_term, &[entry.clone()]));
        assert_eq!(follower.log.len(), 1);
        assert_eq!(follower.log[0].command, b"cmd1".to_vec());
    }

    #[test]
    fn test_commit_after_majority() {
        let mut leader = make_node("leader", &["f1", "f2"]);
        leader.start_election();
        leader.record_vote("f1".to_string());
        leader.record_vote("f2".to_string());
        leader.become_leader_if_won();

        let entry = leader.propose(b"cmd1".to_vec());

        let mut f1 = make_node("f1", &["leader", "f2"]);
        let mut f2 = make_node("f2", &["leader", "f1"]);

        assert!(f1.append_entries(leader.current_term, &[entry.clone()]));
        assert!(f2.append_entries(leader.current_term, &[entry.clone()]));

        // Leader learns that majority (leader + 2 followers = 3) has the entry.
        leader.commit(entry.index);
        assert_eq!(leader.commit_index, entry.index);

        f1.commit(entry.index);
        f2.commit(entry.index);

        assert_eq!(f1.apply_committed().len(), 1);
        assert_eq!(f2.apply_committed().len(), 1);
    }

    #[test]
    fn test_leader_failover() {
        let mut leader = make_node("leader", &["f1", "f2"]);
        leader.start_election();
        leader.record_vote("f1".to_string());
        leader.record_vote("f2".to_string());
        leader.become_leader_if_won();
        assert_eq!(leader.state, NodeState::Leader);

        // Follower triggers failover (simulating leader death).
        let mut follower = make_node("f1", &["leader", "f2"]);
        follower.append_entries(leader.current_term, &[]);
        follower.trigger_failover();

        assert_eq!(follower.state, NodeState::Candidate);
        assert_eq!(follower.current_term, leader.current_term + 1);
    }
}
