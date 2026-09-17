//! Cerebrum Server — custom binary protocol server.
//!
//! Frame format:
//!
//! ```text
//! [4 bytes: frame length][1 byte: frame type][4 bytes: request_id][payload]
//! ```
//!
//! This crate implements frame encoding/decoding, a TCP server skeleton, and
//! the full subsystem routing layer via `state` and `handler`.

pub mod handler;
pub mod state;

use std::net::SocketAddr;

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

/// Fixed header size in bytes.
pub const HEADER_SIZE: usize = 9;

/// Frame type values.
pub mod frame_types {
    pub const QUERY: u8 = 0x01;
    pub const CURATE: u8 = 0x02;
    pub const RETRIEVE: u8 = 0x03;
    pub const SEARCH: u8 = 0x04;
    pub const GRAPH_TRAVERSE: u8 = 0x05;
    pub const CONSOLIDATE: u8 = 0x06;
    pub const PREFETCH: u8 = 0x07;
    pub const RESPONSE: u8 = 0x80;
    pub const ERROR: u8 = 0x81;

    pub const ALL: [u8; 9] = [
        QUERY,
        CURATE,
        RETRIEVE,
        SEARCH,
        GRAPH_TRAVERSE,
        CONSOLIDATE,
        PREFETCH,
        RESPONSE,
        ERROR,
    ];
}

/// Decoded frame header.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct FrameHeader {
    pub length: u32,
    pub frame_type: u8,
    pub request_id: u32,
}

impl FrameHeader {
    /// Encode the header into its 9-byte wire representation.
    pub fn encode(&self) -> [u8; HEADER_SIZE] {
        let mut buf = [0u8; HEADER_SIZE];
        buf[0..4].copy_from_slice(&self.length.to_be_bytes());
        buf[4] = self.frame_type;
        buf[5..9].copy_from_slice(&self.request_id.to_be_bytes());
        buf
    }

    /// Decode a 9-byte header.
    pub fn decode(bytes: &[u8; HEADER_SIZE]) -> Self {
        let length = u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
        let frame_type = bytes[4];
        let request_id = u32::from_be_bytes([bytes[5], bytes[6], bytes[7], bytes[8]]);
        Self {
            length,
            frame_type,
            request_id,
        }
    }
}

/// A complete protocol frame.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Frame {
    pub header: FrameHeader,
    pub payload: Vec<u8>,
}

impl Frame {
    /// Build a frame from type, request id, and payload.
    pub fn new(frame_type: u8, request_id: u32, payload: Vec<u8>) -> Self {
        let length = HEADER_SIZE as u32 + payload.len() as u32;
        Self {
            header: FrameHeader {
                length,
                frame_type,
                request_id,
            },
            payload,
        }
    }

    /// Encode the full frame to bytes.
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(self.header.length as usize);
        bytes.extend_from_slice(&self.header.encode());
        bytes.extend_from_slice(&self.payload);
        bytes
    }

    /// Decode a frame from a byte slice.
    ///
    /// Returns `Ok(Frame)` if the slice contains a valid frame with matching
    /// length, otherwise `Err(())`.
    pub fn decode(bytes: &[u8]) -> Result<Frame, ServerError> {
        if bytes.len() < HEADER_SIZE {
            return Err(ServerError::InvalidFrame("frame too short".to_string()));
        }
        let header = FrameHeader::decode(bytes[0..HEADER_SIZE].try_into().unwrap());
        let total_len = header.length as usize;
        if bytes.len() < total_len {
            return Err(ServerError::InvalidFrame("incomplete frame".to_string()));
        }
        if total_len < HEADER_SIZE {
            return Err(ServerError::InvalidFrame("length below header size".to_string()));
        }

        let payload = bytes[HEADER_SIZE..total_len].to_vec();
        Ok(Frame { header, payload })
    }
}

/// Server configuration.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerConfig {
    pub bind_address: String,
    pub max_connections: usize,
    pub timeout_ms: u64,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            bind_address: "127.0.0.1:4222".to_string(),
            max_connections: 100,
            timeout_ms: 30000,
        }
    }
}

/// Server handle.
#[derive(Debug)]
pub struct CerebrumServer {
    pub config: ServerConfig,
    running: bool,
    listener: Option<TcpListener>,
}

impl CerebrumServer {
    pub fn new(config: ServerConfig) -> Self {
        Self {
            config,
            running: false,
            listener: None,
        }
    }

    pub fn is_running(&self) -> bool {
        self.running
    }

    /// Start the TCP listener. In a real implementation this would accept and
    /// dispatch connections; here it binds and stores the listener.
    pub async fn start(&mut self) -> Result<SocketAddr, ServerError> {
        let listener = TcpListener::bind(&self.config.bind_address)
            .await
            .map_err(|e| ServerError::BindFailed(e.to_string()))?;
        let addr = listener.local_addr().map_err(|e| ServerError::BindFailed(e.to_string()))?;
        self.listener = Some(listener);
        self.running = true;
        Ok(addr)
    }

    pub fn stop(&mut self) {
        self.running = false;
        self.listener = None;
    }

    /// Decode a single frame from a TCP stream.
    pub async fn read_frame(stream: &mut TcpStream) -> Result<Frame, ServerError> {
        let mut header_buf = [0u8; HEADER_SIZE];
        stream
            .read_exact(&mut header_buf)
            .await
            .map_err(|e| ServerError::Io(e.to_string()))?;

        let header = FrameHeader::decode(&header_buf);
        let payload_len = header.length as usize - HEADER_SIZE;
        let mut payload = vec![0u8; payload_len];
        if payload_len > 0 {
            stream
                .read_exact(&mut payload)
                .await
                .map_err(|e| ServerError::Io(e.to_string()))?;
        }

        Ok(Frame { header, payload })
    }

    /// Write a frame to a TCP stream.
    pub async fn write_frame(stream: &mut TcpStream, frame: &Frame) -> Result<(), ServerError> {
        stream
            .write_all(&frame.encode())
            .await
            .map_err(|e| ServerError::Io(e.to_string()))?;
        Ok(())
    }
}

/// Server-level errors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ServerError {
    BindFailed(String),
    InvalidFrame(String),
    Io(String),
}

impl std::fmt::Display for ServerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ServerError::BindFailed(msg) => write!(f, "bind failed: {}", msg),
            ServerError::InvalidFrame(msg) => write!(f, "invalid frame: {}", msg),
            ServerError::Io(msg) => write!(f, "io error: {}", msg),
        }
    }
}

impl std::error::Error for ServerError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_frame_encode_decode_roundtrip() {
        let frame = Frame::new(frame_types::QUERY, 42, b"hello".to_vec());
        let encoded = frame.encode();
        let decoded = Frame::decode(&encoded).unwrap();
        assert_eq!(frame, decoded);
    }

    #[test]
    fn test_all_frame_types() {
        for (idx, frame_type) in frame_types::ALL.iter().enumerate() {
            let payload = format!("payload-{}", idx).into_bytes();
            let frame = Frame::new(*frame_type, idx as u32 + 1, payload);
            let encoded = frame.encode();
            let decoded = Frame::decode(&encoded).unwrap();
            assert_eq!(decoded.header.frame_type, *frame_type);
        }
    }

    #[test]
    fn test_header_parsing() {
        let header = FrameHeader {
            length: 14,
            frame_type: frame_types::SEARCH,
            request_id: 1234,
        };
        let encoded = header.encode();
        let decoded = FrameHeader::decode(&encoded);
        assert_eq!(header, decoded);
    }

    #[test]
    fn test_invalid_frame_too_short() {
        let result = Frame::decode(b"\x00\x00");
        assert!(matches!(result, Err(ServerError::InvalidFrame(_))));
    }

    #[test]
    fn test_invalid_frame_incomplete() {
        let frame = Frame::new(frame_types::QUERY, 1, vec![0u8; 100]);
        let encoded = frame.encode();
        let truncated = &encoded[..HEADER_SIZE + 10];
        let result = Frame::decode(truncated);
        assert!(matches!(result, Err(ServerError::InvalidFrame(_))));
    }

    #[tokio::test]
    async fn test_server_start_stop() {
        let mut server = CerebrumServer::new(ServerConfig::default());
        let addr = server.start().await.unwrap();
        assert!(server.is_running());
        assert_eq!(addr.to_string(), "127.0.0.1:4222");
        server.stop();
        assert!(!server.is_running());
    }
}
