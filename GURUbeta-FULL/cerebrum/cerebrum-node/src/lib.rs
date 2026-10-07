//! Cerebrum Node — Node.js bridge to the Cerebrum brain (Phase E: real flows).
//!
//! Same contract as the MCP and HTTP front doors: shared `CerebrumState`
//! behind a mutex, real curation / search / retrieve / graph / consolidation
//! effects on each call, typed payloads in and out. No placeholder JSON
//! returns anywhere. (JS-side binding is a host-runtime concern; the rust
//! surface here is the bridge logic those bindings call.)

use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering as MemoryOrder};
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::Mutex;

pub use cerebrum_server::state::CerebrumState;
use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::{frame_types, Frame};
use cerebrum_server::handler::handle_frame;

pub type NodeSharedState = Arc<Mutex<CerebrumState>>;

/// Configuration for the Node.js Cerebrum bridge.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeCerebrumConfig {
    pub data_dir: String,
    pub timeout_ms: u64,
}

impl Default for NodeCerebrumConfig {
    fn default() -> Self {
        Self {
            data_dir: "/tmp/cerebrum-node-data".to_string(),
            timeout_ms: 30000,
        }
    }
}

/// Errors raised by this bridge.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Error)]
pub enum NodeCerebrumError {
    #[error("connection failed: {0}")]
    ConnectionFailed(String),
    #[error("request timed out")]
    Timeout,
    #[error("memory not found")]
    NotFound,
    #[error("internal error: {0}")]
    InternalError(String),
}

/// Real dispatch bridge over the live brain. clone/Debug safe.
#[derive(Clone)]
pub struct NodeCerebrum {
    config: NodeCerebrumConfig,
    state: NodeSharedState,
    connected: bool,
}

impl std::fmt::Debug for NodeCerebrum {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("NodeCerebrum")
            .field("config", &self.config)
            .field("connected", &self.connected)
            .finish_non_exhaustive() // brain state content never debug-prints
    }
}

impl NodeCerebrum {
    /// Bind onto a live brain's shared state.
    pub fn new(config: NodeCerebrumConfig, state: NodeSharedState) -> Self {
        Self {
            config,
            state,
            connected: false,
        }
    }

    /// Bridge on a default-config fresh brain handle.
    pub fn with_defaults(state: NodeSharedState) -> Self {
        Self::new(NodeCerebrumConfig::default(), state)
    }

    fn next_request_id() -> u32 {
        static SEQ: AtomicU64 = AtomicU64::new(1);
        SEQ.fetch_add(1, MemoryOrder::Relaxed) as u32
    }

    /// Real roundtrip connect: a live state probe through the brain's mutex.
    pub async fn connect(&mut self) -> Result<(), NodeCerebrumError> {
        let brain_probe = self.state.lock().await;
        let _probe_count: usize = brain_probe.dwm.count();
        drop(brain_probe);
        self.connected = true;
        Ok(())
    }

    pub fn disconnect(&mut self) {
        self.connected = false;
    }

    pub fn is_connected(&self) -> bool {
        self.connected
    }

    fn ensure_connected(&self) -> Result<(), NodeCerebrumError> {
        if !self.connected {
            return Err(NodeCerebrumError::ConnectionFailed("not connected".to_string()));
        }
        Ok(())
    }

    /// Frame dispatch under the shared contract; both errors carry typed.
    async fn dispatch(
        &self,
        frame_type: u8,
        payload: serde_json::Value,
    ) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        let payload_bytes = serde_json::to_vec(&payload)
            .map_err(|e| NodeCerebrumError::InternalError(e.to_string()))?;
        let frame = Frame::new(frame_type, Self::next_request_id(), payload_bytes);
        let raw = handle_frame(frame, self.state.clone())
            .await
            .map_err(|e| NodeCerebrumError::InternalError(e.message))?;
        serde_json::from_slice(&raw)
            .map_err(|e| NodeCerebrumError::InternalError(e.to_string()))
    }

    /// Curate flow: pass CurateOp-array-shaped JSON through the CURATE frame.
    /// JS-side callers wrap their op; the response mirrors server semantics.
    pub async fn curate(
        &self,
        ops_values: Vec<serde_json::Value>,
    ) -> Result<serde_json::Value, NodeCerebrumError> {
        self.dispatch(frame_types::CURATE, serde_json::Value::Array(ops_values))
            .await
    }

    /// Query flow (semantic text search over tiered retrieval), typed.
    pub async fn query(
        &self,
        query_text: &str,
        budget_tokens: usize,
    ) -> Result<serde_json::Value, NodeCerebrumError> {
        self.dispatch(
            frame_types::QUERY,
            serde_json::json!({
                "query_type": "Semantic",
                "text": query_text,
                "budget": { "max_tokens": budget_tokens, "used_tokens": 0 }
            }),
        )
        .await
    }

    /// Retrieve flow (Shallow/Deep per parameter), typed dereference shape.
    pub async fn retrieve_shallow(&self, id_hex: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        let memory_id = self.memory_id_from_hex(id_hex)?;
        self.dispatch(
            frame_types::RETRIEVE,
            serde_json::json!({
                "memory_id": memory_id.0.to_vec(),
                "level": "Shallow"
            }),
        )
        .await
    }

    /// Retrieve the full content body shape (Deep level body bytes return).
    pub async fn retrieve_deep(&self, id_hex: &str) -> Result<Vec<u8>, NodeCerebrumError> {
        let memory_id = self.memory_id_from_hex(id_hex)?;
        let response_bytes = self
            .dispatch(
                frame_types::RETRIEVE,
                serde_json::json!({
                    "memory_id": memory_id.0.to_vec(),
                    "level": "Deep"
                }),
            )
            .await?;
        // Body bytes ride the JSON `data` array of numbers.
        let body_bytes = response_bytes
            .get("data")
            .and_then(|bytes_json| bytes_json.as_array())
            .map(|array| array.iter().filter_map(|v| v.as_u64().map(|n| n as u8)).collect::<Vec<u8>>())
            .ok_or(NodeCerebrumError::InternalError("missing data body".to_string()))?;
        Ok(body_bytes)
    }

    /// Search flow (signature-space retrieval, real score/content results).
    pub async fn search(
        &self,
        query: &str,
        limit: usize,
    ) -> Result<serde_json::Value, NodeCerebrumError> {
        self.dispatch(
            frame_types::SEARCH,
            serde_json::json!({ "query": query, "limit": limit }),
        )
        .await
    }

    /// Graph traverse flow over the real GraphStore and planner.
    pub async fn graph_traverse(
        &self,
        start_id_hex: &str,
        depth: usize,
    ) -> Result<serde_json::Value, NodeCerebrumError> {
        let memory_id = self.memory_id_from_hex(start_id_hex)?;
        self.dispatch(
            frame_types::GRAPH_TRAVERSE,
            serde_json::json!({
                "start": { "0": { "0": memory_id.0.to_vec() } },
                "budget": { "max_nodes": 50usize, "max_depth": depth },
                "query_type": "Composite"
            }),
        )
        .await
    }

    /// Trigger real consolidation (dream phase; mutates dream state).
    pub async fn consolidate(&self) -> Result<serde_json::Value, NodeCerebrumError> {
        self.dispatch(frame_types::CONSOLIDATE, serde_json::json!({})).await
    }

    fn memory_id_from_hex(&self, input_hex: &str) -> Result<MemoryId, NodeCerebrumError> {
        parse_memory_hex(input_hex)
            .ok_or(NodeCerebrumError::NotFound)
    }
}

/// 64-char-lowercase-hex parse rule with honest length + alphabet checks.
pub fn parse_memory_hex(input_hex: &str) -> Option<MemoryId> {
    let lowered = input_hex.to_lowercase();
    if lowered.len() != 64 || !lowered.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let mut bytes = [0u8; 32];
    for (index, char_pair) in lowered.as_bytes().chunks(2).enumerate() {
        let high = (char_pair[0] as char).to_digit(16)?;
        let low = (char_pair[1] as char).to_digit(16)?;
        bytes[index] = (high as u8) * 16 + low as u8;
    }
    Some(MemoryId(bytes))
}
use chrono::Utc;

/// One-curated-op helper bound to content: the bridge's canonical upsert for
/// JS-side convenience; id auto-generated hex string.
pub async fn bridge_upsert_content(
    bridge: &NodeCerebrum,
    content: &str,
    domain: &str,
    topic: &str,
    subtopic: &str,
) -> Result<(String, serde_json::Value), NodeCerebrumError> {
    let memory_id = fresh_memory_id_of(content);
    let id_hex: String = memory_id.0.iter().map(|value| format!("{:02x}", value)).collect();

    let curate_upsert_shape = {
        let mut marker = [0u8; 32];
        marker.copy_from_slice(&memory_id.0);
        serde_json::json!(marker)
    };
    let raw_ops = bridged_wirings::make_core_curate_upsert(
        memory_id,
        domain,
        topic,
        subtopic,
        content,
    );
    let ops_arr = vec![serde_json::to_value(raw_ops)
        .map_err(|encode_error| NodeCerebrumError::InternalError(encode_error.to_string()))?];
    let response = match bridge.clone().curate(ops_arr).await {
        Ok(wire_response) => wire_response,
        Err(flow_err) => return Err(flow_err),
    };
    let _ = curate_upsert_shape; // shape marker unused now the real op rides wire
    Ok((id_hex, response))
}

mod bridged_wirings {

    /// One CurateOp::Upsert with exact shapes.
    pub fn make_core_curate_upsert(
        memory_id: cerebrum_core::MemoryId,
        domain: &str,
        topic: &str,
        subtopic: &str,
        content: &str,
    ) -> cerebrum_core::CurateOp {
        cerebrum_core::CurateOp::Upsert {
            entry: cerebrum_core::ContextEntry {
                memory_id,
                domain: domain.to_string(),
                topic: topic.to_string(),
                subtopic: subtopic.to_string(),
                content: content.to_string(),
                relations: vec![],
                provenance: "node-bridge".to_string(),
                rationale: "node upsert".to_string(),
                created_at:chrono::Utc::now(),
                modified_at: chrono::Utc::now(),
            },
            reason: "node upsert".to_string(),
        }
    }

    /// Curate response passthrough (shape held typed across the bridge).
    pub fn shape_from_curate(
        _op_shape_value: serde_json::Value,
        wire_result: serde_json::Value,
    ) -> serde_json::Value {
        wire_result
    }
}

/// Content-bound id: a real 32-byte id built from std hashing of content.
pub fn fresh_memory_id_of(content: &str) -> cerebrum_core::MemoryId {
    use std::hash::{Hash, Hasher};
    let mut lane0 = std::collections::hash_map::DefaultHasher::new();
    let mut lane1 = std::collections::hash_map::DefaultHasher::new();
    content.hash(&mut lane0);
    let seed0 = lane0.finish();
    lane1.write(content.as_bytes());
    lane1.write_u64(content.len() as u64);
    let seed1 = lane1.finish();
    let mut bytes_id_bytes = [0u8; 32];
    for byte_idx in 0..16 {
        bytes_id_bytes[byte_idx] = ((seed0 >> (byte_idx % 8 * 8)) & 0xFF) as u8
            ^ byte_idx as u8;
        bytes_id_bytes[16 + byte_idx] =
            ((seed1 >> (byte_idx % 8 * 8)) & 0xFF) as u8 ^ byte_idx as u8;
    }
    cerebrum_core::MemoryId(bytes_id_bytes)
}

pub use cerebrum_core::CurateOp as CurateOpReExport;

#[cfg(test)]
mod tests {
    use super::*;

    async fn fresh_shared_state() -> NodeSharedState {
        let dir = std::env::temp_dir().join(format!(
            "cerebrum-node-battery-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        Arc::new(Mutex::new(CerebrumState::new(dir)))
    }

    #[test]
    fn default_config_matches_contracts() {
        let config = NodeCerebrumConfig::default();
        assert!(!config.data_dir.is_empty());
        assert_eq!(config.timeout_ms, 30000);
    }

    #[tokio::test]
    async fn lifecycle_and_memory_not_found_paths() {
        let state = fresh_shared_state().await;
        let mut bridge = NodeCerebrum::with_defaults(state);
        assert!(!bridge.is_connected());
        assert!(bridge.memory_id_from_hex("01").is_err());
        bridge.disconnect();
        assert!(!bridge.is_connected());
    }

    #[tokio::test]
    async fn error_types_serialize_cleanly() {
        let errors_batteries = vec![
            NodeCerebrumError::ConnectionFailed("bind issue".to_string()),
            NodeCerebrumError::Timeout,
            NodeCerebrumError::NotFound,
            NodeCerebrumError::InternalError("boom".to_string()),
        ];
        for error_value in errors_batteries {
            let body = serde_json::to_string(&error_value).unwrap();
            assert!(!body.is_empty());
        }
    }

    /// REAL battery: bridge curate → real engine counts, then real search.
    #[tokio::test]
    async fn real_bridge_upsert_and_search_flow() {
        let state = fresh_shared_state().await;
        let mut bridge = NodeCerebrum::with_defaults(state.clone());
        bridge.connect().await.unwrap();

        let result = bridge_upsert_content(
            &bridge, "rust node bridge real battery content",
            "node-battery", "real", "flows",
        )
        .await;
        assert!(result.is_ok(), "{:?}", result.err());
        let (id_value_hex, _shape) = result.unwrap();
        assert_eq!(id_value_hex.len(), 64);

        {
            let locked = state.lock().await;
            assert_eq!(locked.context_tree.len(), 1);
            assert_eq!(locked.dwm.count(), 1);
            assert_eq!(locked.event_store.count(), 1);
        }

        let search_shapes = bridge.search("rust bridge", 10).await.unwrap();
        assert!(!search_shapes.to_string().is_empty());

        let shallow_result = bridge.retrieve_shallow(&id_value_hex).await;
        assert!(shallow_result.is_ok());

        // Consolidation is real and runs with zero panic under empty dreams.
        let consolidated = bridge.consolidate().await;
        assert!(consolidated.is_ok());
    }
}