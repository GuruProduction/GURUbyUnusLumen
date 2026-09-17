//! Cerebrum MCP — Model Context Protocol server definitions.
//!
//! Exposes Cerebrum operations as MCP tools so agents can query, curate,
//! retrieve, search, traverse the graph, consolidate, and prefetch memories
//! through the industry-standard MCP interface.

use serde::{Deserialize, Serialize};
use serde_json::json;

/// An MCP tool definition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
}

impl McpTool {
    pub fn new(name: impl Into<String>, description: impl Into<String>, input_schema: serde_json::Value) -> Self {
        Self {
            name: name.into(),
            description: description.into(),
            input_schema,
        }
    }
}

/// A parsed MCP request.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct McpRequest {
    pub method: String,
    pub params: serde_json::Value,
}

/// A formatted MCP response.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct McpResponse {
    pub result: serde_json::Value,
    pub error: Option<String>,
}

impl McpResponse {
    pub fn success(result: serde_json::Value) -> Self {
        Self { result, error: None }
    }

    pub fn error(message: impl Into<String>) -> Self {
        Self {
            result: serde_json::Value::Null,
            error: Some(message.into()),
        }
    }
}

/// Returns the canonical set of Cerebrum MCP tools.
pub fn cerebrum_tools() -> Vec<McpTool> {
    vec![
        McpTool::new(
            "cerebrum_query",
            "Query Cerebrum with a natural-language question.",
            json!({
                "type": "object",
                "properties": {
                    "query": { "type": "string" },
                    "query_type": {
                        "type": "string",
                        "enum": ["semantic", "temporal", "causal", "entity", "composite"]
                    }
                },
                "required": ["query"]
            }),
        ),
        McpTool::new(
            "cerebrum_curate",
            "Perform a curation operation on the memory graph.",
            json!({
                "type": "object",
                "properties": {
                    "operation": {
                        "type": "string",
                        "enum": ["add", "update", "upsert", "merge", "delete"]
                    },
                    "entry": { "type": "object" },
                    "reason": { "type": "string" }
                },
                "required": ["operation"]
            }),
        ),
        McpTool::new(
            "cerebrum_retrieve",
            "Retrieve a specific memory by ID.",
            json!({
                "type": "object",
                "properties": {
                    "memory_id": { "type": "string" },
                    "level": {
                        "type": "string",
                        "enum": ["shallow", "deep", "partial"]
                    }
                },
                "required": ["memory_id"]
            }),
        ),
        McpTool::new(
            "cerebrum_search",
            "Search memories by semantic similarity.",
            json!({
                "type": "object",
                "properties": {
                    "query": { "type": "string" },
                    "radius": { "type": "integer" },
                    "limit": { "type": "integer" }
                },
                "required": ["query"]
            }),
        ),
        McpTool::new(
            "cerebrum_graph_traverse",
            "Traverse the memory graph from a starting memory.",
            json!({
                "type": "object",
                "properties": {
                    "start_memory_id": { "type": "string" },
                    "edge_type": {
                        "type": "string",
                        "enum": ["semantic", "temporal", "causal", "entity"]
                    },
                    "depth": { "type": "integer" }
                },
                "required": ["start_memory_id"]
            }),
        ),
        McpTool::new(
            "cerebrum_consolidate",
            "Trigger memory consolidation (dream phase).",
            json!({
                "type": "object",
                "properties": {
                    "domain": { "type": "string" }
                }
            }),
        ),
        McpTool::new(
            "cerebrum_prefetch",
            "Prefetch likely-needed memories for a task.",
            json!({
                "type": "object",
                "properties": {
                    "task_type": { "type": "string" }
                },
                "required": ["task_type"]
            }),
        ),
    ]
}

/// Parse an MCP request from JSON.
pub fn parse_request(json: &str) -> Result<McpRequest, serde_json::Error> {
    serde_json::from_str(json)
}

/// Format an MCP response as JSON.
pub fn format_response(response: &McpResponse) -> Result<String, serde_json::Error> {
    serde_json::to_string(response)
}

/// Execute an MCP tool by name, returning a structured response.
///
/// This is a stub dispatcher; real implementations will call into the
/// corresponding Cerebrum subsystem.
pub fn execute_tool(name: &str, params: &serde_json::Value) -> McpResponse {
    match name {
        "cerebrum_query" => McpResponse::success(json!({
            "tool": "cerebrum_query",
            "query": params.get("query"),
            "status": "dispatched"
        })),
        "cerebrum_curate" => McpResponse::success(json!({
            "tool": "cerebrum_curate",
            "operation": params.get("operation"),
            "status": "dispatched"
        })),
        "cerebrum_retrieve" => McpResponse::success(json!({
            "tool": "cerebrum_retrieve",
            "memory_id": params.get("memory_id"),
            "status": "dispatched"
        })),
        "cerebrum_search" => McpResponse::success(json!({
            "tool": "cerebrum_search",
            "query": params.get("query"),
            "status": "dispatched"
        })),
        "cerebrum_graph_traverse" => McpResponse::success(json!({
            "tool": "cerebrum_graph_traverse",
            "start_memory_id": params.get("start_memory_id"),
            "status": "dispatched"
        })),
        "cerebrum_consolidate" => McpResponse::success(json!({
            "tool": "cerebrum_consolidate",
            "status": "dispatched"
        })),
        "cerebrum_prefetch" => McpResponse::success(json!({
            "tool": "cerebrum_prefetch",
            "task_type": params.get("task_type"),
            "status": "dispatched"
        })),
        _ => McpResponse::error(format!("unknown tool: {}", name)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tool_definitions() {
        let tools = cerebrum_tools();
        assert_eq!(tools.len(), 7);
        let names: Vec<String> = tools.iter().map(|t| t.name.clone()).collect();
        for expected in [
            "cerebrum_query",
            "cerebrum_curate",
            "cerebrum_retrieve",
            "cerebrum_search",
            "cerebrum_graph_traverse",
            "cerebrum_consolidate",
            "cerebrum_prefetch",
        ] {
            assert!(names.contains(&expected.to_string()), "missing tool {}", expected);
        }
        for tool in &tools {
            assert!(!tool.description.is_empty());
            assert!(tool.input_schema.is_object());
        }
    }

    #[test]
    fn test_request_parsing() {
        let json = r#"{"method": "tools/call", "params": {"name": "cerebrum_query", "arguments": {"query": "rust"}}}"#;
        let request = parse_request(json).unwrap();
        assert_eq!(request.method, "tools/call");
        assert!(request.params.is_object());
    }

    #[test]
    fn test_response_formatting() {
        let response = McpResponse::success(json!({"status": "ok"}));
        let formatted = format_response(&response).unwrap();
        assert!(formatted.contains("ok"));
        assert!(formatted.contains("null") || formatted.contains("error"));
    }

    #[test]
    fn test_tool_execution() {
        for tool in cerebrum_tools() {
            let params = json!({"query": "test", "task_type": "search", "memory_id": "abc"});
            let response = execute_tool(&tool.name, &params);
            assert!(response.error.is_none(), "tool {} failed: {:?}", tool.name, response.error);
            assert_eq!(response.result.get("tool"), Some(&json!(tool.name)));
            assert_eq!(response.result.get("status"), Some(&json!("dispatched")));
        }
    }

    #[test]
    fn test_unknown_tool() {
        let response = execute_tool("not_a_tool", &json!({}));
        assert!(response.error.is_some());
    }
}
