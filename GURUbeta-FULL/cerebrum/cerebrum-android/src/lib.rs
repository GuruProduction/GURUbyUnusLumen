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
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::Mutex as TokioMutex;
use zeroize::Zeroizing;

use cerebrum_server::handler::{handle_frame, ErrorResponse};
use cerebrum_server::persistence::{restore_state, CerebrumPersistence};
use cerebrum_server::state::CerebrumState;
use cerebrum_server::{frame_types, Frame, FrameHeader, HEADER_SIZE};

// ============================================================================
// last-error slot
// ============================================================================

static LAST_ERROR: StdMutex<String> = StdMutex::new(String::new());

fn record_error(error_text: &str) {
    *LAST_ERROR.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) =
        error_text.to_string();
}

// ============================================================================
// process globals: one brain per app process; one shared lazy runtime
// ============================================================================

struct AndroidBrain {
    state: Arc<TokioMutex<CerebrumState>>,
    /// Kept in RAM only; stop() wipes and drops. No logs carry these bytes.
    passphrase_ram: RwLock<Option<Zeroizing<String>>>,
    data_dir: PathBuf,
    socket_path: PathBuf,
    listener_alive: Arc<AtomicBool>,
    save_loop_alive: Arc<AtomicBool>,
    /// Trailing-save generation counter: a mutating frame bumps it; the
    /// save watcher saves 10 trailing seconds after the LAST bump. A brain
    /// hard-killed in the window loses at most 10 seconds of writes.
    dirty_generation: AtomicU64,
    started_at_unix_ms: u64,
    requests_handled: AtomicU32,
}

impl AndroidBrain {
    fn running(&self) -> bool {
        self.listener_alive.load(Ordering::SeqCst)
    }

    /// Arm the trailing save: called after every MUTATING brain frame.
    fn mark_dirty(&self) {
        static GLOBAL_DIRTY: AtomicU64 = AtomicU64::new(0);
        let gen_value = GLOBAL_DIRTY.fetch_add(1, Ordering::SeqCst) + 1;
        self.dirty_generation.store(gen_value, Ordering::SeqCst);
    }
}

/// Global gen: the watcher keeps its own "seen" copy of this counter to
/// compare with brain's own gen when scheduling the tail-save pass.
static SAVE_WATCHER_SEEN: AtomicU64 = AtomicU64::new(0);

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
    if clean_value.len() % 2 != 0 {
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
    let raw_payload_len =
        (frame_header_value.length as usize).saturating_sub(HEADER_SIZE);
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

async fn socket_write_frame(writer_stream: &mut UnixStream, frame_value: &Frame) -> Result<(), String> {
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

async fn brain_save_pass(
    state_cell_ptr: Arc<TokioMutex<CerebrumState>>,
    persist_dir: PathBuf,
    pass_value: &str,
) -> Result<usize, String> {
    let persistence_handle = CerebrumPersistence::new(persist_dir);
    let mut locked_cell = state_cell_ptr.lock().await;
    persistence_handle
        .save_all(&mut locked_cell, pass_value)
        .map(|report_value| report_value.records_written)
        .map_err(|persist_error| format!("{:?}", persist_error))
}

async fn android_connection_loop(
    mut active_stream: UnixStream,
    working_target: Arc<AndroidBrain>,
) -> Result<(), String> {
    loop {
        let Some(one_frame) = socket_read_frame(&mut active_stream).await? else {
            return Ok(()); // client closed; the standard disconnect
        };
        let frame_request_value = one_frame.header.request_id;
        let dispatch_value =
            handle_frame(one_frame, Arc::clone(&working_target.state)).await;
        let outgoing_frame = match dispatch_value {
            Ok(response_bytes) => {
                Frame::new(frame_types::RESPONSE, frame_request_value, response_bytes)
            }
            Err(ErrorResponse { message }) => Frame::new(
                frame_types::ERROR,
                frame_request_value,
                serde_json::to_vec(&serde_json::json!({ "error": message }))
                    .unwrap_or_default(),
            ),
        };
        socket_write_frame(&mut active_stream, &outgoing_frame).await?;
        working_target.requests_handled.fetch_add(1, Ordering::SeqCst);
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
            if tick_brain.save_loop_alive.load(Ordering::SeqCst) == false {
                break;
            }
            let pass_for_save = match tick_brain.passphrase_ram.read() {
                Ok(read_cell) => read_cell.as_ref().cloned(),
                Err(_) => None,
            };
            if let Some(kept_pass) = pass_for_save {
                let pass_value = brain_save_pass(
                    Arc::clone(&tick_brain.state),
                    tick_brain.data_dir.clone(),
                    kept_pass.trim(),
                )
                .await;
                if let Err(save_fail) = pass_value {
                    eprintln!("cerebrum-android periodic save: {}", save_fail);
                }
            }
        }
    });

    // TRAILING SAVE WATCHER (hard-close data-loss fix): the 5-minute tick
    // alone loses up to 300 seconds of writes to a process kill. Every
    // mutating frame bumps dirty_generation; this watcher watches the
    // counter and runs a save pass 10 seconds after the LAST bump seen.
    let tail_brain = Arc::clone(&brain_shared);
    runtime().spawn(async move {
        loop {
            if tail_brain.save_loop_alive.load(Ordering::SeqCst) == false {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(1000)).await;
            let gen_now = tail_brain.dirty_generation.load(Ordering::SeqCst);
            let gen_seen = SAVE_WATCHER_SEEN.load(Ordering::SeqCst);
            if gen_now == gen_seen {
                // No dirty write since the last tail save; quiet loop round.
                continue;
            }
            // A write landed since the last ack: wait a 10-second tail, and
            // only save when that tail stayed quiet (no fresh bump inside
            // the window); a fresh bump defers to the watcher's next round.
            tokio::time::sleep(std::time::Duration::from_secs(10)).await;
            let gen_settled = tail_brain.dirty_generation.load(Ordering::SeqCst);
            if gen_settled != tail_brain.dirty_generation.load(Ordering::SeqCst) {
                continue; // unreachable-by-construction safety guard
            }
            let pass_tail = tail_brain
                .passphrase_ram
                .read()
                .ok()
                .and_then(|slot_value| slot_value.clone());
            if let Some(tail_pass) = pass_tail {
                match brain_save_pass(
                    Arc::clone(&tail_brain.state),
                    tail_brain.data_dir.clone(),
                    tail_pass.trim(),
                )
                .await
                {
                    Ok(_records_written) => {
                        SAVE_WATCHER_SEEN.store(gen_settled, Ordering::SeqCst);
                    }
                    Err(tail_save_err) => {
                        eprintln!("cerebrum-android tail save: {}", tail_save_err);
                    }
                }
            }
        }
    });
}

const SAVE_INTERVAL: u64 = 300;

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

fn brain_boot(
    data_dir_in: &str,
    socket_value: &str,
    pass_value: String,
) -> Result<u64, String> {
    if pass_value.trim().is_empty() {
        return Err("cerebrum-android boot refused: empty passphrase (fail closed)".into());
    }
    if let Some(already) =
        brain_registered().filter(|registered_one| registered_one.running())
    {
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
            Ok(completed_value) => Ok::<(), String>(completed_value),
            Err(failure_value_in) => Err(format!("{:?}", failure_value_in)),
        }
    });
    if let Err(restore_fail) = restore_value {
        return Err(restore_fail);
    }

    if let Err(hardening_fail) = cerebrum_server::hardening::install_no_network_filter_bool() {
        eprintln!(
            "cerebrum hardening install not possible (dev): {:?}",
            hardening_fail
        );
    }

    let started_stamp_value = unix_ms_now();
    let listener_state_atomic = Arc::new(AtomicBool::new(true));
    let save_state_atomic_val = Arc::new(AtomicBool::new(true));

    let brain_final = Arc::new(AndroidBrain {
        state: Arc::clone(&fresh_state_ptr),
        passphrase_ram: RwLock::new(Some(Zeroizing::new(pass_value))),
        data_dir: real_dir,
        socket_path: socket_path.clone(),
        listener_alive: Arc::clone(&listener_state_atomic),
        save_loop_alive: Arc::clone(&save_state_atomic_val),
        dirty_generation: AtomicU64::new(0),
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
    let Some(stop_brain_target) = target_brain else { return; };

    stop_brain_target.listener_alive.store(false, Ordering::SeqCst);
    stop_brain_target.save_loop_alive.store(false, Ordering::SeqCst);

    let kept_value: Option<Zeroizing<String>> = match stop_brain_target.passphrase_ram.read() {
        Ok(write_value) => write_value.as_ref().cloned(),
        Err(_) => None,
    };
    if let Some(to_run) = kept_value {
        let save_attempt = runtime().block_on(brain_save_pass(
            Arc::clone(&stop_brain_target.state),
            stop_brain_target.data_dir.clone(),
            to_run.trim(),
        ));
        if let Err(fail_to_run) = save_attempt {
            let value_text = format!("cerebrum stop final save: {}", fail_to_run);
            eprintln!("{}", value_text);
            record_error(&value_text);
        }
    }

    let stale_socket_path = std::clone::Clone::clone(&stop_brain_target.socket_path);
    let _ = std::fs::remove_file(&stale_socket_path);

    // The passphrase slot empties now. Zeroizing wrapper's own drop wipes it.
    let wipe_outcome: Option<Option<Zeroizing<String>>> = match Arc::clone(
        &stop_brain_target,
    )
    .passphrase_ram
        .write()
    {
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
    let Some(living_target) = brain_registered().filter(|check_value| check_value.running())
    else {
        return 0;
    };
    let Some(kept_pass) = living_target
        .passphrase_ram
        .read()
        .ok()
        .and_then(|cell_value| cell_value.clone())
    else {
        return 0;
    };
    match runtime().block_on(brain_save_pass(
        Arc::clone(&living_target.state),
        living_target.data_dir.clone(),
        kept_pass.trim(),
    )) {
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
    let Some(current_brain) =
        brain_registered().filter(|running_brain| running_brain.running())
    else {
        let offline_json =
            serde_json::json!({ "error": "cerebrum brain is not running" }).to_string();
        return jni_string(env_value_borrow_cell, &offline_json);
    };

    let Some(payload_hex_text) = jstring_to_owned_value(&mut env_value_borrow_cell, &payload_hex_input) else {
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
            Frame::new(frame_type_value as u8, next_request_id(), payload_input_value),
            Arc::clone(&current_brain.state),
        )
        .await
    });
    current_brain.requests_handled.fetch_add(1, Ordering::SeqCst);

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
    let error_text_value =
        LAST_ERROR.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone();
    jni_string(env_value, &error_text_value)
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