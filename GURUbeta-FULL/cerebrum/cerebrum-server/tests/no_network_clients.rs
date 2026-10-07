//! Workspace invariant: NO network client dependency can EVER enter any
//! Cargo.toml in the Cerebrum workspace. Locked at the test layer so any
//! future PR adding reqwest/hyper/ureq/curl/isahc/openssl (or plain sockets
//! beyond the AF_UNIX sidecar path) breaks the build with a loud assertion
//! rather than silently shipping a phone exfiltration surface.
//!
//! Private-by-design rule enforcement: the brain never initiates ANY external
//! network I/O; all memory movement happens through local Unix sockets, all
//! transport lives and dies inside one handset.

use std::fs;
use std::path::{Path, PathBuf};

const FORBIDDEN_CLIENT_TOKENS: &[&str] = &[
    "reqwest", "hyper", "ureq", "curl", "isahc", "attohttpc", "openssl",
    "native_tls", "rustls", "_surf", "quinn", "tokio-tungstenite", "websocket",
    "async-compression::http", "libp2p", "mdns", "libseccomp-rs",
    // DNS dialers:
    "trust-dns", "hickory",
];

/// The only network-ish token allowed in any Cargo.toml: NONE, since socket
/// paths ride tokio's in-tree implementations (tokio-net "unix/tcp" surfaces
/// used for the loopback-only debug listener ride the tokio "net" feature
/// already present in cerebrum-server: allowed, it's in-tree and AF_UNIX
// + loopback-gated by hardening.rs).
const APPROVED_SURFACE_NETWORK_TOKENS: &[&str] = &[
    "tokio", // in-tree async net (unix + tcp): gated in code, allowed by design
];

fn workspace_root() -> PathBuf {
    // This test file lives at {server-pkg}/tests/, so two parents up is the
    // Cerebrum workspace root holding every crate's manifest.
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
}

fn cargo_tomls_under(root: &Path, out: &mut Vec<PathBuf>) {
    if let Ok(entries) = fs::read_dir(root) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                let dir_name = path.file_name().map(|s| s.to_string_lossy().to_string());
                // Compiled artifacts + hidden dirs never matter.
                if matches!(dir_name.as_deref(), Some("target") | Some(".git") | Some("examples")) {
                    continue;
                }
                cargo_tomls_under(&path, out);
            } else if path.file_name().map(|n| n == "Cargo.toml").unwrap_or(false) {
                out.push(path);
            }
        }
    }
}

#[test]
fn no_workspace_manifest_may_add_a_network_client() {
    let root = workspace_root();
    let mut manifests: Vec<PathBuf> = Vec::new();
    cargo_tomls_under(&root, &mut manifests);

    assert!(
        !manifests.is_empty(),
        "workspace manifests must exist for this assertion to be real"
    );

    let mut violations: Vec<String> = Vec::new();
    for manifest in manifests {
        let content = fs::read_to_string(&manifest).unwrap_or_default();
        for forbidden in FORBIDDEN_CLIENT_TOKENS {
            let dep_line_pattern = format!("{} =", forbidden);
            let has_direct_dep = content.contains(&dep_line_pattern);
            if has_direct_dep {
                violations.push(format!(
                    "{} carries forbidden network dependency: {}",
                    manifest.display(),
                    forbidden
                ));
            }
        }
    }

    assert!(
        violations.is_empty(),
        "NETWORK-CLIENT INVARIANT VIOLATED:\n{}",
        violations.join("\n")
    );
}

/// The engine surface itself never imports std::net outbound dial paths:
/// source sweep over every .rs in the workspace for dial markers, excluding
/// this invariant file itself (its own detection strings match by design) and
/// server main.rs (whose loopback check uses ToSocketAddrs on purpose).
#[test]
fn source_trees_contain_no_outbound_dial_paths() {
    let root = workspace_root();
    let self_path = fs::canonicalize(env!("CARGO_MANIFEST_DIR"))
        .map(|d| d.join("tests").join("no_network_clients.rs"))
        .unwrap_or_else(|_| PathBuf::from("no_network_clients.rs"));
    let mut sources: Vec<PathBuf> = Vec::new();
    collect_sources(&root, &mut sources);

    let dial_markers = [
        "TcpStream::connect",
        "UdpSocket::bind",
        "HttpConnector",
        "to_socket_addrs().connect",
        "TcpStream::connect_timeout",
    ];

    let mut violations: Vec<String> = Vec::new();
    for source in sources {
        let is_self = fs::canonicalize(&source)
            .map(|c| c == self_path)
            .unwrap_or(false)
            || source
                .file_name()
                .map(|n| n == "no_network_clients.rs")
                .unwrap_or(false);
        if is_self {
            continue;
        }
        let content = fs::read_to_string(&source).unwrap_or_default();
        for marker in dial_markers {
            if content.contains(marker) {
                violations.push(format!("{} in {}", marker, source.display()));
            }
        }
    }
    assert!(
        violations.is_empty(),
        "OUTBOUND-DIAL INVARIANT VIOLATED:\n{}",
        violations.join("\n")
    );
}

fn collect_sources(root: &Path, out: &mut Vec<PathBuf>) {
    if let Ok(entries) = fs::read_dir(root) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                let dir_name = path.file_name().map(|s| s.to_string_lossy().to_string());
                if matches!(dir_name.as_deref(), Some("target") | Some(".git")) {
                    continue;
                }
                collect_sources(&path, out);
            } else if path.extension().map(|e| e == "rs").unwrap_or(false) {
                out.push(path);
            }
        }
    }
}