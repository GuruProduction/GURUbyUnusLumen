//! Cerebrum Node — Node.js/TypeScript FFI bridge definitions.
//!
//! Provides type definitions and a synchronous FFI-style bridge. Actual napi-rs
//! integration will be added later when wiring into Lux Code.

use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Configuration for the Node.js Cerebrum client.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeCerebrumConfig {
    pub server_address: String,
    pub timeout_ms: u64,
}

impl Default for NodeCerebrumConfig {
    fn default() -> Self {
        Self {
            server_address: "127.0.0.1:4222".to_string(),
            timeout_ms: 30000,
        }
    }
}

/// Errors returned by the Node.js bridge.
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

/// Cerebrum client for Node.js/TypeScript integration.
#[derive(Debug, Clone)]
pub struct NodeCerebrum {
    pub config: NodeCerebrumConfig,
    pub connected: bool,
}

impl NodeCerebrum {
    pub fn new(config: NodeCerebrumConfig) -> Self {
        Self {
            config,
            connected: false,
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(NodeCerebrumConfig::default())
    }

    pub fn connect(&mut self) -> Result<(), NodeCerebrumError> {
        if self.config.server_address.is_empty() {
            return Err(NodeCerebrumError::ConnectionFailed(
                "server address is empty".to_string(),
            ));
        }
        self.connected = true;
        Ok(())
    }

    pub fn disconnect(&mut self) {
        self.connected = false;
    }

    fn ensure_connected(&self) -> Result<(), NodeCerebrumError> {
        if !self.connected {
            return Err(NodeCerebrumError::ConnectionFailed(
                "not connected".to_string(),
            ));
        }
        Ok(())
    }

    pub fn query(&self, query_text: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "query",
            "query": query_text,
            "status": "ok"
        }))
    }

    pub fn curate(&self, operation: serde_json::Value) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "curate",
            "payload": operation,
            "status": "ok"
        }))
    }

    pub fn retrieve(&self, memory_id: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "retrieve",
            "memory_id": memory_id,
            "status": "ok"
        }))
    }

    pub fn search(&self, query: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "search",
            "query": query,
            "status": "ok"
        }))
    }

    pub fn graph_traverse(&self, start_id: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "graph_traverse",
            "start_id": start_id,
            "status": "ok"
        }))
    }

    pub fn consolidate(&self) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "consolidate",
            "status": "ok"
        }))
    }

    pub fn prefetch(&self, task_type: &str) -> Result<serde_json::Value, NodeCerebrumError> {
        self.ensure_connected()?;
        Ok(serde_json::json!({
            "operation": "prefetch",
            "task_type": task_type,
            "status": "ok"
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = NodeCerebrumConfig::default();
        assert_eq!(config.server_address, "127.0.0.1:4222");
        assert_eq!(config.timeout_ms, 30000);
    }

    #[test]
    fn test_connect_disconnect_lifecycle() {
        let mut client = NodeCerebrum::with_defaults();
        assert!(!client.connected);
        client.connect().unwrap();
        assert!(client.connected);
        client.disconnect();
        assert!(!client.connected);
    }

    #[test]
    fn test_query_returns_json() {
        let mut client = NodeCerebrum::with_defaults();
        client.connect().unwrap();
        let result = client.query("rust").unwrap();
        assert_eq!(result["operation"], "query");
        assert_eq!(result["query"], "rust");
    }

    #[test]
    fn test_error_types_serialize() {
        let errors = vec![
            NodeCerebrumError::ConnectionFailed("x".to_string()),
            NodeCerebrumError::Timeout,
            NodeCerebrumError::NotFound,
            NodeCerebrumError::InternalError("boom".to_string()),
        ];
        for err in errors {
            let json = serde_json::to_string(&err).unwrap();
            assert!(!json.is_empty());
        }
    }
}
