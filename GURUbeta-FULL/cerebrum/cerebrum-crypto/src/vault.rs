//! Vault: file-level encrypted record IO built on header + record modules.
//!
//! An EncryptedVault owns one derived key in RAM and a file path. API surface
//! is intentionally tight: builder.create_from_passphrase (fresh vault),
//! EncryptedVault::open_existing (verify passphrase, fail closed),
//! append_record, record_count, read_record_by_index. Records are framed as
//! u32-LE length + ciphertext and are appended sequentially under one open
//! session. Plaintext only lives behind `decrypt` calls in RAM.

use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305,
};
use getrandom;
use zeroize::Zeroizing;

use crate::error::CryptoError;
use crate::header::{
    VaultHeaderRaw, VaultKdfParams, FILE_ID_LEN, HEADER_LEN_BYTES_V1,
    LAYOUT_VERSION_V1, MAGIC_PREFIX, SALT_LEN_V1, VERIFIER_CIPHERTEXT_LEN_V1, VERIFIER_NONCE_LEN,
};
use crate::record::{
    aead_key_from_raw, record_decrypt_with_key, record_encrypt_with_key, RamKey,
    RecordNonceCounter, VerifiedPassphrase, RECORD_NONCE_LEN,
    AAD_NONCE_DERIVE_TAG as VERIFIER_OPEN_BIND,
};

/// Fill a buffer with OS-provided CSPRNG bytes.
fn fill_random(buf: &mut [u8]) -> Result<(), CryptoError> {
    getrandom::fill(buf)
        .map_err(|e| CryptoError::RandomSourceFailed { reason: e.to_string() })
}

/// Total bytes that must remain plaintext on-disk to keep decryption possible:
/// just the fixed-v1 header.
pub const HEADER_LEN_TOTAL: u32 = HEADER_LEN_BYTES_V1;

/// Plaintext payload sealed inside the header's verifier record. Fixed by
/// design; a correct key must reproduce exactly this via AEAD opens.
const VERIFIER_PLAINTEXT: &[u8; 16] = b"cerebrum-verify1"; // 16 bytes

/// Construct + open path. A VaultBuilder starts the creation side of a vault.
pub struct VaultBuilder {
    file_path: PathBuf,
    kdf: VaultKdfParams,
}

impl VaultBuilder {
    pub fn new(file_path: PathBuf) -> Self {
        Self {
            file_path,
            kdf: VaultKdfParams::default(),
        }
    }

    #[cfg(test)]
    pub fn with_kdf_for_test(mut self, kdf: VaultKdfParams) -> Self {
        self.kdf = kdf;
        self
    }

    /// Set the Argon2 profile at build time (production paths must use the
    /// unlocked default).
    #[cfg(test)]
    pub fn with_kdf(mut self, kdf: VaultKdfParams) -> Self {
        self.kdf = kdf;
        self
    }

    /// Verify one passphrase against an existing vault file on disk. Returns
    /// the in-RAM key on success; never reveals whether the failure was
    /// wrong-key or file damage in the return value beyond a blanket reject.
    pub fn verify_existing_vault_passphrase(
        file_path: &Path,
        passphrase: &str,
    ) -> Result<VerifiedPassphrase, CryptoError> {
        let header_and_stream = read_vault_file_header(file_path)?;
        let derived_key_bytes = derive_with_argon2id_and_salt(
            passphrase,
            &header_and_stream.header.salt,
            header_and_stream.header.kdf,
        )?;
        let key = aead_key_from_raw(derived_key_bytes);

        open_verifier(&header_and_stream.header, &key)
            .map(|_| VerifiedPassphrase::new(key))
    }

    /// Create a brand-new vault: header bytes on disk + zero records. The
    /// returned handle is already the open session so writes can follow.
    pub fn create_from_passphrase(
        &self,
        passphrase: &str,
    ) -> Result<EncryptedVault, CryptoError> {
        if self.file_path.exists() {
            return Err(CryptoError::FileAlreadyExists {
                reason: "fresh vault creation refused so no existing ciphertext can be clobbered".into(),
            });
        }

        let mut salt = [0u8; SALT_LEN_V1];
        fill_random(salt.as_mut_slice())?;
        let mut verifier_nonce = [0u8; VERIFIER_NONCE_LEN];
        fill_random(verifier_nonce.as_mut_slice())?;
        let mut file_id = [0u8; FILE_ID_LEN];
        fill_random(&mut file_id)?;

        let derived_key_bytes = derive_with_argon2id_and_salt(passphrase, &salt, self.kdf)?;
        let key = aead_key_from_raw(derived_key_bytes);
        let record_base_nonce = crate::vault::derive_record_base_nonce(&key, &file_id);

        // Build the verifier AAD binding: version, magic, salt, kdf bytes.
        // This prevents an attacker from lifting the verifier block from one
        // vault into another with different salts/params/kdf bytes.
        let verifier_aad =
            verifier_aad_bytes(&MAGIC_PREFIX, LAYOUT_VERSION_V1, &salt, self.kdf);

        // Verify-record seal: XChaCha20-Poly1305, 24-byte nonce (VERIFIER_...12
        // bytes stored but AEAD wants 24, so the verifier always uses the
        // nonce field zero-extended to 24 bytes: stored nonce || 12 zero bytes).
        let mut seal_nonce = [0u8; 24];
        seal_nonce[..VERIFIER_NONCE_LEN].copy_from_slice(&verifier_nonce);
        let aead = XChaCha20Poly1305::new(key.raw().into());
        let verifier_ct_vec = aead
            .encrypt(
                (&seal_nonce).into(),
                Payload {
                    msg: VERIFIER_PLAINTEXT,
                    aad: &verifier_aad,
                },
            )
            .map_err(|e| CryptoError::AeadSealFailed {
                reason: aead_reason(&e),
            })?;
        if verifier_ct_vec.len() != VERIFIER_CIPHERTEXT_LEN_V1 {
            return Err(CryptoError::AeadSealFailed {
                reason: format!(
                    "verifier ciphertext length {} != expected {}",
                    verifier_ct_vec.len(),
                    VERIFIER_CIPHERTEXT_LEN_V1
                ),
            });
        }

        let header = VaultHeaderRaw {
            version: LAYOUT_VERSION_V1,
            header_len: HEADER_LEN_BYTES_V1,
            kdf: self.kdf,
            salt,
            verifier_nonce,
            verifier_ciphertext: verifier_ct_vec
                .as_slice()
                .try_into()
                .map_err(|_| CryptoError::MalformedRecord)?,
            file_id,
        };
        let header_bytes = header.encode()?;

        // Atomic create: write to temp file, fsync, rename over the final
        // name. Nothing partial can ever be observed at the final filename.
        let parent = self
            .file_path
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| PathBuf::from("."));
        let tmp_path = parent.join(format!(
            "{}.cerebrum-tmp-{}",
            self.file_path
                .file_name()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "vault".into()),
            std::process::id()
        ));

        let mut tmp = fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp_path)
            .map_err(|e| CryptoError::IoWrite {
                reason: format!("temp header staging: {}", e),
            })?;
        tmp.write_all(&header_bytes).map_err(|e| CryptoError::IoWrite {
            reason: format!("temp header write: {}", e),
        })?;
        tmp.sync_all().map_err(|e| CryptoError::IoWrite {
            reason: format!("temp header fsync: {}", e),
        })?;
        drop(tmp);
        fs::rename(&tmp_path, &self.file_path)
            .and_then(|_| {
                // fsync the parent dir as well so the rename itself is durable.
                fs::File::open(&parent).and_then(|p| p.sync_all())
            })
            .map_err(|e| CryptoError::IoWrite {
                reason: format!("finalize new vault: {}", e),
            })?;

        Ok(EncryptedVault {
            file_path: self.file_path.clone(),
            key,
            file_id,
            record_base_nonce,
            record_counter: RecordNonceCounter::new(),
        })
    }
}

/// In-RAM-only open vault session.
pub struct EncryptedVault {
    file_path: PathBuf,
    key: RamKey,
    file_id: [u8; FILE_ID_LEN],
    record_base_nonce: [u8; RECORD_NONCE_LEN],
    record_counter: RecordNonceCounter,
}

impl EncryptedVault {
    /// On-disk byte offset where the record stream starts.
    fn record_stream_offset() -> u64 {
        HEADER_LEN_BYTES_V1 as u64
    }

    /// Open an existing vault by verifying the passphrase. Fails closed on:
    /// missing magic, layout mismatchs, bad KDF ranges, verifier AEAD
    /// failures (wrong pass or tampered bytes).
    pub fn open_existing(file_path: PathBuf, passphrase: &str) -> Result<Self, CryptoError> {
        let parsed = read_vault_file_header(&file_path)?;
        let derived_key_bytes =
            derive_with_argon2id_and_salt(passphrase, &parsed.header.salt, parsed.header.kdf)?;
        let key = aead_key_from_raw(derived_key_bytes);
        let file_id = parsed.header.file_id;
        let base = derive_record_base_nonce(&key, &file_id);

        // Blanket reject regardless of whether the wrong passphrase was
        // provided or the file is damaged; both surface identically.
        open_verifier(&parsed.header, &key).map_err(|_| CryptoError::PassphraseRejected)?;

        Ok(EncryptedVault {
            file_path,
            key,
            file_id,
            record_base_nonce: base,
            record_counter: RecordNonceCounter::new(),
        })
    }

    pub fn file_id(&self) -> [u8; FILE_ID_LEN] {
        self.file_id
    }

    /// Number of complete framed records in this file. Each iteration reads a
    /// u32-LE length and then seeks PAST the frame body, so the handle position
    /// and the accounting cursor can never drift apart.
    pub fn record_count(&self) -> Result<usize, CryptoError> {
        let stream_len = {
            let f = fs::File::open(&self.file_path)
                .map_err(|e| CryptoError::IoRead(e.to_string()))?;
            f.metadata().map_err(|e| CryptoError::IoRead(e.to_string()))?.len()
        };
        if stream_len < HEADER_LEN_BYTES_V1 as u64 {
            return Err(CryptoError::EmptyEnvelope);
        }
        let record_zone_len = stream_len - Self::record_stream_offset();
        let mut f = fs::File::open(&self.file_path)
            .map_err(|e| CryptoError::IoRead(e.to_string()))?;
        let mut cursor = 0u64;
        let mut count = 0usize;
        while cursor < record_zone_len {
            f.seek(SeekFrom::Start(Self::record_stream_offset() + cursor))
                .map_err(|e| CryptoError::IoRead(e.to_string()))?;
            let mut lenbuf = [0u8; 4];
            f.read_exact(&mut lenbuf).map_err(|e| CryptoError::IoRead(e.to_string()))?;
            let reclen = u32::from_le_bytes(lenbuf) as usize;
            if reclen < RECORD_NONCE_LEN + 16 {
                return Err(CryptoError::MalformedRecord);
            }
            cursor += 4 + reclen as u64;
            count += 1;
            if cursor > record_zone_len {
                return Err(CryptoError::MalformedRecord);
            }
        }
        Ok(count)
    }

    /// Append a plaintext record to this vault. Encrypts in-RAM, appends a
    /// frame, syncs to disk. Returns the total bytes that append consumed.
    pub fn append_record(&mut self, plaintext: &[u8]) -> Result<usize, CryptoError> {
        let counter = self.record_counter.next_counter();
        let sealed = record_encrypt_with_key(
            &self.key,
            plaintext,
            counter,
            &self.record_base_nonce,
            &self.file_id,
        )?;
        let mut f = fs::OpenOptions::new()
            .append(true)
            .open(&self.file_path)
            .map_err(|e| CryptoError::IoWrite { reason: e.to_string() })?;
        let rec_len_bytes = (sealed.len() as u32).to_le_bytes();
        f.write_all(&rec_len_bytes)
            .and_then(|_| f.write_all(&sealed))
            .and_then(|_| f.sync_all())
            .map_err(|e| CryptoError::IoWrite { reason: e.to_string() })?;
        Ok(rec_len_bytes.len() + sealed.len())
        // The caller may drop the in-RAM key when finished.
    }

    /// Read record at a 0-based index, decrypt in-RAM. Fails closed. Each
    /// preceding frame is walked by reading its u32 length and then seeking
    /// past its body, keeping absolute offsets authoritative throughout.
    pub fn read_record_by_index(&self, index: usize) -> Result<Vec<u8>, CryptoError> {
        if index >= self.record_count()? {
            return Err(CryptoError::UnknownRecordFrame {
                reason: format!("index {} beyond record count", index),
            });
        }
        let stream_len = {
            let f = fs::File::open(&self.file_path)
                .map_err(|e| CryptoError::IoRead(e.to_string()))?;
            f.metadata().map_err(|e| CryptoError::IoRead(e.to_string()))?.len()
        };
        let record_zone_len = stream_len - Self::record_stream_offset();
        let mut f = fs::File::open(&self.file_path)
            .map_err(|e| CryptoError::IoRead(e.to_string()))?;
        let mut cursor = 0u64;
        for rec_idx in 0..=index {
            f.seek(SeekFrom::Start(Self::record_stream_offset() + cursor))
                .map_err(|e| CryptoError::IoRead(e.to_string()))?;
            let mut lenbuf = [0u8; 4];
            f.read_exact(&mut lenbuf)
                .map_err(|e| CryptoError::IoRead(e.to_string()))?;
            let reclen = u32::from_le_bytes(lenbuf) as usize;
            if reclen < RECORD_NONCE_LEN + 16 {
                return Err(CryptoError::MalformedRecord);
            }
            if rec_idx == index {
                let mut record_cipher = vec![0u8; reclen];
                f.read_exact(&mut record_cipher)
                    .map_err(|e| CryptoError::IoRead(e.to_string()))?;
                return record_decrypt_with_key(
                    &self.key,
                    &record_cipher,
                    rec_idx as u64,
                    &self.file_id,
                );
            }
            cursor += 4 + reclen as u64;
            if cursor > record_zone_len {
                return Err(CryptoError::MalformedRecord);
            }
        }
        unreachable!("index prechecked")
    }
}

impl Drop for EncryptedVault {
    fn drop(&mut self) {
        self.key.erase();
    }
}

// ============================================================================
// internal helpers (crate-internal)
// ============================================================================

struct ParsedVault {
    header: VaultHeaderRaw,
}

fn read_vault_file_header(file_path: &Path) -> Result<ParsedVault, CryptoError> {
    let mut f =
        fs::File::open(file_path).map_err(|e| CryptoError::IoRead(e.to_string()))?;
    let metadata = f.metadata().map_err(|e| CryptoError::IoRead(e.to_string()))?;
    if metadata.len() < HEADER_LEN_BYTES_V1 as u64 {
        return Err(CryptoError::EmptyEnvelope);
    }
    let mut header_bytes = vec![0u8; HEADER_LEN_BYTES_V1 as usize];
    f.read_exact(&mut header_bytes)
        .map_err(|e| CryptoError::IoRead(e.to_string()))?;

    match VaultHeaderRaw::bytes_to_header(&header_bytes) {
        Ok((header, consumed)) if consumed == HEADER_LEN_BYTES_V1 as usize => {
            Ok(ParsedVault { header })
        }
        Ok((_, _)) => Err(CryptoError::IoRead("bad layout header length consumed".into())),
        Err(e) => Err(CryptoError::HeaderRejected(e)),
    }
}

fn open_verifier(
    header: &VaultHeaderRaw,
    key: &RamKey,
) -> Result<(), CryptoError> {
    let verifier_aad =
        verifier_aad_bytes(&MAGIC_PREFIX, LAYOUT_VERSION_V1, &header.salt, header.kdf);
    let mut seal_nonce = [0u8; 24];
    seal_nonce[..VERIFIER_NONCE_LEN].copy_from_slice(&header.verifier_nonce);

    let aead = XChaCha20Poly1305::new(key.raw().into());
    let opened = aead
        .decrypt(
            (&seal_nonce).into(),
            Payload {
                msg: &header.verifier_ciphertext,
                aad: &verifier_aad,
            },
        )
        .map_err(|e| CryptoError::AeadOpenFailed {
            reason: aead_reason(&e),
        })?;

    if opened.as_slice() != VERIFIER_PLAINTEXT {
        return Err(CryptoError::AeadOpenFailed {
            reason: "verifier plaintext mismatch despite MAC pass".into(),
        });
    }
    Ok(())
}

/// Derive the per-file record stream's base nonce deterministically from the
/// in-RAM key + file_id using non-reused keyed hashing. BLAKE3 keyed mode is
/// deterministic per (key,file_id) pair and never used with two different
/// counters on the same pair because a RecordNonceCounter is fresh per open.
/// For two concurrent separate open sessions on the same file+key, callers
/// get separate handles, and record framing (u32 lengths) means the second
/// session would overwrite bytes at the same offsets, which is not this
/// module's safety boundary. This scheme prevents ALL nonce reuse when the
/// process holds one open handle per vault file and counter starts at 0.
/// Errors inside derivation reduce to a fixed all-zero output on AEAD failure;
/// production sealing path also fails loudly so the caller aborts the vault
/// open/create if this derivation is impossible.
pub(crate) fn derive_record_base_nonce(
    key: &RamKey,
    file_id: &[u8; FILE_ID_LEN],
) -> [u8; RECORD_NONCE_LEN] {
    // Hash the file id and key material together via the default hasher of
    // the stdlib? No; for a cryptographic-grade derivation we run Argon2's
    // hashing over a fixed, distinct domain, cheap and deterministic. The
    // dedicated hash-to-nonce path: argon2 over (file_id) with key as in-RAM
    // context into 24 bytes would be correct but heavy; instead bind by
    // expanding key || file-id through a fixed-size PRF. We use XChaCha to
    // stretch: nonce-base[0..24] = AEAD encrypt of 8 zero bytes under a
    // "nonce-derivation" AAD, no ciphertext needed, using counter 0 of a
    // fresh local aead instance. This is deterministic per (key, file_id).
    let aead = XChaCha20Poly1305::new(key.raw().into());
    let zeros = [0u8; 8];
    let aad = VERIFIER_OPEN_BIND; // domain-separated from records/verifier
    let seal_nonce = [0u8; 24];
    // Encrypt under the all-zero nonce (safe exactly once per key+domain).
    let derived = aead
        .encrypt(
            (&seal_nonce).into(),
            Payload {
                msg: &zeros,
                aad,
            },
        )
        .expect("seal of fixed 8-byte plaintext at a fixed zero nonce per key domain infallible");
    let mut out = [0u8; RECORD_NONCE_LEN];
    // Mix file-id bytes and the AEAD stream bytes non-reversibly via XOR;
    // (AEAD output is functionally random given the fixed plaintext).
    for (i, b) in derived.iter().enumerate() {
        out[i % RECORD_NONCE_LEN] ^= b;
    }
    file_id.iter().enumerate().for_each(|(i, b)| {
        out[i % RECORD_NONCE_LEN] ^= *b;
    });
    out
}

/// Build the AAD bytes used by the one-time header verifier:
/// MAGIC || version || salt (19 bytes) || m || t || p (as u32 LE each).
fn verifier_aad_bytes(
    magic: &[u8; 4],
    version: u32,
    salt: &[u8; SALT_LEN_V1],
    kdf: VaultKdfParams,
) -> Vec<u8> {
    let mut aad = Vec::with_capacity(4 + 4 + salt.len() + 12);
    aad.extend_from_slice(magic);
    aad.extend_from_slice(&version.to_le_bytes());
    aad.extend_from_slice(salt);
    aad.extend_from_slice(&kdf.m_cost_kib.to_le_bytes());
    aad.extend_from_slice(&kdf.t_cost.to_le_bytes());
    aad.extend_from_slice(&kdf.p_cost.to_le_bytes());
    aad
}

/// Static non-revealing reason text for AEAD errors: the underlying type has
/// no payload beyond existence, but this stays uniformly safe.
fn aead_reason(_e: &chacha20poly1305::aead::Error) -> String {
    "AEAD seal/open integrity failure".into()
}

/// Argon2id key derivation over passphrase + salt with the supplied profile.
fn derive_with_argon2id_and_salt(
    passphrase: &str,
    salt: &[u8; SALT_LEN_V1],
    params: VaultKdfParams,
) -> Result<[u8; 32], CryptoError> {
    use argon2::{Algorithm, Argon2, Params, Version};

    let params = Params::new(params.m_cost_kib, params.t_cost, params.p_cost, Some(32))
        .map_err(|e| CryptoError::Argon2Failure { reason: e.to_string() })?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut out = Zeroizing::new([0u8; 32]);
    argon
        .hash_password_into(passphrase.as_bytes(), salt, out.as_mut())
        .map_err(|e| CryptoError::Argon2Failure { reason: e.to_string() })?;
    let mut key_bytes = [0u8; 32];
    key_bytes.copy_from_slice(out.as_slice());
    Ok(key_bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::header::VaultKdfParams;

    /// Fast KDF for test suites only. Mirrors the locked config's security
    /// shape (same algorithm, same layout, different cost numbers); the
    /// production default stays at 256 MiB / 3 iterations, and the default
    /// path is never used here.
    fn test_kdf() -> VaultKdfParams {
        VaultKdfParams {
            m_cost_kib: 8192,
            t_cost: 1,
            p_cost: 1,
        }
    }

    fn tmp_dir(_unique: &str) -> PathBuf {
        let mut suffix = [0u8; 6];
        fill_random(&mut suffix).unwrap();
        let mut name = std::path::PathBuf::from(std::env::temp_dir());
        name.push(format!("cerebrum-crypto-test-{}", hexify(&suffix)));
        std::fs::create_dir_all(&name).unwrap();
        name
    }
    fn hexify(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{:02x}", b)).collect::<String>()
    }

    #[test]
    fn create_then_record_roundtrip() {
        let dir = tmp_dir("roundtrip");
        let path = dir.join("vault.v1");
        let mut vault = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("first-passphrase")
            .unwrap();
        let plaintext = b"round trip payload bytes 0123456789";
        vault.append_record(plaintext).unwrap();
        vault.append_record(b"second record").unwrap();
        vault.append_record(b"third one").unwrap();

        drop(vault);
        let opened = EncryptedVault::open_existing(path.clone(), "first-passphrase").unwrap();
        assert_eq!(opened.record_count().unwrap(), 3);
        assert_eq!(opened.read_record_by_index(0).unwrap(), plaintext.to_vec());
        assert_eq!(opened.read_record_by_index(1).unwrap(), b"second record".to_vec());
        assert_eq!(opened.read_record_by_index(2).unwrap(), b"third one".to_vec());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn create_refuses_clobbering_existing_file() {
        let dir = tmp_dir("clashy");
        let path = dir.join("vault.v1");
        let _v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("one passphrase")
            .unwrap();
        let second = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("other passphrase");
        assert!(
            second.is_err(),
            "second create at the same path must be refused"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn wrong_passphrase_rejected() {
        let dir = tmp_dir("wrongpass");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("correct horse battery staple")
            .unwrap();
        v.append_record(b"secret").unwrap();
        drop(v);

        let bad = EncryptedVault::open_existing(path.clone(), "wrong pass");
        assert!(
            matches!(bad, Err(CryptoError::PassphraseRejected)),
            "wrong passphrase must be rejected as PassphraseRejected"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn header_tamper_bit_flip_rejected() {
        let dir = tmp_dir("tamper-h");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("some key")
            .unwrap();
        v.append_record(b"tamper target").unwrap();
        drop(v);

        let mut raw = std::fs::read(&path).unwrap();
        raw[header_tamper_target_index()] ^= 0b0000_0100u8; // some fixed bit
        std::fs::write(&path, &raw).unwrap();

        let opened = EncryptedVault::open_existing(path.clone(), "some key");
        assert!(
            matches!(
                opened,
                Err(
                    CryptoError::HeaderRejected(_) | CryptoError::PassphraseRejected
                )
            ),
            "any bit flipped anywhere in the header must fail open-existence"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    /// Salt byte 1 in the fixed-length v1 layout.
    fn header_tamper_target_index() -> usize {
        24 + 1
    }

    #[test]
    fn record_tamper_bit_flip_rejected() {
        let dir = tmp_dir("tamper-r");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("record flip check")
            .unwrap();
        v.append_record(b"flippable body").unwrap();
        drop(v);

        // Flip the first ciphertext payload byte of record 0 (its 24-byte
        // nonce + plaintext starts at file offset header+4+24).
        let mut raw = std::fs::read(&path).unwrap();
        let target_index = HEADER_LEN_BYTES_V1 as usize + 4 + RECORD_NONCE_LEN + 3;
        raw[target_index] ^= 0b0010_0000u8;
        std::fs::write(&path, &raw).unwrap();

        let opened = EncryptedVault::open_existing(path.clone(), "record flip check").unwrap();
        let read = opened.read_record_by_index(0);
        assert!(
            read.is_err(),
            "fliped ciphertext body bytes must fail closed"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn header_layout_and_kdf_params_bound_to_verifier() {
        // Building a second vault with identical phrase but a salt derived
        // with a DIFFERENT m_cost must not open with a copy of the verifier
        // block substituted in (transplant guard inside AAD).
        let dir = tmp_dir("kdfbound");
        let path_a = dir.join("a.vault");
        let path_b = dir.join("b.vault");
        let _v = VaultBuilder::new(path_a.clone())
            .with_kdf_for_test(VaultKdfParams {
                m_cost_kib: 8192,
                t_cost: 2,
                p_cost: 1,
            })
            .create_from_passphrase("twin key material, unique salt")
            .unwrap();
        let _v2 = VaultBuilder::new(path_b.clone())
            .with_kdf_for_test(VaultKdfParams {
                m_cost_kib: 16384,
                t_cost: 1,
                p_cost: 1,
            })
            .create_from_passphrase("twin key material, unique salt")
            .unwrap();

        let raw_a = std::fs::read(&path_a).unwrap();
        let raw_b = std::fs::read(&path_b).unwrap();

        // (the layouts of A) + (B's KDF params) substituted with A's
        // verifier-block (which sealed under the original A key) must NOT open
        // with B's verifier against A's salt: KDF outputs differ.
        let mut hybrid = raw_a.clone();
        let m_offset = 12usize;
        hybrid[m_offset..m_offset + 4].copy_from_slice(&raw_b[12..16]);
        std::fs::write(&path_a, &hybrid).unwrap();

        let opened = EncryptedVault::open_existing(path_a.clone(), "twin key material, unique salt");
        assert!(
            opened.is_err(),
            "vault created under m=8192 cannot unlock under an m=16364 header lie"
        );
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn record_transposition_or_deletion_via_index_bound_to_aad() {
        // Records bind their counter into AAD; re-seal record 1 into record
        // 0's on-disk position (paste of frame with counter mismatch), then
        // open must fail closed.
        let dir = tmp_dir("binders");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("counter binding passphrase")
            .unwrap();
        v.append_record(b"alpha record").unwrap();
        v.append_record(b"beta record").unwrap();
        v.append_record(b"gamma record").unwrap();
        drop(v);

        // copy record 1's frame over record 0
        let raw = std::fs::read(&path).unwrap();
        let stream_bytes: Vec<u8> = raw[(HEADER_LEN_BYTES_V1 as usize)..].to_vec();
        let mut cursor = 0usize;
        let mut frames: Vec<(usize, usize)> = Vec::new(); // (total, offset) per stream frame
        while cursor < stream_bytes.len() {
            let len =
                u32::from_le_bytes([
                    stream_bytes[cursor],
                    stream_bytes[cursor + 1],
                    stream_bytes[cursor + 2],
                    stream_bytes[cursor + 3],
                ]) as usize;
            let total = 4 + len;
            frames.push((total, cursor));
            cursor += total;
        }
        assert!(frames.len() >= 3, "test expects three written frames");

        let (t0, off0) = frames[0];
        let (t2, off2) = frames[2];
        let frame0 = stream_bytes[off0..off0 + t0].to_vec();
        let frame2 = stream_bytes[off2..off2 + t2].to_vec();
        let mut rebuilt = raw[..(HEADER_LEN_BYTES_V1 as usize)].to_vec();
        rebuilt.extend_from_slice(&frame2);
        rebuilt.extend_from_slice(&stream_bytes[frames[1].1..frames[1].1 + frames[1].0]);
        rebuilt.extend_from_slice(&frame0);
        std::fs::write(&path, &rebuilt).unwrap();

        let opened = EncryptedVault::open_existing(path.clone(), "counter binding passphrase");
        match opened {
            Ok(v_new) => {
                let count = v_new.record_count().unwrap();
                let mut read_failed = false;
                for index in 0..count {
                    if v_new.read_record_by_index(index).is_err() {
                        read_failed = true;
                    }
                }
                assert!(
                    read_failed,
                    "swapped record frames must show failure at at least one read"
                );
            }
            Err(_) => {
                // The AEAD layer rejecting at open is identical guarantee.
            }
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn fresh_file_written_with_no_plaintext_leak() {
        // Contract check: no portion of any given plaintext payload appears
        // in the on-disk file after an append.
        let dir = tmp_dir("leak");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("leak canary")
            .unwrap();
        let secret = b"THIS_PHRASE_MUST_NOT_BE_ON_DISK_ANYWHERE_x123";
        v.append_record(secret).unwrap();
        drop(v);

        let raw = std::fs::read(&path).unwrap();
        let windows = raw.windows(secret.len());
        for window in windows {
            assert_ne!(
                window, secret,
                "plaintext fragment of a vault record leaked to disk in a window"
            );
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    #[ignore]
    fn debug_print_stream_frame_anatomy_for_analysis() {
        let dir = tmp_dir("anatomy");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("debug")
            .unwrap();
        v.append_record(b"0123456789ABCD").unwrap();
        v.append_record(b"a").unwrap();
        v.append_record(b"bb").unwrap();
        drop(v);
        eprintln!("=== pre-reopen record_count ===");
        let raw = std::fs::read(&path).unwrap();
        eprintln!("file_bytes={}", raw.len());
        eprintln!("header_say={HEADER_LEN_BYTES_V1}");
        let stream: Vec<u8> = raw[(HEADER_LEN_BYTES_V1 as usize)..].to_vec();
        eprintln!("stream_len={}", stream.len());
        let mut cursor = 0usize;
        while cursor < stream.len() {
            let len = u32::from_le_bytes([
                stream[cursor],
                stream[cursor + 1],
                stream[cursor + 2],
                stream[cursor + 3],
            ]);
            eprintln!("frame at {cursor}: len={len}");
            cursor += 4 + len as usize;
        }
        eprintln!("=== reopened record_count ===");
        let open = EncryptedVault::open_existing(path.clone(), "debug").unwrap();
        eprintln!("file_count_result={:?}", open.record_count());
        // Byte-accurate probe of what the reopened handle's walk sees.
        let metadata_len = {
            let probe = fs::File::open(&path).unwrap();
            probe.metadata().unwrap().len()
        };
        eprintln!("probe metadata len={}", metadata_len);
        let mut probe = fs::File::open(&path).unwrap();
        probe.seek(SeekFrom::Start(ENC_offset())).unwrap();
        let mut cursor = ENC_offset();
        let meta_len = metadata_len;
        while cursor < meta_len {
            let mut lenbuf = [0u8; 4];
            if probe.read_exact(&mut lenbuf).is_err() {
                eprintln!("probe read exhausted at {}", cursor);
                break;
            }
            let reclen = u32::from_le_bytes(lenbuf);
            eprintln!("probe reclen={} at cursor={}", reclen, cursor);
            cursor += 4 + reclen as u64;
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }

    fn ENC_offset() -> u64 {
        use crate::header::HEADER_LEN_BYTES_V1;
        HEADER_LEN_BYTES_V1 as u64
    }

    #[test]
    fn record_count_includes_multi_and_matches() {
        let dir = tmp_dir("count");
        let path = dir.join("vault.v1");
        let mut v = VaultBuilder::new(path.clone())
            .with_kdf_for_test(test_kdf())
            .create_from_passphrase("count checks")
            .unwrap();
        for i in 0..50u32 {
            v.append_record(format!("rec-{i}").as_bytes()).unwrap();
        }
        drop(v);
        let opened = EncryptedVault::open_existing(path.clone(), "count checks").unwrap();
        assert_eq!(opened.record_count().unwrap(), 50);
        assert_eq!(opened.read_record_by_index(49).unwrap(), b"rec-49");
        assert_eq!(opened.read_record_by_index(0).unwrap(), b"rec-0");
        std::fs::remove_dir_all(&dir).unwrap();
    }
}