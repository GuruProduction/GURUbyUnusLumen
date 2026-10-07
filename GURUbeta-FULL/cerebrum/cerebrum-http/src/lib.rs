//! Cerebrum HTTP — request router over the real Cerebrum brain (Phase E).
//!
//! `dispatch_request` takes HttpRequest-shaped input and drives it through
//! cerebrum_server's real `handle_frame` contract against a shared
//! CerebrumState, matching how the MCP front door works. One brain state,
//! many front doors. This module opens NO sockets itself; the networked
//! debug listener stays under server main.rs's loopback-only gate.

use cerebrum_core::{BinarySignature, ContextEntry, CurateOp, EdgeType, MemoryId};
use serde::{Deserialize, Serialize};
use serde_json::json;

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use std::sync::atomic::{AtomicU32, Ordering};

/// HTTP server configuration.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HttpConfig {
    pub bind_address: String,
    pub cors_enabled: bool,
}

impl Default for HttpConfig {
    fn default() -> Self {
        Self {
            bind_address: "127.0.0.1:4223".to_string(),
            cors_enabled: true,
        }
    }
}

/// HTTP route definitions.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Route {
    StoreMemory,
    SearchMemories,
    GetMemory { id: MemoryId },
    Curate,
    GraphTraverse,
    Consolidate,
    NotFound,
}

impl Route {
    /// Match a method/path pair to a route.
    pub fn parse(method: &str, path: &str) -> Self {
        let parts: Vec<&str> = path.trim_start_matches('/').split('/').collect();
        match (method.to_uppercase().as_str(), parts.as_slice()) {
            ("POST", ["memory", "store"]) => Route::StoreMemory,
            ("POST", ["memory", "search"]) => Route::SearchMemories,
            ("GET", ["memory", id]) => match decode_memory_id(id) {
                Some(memory_id) => Route::GetMemory { id: memory_id },
                None => Route::NotFound,
            },
            ("POST", ["memory", "curate"]) => Route::Curate,
            ("POST", ["graph", "traverse"]) => Route::GraphTraverse,
            ("POST", ["consolidate"]) => Route::Consolidate,
            _ => Route::NotFound,
        }
    }

    /// Return the HTTP method for this route.
    pub fn method(&self) -> &'static str {
        match self {
            Route::GetMemory { .. } => "GET",
            _ => "POST",
        }
    }

    /// Return the path pattern for this route.
    pub fn path(&self) -> &'static str {
        match self {
            Route::StoreMemory => "/memory/store",
            Route::SearchMemories => "/memory/search",
            Route::GetMemory { .. } => "/memory/{id}",
            Route::Curate => "/memory/curate",
            Route::GraphTraverse => "/graph/traverse",
            Route::Consolidate => "/consolidate",
            Route::NotFound => "/",
        }
    }
}

/// Hex-decode a 64-char id string into a full MemoryId, else None.
pub fn decode_memory_id(input_hex: &str) -> Option<MemoryId> {
    let bytes = hex::decode(input_hex).ok()?;
    let bytes_arr: [u8; 32] = bytes.try_into().ok()?;
    Some(MemoryId(bytes_arr))
}

/// Encode a MemoryId as 64-char lowercase hex.
pub fn encode_memory_id(id: &MemoryId) -> String {
    id.0.iter().map(|b| format!("{:02x}", b)).collect()
}

/// Request body for POST /memory/store.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoreMemoryRequest {
    pub content: String,
    pub domain: Option<String>,
    pub topic: Option<String>,
    pub subtopic: Option<String>,
}

/// Request body for POST /memory/search.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SearchMemoriesRequest {
    pub query: String,
    pub radius: Option<u32>,
    pub limit: Option<usize>,
}

/// Request body for POST /memory/curate.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CurateRequest {
    pub operation: CurateOp,
}

/// Request body for POST /graph/traverse.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GraphTraverseRequest {
    pub start: MemoryId,
    pub edge_type: EdgeType,
    pub depth: Option<usize>,
}

/// Request body for POST /consolidate.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConsolidateRequest {
    pub domain: Option<String>,
}

/// Generic HTTP request router shape.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HttpRequest {
    pub method: String,
    pub path: String,
    pub body: Option<serde_json::Value>,
}

impl HttpRequest {
    pub fn route(&self) -> Route {
        Route::parse(&self.method, &self.path)
    }
}

/// Generic HTTP response shape.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HttpResponse {
    pub status: u16,
    pub body: serde_json::Value,
}

impl HttpResponse {
    pub fn ok(body: serde_json::Value) -> Self {
        Self { status: 200, body }
    }

    pub fn error(status: u16, message: &str) -> Self {
        Self {
            status,
            body: json!({ "error": message }),
        }
    }
}

/// Byte-identity signature helper (kept engine-agnostic for stability).
pub fn query_to_signature(query: &str) -> BinarySignature {
    let bytes = query.as_bytes();
    let mut sig = [0u8; 32];
    for (index, byte) in bytes.iter().enumerate().take(32) {
        sig[index] = *byte;
    }
    BinarySignature(sig)
}

// ============================================================================
// Dispatch over the real brain state
// ============================================================================

pub use cerebrum_server::state::CerebrumState;

/// Shared brain handle shape (MCP and Node carry the same shape).
pub type SharedHttpState = std::sync::Arc<tokio::sync::Mutex<CerebrumState>>;

fn next_request_id() -> u32 {
    static SEQ: AtomicU32 = AtomicU32::new(1);
    SEQ.fetch_add(1, Ordering::Relaxed)
}

/// Wrap a JSON body to (frame_type, payload_bytes) or an early HTTP error.
/// Pure functions keep the async dispatch fn to straight-line control flow:
/// no ? operators, only early returns on a Result of tuple-vs-error.
type PreparedFrame = Result<(u8, Vec<u8>), HttpResponse>;

fn prepare_store(body: Option<serde_json::Value>) -> PreparedFrame {
    let Some(raw) = body else {
        return Err(HttpResponse::error(400, "store body missing"));
    };
    let parsed: StoreMemoryRequest = match serde_json::from_value(raw) {
        Ok(parsed) => parsed,
        Err(e) => return Err(HttpResponse::error(400, &format!("store body malformed: {}", e))),
    };
    let ops = store_to_curate_ops(parsed);
    let ops_values = ops
        .iter()
        .map(|op| serde_json::to_value(op))
        .collect::<Result<Vec<serde_json::Value>, _>>();
    let Ok(values) = ops_values else {
        return Err(HttpResponse::error(500, "serialize failure (ops)"));
    };
    match serde_json::to_vec(&values) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::CURATE, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

fn prepare_curate(body: Option<serde_json::Value>) -> PreparedFrame {
    let Some(raw) = body else {
        return Err(HttpResponse::error(400, "curate body missing"));
    };
    match serde_json::to_vec(&raw) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::CURATE, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

fn prepare_search(body: Option<serde_json::Value>) -> PreparedFrame {
    let Some(raw) = body else {
        return Err(HttpResponse::error(400, "search body missing"));
    };
    let parsed: SearchMemoriesRequest = match serde_json::from_value(raw) {
        Ok(parsed) => parsed,
        Err(e) => return Err(HttpResponse::error(400, &format!("search body malformed: {}", e))),
    };
    let shape = json!({ "query": parsed.query, "limit": parsed.limit.unwrap_or(10) });
    match serde_json::to_vec(&shape) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::SEARCH, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

fn prepare_get(id: &MemoryId) -> PreparedFrame {
    let shape = json!({ "memory_id": id.0.to_vec(), "level": "Shallow" });
    match serde_json::to_vec(&shape) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::RETRIEVE, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

fn prepare_graph_traverse(body: Option<serde_json::Value>) -> PreparedFrame {
    let Some(raw) = body else {
        return Err(HttpResponse::error(400, "traverse body missing"));
    };
    match serde_json::to_vec(&raw) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::GRAPH_TRAVERSE, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

fn prepare_consolidate(body: Option<serde_json::Value>) -> PreparedFrame {
    let parse_attempt: Result<ConsolidateRequest, String> = match body {
        Some(raw) => serde_json::from_value(raw)
            .map_err(|e| format!("consolidate body malformed: {}", e)),
        None => Ok(ConsolidateRequest { domain: None }),
    };
    let shape = match parse_attempt {
        Ok(parsed) => parsed,
        Err(message) => return Err(HttpResponse::error(400, &message)),
    };
    match serde_json::to_vec(&json!({ "domain": shape.domain })) {
        Ok(bytes) => Ok((cerebrum_server::frame_types::CONSOLIDATE, bytes)),
        Err(e) => Err(HttpResponse::error(500, &format!("serialize failure: {}", e))),
    }
}

/// Dispatch one HTTP-shaped request against the real brain. Real engine
/// mutation via shared CerebrumState; no sockets opened by dispatch.
pub async fn dispatch_request(
    request: HttpRequest,
    state_handle: SharedHttpState,
) -> HttpResponse {
    use cerebrum_server::Frame;

    let route = request.route();
    let prepared = match route {
        Route::NotFound => {
            return HttpResponse::error(404, &request.path.clone());
        }
        Route::StoreMemory => prepare_store(request.body.clone()),
        Route::Curate => prepare_curate(request.body.clone()),
        Route::SearchMemories => prepare_search(request.body.clone()),
        Route::GetMemory { id } => prepare_get(&id),
        Route::GraphTraverse => prepare_graph_traverse(request.body.clone()),
        Route::Consolidate => prepare_consolidate(request.body.clone()),
    };

    let frame_and_bytes = match prepared {
        Ok(ok_shape) => ok_shape,
        Err(response) => return response,
    };

    let (frame_type, payload) = frame_and_bytes;
    let frame = Frame::new(frame_type, next_request_id(), payload);
    let dispatch_value = cerebrum_server::handler::handle_frame(frame, state_handle).await;

    match dispatch_value {
        Ok(raw) => match serde_json::from_slice::<serde_json::Value>(&raw) {
            Ok(body_value) => HttpResponse::ok(body_value),
            Err(_) => HttpResponse::ok(json!({ "payload_bytes": raw.len() })),
        },
        Err(err_block) => HttpResponse::error(500, &err_block.message),
    }
}

/// One StoreMemoryRequest carries content over a fresh MemoryId (a stable,
/// content-driven std-hash 4-lane build: deterministic given identical
/// content and length; distinct with overwhelming probability otherwise; the
/// dream engine stays the dedup authority downstream).
fn store_to_curate_ops(store_req: StoreMemoryRequest) -> Vec<CurateOp> {
    let memory_id = deterministic_id_of_content(&store_req.content);

    let ctx_entry = ContextEntry {
        memory_id,
        domain: store_req.domain.unwrap_or_else(|| "http".to_string()),
        topic: store_req.topic.unwrap_or_else(|| "general".to_string()),
        subtopic: store_req.subtopic.unwrap_or_else(|| "http".to_string()),
        content: store_req.content,
        relations: Vec::new(),
        provenance: "http".to_string(),
        rationale: store_reason_text(),
        created_at: rfc3339_now(),
        modified_at: rfc3339_now(),
    };

    let bytes_for_debug_id = memory_id.0;
    assert_eq!(bytes_for_debug_id.len(), 32);

    vec![CurateOp::Upsert {
        entry: ctx_entry,
        reason: store_reason_text(),
    }]
}

fn store_reason_text() -> String {
    "http upsert".to_string()
}

fn rfc3339_now() -> chrono::DateTime<chrono::Utc> {
    chrono::Utc::now()
}

/// Stable 4-lane content hash into a 32-byte id. std-hash keeps this
/// dependency-free: FNV-quality mixing without extra deps (std::hash).
fn deterministic_id_of_content(content: &str) -> MemoryId {
    let mut lane0 = DefaultHasher::new();
    let mut lane1 = DefaultHasher::new();
    let mut lane2 = DefaultHasher::new();
    let mut lane3 = DefaultHasher::new();
    content.hash(&mut lane0);
    lane1.write_u64(0x9E3779B97F4A7C15u64.rotate_left(11) ^ (content.len() as u64));
    lane1.write(content.as_bytes());
    lane2.write_u64(u64::from_be_bytes([b'c', b'e', b'r', b'b', 0, 0, 0, 1]));
    lane2.write(content.as_bytes());
    lane3.write_u64(0x5F3FA71E19C0DEAEu64.rotate_right(3) ^ content.len() as u64);
    lane3.write(content.as_bytes());
    let lanes = [lane0.finish(), lane1.finish(), lane2.finish(), lane3.finish()];
    let mut bytes = [0u8; 32];
    for lane_index_index in 0..4usize {
        for byte_lane in 0..8usize {
            bytes[lane_index_index * 8 + byte_lane] =
                ((lanes[lane_index_index] >> (byte_lane * 8)) & 0xFF) as u8;
        }
    }
    bytes[0] ^= b'H'; // HTTP front-door lane marker; id shape stays the real type
    MemoryId(bytes)
}