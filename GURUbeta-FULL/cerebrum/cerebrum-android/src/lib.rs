//! Cerebrum Android host glue (Phase F).
//!
//! The Android app hosts the real Cerebrum brain inside its own process
//! through this shared library (cdylib). Kotlin talks ONLY to these exported
//! JNI functions; everything else in the rust workspace stays internal.
//!
//! JNI surface (com.unuslumen.app.data.cerebrum.CerebrumHost), Kotlin side:
//! - cerebrumStart(dataDir, socketPath, passphrase): Long = start stamp or 0
//! - cerebrumStop(): the server SIGTERM path
//! - cerebrumSaveNow(): Int = records persisted by that vault pass
//! - cerebrumExecute(frameType: Int, payloadHex: String): String reply hex
//! - cerebrumStatus(): String status-JSON line
//! - cerebrumLastError(): String ("" when nothing failed)
//!
//! Payloads cross JNI as lowercase hex both directions: the exact same
//! bytes the unix/TCP front-door clients exchange with this brain.
//!
//! Key hygiene: the passphrase arrives once through JNI and lands in a
//! `Zeroizing<String>` inside the brain holder only. Stop zeroes it and
//! drops it. No log line sees it; no clone outlives stop.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex, RwLock};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::Mutex as TokioMutex;
use zeroize::Zeroizing;

use cerebrum_server::handler::{handle_frame, ErrorResponse};
use cerebrum_server::persistence::{restore_state, VaultSession};
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame, FrameHeader, HEADER_SIZE};

// ============================================================================
// last-error slot
// ============================================================================

static LAST_ERROR: StdMutex<String> = StdMutex::new(String::new());

fn record_error(error_text: &str) {
    *LAST_ERROR
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = error_text.to_string();
}

/// Empty the last-error slot, so the next failure is described by its own cause.
///
/// THE DEFECT THIS EXISTS FOR, and it cost a red CI run.
///
/// `LAST_ERROR` is one string for the whole process and nothing ever cleared it. Kotlin's
/// `bootOnce` reads it back immediately after a failed start and hands it to
/// `attemptWasKeyOrphan`, which treats a `PassphraseRejected` in that string as permission to
/// delete the Keystore alias and the vault. So a `PassphraseRejected` left in the slot by ANY
/// earlier operation - a previous app launch, an earlier test in the same instrumentation
/// process - became the diagnosis for an unrelated start failure, and the heal destroyed the
/// key on the strength of a stale sentence.
///
/// The symptom was a device test failing intermittently with `pass missing, fail closed` at the
/// point where it booted a restored vault with no caller-supplied passphrase. The vault was
/// fine. The envelope had been deleted by a heal that fired on a message about something else.
///
/// A failed start now describes itself or says nothing, which is the only reading `bootOnce` can
/// safely act on. A start that records no error is reported by Kotlin as the empty string, and
/// an empty string is not a keyword match.
fn clear_last_error() {
    LAST_ERROR
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clear();
}

// ============================================================================
// process globals: one brain per app process; one shared lazy runtime
// ============================================================================

struct AndroidBrain {
    state: Arc<TokioMutex<CerebrumState>>,
    /// Kept in RAM only; stop() wipes and drops. No logs carry these bytes.
    passphrase_ram: RwLock<Option<Zeroizing<String>>>,
    /// Verified vault key held for the life of the brain, so a save pass is a
    /// rewrite plus a fast seal instead of a 256 MiB Argon2id derivation.
    vault: VaultSession,
    socket_path: PathBuf,
    listener_alive: Arc<AtomicBool>,
    save_loop_alive: Arc<AtomicBool>,
    /// Trailing-save generation counter: a mutating frame bumps it; the
    /// save watcher saves 10 trailing seconds after the LAST bump. A brain
    /// hard-killed in the window loses at most 10 seconds of writes.
    dirty_generation: Arc<AtomicU64>,
    started_at_unix_ms: u64,
    requests_handled: AtomicU32,
}

impl AndroidBrain {
    fn running(&self) -> bool {
        self.listener_alive.load(Ordering::SeqCst)
    }

    /// Arm the trailing save: called after every MUTATING brain frame.
    fn mark_dirty(&self) {
        self.dirty_generation.fetch_add(1, Ordering::SeqCst);
    }

    /// Save everything as one fresh vault generation under the held key.
    async fn save_pass(&self) -> Result<usize, String> {
        let mut locked_cell = self.state.lock().await;
        self.vault
            .save_all(&mut locked_cell)
            .map(|report| report.records_written)
            .map_err(|persist_error| format!("{:?}", persist_error))
    }
}

static BRAIN_CELL: RwLock<Option<Arc<AndroidBrain>>> = RwLock::new(None);

fn brain_registered() -> Option<Arc<AndroidBrain>> {
    match BRAIN_CELL.read() {
        Ok(cell_value) => (*cell_value).clone(),
        Err(_) => None,
    }
}

static RUNTIME_HANDLE: RwLock<Option<&'static tokio::runtime::Runtime>> = RwLock::new(None);

fn runtime() -> &'static tokio::runtime::Runtime {
    // Already-built fast path: read-lock then copy the leaked reference out.
    if let Ok(cell_state) = RUNTIME_HANDLE.read() {
        if let Some(built) = *cell_state {
            return built;
        }
    }
    let mut writer = RUNTIME_HANDLE
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(existing) = *writer {
        return existing;
    }
    let built_unboxed = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .worker_threads(2)
        .build()
        .expect("cerebrum-android runtime must build once");
    let leaked_pointer: &'static tokio::runtime::Runtime = Box::leak(Box::new(built_unboxed));
    *writer = Some(leaked_pointer);
    leaked_pointer
}

fn next_request_id() -> u32 {
    static SEQ: AtomicU32 = AtomicU32::new(1);
    SEQ.fetch_add(1, Ordering::Relaxed)
}

fn unix_ms_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

// ============================================================================
// hex helpers (self-written; exact, no deps)
// ============================================================================

const HEX_CHAR_UPPER: &[u8] = b"0123456789abcdef";

fn jstring_to_owned_value<'local>(
    env_value_ref: &mut JNIEnv<'local>,
    string_handle_value: &JString<'local>,
) -> Option<String> {
    // `unsafe` here: the object IS a java.lang.String (its native type on this
    // export's Kotlin signature). Android's GetObjectClass call inside
    // get_string is skipped on this unchecked path. This is the crate's own
    // doc-prescribed pattern for externally typed strings.
    unsafe { env_value_ref.get_string_unchecked(string_handle_value) }
        .ok()
        .map(|borrowed_value| borrowed_value.to_string_lossy().into_owned())
}

fn bytes_to_hex(raw_input: &[u8]) -> String {
    let mut built = String::with_capacity(raw_input.len() * 2);
    for each_value in raw_input.iter() {
        let upper_digit = (each_value >> 4) as usize;
        let lower_digit = (each_value & 0x0f) as usize;
        built.push(HEX_CHAR_UPPER[upper_digit] as char);
        built.push(HEX_CHAR_UPPER[lower_digit] as char);
    }
    built
}

fn hex_to_bytes(hex_input: &str) -> Result<Vec<u8>, String> {
    if hex_input.is_empty() {
        return Ok(Vec::new());
    }
    let clean_value = hex_input.trim().to_lowercase();
    if !clean_value.len().is_multiple_of(2) {
        return Err("odd-length hex payload".to_string());
    }
    let clean_bytes = clean_value.as_bytes();
    let out_length = clean_bytes.len() / 2;
    let mut decoded = Vec::with_capacity(out_length);
    let digit_at = |byte_value: u8| -> Option<u8> {
        match byte_value {
            letter_lower @ b'a'..=b'f' => Some(letter_lower - b'a' + 10),
            decimal_digit @ b'0'..=b'9' => Some(decimal_digit - b'0'),
            _ => None,
        }
    };
    for raw_chunk in clean_bytes.chunks(2) {
        let high = digit_at(raw_chunk[0]).ok_or("non-hex character")?;
        let low = digit_at(raw_chunk[1]).ok_or("non-hex character")?;
        decoded.push(high << 4 | low);
    }
    Ok(decoded)
}

// ============================================================================
// socket frame IO (wire contract equals the CLI 9-byte framed protocol)
// ============================================================================

async fn socket_read_frame(reader_stream: &mut UnixStream) -> Result<Option<Frame>, String> {
    let mut header_space = [0u8; HEADER_SIZE];
    if let Err(header_read_error) = reader_stream.read_exact(&mut header_space).await {
        match header_read_error.kind() {
            std::io::ErrorKind::UnexpectedEof | std::io::ErrorKind::ConnectionReset => {
                return Ok(None)
            }
            _ => return Err(header_read_error.to_string()),
        }
    }
    let frame_header_value = FrameHeader::decode(&header_space);
    let raw_payload_len = (frame_header_value.length as usize).saturating_sub(HEADER_SIZE);
    let mut body_buffer = vec![0u8; raw_payload_len];
    if raw_payload_len > 0 {
        reader_stream
            .read_exact(&mut body_buffer)
            .await
            .map_err(payload_read_capture_to_string)?;
    }
    Ok(Some(Frame {
        header: frame_header_value,
        payload: body_buffer,
    }))
}

#[allow(dead_code)]
fn payload_read_capture_to_string<T: std::fmt::Debug>(value: T) -> String {
    format!("{:?}", value)
}

async fn socket_write_frame(
    writer_stream: &mut UnixStream,
    frame_value: &Frame,
) -> Result<(), String> {
    writer_stream
        .write_all(&frame_value.encode())
        .await
        .map_err(|failure_val| failure_val.to_string())?;
    writer_stream
        .flush()
        .await
        .map_err(|flush_val| flush_val.to_string())
}

// ============================================================================
// persistence + connection dispatch helpers
// ============================================================================

async fn android_connection_loop(
    mut active_stream: UnixStream,
    working_target: Arc<AndroidBrain>,
) -> Result<(), String> {
    loop {
        let Some(one_frame) = socket_read_frame(&mut active_stream).await? else {
            return Ok(()); // client closed; the standard disconnect
        };
        let frame_request_value = one_frame.header.request_id;
        let dispatch_value = handle_frame(one_frame, Arc::clone(&working_target.state)).await;
        let outgoing_frame = match dispatch_value {
            Ok(response_bytes) => {
                Frame::new(frame_types::RESPONSE, frame_request_value, response_bytes)
            }
            Err(ErrorResponse { message }) => Frame::new(
                frame_types::ERROR,
                frame_request_value,
                serde_json::to_vec(&serde_json::json!({ "error": message })).unwrap_or_default(),
            ),
        };
        socket_write_frame(&mut active_stream, &outgoing_frame).await?;
        working_target
            .requests_handled
            .fetch_add(1, Ordering::SeqCst);
    }
}

// ============================================================================
// spawned loops: dirty-save watcher (trailing, 10s after last write),
// unix listener, plus the 5-min tick kept as the floor cadence
// ============================================================================

fn spawn_periodic_save_cell(brain_shared: Arc<AndroidBrain>) {
    // Clone per task: the tick loop and the trailing-save loop each own.
    let tick_brain = Arc::clone(&brain_shared);
    runtime().spawn(async move {
        let mut interval_cell =
            tokio::time::interval(std::time::Duration::from_secs(SAVE_INTERVAL));
        interval_cell.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            interval_cell.tick().await;
            if !tick_brain.save_loop_alive.load(Ordering::SeqCst) {
                break;
            }
            if let Err(save_fail) = tick_brain.save_pass().await {
                eprintln!("cerebrum-android periodic save: {}", save_fail);
            }
        }
    });

    // TRAILING SAVE WATCHER (hard-close data-loss fix): the 5-minute tick
    // alone loses up to 300 seconds of writes to a process kill. Every
    // mutating frame bumps dirty_generation; this watcher runs a save pass 10
    // seconds after the LAST bump it saw.
    //
    // The quiet-window check compares the generation captured BEFORE the sleep
    // against the live value AFTER it. An earlier version compared the live
    // value against itself, so the guard could never trip and every pass fired
    // the tail save regardless of fresh writes.
    let tail_brain = Arc::clone(&brain_shared);
    runtime().spawn(async move {
        let mut gen_seen: u64 = 0;
        loop {
            if !tail_brain.save_loop_alive.load(Ordering::SeqCst) {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(1000)).await;
            let gen_now = tail_brain.dirty_generation.load(Ordering::SeqCst);
            if gen_now == gen_seen {
                // No dirty write since the last tail save; quiet loop round.
                continue;
            }
            tokio::time::sleep(std::time::Duration::from_secs(TAIL_SAVE_QUIET_SECS)).await;
            let gen_settled = tail_brain.dirty_generation.load(Ordering::SeqCst);
            if gen_settled != gen_now {
                // Fresh writes landed inside the window: defer to a later round
                // rather than rewriting a generation that is already stale.
                continue;
            }
            match tail_brain.save_pass().await {
                Ok(_records_written) => {
                    gen_seen = gen_settled;
                }
                Err(tail_save_err) => {
                    eprintln!("cerebrum-android tail save: {}", tail_save_err);
                }
            }
        }
    });
}

/// Longest a mutating frame can sit unsaved before the trailing watcher fires.
const SAVE_INTERVAL: u64 = 300;

/// Quiet window the trailing watcher waits for before committing a generation.
const TAIL_SAVE_QUIET_SECS: u64 = 10;

fn spawn_socket_listener_cell(brain_shared: Arc<AndroidBrain>) {
    runtime().spawn(async move {
        // Stale socket removal; same file-system behaviour as CLI startup.
        let _ = tokio::fs::remove_file(&brain_shared.socket_path).await;
        let bind_attempt = UnixListener::bind(&brain_shared.socket_path);
        let bound_listener = match bind_attempt {
            Ok(listener_value) => listener_value,
            Err(bind_fail) => {
                eprintln!(
                    "cerebrum-android socket bind {} failed: {}",
                    brain_shared.socket_path.display(),
                    bind_fail
                );
                brain_shared.listener_alive.store(false, Ordering::SeqCst);
                return;
            }
        };
        eprintln!(
            "cerebrum-android unix socket live: {}",
            brain_shared.socket_path.display()
        );
        loop {
            if !brain_shared.running() {
                break;
            }
            match bound_listener.accept().await {
                Ok((fresh_stream, _peer)) => {
                    let shared_cell = Arc::clone(&brain_shared);
                    tokio::spawn(async move {
                        if let Err(connection_failure) =
                            android_connection_loop(fresh_stream, shared_cell).await
                        {
                            eprintln!("cerebrum-android connection: {}", connection_failure);
                        }
                    });
                }
                Err(accept_failure) => {
                    eprintln!("cerebrum-android accept failed: {}", accept_failure);
                    brain_shared.listener_alive.store(false, Ordering::SeqCst);
                    break;
                }
            }
        }
    });
}

// ============================================================================
// boot internal
// ============================================================================

fn brain_boot(data_dir_in: &str, socket_value: &str, pass_value: String) -> Result<u64, String> {
    if pass_value.trim().is_empty() {
        return Err("cerebrum-android boot refused: empty passphrase (fail closed)".into());
    }
    if let Some(already) = brain_registered().filter(|registered_one| registered_one.running()) {
        return Ok(already.started_at_unix_ms);
    }

    let real_dir = PathBuf::from(data_dir_in);
    let socket_path = PathBuf::from(socket_value);
    if let Some(data_parent) = real_dir.parent() {
        match String::from_utf8_lossy(data_parent.as_os_str().as_encoded_bytes()).is_empty() {
            false => {
                let _created_cell = std::fs::create_dir_all(data_parent);
            }
            true => {
                // no parent directory; nothing to prep
            }
        }
    }

    let fresh_state_ptr = Arc::new(TokioMutex::new(CerebrumState::new(real_dir.clone())));
    let fresh_state_clone = Arc::clone(&fresh_state_ptr);

    let restore_value = runtime().block_on(async {
        let mut guard_value = fresh_state_clone.lock().await;
        let restore_target = restore_state(&mut guard_value, real_dir.clone(), pass_value.trim());
        match restore_target {
            Ok(report_value) => Ok::<_, String>(report_value),
            Err(failure_value_in) => Err(format!("{:?}", failure_value_in)),
        }
    });
    let restore_report = restore_value?;
    if !restore_report.clean() {
        eprintln!(
            "cerebrum-android restore: {} records, {} skipped{}",
            restore_report.records_applied,
            restore_report.skipped_records.len(),
            if restore_report.legacy_salvage {
                " (legacy salvage)"
            } else {
                ""
            }
        );
    }

    if let Err(hardening_fail) = cerebrum_server::hardening::install_no_network_filter_bool() {
        eprintln!(
            "cerebrum hardening install not possible (dev): {:?}",
            hardening_fail
        );
    }

    // One verified key for this brain's whole life. Held before the struct is
    // built so a wrong passphrase fails the boot rather than the first save.
    let vault_session = match VaultSession::open(real_dir.clone(), pass_value.trim()) {
        Ok(session_value) => session_value,
        Err(session_error) => return Err(format!("{:?}", session_error)),
    };

    let started_stamp_value = unix_ms_now();
    let listener_state_atomic = Arc::new(AtomicBool::new(true));
    let save_state_atomic_val = Arc::new(AtomicBool::new(true));

    let brain_final = Arc::new(AndroidBrain {
        state: Arc::clone(&fresh_state_ptr),
        passphrase_ram: RwLock::new(Some(Zeroizing::new(pass_value))),
        vault: vault_session,
        socket_path: socket_path.clone(),
        listener_alive: Arc::clone(&listener_state_atomic),
        save_loop_alive: Arc::clone(&save_state_atomic_val),
        dirty_generation: Arc::new(AtomicU64::new(0)),
        started_at_unix_ms: started_stamp_value,
        requests_handled: AtomicU32::new(0),
    });

    spawn_periodic_save_cell(Arc::clone(&brain_final));
    spawn_socket_listener_cell(Arc::clone(&brain_final));

    if let Ok(mut brain_cell_write) = BRAIN_CELL.write() {
        *brain_cell_write = Some(Arc::clone(&brain_final));
    }
    Ok(started_stamp_value)
}

fn status_snapshot_string(brain_value: &Option<Arc<AndroidBrain>>) -> String {
    match brain_value {
        Some(living_value) => {
            let engine_values = runtime().block_on(async {
                let held = living_value.state.lock().await;
                serde_json::json!({
                    "memories": held.context_tree.len(),
                    "dwm": held.dwm.count(),
                    "events": held.event_store.count(),
                    "graph_nodes": held.graph_store.total_nodes(),
                })
            });
            serde_json::json!({
                "running": living_value.running(),
                "save_loop": living_value.save_loop_alive.load(Ordering::SeqCst),
                "uptime_ms": unix_ms_now().saturating_sub(living_value.started_at_unix_ms),
                "requests": living_value.requests_handled.load(Ordering::SeqCst),
                "engines": engine_values,
                "socket": living_value.socket_path.display().to_string(),
            })
            .to_string()
        }
        None => serde_json::json!({ "running": false }).to_string(),
    }
}

// ============================================================================
// JNI exports
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumStartJNI<
    'local,
>(
    env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
    data_input: JString<'local>,
    socket_input: JString<'local>,
    pass_input: JString<'local>,
) -> jlong {
    /*
     * A START BEGINS WITH A CLEAN SLOT. Kotlin reads `lastError` straight after a failed start
     * and treats a `PassphraseRejected` in it as permission to wipe the Keystore alias and the
     * vault. Without this clear, a failure from any earlier operation in this process could be
     * read as the cause of THIS start, and the heal would destroy a healthy key on a stale
     * sentence. That is exactly what made a device test fail intermittently.
     */
    clear_last_error();

    let mut env_value_ref_borrow = env_value;
    let data_extract = jstring_to_owned_value(&mut env_value_ref_borrow, &data_input);
    let socket_extract = jstring_to_owned_value(&mut env_value_ref_borrow, &socket_input);
    let pass_extract = jstring_to_owned_value(&mut env_value_ref_borrow, &pass_input);

    let (data_string, socket_string, pass_string) = (data_extract, socket_extract, pass_extract);
    let Some(data_ready) = data_string else {
        record_error("cerebrum start: dataDir is unreadable or null");
        return 0;
    };
    let Some(socket_ready) = socket_string else {
        record_error("cerebrum start: socketPath is unreadable or null");
        return 0;
    };
    let Some(pass_ready) = pass_string else {
        record_error("cerebrum start: passphrase is unreadable or null");
        return 0;
    };

    match brain_boot(&data_ready, &socket_ready, pass_ready) {
        Ok(stamp_value) => stamp_value as jlong,
        Err(boot_failure) => {
            eprintln!("{}", boot_failure);
            // The slot was cleared at the top of this call, so this is THIS start's failure
            // and nothing else. A caller reading it back gets a cause, not a leftover.
            record_error(&boot_failure);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumStopJNI<
    'local,
>(
    _env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) {
    // The brain handle comes out first; nothing else can call into a mid-stop
    // brain. Everything after is teardown order preserved from the CLI stop.
    let target_brain: Option<Arc<AndroidBrain>> = match BRAIN_CELL.write() {
        Ok(mut writer_slot) => writer_slot.take(),
        Err(_) => None,
    };
    let Some(stop_brain_target) = target_brain else {
        return;
    };

    stop_brain_target
        .listener_alive
        .store(false, Ordering::SeqCst);
    stop_brain_target
        .save_loop_alive
        .store(false, Ordering::SeqCst);

    let save_attempt = runtime().block_on(stop_brain_target.save_pass());
    if let Err(fail_to_run) = save_attempt {
        let value_text = format!("cerebrum stop final save: {}", fail_to_run);
        eprintln!("{}", value_text);
        record_error(&value_text);
    }

    let stale_socket_path = std::clone::Clone::clone(&stop_brain_target.socket_path);
    let _ = std::fs::remove_file(&stale_socket_path);

    // The passphrase slot empties now. Zeroizing wrapper's own drop wipes it.
    let wipe_outcome: Option<Option<Zeroizing<String>>> =
        match Arc::clone(&stop_brain_target).passphrase_ram.write() {
            Ok(mut holder_slot) => Some(holder_slot.take()),
            Err(_) => None,
        };
    let _ = wipe_outcome;
}

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumSaveNowJNI<
    'local,
>(
    _env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) -> jint {
    // Some brain, and alive, or zero records.
    let Some(living_target) = brain_registered().filter(|check_value| check_value.running()) else {
        return 0;
    };
    match runtime().block_on(living_target.save_pass()) {
        Ok(persist_count) => persist_count as jint,
        Err(manual_save_fail) => {
            record_error(&format!("cerebrum manual save: {}", manual_save_fail));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumExecuteJNI<
    'local,
>(
    env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
    frame_type_value: jint,
    payload_hex_input: JString<'local>,
) -> JString<'local> {
    let now_mutable_environment = env_value;
    let mut env_value_borrow_cell = now_mutable_environment;
    let Some(current_brain) = brain_registered().filter(|running_brain| running_brain.running())
    else {
        let offline_json =
            serde_json::json!({ "error": "cerebrum brain is not running" }).to_string();
        return jni_string(env_value_borrow_cell, &offline_json);
    };

    let Some(payload_hex_text) =
        jstring_to_owned_value(&mut env_value_borrow_cell, &payload_hex_input)
    else {
        let null_arg_json =
            serde_json::json!({ "error": "payload hex argument null or unreadable" }).to_string();
        return jni_string(env_value_borrow_cell, &null_arg_json);
    };
    let payload_input_value = match hex_to_bytes(&payload_hex_text) {
        Ok(parsed_payload) => parsed_payload,
        Err(hex_failure_text) => {
            let bad_hex_json = serde_json::json!({
                "error": format!("payload hex malformed: {}", hex_failure_text)
            })
            .to_string();
            return jni_string(env_value_borrow_cell, &bad_hex_json);
        }
    };

    let dispatched_result = runtime().block_on(async {
        handle_frame(
            Frame::new(
                frame_type_value as u8,
                next_request_id(),
                payload_input_value,
            ),
            Arc::clone(&current_brain.state),
        )
        .await
    });
    current_brain
        .requests_handled
        .fetch_add(1, Ordering::SeqCst);

    // Trailing-save arm: ANY successful mutating frame (curate/consolidate)
    // bumps the dirty count so the 10-second tail-save watcher comes in and
    // lands the encrypted pass even on sudden process death. Query frames
    // (1/search 4/graph 5/retrieve 3) never mark dirty.
    match frame_type_value {
        2 | 6 => current_brain.mark_dirty(),
        _ => {}
    }
    let dispatch_attempt = dispatched_result;

    let response_hex = match dispatch_attempt {
        Ok(response_payload) => bytes_to_hex(&response_payload),
        Err(ErrorResponse { message }) => bytes_to_hex(
            serde_json::to_vec(&serde_json::json!({ "error": message }))
                .unwrap_or_default()
                .as_slice(),
        ),
    };
    jni_string(env_value_borrow_cell, &response_hex)
}

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumStatusJNI<
    'local,
>(
    env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) -> JString<'local> {
    let registered_current = brain_registered();
    let status_text = status_snapshot_string(&registered_current);
    jni_string(env_value, &status_text)
}

#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumLastErrorJNI<
    'local,
>(
    env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) -> JString<'local> {
    let error_text_value = LAST_ERROR
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    jni_string(env_value, &error_text_value)
}

/// Ask whether this boot's failure was the vault's own header refusing the passphrase.
///
/// THE KEY-VAULT ORPHAN SIGNAL, ASKED AS ITS OWN QUESTION rather than found by searching prose.
///
/// Kotlin's heal path deletes the Keystore alias and the vault, so it must fire on exactly one
/// condition. It used to search the last-error text for `PassphraseRejected`, and that string is
/// a single process-wide slot: a rejection recorded by an earlier operation stayed there, so a
/// later unrelated failure inherited it, the heal ran, and it destroyed a healthy key. A device
/// test failed intermittently at exactly that point - booting a restored vault with no
/// caller-supplied passphrase - because the envelope had been deleted by a heal triggered by a
/// sentence about something else.
///
/// The slot is cleared at the start of every boot now, so this reads THIS attempt's failure.
/// The substring test remains the mechanism, but it is over a string with one writer and one
/// lifetime rather than over whatever happened to be in there.
#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumLastErrorWasPassphraseRejectionJNI<
    'local,
>(
    _env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) -> jboolean {
    let was_rejection = LAST_ERROR
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .contains("PassphraseRejected");
    jboolean::from(was_rejection)
}

/// Empty the last-error slot on demand.
///
/// The start path already clears it, so this exists for a caller that has read the slot and
/// wants the next read to describe a new attempt rather than the one it just handled.
#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumClearLastErrorJNI<
    'local,
>(
    _env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) {
    clear_last_error();
}

// ============================================================================
// build identity
// ============================================================================

/// The commit this cdylib was compiled from, as one searchable marker.
///
/// WHY THIS EXISTS. Until now the shipped `libcerebrum_android.so` was a
/// hand-copied artefact, and nothing in any build could say which Cerebrum
/// source it came from. Three merged fixes were missing from the released APK
/// and no check caught it, because there was no way to ask the binary what it
/// was. Gradle now compiles this crate, and this marker is how the packaged APK
/// proves which revision landed inside it: the string is read back out of the
/// shipped `.so` and compared with the revision being built.
///
/// A build with no revision available still compiles and reports "unknown",
/// which is honest rather than plausible.
///
/// A bare 40-character hex string appears nowhere else in the library and is
/// awkward to find in an extracted APK. The `cerebrum-build-rev=` prefix makes
/// it greppable and unmistakable.
///
/// It is a single `concat!` constant rather than `format!` on purpose: a
/// formatted string stores the prefix and the revision as two separate literals,
/// so a scan for `cerebrum-build-rev=<rev>` finds the prefix and then nothing.
/// Concatenation at compile time puts the whole marker in the binary as one run
/// of bytes, which is what the APK provenance check reads.
///
/// THE TRAILING `;` IS LOAD-BEARING, and its absence failed a CI run.
///
/// A Rust string literal carries no NUL in `.rodata` once the linker has merged
/// adjacent constants, so an unterminated marker sits immediately next to
/// whatever literal follows it. The APK check reads the marker back with
/// `[A-Za-z0-9._-]+`, which happily ran on past the end of the revision and
/// produced `cerebrum-build-rev=<40 hex>payload` - the start of the next string
/// in the binary. The comparison then failed even though the brain WAS built
/// from the right commit, and reported it as a stale artefact.
///
/// `;` is outside that character class, so the scan stops exactly where the
/// revision ends. The marker is now self-delimiting rather than relying on what
/// the linker happens to place after it.
const BUILD_IDENTITY: &str = concat!("cerebrum-build-rev=", env!("CEREBRUM_BUILD_REV"), ";");

/// Report the Cerebrum source revision compiled into this library.
///
/// `CerebrumHost.buildIdentity()` calls this, and the native-provenance check
/// compares it against the git revision the APK was built from.
#[no_mangle]
pub extern "system" fn Java_com_unuslumen_app_data_brain_cerebrum_CerebrumHost_cerebrumBuildIdentityJNI<
    'local,
>(
    env_value: JNIEnv<'local>,
    _class_value: JClass<'local>,
) -> JString<'local> {
    jni_string(env_value, BUILD_IDENTITY)
}

// ============================================================================
// jstring helper
// ============================================================================

fn jni_string<'local>(env_value: JNIEnv<'local>, text_value: &str) -> JString<'local> {
    // Fallback: empty-string if build fails for any reason including OOM
    // inside the JVM at this call.
    match env_value.new_string(text_value) {
        Ok(created_value) => created_value,
        Err(_) => {
            // Nothing safer exists: hard fallback of empty text.
            env_value
                .new_string("")
                .expect("jvm cannot even allocate empty string; hard stop")
        }
    }
}

#[cfg(test)]
mod last_error_slot_tests {
    use super::{clear_last_error, record_error};

    /// The slot describes the attempt that just failed, and nothing older.
    ///
    /// THE DEFECT THAT FAILED A DEVICE TEST INTERMITTENTLY. This slot is one string for the
    /// whole process. Kotlin reads it after a failed boot and treats a `PassphraseRejected` in
    /// it as permission to delete the Keystore alias and the vault. Nothing cleared it, so a
    /// rejection from an earlier operation was inherited by a later unrelated failure and the
    /// heal destroyed a healthy key. The device test then found no envelope and reported
    /// `pass missing, fail closed` against a vault that was never opened.
    ///
    /// Clearing at the start of each attempt is what makes a stale sentence unreachable. These
    /// tests exist because the sequence, not the string, is the entire defect; a test that only
    /// checked the error text would pass with the clear removed.
    ///
    /// THE TESTS SERIALISE ON A MUTEX because the slot is process-global and `cargo test` runs
    /// tests in parallel. Without it, two of these would clear each other's writes and fail for
    /// a reason that has nothing to do with the behaviour.
    static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn with_lock<R>(body: impl FnOnce() -> R) -> R {
        let _guard = SERIAL
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        body()
    }

    #[test]
    fn a_stale_rejection_is_not_readable_after_the_slot_is_cleared() {
        with_lock(|| {
            record_error("restore failed: PassphraseRejected");
            assert!(
                super::LAST_ERROR
                    .lock()
                    .unwrap()
                    .contains("PassphraseRejected"),
                "precondition: the slot holds a rejection",
            );

            clear_last_error();
            assert!(
                !super::LAST_ERROR
                    .lock()
                    .unwrap()
                    .contains("PassphraseRejected"),
                "after the clear, an earlier rejection must not be readable as this attempt's cause",
            );
        });
    }

    #[test]
    fn a_later_failure_replaces_an_earlier_rejection_rather_than_joining_it() {
        with_lock(|| {
            record_error("restore failed: PassphraseRejected");
            // A different failure on the next attempt, as the start path writes it.
            record_error("cerebrum-android boot refused: empty passphrase (fail closed)");

            let slot = super::LAST_ERROR.lock().unwrap().clone();
            assert!(
                !slot.contains("PassphraseRejected"),
                "the slot must carry only the newest failure; got {slot:?}",
            );
        });
    }
}

#[cfg(test)]
mod build_identity_tests {
    use super::BUILD_IDENTITY;

    /// The marker terminates itself, so a scan cannot run into the next literal.
    ///
    /// THE DEFECT THIS GUARDS. The APK provenance check failed a CI run with
    /// `cerebrum-build-rev=<40 hex>payload` because the marker had no terminator and the
    /// scanner's character class ran on into the following string in `.rodata`. The
    /// revision was correct; the bytes after it were not part of it. A delimiter outside
    /// the scanned class ends the match exactly at the revision.
    #[test]
    fn the_marker_ends_where_the_revision_ends() {
        assert_eq!(
            BUILD_IDENTITY,
            format!("cerebrum-build-rev={};", env!("CEREBRUM_BUILD_REV")),
            "the marker must be the prefix, the revision and one terminator and nothing else",
        );

        // The scan the pipeline runs. It must recover exactly the revision.
        let scanned: String = BUILD_IDENTITY
            .chars()
            .skip("cerebrum-build-rev=".len())
            .take_while(|character| {
                character.is_ascii_alphanumeric()
                    || *character == '-'
                    || *character == '_'
                    || *character == '.'
            })
            .collect();
        assert_eq!(
            scanned,
            env!("CEREBRUM_BUILD_REV"),
            "a scan of the marker must recover the revision and stop at the terminator",
        );
    }
}
