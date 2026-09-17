//! Persistence for the Dynamic Wavelet Matrix.
//!
//! Implements memory-mapped file persistence, WAL (write-ahead log),
//! and periodic snapshots for crash recovery.
//!
//! Per Build Bible section 0.2.3:
//! - DWM serialized to mmap-able file format
//! - Fast reload on restart — no reconstruction needed
//! - Snapshots for crash recovery
//! - WAL for incremental updates between snapshots
//!
//! # File Format
//!
//! ```text
//! [Header: 24 bytes]
//!   4 bytes: magic "DWM\x01"
//!   4 bytes: format version (u32 LE)
//!   4 bytes: signature_bits (u32 LE)
//!   4 bytes: token_alphabet_size (u32 LE)
//!   4 bytes: number of memories (u32 LE)
//!   4 bytes: token_vocab_id (u32 LE)
//!
//! [Memory entries: variable size]
//!   For each memory:
//!     32 bytes: MemoryId
//!     32 bytes: BinarySignature
//!     4 bytes:  token count (u32 LE)
//!     N × 4 bytes: token IDs (u32 LE each)
//!
//! [Footer: 4 bytes CRC32 of everything above]
//! ```
//!
//! # WAL Format
//!
//! ```text
//! [WAL Header: 8 bytes]
//!   4 bytes: magic "DWMW"
//!   4 bytes: WAL version (u32 LE)
//!
//! [WAL Entries: variable]
//!   For each operation:
//!     1 byte:  entry type (1=Insert, 2=Delete, 3=Checkpoint)
//!     4 bytes: entry length (u32 LE) — length of payload
//!     N bytes: payload (serialized entry data)
//!     4 bytes: CRC32 of type + payload
//! ```

use crate::dynamic_wavelet_matrix::DynamicWaveletMatrix;
use cerebrum_core::{BinarySignature, CerebrumError, MemoryId, TokenSequence};
use memmap2::Mmap;
use std::fs;
use std::io::{BufWriter, Write};
use std::path::Path;

/// File format version.
const DWM_FORMAT_VERSION: u32 = 1;

/// Magic bytes for DWM snapshot files.
const DWM_MAGIC: &[u8; 4] = b"DWM\x01";

/// Magic bytes for WAL files.
#[allow(dead_code)]
const WAL_MAGIC: &[u8; 4] = b"DWMW";

/// WAL entry types.
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[allow(dead_code)]
enum WalEntryType {
    Insert = 1,
    Delete = 2,
    Checkpoint = 3,
}

/// Header size in bytes.
const HEADER_SIZE: usize = 24;

/// CRC32 polynomial (IEEE 802.3).
const CRC32_POLY: u32 = 0xEDB88320;

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xFFFFFFFFu32;
    for &byte in data {
        crc ^= byte as u32;
        for _ in 0..8 {
            if crc & 1 == 1 {
                crc = (crc >> 1) ^ CRC32_POLY;
            } else {
                crc >>= 1;
            }
        }
    }
    !crc
}

/// Persistence handler for DWM.
pub struct DwmPersistence;

impl DwmPersistence {
    // ========================================================================
    // Snapshot save/load (buffered I/O, with CRC)
    // ========================================================================

    /// Save a DWM snapshot to disk with atomic write semantics.
    ///
    /// Writes to a temporary file first, then atomically renames.
    /// Includes CRC32 checksum for data integrity.
    pub fn snapshot(dwm: &mut DynamicWaveletMatrix, path: &Path) -> Result<(), CerebrumError> {
        let tmp_path = path.with_extension("tmp");
        Self::save_internal(dwm, &tmp_path)?;
        fs::rename(&tmp_path, path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to rename snapshot: {}", e))
        })
    }

    /// Save a DWM to disk (non-atomic, use snapshot() for crash safety).
    pub fn save(dwm: &mut DynamicWaveletMatrix, path: &Path) -> Result<(), CerebrumError> {
        Self::save_internal(dwm, path)
    }

    fn save_internal(dwm: &mut DynamicWaveletMatrix, path: &Path) -> Result<(), CerebrumError> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| {
                CerebrumError::StorageError(format!("Failed to create directory: {}", e))
            })?;
        }

        let file = fs::File::create(path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to create file: {}", e))
        })?;
        let mut writer = BufWriter::new(file);

        // Write header
        writer.write_all(DWM_MAGIC).map_err(write_err)?;
        Self::write_u32(&mut writer, DWM_FORMAT_VERSION)?;
        Self::write_u32(&mut writer, dwm.signature_bits() as u32)?;
        Self::write_u32(&mut writer, dwm.token_alphabet_size() as u32)?;
        Self::write_u32(&mut writer, dwm.count() as u32)?;
        Self::write_u32(&mut writer, dwm.token_vocab_id())?;

        // Write memory entries
        for i in 0..dwm.count() {
            let id = dwm.memory_id_at(i).ok_or_else(|| {
                CerebrumError::StorageError(format!("Missing memory ID at position {}", i))
            })?;
            writer.write_all(&id.0).map_err(write_err)?;

            let sig = dwm.reconstruct_signature(i).ok_or_else(|| {
                CerebrumError::StorageError(format!("Missing signature at position {}", i))
            })?;
            writer.write_all(&sig.0).map_err(write_err)?;

            let tokens = dwm.reconstruct_tokens(i).ok_or_else(|| {
                CerebrumError::StorageError(format!("Missing tokens at position {}", i))
            })?;
            Self::write_u32(&mut writer, tokens.tokens.len() as u32)?;
            for &tid in &tokens.tokens {
                Self::write_u32(&mut writer, tid)?;
            }
        }

        writer.flush().map_err(write_err)?;

        // Append CRC32 footer
        drop(writer);
        let data = fs::read(path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to read back for CRC: {}", e))
        })?;
        let checksum = crc32(&data);
        let mut file = fs::OpenOptions::new().append(true).open(path).map_err(write_err)?;
        file.write_all(&checksum.to_le_bytes()).map_err(write_err)?;

        Ok(())
    }

    // ========================================================================
    // Load from disk (buffered I/O, with CRC verification)
    // ========================================================================

    /// Load a DWM from a snapshot file.
    ///
    /// Verifies CRC32 checksum before loading.
    pub fn load(path: &Path) -> Result<DynamicWaveletMatrix, CerebrumError> {
        let data = fs::read(path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to read file: {}", e))
        })?;

        if data.len() < HEADER_SIZE + 4 {
            return Err(CerebrumError::StorageError("File too small".into()));
        }

        // Verify CRC32: last 4 bytes are the checksum of everything before
        let stored_crc = u32::from_le_bytes(
            data[data.len() - 4..].try_into().map_err(|_| {
                CerebrumError::StorageError("CRC read failed".into())
            })?,
        );
        let computed_crc = crc32(&data[..data.len() - 4]);
        if stored_crc != computed_crc {
            return Err(CerebrumError::StorageError(format!(
                "CRC mismatch: stored={:#010x}, computed={:#010x}",
                stored_crc, computed_crc
            )));
        }

        Self::load_from_bytes(&data[..data.len() - 4])
    }

    /// Load a DWM via memory-mapped file.
    ///
    /// The OS maps the file into virtual memory, allowing lazy page-in.
    /// This is faster than buffered I/O for large files because:
    /// - No copy from kernel to user space
    /// - Pages are loaded on demand (lazy page-in)
    /// - The OS handles page eviction
    ///
    /// Per the Build Bible: "Fast reload on restart — no reconstruction needed."
    pub fn load_mmap(path: &Path) -> Result<DynamicWaveletMatrix, CerebrumError> {
        let file = fs::File::open(path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to open file for mmap: {}", e))
        })?;

        let metadata = file.metadata().map_err(|e| {
            CerebrumError::StorageError(format!("Failed to get file metadata: {}", e))
        })?;

        if (metadata.len() as usize) < HEADER_SIZE + 4 {
            return Err(CerebrumError::StorageError("File too small for mmap".into()));
        }

        let mmap = unsafe { Mmap::map(&file).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to mmap file: {}", e))
        })? };

        // Verify CRC32
        let len = mmap.len();
        let stored_crc = u32::from_le_bytes(
            mmap[len - 4..].try_into().map_err(|_| {
                CerebrumError::StorageError("CRC read from mmap failed".into())
            })?,
        );
        let computed_crc = crc32(&mmap[..len - 4]);
        if stored_crc != computed_crc {
            return Err(CerebrumError::StorageError(format!(
                "CRC mismatch (mmap): stored={:#010x}, computed={:#010x}",
                stored_crc, computed_crc
            )));
        }

        Self::load_from_bytes(&mmap[..len - 4])
    }

    /// Parse DWM from raw bytes (shared by load and load_mmap).
    fn load_from_bytes(data: &[u8]) -> Result<DynamicWaveletMatrix, CerebrumError> {
        if data.len() < HEADER_SIZE {
            return Err(CerebrumError::StorageError("Data too small for header".into()));
        }

        // Verify magic
        if &data[0..4] != DWM_MAGIC {
            return Err(CerebrumError::StorageError(format!(
                "Invalid DWM magic: {:?}",
                &data[0..4]
            )));
        }

        let version = u32::from_le_bytes(data[4..8].try_into().unwrap());
        if version != DWM_FORMAT_VERSION {
            return Err(CerebrumError::StorageError(format!(
                "Unsupported version: {}",
                version
            )));
        }

        let signature_bits = u32::from_le_bytes(data[8..12].try_into().unwrap()) as usize;
        let token_alphabet_size = u32::from_le_bytes(data[12..16].try_into().unwrap()) as usize;
        let count = u32::from_le_bytes(data[16..20].try_into().unwrap()) as usize;
        let token_vocab_id = u32::from_le_bytes(data[20..24].try_into().unwrap());

        let mut dwm = DynamicWaveletMatrix::with_config(signature_bits, token_alphabet_size);
        dwm.set_token_vocab_id(token_vocab_id);

        // Parse memory entries
        let mut offset = HEADER_SIZE;
        for _ in 0..count {
            // MemoryId: 32 bytes
            if offset + 32 > data.len() {
                return Err(CerebrumError::StorageError("Truncated MemoryId".into()));
            }
            let mut id_bytes = [0u8; 32];
            id_bytes.copy_from_slice(&data[offset..offset + 32]);
            offset += 32;

            // BinarySignature: 32 bytes
            if offset + 32 > data.len() {
                return Err(CerebrumError::StorageError("Truncated signature".into()));
            }
            let mut sig_bytes = [0u8; 32];
            sig_bytes.copy_from_slice(&data[offset..offset + 32]);
            offset += 32;

            // Token count: 4 bytes
            if offset + 4 > data.len() {
                return Err(CerebrumError::StorageError("Truncated token count".into()));
            }
            let token_count = u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize;
            offset += 4;

            // Token IDs: token_count × 4 bytes
            let tokens_end = offset + token_count * 4;
            if tokens_end > data.len() {
                return Err(CerebrumError::StorageError("Truncated tokens".into()));
            }
            let mut tokens = Vec::with_capacity(token_count);
            for j in 0..token_count {
                let tid = u32::from_le_bytes(
                    data[offset + j * 4..offset + j * 4 + 4].try_into().unwrap()
                );
                tokens.push(tid);
            }
            offset = tokens_end;

            let token_seq = TokenSequence::new(token_vocab_id, tokens);
            dwm.insert_memory(MemoryId(id_bytes), BinarySignature(sig_bytes), token_seq)
                .map_err(|e| {
                    CerebrumError::StorageError(format!("Insert during load failed: {:?}", e))
                })?;
        }

        Ok(dwm)
    }

    // ========================================================================
    // WAL (Write-Ahead Log)
    // ========================================================================

    /// Append an insert operation to the WAL.
    pub fn wal_append_insert(
        wal_path: &Path,
        memory_id: &MemoryId,
        signature: &BinarySignature,
        tokens: &TokenSequence,
    ) -> Result<(), CerebrumError> {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(wal_path)
            .map_err(|e| {
                CerebrumError::StorageError(format!("Failed to open WAL: {}", e))
            })?;

        // Build payload
        let mut payload = Vec::new();
        payload.extend_from_slice(&memory_id.0);
        payload.extend_from_slice(&signature.0);
        payload.extend_from_slice(&(tokens.tokens.len() as u32).to_le_bytes());
        for &tid in &tokens.tokens {
            payload.extend_from_slice(&tid.to_le_bytes());
        }

        // Write entry: type + length + payload + CRC
        let entry_type = WalEntryType::Insert as u8;
        let entry_len = payload.len() as u32;

        // Compute CRC over type + payload
        let mut crc_data = vec![entry_type];
        crc_data.extend_from_slice(&payload);
        let entry_crc = crc32(&crc_data);

        file.write_all(&[entry_type]).map_err(write_err)?;
        file.write_all(&entry_len.to_le_bytes()).map_err(write_err)?;
        file.write_all(&payload).map_err(write_err)?;
        file.write_all(&entry_crc.to_le_bytes()).map_err(write_err)?;
        file.flush().map_err(write_err)?;

        Ok(())
    }

    /// Append a delete operation to the WAL.
    pub fn wal_append_delete(wal_path: &Path, memory_id: &MemoryId) -> Result<(), CerebrumError> {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(wal_path)
            .map_err(|e| {
                CerebrumError::StorageError(format!("Failed to open WAL: {}", e))
            })?;

        let entry_type = WalEntryType::Delete as u8;
        let payload = memory_id.0;
        let entry_len = payload.len() as u32;

        let mut crc_data = vec![entry_type];
        crc_data.extend_from_slice(&payload);
        let entry_crc = crc32(&crc_data);

        file.write_all(&[entry_type]).map_err(write_err)?;
        file.write_all(&entry_len.to_le_bytes()).map_err(write_err)?;
        file.write_all(&payload).map_err(write_err)?;
        file.write_all(&entry_crc.to_le_bytes()).map_err(write_err)?;
        file.flush().map_err(write_err)?;

        Ok(())
    }

    /// Replay a WAL against a DWM to recover operations since the last snapshot.
    pub fn wal_replay(wal_path: &Path, dwm: &mut DynamicWaveletMatrix) -> Result<usize, CerebrumError> {
        if !wal_path.exists() {
            return Ok(0);
        }

        let data = fs::read(wal_path).map_err(|e| {
            CerebrumError::StorageError(format!("Failed to read WAL: {}", e))
        })?;

        if data.is_empty() {
            return Ok(0);
        }

        let mut offset = 0;
        let mut replayed = 0;

        while offset < data.len() {
            // Read entry type
            if offset + 1 > data.len() { break; }
            let entry_type = data[offset];
            offset += 1;

            // Read entry length
            if offset + 4 > data.len() { break; }
            let entry_len = u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap()) as usize;
            offset += 4;

            // Read payload
            if offset + entry_len > data.len() { break; }
            let payload = &data[offset..offset + entry_len];
            offset += entry_len;

            // Read and verify CRC
            if offset + 4 > data.len() { break; }
            let stored_crc = u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
            offset += 4;

            let mut crc_data = vec![entry_type];
            crc_data.extend_from_slice(payload);
            let computed_crc = crc32(&crc_data);

            if stored_crc != computed_crc {
                // Corrupted entry — stop replay (last complete entry is safe)
                break;
            }

            // Replay the operation
            match entry_type {
                1 => {
                    // Insert
                    if payload.len() < 68 {
                        break; // Need at least 32 (id) + 32 (sig) + 4 (count)
                    }
                    let mut id_bytes = [0u8; 32];
                    id_bytes.copy_from_slice(&payload[0..32]);
                    let mut sig_bytes = [0u8; 32];
                    sig_bytes.copy_from_slice(&payload[32..64]);
                    let token_count = u32::from_le_bytes(payload[64..68].try_into().unwrap()) as usize;

                    if payload.len() < 68 + token_count * 4 {
                        break;
                    }
                    let mut tokens = Vec::with_capacity(token_count);
                    for j in 0..token_count {
                        let tid = u32::from_le_bytes(
                            payload[68 + j * 4..68 + j * 4 + 4].try_into().unwrap()
                        );
                        tokens.push(tid);
                    }

                    let token_seq = TokenSequence::new(dwm.token_vocab_id(), tokens);
                    let _ = dwm.insert_memory(MemoryId(id_bytes), BinarySignature(sig_bytes), token_seq);
                    replayed += 1;
                }
                2 => {
                    // Delete
                    if payload.len() < 32 {
                        break;
                    }
                    let mut id_bytes = [0u8; 32];
                    id_bytes.copy_from_slice(&payload[0..32]);
                    let _ = dwm.remove_memory(MemoryId(id_bytes));
                    replayed += 1;
                }
                3 => {
                    // Checkpoint marker — no-op
                    replayed += 1;
                }
                _ => {
                    // Unknown entry type — stop
                    break;
                }
            }
        }

        Ok(replayed)
    }

    /// Truncate the WAL after a successful snapshot.
    pub fn wal_truncate(wal_path: &Path) -> Result<(), CerebrumError> {
        if wal_path.exists() {
            fs::write(wal_path, []).map_err(|e| {
                CerebrumError::StorageError(format!("Failed to truncate WAL: {}", e))
            })?;
        }
        Ok(())
    }

    // ========================================================================
    // Recovery: load snapshot + replay WAL
    // ========================================================================

    /// Full recovery: load the latest snapshot, then replay the WAL.
    ///
    /// Per the Build Bible: "Snapshots for crash recovery. WAL for incremental
    /// updates between snapshots."
    pub fn recover(
        snapshot_path: &Path,
        wal_path: &Path,
    ) -> Result<DynamicWaveletMatrix, CerebrumError> {
        // Try mmap first (faster), fall back to buffered I/O
        let mut dwm = if snapshot_path.exists() {
            match Self::load_mmap(snapshot_path) {
                Ok(dwm) => dwm,
                Err(_) => Self::load(snapshot_path)?,
            }
        } else {
            DynamicWaveletMatrix::new()
        };

        // Replay WAL entries on top of the snapshot
        if wal_path.exists() {
            Self::wal_replay(wal_path, &mut dwm)?;
        }

        Ok(dwm)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    fn write_u32<W: Write>(writer: &mut W, val: u32) -> Result<(), CerebrumError> {
        writer.write_all(&val.to_le_bytes()).map_err(write_err)
    }
}

fn write_err(e: std::io::Error) -> CerebrumError {
    CerebrumError::StorageError(format!("Write failed: {}", e))
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    #[test]
    fn test_save_load_roundtrip() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("test.dwm");

        let mut dwm = DynamicWaveletMatrix::new();
        for i in 0..10u32 {
            let mut sig_bytes = [0u8; 32];
            sig_bytes[0] = (i * 7) as u8;
            let sig = BinarySignature(sig_bytes);
            let tokens = TokenSequence::new(0, vec![i, i + 1]);
            let mut id_bytes = [0u8; 32];
            id_bytes[0..4].copy_from_slice(&i.to_le_bytes());
            let id = MemoryId(id_bytes);
            dwm.insert_memory(id, sig, tokens).unwrap();
        }

        DwmPersistence::snapshot(&mut dwm, &path).unwrap();

        let loaded = DwmPersistence::load(&path).unwrap();
        assert_eq!(loaded.count(), dwm.count());

        // Verify all signatures match
        for i in 0..dwm.count() {
            let orig_sig = dwm.reconstruct_signature(i).unwrap();
            let loaded_sig = loaded.reconstruct_signature(i).unwrap();
            assert_eq!(orig_sig.0, loaded_sig.0, "Signature mismatch at position {}", i);
        }
    }

    #[test]
    fn test_mmap_load() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("test_mmap.dwm");

        let mut dwm = DynamicWaveletMatrix::new();
        let sig = BinarySignature([0x42; 32]);
        let tokens = TokenSequence::new(0, vec![1, 2, 3]);
        let id = MemoryId::new();
        dwm.insert_memory(id, sig, tokens).unwrap();

        DwmPersistence::snapshot(&mut dwm, &path).unwrap();

        let loaded = DwmPersistence::load_mmap(&path).unwrap();
        assert_eq!(loaded.count(), 1);
        let loaded_sig = loaded.reconstruct_signature(0).unwrap();
        assert_eq!(loaded_sig.0, [0x42; 32]);
    }

    #[test]
    fn test_crc_detection() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("corrupt.dwm");

        let mut dwm = DynamicWaveletMatrix::new();
        let sig = BinarySignature([0xFF; 32]);
        let tokens = TokenSequence::new(0, vec![42]);
        let id = MemoryId::new();
        dwm.insert_memory(id, sig, tokens).unwrap();

        DwmPersistence::snapshot(&mut dwm, &path).unwrap();

        // Corrupt a byte in the middle of the file
        let mut data = fs::read(&path).unwrap();
        let mid = data.len() / 2;
        data[mid] ^= 0xFF;
        fs::write(&path, &data).unwrap();

        let result = DwmPersistence::load(&path);
        assert!(result.is_err(), "Should detect corruption");
    }

    #[test]
    fn test_wal_insert_replay() {
        let dir = TempDir::new().unwrap();
        let wal_path = dir.path().join("test.wal");

        let _dwm = DynamicWaveletMatrix::new();

        // Write WAL entries
        let sig = BinarySignature([0xAB; 32]);
        let tokens = TokenSequence::new(0, vec![10, 20]);
        let id = MemoryId::new();
        DwmPersistence::wal_append_insert(&wal_path, &id, &sig, &tokens).unwrap();

        // Replay WAL against empty DWM
        let mut dwm2 = DynamicWaveletMatrix::new();
        let replayed = DwmPersistence::wal_replay(&wal_path, &mut dwm2).unwrap();
        assert_eq!(replayed, 1);
        assert_eq!(dwm2.count(), 1);
    }

    #[test]
    fn test_wal_delete_replay() {
        let dir = TempDir::new().unwrap();
        let wal_path = dir.path().join("test.wal");

        // Insert a memory, then delete it via WAL
        let sig = BinarySignature([0xFF; 32]);
        let tokens = TokenSequence::new(0, vec![1]);
        let id = MemoryId::new();

        let mut dwm = DynamicWaveletMatrix::new();
        dwm.insert_memory(id, sig, tokens).unwrap();

        DwmPersistence::wal_append_delete(&wal_path, &id).unwrap();

        // Replay delete WAL
        let replayed = DwmPersistence::wal_replay(&wal_path, &mut dwm).unwrap();
        assert_eq!(replayed, 1);
        assert_eq!(dwm.count(), 0);
    }

    #[test]
    fn test_recovery_snapshot_plus_wal() {
        let dir = TempDir::new().unwrap();
        let snapshot_path = dir.path().join("base.dwm");
        let wal_path = dir.path().join("incremental.wal");

        // Save a snapshot with 5 memories
        let mut dwm = DynamicWaveletMatrix::new();
        for i in 0..5u32 {
            let mut sig_bytes = [0u8; 32];
            sig_bytes[0] = (i * 10) as u8;
            let sig = BinarySignature(sig_bytes);
            let tokens = TokenSequence::new(0, vec![i]);
            let mut id_bytes = [0u8; 32];
            id_bytes[0..4].copy_from_slice(&i.to_le_bytes());
            let id = MemoryId(id_bytes);
            dwm.insert_memory(id, sig, tokens).unwrap();
        }
        DwmPersistence::snapshot(&mut dwm, &snapshot_path).unwrap();

        // Add 3 more via WAL
        for i in 5..8u32 {
            let mut sig_bytes = [0u8; 32];
            sig_bytes[0] = (i * 10) as u8;
            let sig = BinarySignature(sig_bytes);
            let tokens = TokenSequence::new(0, vec![i]);
            let mut id_bytes = [0u8; 32];
            id_bytes[0..4].copy_from_slice(&i.to_le_bytes());
            let id = MemoryId(id_bytes);
            DwmPersistence::wal_append_insert(&wal_path, &id, &sig, &tokens).unwrap();
        }

        // Recovery: snapshot + WAL = 8 memories
        let recovered = DwmPersistence::recover(&snapshot_path, &wal_path).unwrap();
        assert_eq!(recovered.count(), 8);
    }

    #[test]
    fn test_wal_truncation() {
        let dir = TempDir::new().unwrap();
        let wal_path = dir.path().join("truncate.wal");

        let sig = BinarySignature([0; 32]);
        let tokens = TokenSequence::new(0, vec![1]);
        let id = MemoryId::new();
        DwmPersistence::wal_append_insert(&wal_path, &id, &sig, &tokens).unwrap();

        assert!(wal_path.exists());

        DwmPersistence::wal_truncate(&wal_path).unwrap();
        let metadata = fs::metadata(&wal_path).unwrap();
        assert_eq!(metadata.len(), 0);
    }
}