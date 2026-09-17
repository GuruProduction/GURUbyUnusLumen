//! Cerebrum Server — binary entry point for the Cerebrum AGI memory system.
//!
//! Starts a Unix domain socket listener (primary transport for Lux Code sidecar
//! communication) and an optional TCP listener. Each connection is accepted in
//! its own Tokio task; frames are read, routed through `CerebrumState`, and a
//! response frame is written back. Graceful shutdown on SIGTERM/SIGINT.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use cerebrum_server::{frame_types, Frame, FrameHeader, HEADER_SIZE};
use clap::Parser;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixListener;
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;

use cerebrum_server::handler::handle_frame;
use cerebrum_server::handler::ErrorResponse;
use cerebrum_server::state::CerebrumState;

/// Cerebrum server command-line options.
#[derive(Debug, Clone, Parser)]
#[command(name = "cerebrum-server", about = "Cerebrum AGI memory system server")]
struct Cli {
    /// Unix socket path for the primary sidecar listener.
    #[arg(short = 's', long = "socket", default_value = "/tmp/cerebrum.sock")]
    socket: PathBuf,

    /// Optional TCP bind address for remote access.
    #[arg(short = 't', long = "tcp")]
    tcp: Option<String>,

    /// Data directory for persistent storage.
    #[arg(short = 'd', long = "data-dir", default_value = "./cerebrum-data")]
    data_dir: PathBuf,
}

/// Shared server state wrapped for concurrent access across connection tasks.
type SharedState = Arc<Mutex<CerebrumState>>;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .init();

    let state = Arc::new(Mutex::new(CerebrumState::new(cli.data_dir.clone())));

    // Remove stale socket file before binding.
    cleanup_socket(&cli.socket).await;

    let socket_path = cli.socket.clone();
    let unix_state = Arc::clone(&state);
    let unix_server_handle = tokio::spawn(async move {
        if let Err(e) = run_unix_listener(&socket_path, unix_state).await {
            eprintln!("Unix listener error: {}", e);
        }
    });

    let tcp_handle = if let Some(tcp_addr) = cli.tcp.clone() {
        let tcp_state = Arc::clone(&state);
        Some(tokio::spawn(async move {
            if let Err(e) = run_tcp_listener(&tcp_addr, tcp_state).await {
                eprintln!("TCP listener error: {}", e);
            }
        }))
    } else {
        None
    };

    eprintln!(
        "Cerebrum server started: unix_socket={}, tcp={}",
        cli.socket.display(),
        cli.tcp.as_deref().unwrap_or("disabled")
    );

    // Wait for SIGTERM or SIGINT.
    let mut sigterm = signal(SignalKind::terminate())?;
    let mut sigint = signal(SignalKind::interrupt())?;
    tokio::select! {
        _ = sigterm.recv() => eprintln!("Received SIGTERM, shutting down gracefully..."),
        _ = sigint.recv() => eprintln!("Received SIGINT, shutting down gracefully..."),
    }

    // Cancel connection accept loops.
    unix_server_handle.abort();
    if let Some(h) = tcp_handle {
        h.abort();
    }

    cleanup_socket(&cli.socket).await;
    eprintln!("Cerebrum server stopped.");
    Ok(())
}

async fn cleanup_socket(path: &Path) {
    if path.exists() {
        let _ = tokio::fs::remove_file(path).await;
    }
}

/// Accept connections on a Unix domain socket and dispatch frames.
async fn run_unix_listener(
    path: &Path,
    state: SharedState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let listener = UnixListener::bind(path)?;
    eprintln!("Listening on Unix socket: {}", path.display());

    loop {
        let (stream, _) = listener.accept().await?;
        let state = Arc::clone(&state);
        tokio::spawn(async move {
            if let Err(e) = handle_connection(stream, state).await {
                tracing::error!("unix connection error: {}", e);
            }
        });
    }
}

/// Accept connections on a TCP socket and dispatch frames.
async fn run_tcp_listener(
    addr: &str,
    state: SharedState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let listener = tokio::net::TcpListener::bind(addr).await?;
    eprintln!("Listening on TCP: {}", addr);

    loop {
        let (stream, peer) = listener.accept().await?;
        tracing::info!("tcp connection accepted from {}", peer);
        let state = Arc::clone(&state);
        tokio::spawn(async move {
            if let Err(e) = handle_connection(stream, state).await {
                tracing::error!("tcp connection error from {}: {}", peer, e);
            }
        });
    }
}

/// Handle a single connection (Unix or TCP) until it closes.
///
/// Uses a simple read-frame / dispatch / write-frame loop. Each frame is
/// processed independently — no pipelining or multiplexing within a
/// connection.
async fn handle_connection<S>(mut stream: S, state: SharedState) -> Result<(), String>
where
    S: AsyncReadExt + AsyncWriteExt + Unpin,
{
    loop {
        match read_frame(&mut stream).await {
            Ok(Some(frame)) => {
                let request_id = frame.header.request_id;
                let response = match handle_frame(frame, Arc::clone(&state)).await {
                    Ok(payload) => Frame::new(frame_types::RESPONSE, request_id, payload),
                    Err(ErrorResponse { message }) => Frame::new(
                        frame_types::ERROR,
                        request_id,
                        serde_json::to_vec(&serde_json::json!({ "error": message }))
                            .unwrap_or_default(),
                    ),
                };
                write_frame(&mut stream, &response).await?;
            }
            Ok(None) => break, // peer closed connection
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// Read a single frame from an async stream.
///
/// Returns `Ok(None)` when the peer has cleanly closed the connection.
async fn read_frame<S>(stream: &mut S) -> Result<Option<Frame>, String>
where
    S: AsyncReadExt + Unpin,
{
    let mut header_buf = [0u8; HEADER_SIZE];
    match stream.read_exact(&mut header_buf).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => return Ok(None),
        Err(e) => return Err(e.to_string()),
    }

    let header = FrameHeader::decode(&header_buf);
    let payload_len = header.length.saturating_sub(HEADER_SIZE as u32) as usize;
    let mut payload = vec![0u8; payload_len];
    if payload_len > 0 {
        stream
            .read_exact(&mut payload)
            .await
            .map_err(|e| e.to_string())?;
    }

    Ok(Some(Frame { header, payload }))
}

/// Write a frame to an async stream.
async fn write_frame<S>(stream: &mut S, frame: &Frame) -> Result<(), String>
where
    S: AsyncWriteExt + Unpin,
{
    stream
        .write_all(&frame.encode())
        .await
        .map_err(|e| e.to_string())?;
    stream.flush().await.map_err(|e| e.to_string())?;
    Ok(())
}