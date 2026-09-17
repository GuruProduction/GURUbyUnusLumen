//! Cerebrum HTTP — REST API route and request/response definitions.
//!
//! Full HTTP server implementation (axum/actix) comes later. This crate defines
//! the route surface, request bodies, and response shapes.

use cerebrum_core::{BinarySignature, CurateOp, EdgeType, MemoryId};
use serde::{Deserialize, Serialize};

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

fn decode_memory_id(input: &str) -> Option<MemoryId> {
    let bytes = hex::decode(input).ok()?;
    if bytes.len() != 32 {
        return None;
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    Some(MemoryId(arr))
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

/// Generic HTTP request stub.
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

/// Generic HTTP response stub.
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
            body: serde_json::json!({"error": message}),
        }
    }
}

/// Helper to build a Search frame-style signature from a query string.
pub fn query_to_signature(query: &str) -> BinarySignature {
    let bytes = query.as_bytes();
    let mut sig = [0u8; 32];
    for (i, byte) in bytes.iter().enumerate().take(32) {
        sig[i] = *byte;
    }
    BinarySignature(sig)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_route_definitions() {
        let routes = vec![
            ("POST", "/memory/store", Route::StoreMemory),
            ("POST", "/memory/search", Route::SearchMemories),
            ("GET", "/memory/010203", Route::NotFound), // too short
            ("POST", "/memory/curate", Route::Curate),
            ("POST", "/graph/traverse", Route::GraphTraverse),
            ("POST", "/consolidate", Route::Consolidate),
        ];
        for (method, path, expected) in routes {
            assert_eq!(Route::parse(method, path), expected, "{} {}", method, path);
        }
    }

    #[test]
    fn test_get_memory_route() {
        let id = MemoryId([1u8; 32]);
        let hex = hex::encode(id.0);
        let route = Route::parse("GET", &format!("/memory/{}", hex));
        match route {
            Route::GetMemory { id: parsed } => assert_eq!(parsed, id),
            _ => panic!("expected GetMemory route"),
        }
    }

    #[test]
    fn test_request_parsing() {
        let store = serde_json::from_str::<StoreMemoryRequest>(
            r#"{"content":"hello"}"#
        ).unwrap();
        assert_eq!(store.content, "hello");

        let search = serde_json::from_str::<SearchMemoriesRequest>(
            r#"{"query":"rust","radius":10,"limit":5}"#
        ).unwrap();
        assert_eq!(search.query, "rust");
        assert_eq!(search.radius, Some(10));

        let consolidate = serde_json::from_str::<ConsolidateRequest>(
            r#"{"domain":"code"}"#
        ).unwrap();
        assert_eq!(consolidate.domain, Some("code".to_string()));
    }

    #[test]
    fn test_response_formatting() {
        let response = HttpResponse::ok(serde_json::json!({"status": "ok"}));
        let json = serde_json::to_string(&response).unwrap();
        assert!(json.contains("ok"));
        assert!(json.contains("200"));
    }

    #[test]
    fn test_config_defaults() {
        let config = HttpConfig::default();
        assert_eq!(config.bind_address, "127.0.0.1:4223");
        assert!(config.cors_enabled);
    }
}
