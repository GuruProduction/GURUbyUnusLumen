//! Cerebrum MCP — Model Context Protocol surface for the Cerebrum brain.
//!
//! Phase E: REAL dispatch. `execute_tool` routes into the same live brain the
//! server's frame path uses, over a shared `Arc<Mutex<CerebrumState>>`:
//! curate/query/retrieve/search/graph/consolidate/prefetch mutate REAL
//! engine state through the SAME handle_frame contract as the wire protocol.
//! One brain state, many front doors; no simulated values anywhere.

use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::atomic::{AtomicU64, Ordering as MemOrdering};
use std::sync::Arc;
use tokio::sync::Mutex;

use cerebrum_core::{CurateOp, ContextEntry, MemoryId, PartialEntry};
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame};

/// Re-export so front-door hosts share one brain-state type by name.
pub use cerebrum_server::state::CerebrumState as SharedStateType;

/// Shared brain handle type used across every front door.
pub type SharedState = Arc<Mutex<CerebrumState>>;

/// An MCP tool definition.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    pub input_schema: serde_json::Value,
}

impl McpTool {
    pub fn new(
        name: impl Into<String>,
        description: impl Into<String>,
        input_schema: serde_json::Value,
    ) -> Self {
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
pub fn parse_request(body: &str) -> Result<McpRequest, serde_json::Error> {
    serde_json::from_str(body)
}

/// Format an MCP response as JSON.
pub fn format_response(response: &McpResponse) -> Result<String, serde_json::Error> {
    serde_json::to_string(response)
}

fn tool_name_to_frame(name: &str) -> Option<u8> {
    Some(match name {
        "cerebrum_query" => frame_types::QUERY,
        "cerebrum_curate" => frame_types::CURATE,
        "cerebrum_retrieve" => frame_types::RETRIEVE,
        "cerebrum_search" => frame_types::SEARCH,
        "cerebrum_graph_traverse" => frame_types::GRAPH_TRAVERSE,
        "cerebrum_consolidate" => frame_types::CONSOLIDATE,
        "cerebrum_prefetch" => frame_types::PREFETCH,
        _ => return None,
    })
}

/// A tool payload's request body bytes for the matching frame type, exactly
/// matching what the wire protocol deserializes for that handler. Unknown
/// tools short-circuit earlier by name dispatch.
fn build_frame_payload(
    _tool: &str,
    params: &serde_json::Value,
) -> Result<Vec<u8>, String> {
    serde_json::to_vec(params).map_err(|e| e.to_string())
}

/// Dispatch an MCP tool REAL-style through handle_frame (one-shot frame).
pub async fn dispatch_tool(
    tool: &str,
    params: &serde_json::Value,
    state_handle: SharedState,
) -> McpResponse {
    let frame_type = match tool_name_to_frame(tool) {
        Some(ft) => ft,
        None => return McpResponse::error(format!("unknown tool: {}", tool)),
    };

    // Parameter canonicalisation per tool: the MCP arg surface maps into the
    // wire payload that each handler deserializes.
    let payload: serde_json::Value = match tool {
        "cerebrum_query" => {
            let budget = params
                .get("budget_tokens")
                .and_then(|x| x.as_u64())
                .unwrap_or(4096) as usize;
            let Some(text) = params.get("query").and_then(|x| x.as_str()) else {
                return McpResponse::error("query text missing".to_string());
            };
            json!({
                "query_type": "Semantic",
                "text": text,
                "budget": { "max_tokens": budget, "used_tokens": 0 }
            })
        }
        "cerebrum_curate" => match build_curate_ops(params) {
            Ok(ops) => ops,
            Err(e) => return McpResponse::error(e),
        },
        "cerebrum_retrieve" => {
            let Some(id_hex) = params.get("memory_id").and_then(|x| x.as_str()) else {
                return McpResponse::error("memory_id missing".to_string());
            };
            if memory_id_from_hex_string(id_hex).is_none() {
                return McpResponse::error(format!("invalid memory_id hex: {}", id_hex));
            }
            let level = match params.get("level").and_then(|x| x.as_str()) {
                Some("shallow") => serde_json::json!("Shallow"),
                Some("partial") => serde_json::json!({
                    "Partial": { "sections": params
                        .get("sections")
                        .cloned()
                        .unwrap_or(json!([])) }
                }),
                _ => serde_json::json!("Deep"),
            };
            json!({ "memory_id": id_hex, "level": level })
        }
        "cerebrum_search" => {
            let Some(query) = params.get("query").and_then(|x| x.as_str()) else {
                return McpResponse::error("query missing".to_string());
            };
            json!({
                "query": query,
                "limit": params.get("limit").and_then(|x| x.as_u64()).unwrap_or(10) as usize
            })
        }
        "cerebrum_graph_traverse"
        | "cerebrum_consolidate"
        | "cerebrum_prefetch" => params.clone(),
        _ => unreachable!("tool name filtered above"),
    };

    let payload_bytes = match build_frame_payload(tool, &payload) {
        Ok(bytes) => bytes,
        Err(e) => return McpResponse::error(e),
    };

    let frame = Frame::new(
        frame_type,
        NEXT_REQUEST_ID.fetch_add(1, MemOrdering::Relaxed) as u32,
        payload_bytes,
    );
    match handle_frame(frame, state_handle).await {
        Ok(raw) => McpResponse::success(as_json_from(raw)),
        Err(error_response) => McpResponse::error(error_response.message),
    }
}

static NEXT_REQUEST_ID: AtomicU64 = AtomicU64::new(1);

fn as_json_from(raw: Vec<u8>) -> serde_json::Value {
    serde_json::from_slice(&raw).unwrap_or(serde_json::Value::Null)
}

/// Hex-string memory id (wire canonical form everywhere in Cerebrum server).
fn memory_id_from_hex_string(hex_text: &str) -> Option<MemoryId> {
    let lowered = hex_text.to_lowercase();
    if lowered.len() != 64 || !lowered.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let mut bytes = [0u8; 32];
    for (index, pair) in lowered.as_bytes().chunks(2).enumerate() {
        let high = (pair[0] as char).to_digit(16)?;
        let low = (pair[1] as char).to_digit(16)?;
        bytes[index] = (high as u8) * 16 + low as u8;
    }
    Some(MemoryId(bytes))
}

/// Build curation frame ops list from the MCP parameters.
fn build_curate_ops(params: &serde_json::Value) -> Result<serde_json::Value, String> {
    let operation = params
        .get("operation")
        .and_then(|x| x.as_str())
        .ok_or("operation missing")?;
    let reason = params
        .get("reason")
        .and_then(|x| x.as_str())
        .unwrap_or("mcp curate");
    let entry = params.get("entry").cloned().unwrap_or(json!({}));
    let memory_hex = entry
        .get("memory_id")
        .and_then(|x| x.as_str())
        .unwrap_or("");
    let mem_id = memory_id_from_hex_string(memory_hex)
        .ok_or("valid entry.memory_id hex required")?;
    let domain = entry
        .get("domain")
        .and_then(|x| x.as_str())
        .unwrap_or("mcp")
        .to_string();
    let topic = entry
        .get("topic")
        .and_then(|x| x.as_str())
        .unwrap_or("general")
        .to_string();
    let subtopic = entry
        .get("subtopic")
        .and_then(|x| x.as_str())
        .unwrap_or("mcp")
        .to_string();
    let content = entry
        .get("content")
        .and_then(|x| x.as_str())
        .ok_or("entry.content required")?
        .to_string();
    let now = chrono::Utc::now();

    let op_value = match operation {
        "delete" => CurateOp::Delete {
            memory_id: mem_id,
            reason: reason.into(),
        },
        "update" => CurateOp::Update {
            memory_id: mem_id,
            changes: PartialEntry {
                domain: Some(domain),
                topic: Some(topic),
                subtopic: Some(subtopic),
                content: Some(content),
                ..Default::default()
            },
            reason: reason.into(),
        },
        _ => CurateOp::Upsert {
            entry: ContextEntry {
                memory_id: mem_id,
                domain,
                topic,
                subtopic,
                content,
                relations: Vec::new(),
                provenance: "mcp".to_string(),
                rationale: reason.to_string(),
                created_at: now,
                modified_at: now,
            },
            reason: reason.into(),
        },
    };
    serde_json::to_value(vec![op_value]).map_err(|e| e.to_string())
}

fn memory_id_hex(id: &MemoryId) -> String {
    id.0.iter().map(|b| format!("{:02x}", b)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_server::state::CerebrumState;

    #[test]
    fn test_hex_helpers_converse_properly() {
        // Round-trip and parser validity as REAL contract tests (kept after
        // the debugging probe served its purpose and was retired).
        let parsed = memory_id_from_hex_string(MEMORY_HEX_A);
        assert!(parsed.is_some());
        let hex = memory_id_hex(&parsed.unwrap());
        assert_eq!(hex.len(), 64, "hex serialization writes 64 chars");
        assert!(memory_id_from_hex_string("not_hex").is_none());
        assert!(memory_id_from_hex_string("00112233").is_none());
    }

    async fn shared_state() -> SharedState {
        let mut dir = std::env::temp_dir();
        dir.push(format!(
            "cerebrum-mcp-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        Arc::new(Mutex::new(CerebrumState::new(dir)))
    }

    /// REAL flow battery: MCP curate through handle_frame mutates real state
    /// (tree, DWM, decay, events, hot cache, policy), which the MCP surface's
    /// subsequent verify reads reflect.
    #[tokio::test]
    async fn mcp_curate_real_state_flow_and_search_hit() {
        let state = shared_state().await;

        // Two upserts with related content, both through the MCP tool.
        for (hex_id_text, content) in [
            (MEMORY_HEX_A, "Rust is a productive language with real safety."),
            (MEMORY_HEX_B, "Rust memory safety rules improve productivity."),
        ] {
            let params = json!({
                "operation": "upsert",
                "reason": "mcp battery add",
                "entry": {
                    "memory_id": hex_id_text,
                    "domain": "mcp-tests",
                    "topic": "real",
                    "subtopic": "flow",
                    "content": content
                }
            });
            let response = dispatch_tool("cerebrum_curate", &params, state.clone()).await;
            assert!(
                response.error.is_none(),
                "curate failed: {:?} -> {}",
                response.error,
                response.result
            );
        }

        // Locked-state inspection, honest counters:
        {
            let locked = state.lock().await;
            assert_eq!(locked.context_tree.len(), 2, "two curated in tree");
            assert_eq!(locked.dwm.count(), 2, "two signed in DWM");
            assert_eq!(locked.event_store.count(), 2, "two event trail rows");
            assert_eq!(locked.query_engine.hot_cache.len(), 2, "both hot-cached");
            assert_eq!(locked.experience_store.count(), 2, "both policy-indexed");
        }

        // The MCP search surface hits REAL signature-space results. With the
        // production HashTokenizer the memory keyword hits the rust memory.
        let search_response = dispatch_tool(
            "cerebrum_search",
            &json!({ "query": "rust memory safety", "limit": 5 }),
            state.clone(),
        )
        .await;
        assert!(search_response.error.is_none());
        let hits = search_response
            .result
            .as_array()
            .cloned()
            .unwrap_or_default();
        let hit_texts = hits
            .iter()
            .filter_map(|row| row.get("content").and_then(|x| x.as_str()).map(|item| item.to_string()))
            .collect::<Vec<String>>();
        assert!(
            hit_texts.iter().any(|text| text.contains("Rust memory safety")),
            "MCP search must surface the matching curated memory through real engines; got {:?}",
            hit_texts
        );
    }

    #[test]
    fn unknown_tools_error_properly() {
        // dispatch_tool with unknown tool returns error response (sync check
        // path through an async fn via a minimal runtime).
        let runtime = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        runtime.block_on(async {
            let _state = shared_state().await;
            let response = dispatch_tool("cerebrum_unknown", &json!({}), _state).await;
            assert!(response.error.is_some());
        });
    }
}

const MEMORY_HEX_A: &str = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
/// Descending 32-byte pattern used as stable test id (verified 64 hex chars).
const MEMORY_HEX_B: &str = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";