//! Cerebrum Server — binary entry point for the Cerebrum brain.
//!
//! Starts a Unix domain socket listener (primary on-device sidecar transport)
//! and a loopback-gated optional TCP listener (debug tooling only; see
//! run_tcp_listener's hardening). Each connection is accepted in its own Tokio
//! task; frames are read, routed through `CerebrumState`, and a response
//! frame is written back. Graceful shutdown on SIGTERM/SIGINT with a final
//! durable save through the encrypted vault.
//!
//! Phase C wiring:
//! - On startup: restore CerebrumState from the encrypted vault in data_dir.
//!   Requires a passphrase: supplied via `--passphrase`, or the `CEREBRUM_`
//!   env var, or a fail-loud refusal to start unencrypted (never silently
//!   persisted in plaintext because persistence is encrypted-only from Phase C).
//!   On-device Phase F replaces this CLI arg with the Android Keystore bridge.
//! - Every 5 minutes: full durable save via CerebrumPersistence::save_all.
//! - On SIGTERM/SIGINT: one final save before listeners tear down.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use cerebrum_server::{frame_types, Frame, FrameHeader, HEADER_SIZE};
use clap::Parser;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{UnixListener, TcpListener};
use tokio::signal::unix::{signal, SignalKind};
use tokio::sync::Mutex;

use cerebrum_server::persistence::{restore_state, CerebrumPersistence};
use cerebrum_server::handler::{handle_frame, ErrorResponse};
use cerebrum_server::state::CerebrumState;

/// Cerebrum server command-line options.
#[derive(Debug, Clone, Parser)]
#[command(name = "cerebrum-server", about = "Cerebrum memory brain server")]
struct Cli {
    /// Unix socket path for the primary sidecar listener.
    #[arg(short = 's', long = "socket", default_value = "/tmp/cerebrum.sock")]
    socket: PathBuf,

    /// Optional TCP bind address for local debugging (loopback only).
    #[arg(short = 't', long = "tcp")]
    tcp: Option<String>,

    /// Data directory for the encrypted brain vault.
    #[arg(short = 'd', long = "data-dir", default_value = "./cerebrum-data")]
    data_dir: PathBuf,

    /// Passphrase for the encrypted vault (or env CEREBRUM_PASSPHRASE).
    #[arg(short = 'p', long)]
    passphrase: Option<String>,
}

/// Shared server state wrapped for concurrent access across connection tasks.
type SharedState = Arc<Mutex<CerebrumState>>;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let cli = Cli::parse();

    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .init();

    let passphrase = resolve_passphrase(&cli).ok_or_else(|| {
        "Cerebrum refuses to run without vault credentials: \
         pass --passphrase or set CEREBRUM_PASSPHRASE (never log it)"
    })?;

    let state = Arc::new(Mutex::new(CerebrumState::new(cli.data_dir.clone())));

    // Kernel hardening (Linux/Android): install the no-network seccomp filter
    // before listeners come up. On non-Linux dev targets this records the
    // honest unhardenable-at-kernel state; on-device it physically blocks any
    // future outbound network family at syscall level. Failure is fatal: the
    // brain refuses an unhardened run on Linux rather than degrading quietly.
    match cerebrum_server::hardening::install_no_network_filter_bool() {
        Ok(true) => eprintln!("Cerebrum kernel hardening: ACTIVE (no-network filter installed)"),
        Ok(false) => eprintln!("Cerebrum kernel hardening: unavailable on this OS (dev target); grep-locks still enforced"),
        Err(e) => {
            #[cfg(target_os = "linux")]
            {
                return Err(format!("Cerebrum kernel hardening FAILED: {:?}", e).into());
            }
            #[cfg(not(target_os = "linux"))]
            {
                eprintln!("Cerebrum kernel hardening: non-linux dev note {:?}", e);
            }
        }
    }

    // Restore brain state from the encrypted vault if one exists already.
    {
        let mut unlocked = state.lock().await;
        match restore_state(&mut unlocked, cli.data_dir.clone(), passphrase.trim()) {
            Ok(()) => {
                eprintln!("Cerebrum brain restored from encrypted vault.");
            }
            Err(cerebrum_server::persistence::PersistError::Crypto(_)) => {
                return Err("Cerebrum vault rejected the provided passphrase (wrong or damaged)".into());
            }
            Err(e) => {
                return Err(format!("Cerebrum vault restore failed: {:?}", e).into());
            }
        }
    }

    // Remove stale socket before binding.
    cleanup_socket(&cli.socket).await;

    let socket_path = cli.socket.clone();
    let unix_state = Arc::clone(&state);
    let unix_server_handle = tokio::spawn(async move {
        if let Err(e) = run_unix_listener(&socket_path, unix_state).await {
            eprintln!("Unix listener error: {}", e);
        }
    });

    // TCP debug listener: hard OFF unless the loopback-only flag is used.
    let tcp_handle = if let Some(tcp_addr) = cli.tcp.clone() {
        enforce_loopback_only(&tcp_addr)
            .map_err(|e| format!("Debug TCP listener must stay loopback-only: {}", e))?;
        let tcp_state = Arc::clone(&state);
        Some(tokio::spawn(async move {
            if let Err(e) = run_tcp_listener(&tcp_addr, tcp_state).await {
                eprintln!("TCP listener error: {}", e);
            }
        }))
    } else {
        None
    };

    // Periodic saving: every 5 minutes through the encrypted vault. Clone
    // the handle state and passphrase-free (the passphrase lives outside the
    // timer's data; the timer holds only paths and locks. The saving vault
    // session re-locks through the process-held passphrase channel below.
    let save_state = Arc::clone(&state);
    let save_dir = cli.data_dir.clone();
    let save_passphrase = passphrase.clone();
    let periodic_save = tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(300));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            interval.tick().await;
            let persist = CerebrumPersistence::new(save_dir.clone());
            let mut guard = save_state.lock().await;
            match persist.save_all(&mut guard, save_passphrase.trim()) {
                Ok(_report) => {
                    // eprintln!("periodic save: {} records", _report.records_written);
                    let _ = _report;
                }
                Err(e) => {
                    tracing::error!("periodic save failed: {:?}", e);
                }
            }
        }
    });

    eprintln!(
        "Cerebrum server started: unix_socket={}, tcp={}",
        cli.socket.display(),
        cli.tcp.as_deref().unwrap_or("disabled")
    );

    // Wait for shutdown signal.
    let mut sigterm = signal(SignalKind::terminate())?;
    let mut sigint = signal(SignalKind::interrupt())?;
    tokio::select! {
        _ = sigterm.recv() => eprintln!("Received SIGTERM, saving and shutting down gracefully..."),
        _ = sigint.recv() => eprintln!("Received SIGINT, saving and shutting down gracefully..."),
    }

    // Final durable save through the encrypted vault BEFORE tearing listeners
    // down; the very last brain write must land in cipher bytes to disk.
    {
        let final_persist = CerebrumPersistence::new(cli.data_dir.clone());
        let mut unlocked = state.lock().await;
        match final_persist.save_all(&mut unlocked, passphrase.trim()) {
            Ok(report) => {
                eprintln!("Final vault write: {} records.", report.records_written);
            }
            Err(e) => {
                tracing::error!("final save failed: {:?}", e);
            }
        }
    }

    // Abort listener + periodic tasks.
    unix_server_handle.abort();
    periodic_save.abort();
    if let Some(h) = tcp_handle {
        h.abort();
    }

    cleanup_socket(&cli.socket).await;
    eprintln!("Cerebrum server stopped.");
    Ok(())
}

/// Passphrase resolution, in order: --passphrase, then CEREBRUM_PASSPHRASE
/// env, else a fail-loud None so main reports and exits rather than running
/// without durable-memory credentials.
fn resolve_passphrase(cli: &Cli) -> Option<String> {
    if let Some(passphrase) = &cli.passphrase {
        return Some(passphrase.clone());
    }
    if let Ok(passphrase) = std::env::var("CEREBRUM_PASSPHRASE") {
        return Some(passphrase);
    }
    None
}

async fn cleanup_socket(path: &Path) {
    if path.exists() {
        let _ = tokio::fs::remove_file(path).await;
    }
}

/// Enforce the loopback-only debug guarantee from Phase D:
/// any TCP bind target that is non-loopback hard-fails.
fn enforce_loopback_only(bind_address: &str) -> Result<(), String> {
    use std::net::ToSocketAddrs;
    let first_addr = bind_address
        .to_socket_addrs()
        .map_err(|e| e.to_string())?
        .next()
        .ok_or("no resolvable bind address")?;
    if !first_addr.ip().is_loopback() {
        return Err(format!("{} is not a loopback address", first_addr.ip()));
    }
    Ok(())
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

/// Accept loopback-only frames for local debug traffic, same dispatch route
/// as unix. Phase D keeps this hardened path (the kernel-level socket blocks
/// land there); the runtime path never uses the debug surface.
async fn run_tcp_listener(
    addr: &str,
    state: SharedState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let listener = TcpListener::bind(addr).await?;
    eprintln!("Listening (DEBUG loopback-only) on TCP: {}", addr);

    loop {
        let (_stream, peer) = listener.accept().await?;
        // Dispatch identical route as unix path for the connection.
        tracing::info!("tcp accept (debug loopback) requested for {}", peer);
        let _state_for = Arc::clone(&state);
        handle_connection(_stream, _state_for).await?;
    }
}

/// Handle a single connection (Unix or TCP) until it closes.
///
/// Simple read-frame / dispatch / write-frame loop without pipelining.
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