//! The Vault Header. Everything stored here is plaintext-visible by necessity
//! (it describes how to derive the key); everything beyond it is ciphertext.
//!
//! A header carries: magic bytes, layout version, fixed header length,
//! Argon2id m/t/p parameters, 19 random salt bytes (used to derive a 32-byte
//! key together with the passphrase), a verifier record (nonce + 32-byte
//! ciphertext sealing exactly 16 plaintext bytes), and a 32-byte file-id.
//! No byte beyond these is visible anywhere; records live past the header
//! entirely as opaque framed ciphertext.
//!
//! Header total length is fixed at 119 bytes:
//!   24 (magic+len fields+params) + 19 (salt) + 12 (verifier nonce)
//!   + 32 (verifier ct: 13 header-bound info bytes or fixed verifier plaintext
//!     + MAC padding... concretely: 16 bytes verifier plaintext + 16-byte tag)
//!   + 32 (file id) = 119.
//! All fixed-width fields are validated before any record byte is ever read.

pub const MAGIC_PREFIX: [u8; 4] = [0x43, 0x45, 0x52, 0x42]; // ascii "CERB"
/// Fixed header length for version 1, see module doc: 119 bytes.
pub const HEADER_LEN_BYTES_V1: u32 = 119;
/// File layout version 1.
pub const LAYOUT_VERSION_V1: u32 = 1;

/// Salt length, 19 bytes of CSPRNG entropy (>= 96 bit security for the KDF by
/// itself and forces a fresh derivation domain per vault).
pub const SALT_LEN_V1: usize = 19;
/// Verifier ciphertext bytes: 16-byte plaintext + 16-byte Poly1305 tag.
pub const VERIFIER_CIPHERTEXT_LEN_V1: usize = 32;
pub const VERIFIER_PLAINTEXT_LEN_V1: usize = 16;
pub const VERIFIER_NONCE_LEN: usize = 12;
pub const FILE_ID_LEN: usize = 32;

/// Argon2 lock configuration: 256 MiB memory cost.
pub const DEFAULT_KDF_M_COST_KIB: u32 = 262_144;
/// 3 sequential passes.
pub const DEFAULT_KDF_T_COST: u32 = 3;
/// Single lane of parallelism.
pub const DEFAULT_KDF_P_COST: u32 = 1;

const KDF_M_MIN_KIB: u32 = 8 * 1024; // 8 MiB minimum (test-scale floor)
const KDF_M_MAX_KIB: u32 = 4 * 1024 * 1024; // 4 GiB ceiling
const KDF_T_MIN: u32 = 1;
const KDF_T_MAX: u32 = 1_024;
const KDF_P_MIN: u32 = 1;
const KDF_P_MAX: u32 = 4;

/// Errors returned when reading raw header bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeaderParseError {
    TooShortForHeader,
    MissingMagic,
    VersionMismatch,
    HeaderFixedLengthReportMismatch,
    UnreasonableKdfParameters,
}

impl std::fmt::Display for HeaderParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HeaderParseError::TooShortForHeader => write!(f, "vault file too short to contain a full fixed-length v1 header"),
            HeaderParseError::MissingMagic => write!(f, "vault file does not begin with the CERB magic bytes"),
            HeaderParseError::VersionMismatch => write!(f, "vault header layout version is not v1 and is therefore rejected"),
            HeaderParseError::HeaderFixedLengthReportMismatch => write!(f, "vault header reported a length that disagrees with the fixed v1 header size"),
            HeaderParseError::UnreasonableKdfParameters => write!(f, "vault header Argon2id parameters fall outside the sane range this build accepts"),
        }
    }
}

impl std::error::Error for HeaderParseError {}

/// Argon2 runtime parameters as a reusable struct; defaults land on the
/// locked production config, tests may shrink them on their own copies.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VaultKdfParams {
    pub m_cost_kib: u32,
    pub t_cost: u32,
    pub p_cost: u32,
}

impl Default for VaultKdfParams {
    fn default() -> Self {
        Self {
            m_cost_kib: DEFAULT_KDF_M_COST_KIB,
            t_cost: DEFAULT_KDF_T_COST,
            p_cost: DEFAULT_KDF_P_COST,
        }
    }
}

/// A parsed, fully shape-validated Vault header.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VaultHeaderRaw {
    pub version: u32,
    pub header_len: u32,
    pub kdf: VaultKdfParams,
    pub salt: [u8; SALT_LEN_V1],
    pub verifier_nonce: [u8; VERIFIER_NONCE_LEN],
    pub verifier_ciphertext: [u8; VERIFIER_CIPHERTEXT_LEN_V1],
    pub file_id: [u8; FILE_ID_LEN],
}

impl VaultHeaderRaw {
    /// Serialize to the fixed V1 on-disk byte layout.
    pub fn encode(&self) -> Result<Vec<u8>, HeaderParseError> {
        validate_params(self.kdf)?;
        let mut out = Vec::with_capacity(HEADER_LEN_BYTES_V1 as usize);
        out.extend_from_slice(&MAGIC_PREFIX);
        out.extend_from_slice(&self.version.to_le_bytes());
        out.extend_from_slice(&self.header_len.to_le_bytes());
        out.extend_from_slice(&self.kdf.m_cost_kib.to_le_bytes());
        out.extend_from_slice(&self.kdf.t_cost.to_le_bytes());
        out.extend_from_slice(&self.kdf.p_cost.to_le_bytes());
        out.extend_from_slice(&self.salt);
        out.extend_from_slice(&self.verifier_nonce);
        out.extend_from_slice(&self.verifier_ciphertext);
        out.extend_from_slice(&self.file_id);
        Ok(out)
    }

    /// Parse + validate: length, magic, version, fixed length contract, and
    /// reasonable KDF parameters before returning.
    pub fn bytes_to_header(bytes: &[u8]) -> Result<(VaultHeaderRaw, usize), HeaderParseError> {
        let total_fixed = HEADER_LEN_BYTES_V1 as usize;
        if bytes.len() < total_fixed {
            return Err(HeaderParseError::TooShortForHeader);
        }
        if bytes[0..4] != MAGIC_PREFIX {
            return Err(HeaderParseError::MissingMagic);
        }

        let version = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
        if version != LAYOUT_VERSION_V1 {
            // Reserved; fail to reject unknown layouts outright.
            version_ok(version)?;
        }

        let header_len = u32::from_le_bytes([bytes[8], bytes[9], bytes[10], bytes[11]]);
        if header_len != total_fixed as u32 {
            return Err(HeaderParseError::HeaderFixedLengthReportMismatch);
        }

        let m_cost_kib = u32::from_le_bytes([bytes[12], bytes[13], bytes[14], bytes[15]]);
        let t_cost = u32::from_le_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);
        let p_cost = u32::from_le_bytes([bytes[20], bytes[21], bytes[22], bytes[23]]);
        let kdf = VaultKdfParams {
            m_cost_kib,
            t_cost,
            p_cost,
        };
        validate_params(kdf)?;

        let mut read = 24;
        let mut salt = [0u8; SALT_LEN_V1];
        salt.copy_from_slice(&bytes[read..read + SALT_LEN_V1]);
        read += SALT_LEN_V1;

        let mut verifier_nonce = [0u8; VERIFIER_NONCE_LEN];
        verifier_nonce.copy_from_slice(&bytes[read..read + VERIFIER_NONCE_LEN]);
        read += VERIFIER_NONCE_LEN;

        let mut verifier_ciphertext = [0u8; VERIFIER_CIPHERTEXT_LEN_V1];
        verifier_ciphertext.copy_from_slice(&bytes[read..read + VERIFIER_CIPHERTEXT_LEN_V1]);
        read += VERIFIER_CIPHERTEXT_LEN_V1;

        let mut file_id = [0u8; FILE_ID_LEN];
        file_id.copy_from_slice(&bytes[read..read + FILE_ID_LEN]);
        read += FILE_ID_LEN;

        Ok((
            VaultHeaderRaw {
                version,
                header_len,
                kdf,
                salt,
                verifier_nonce,
                verifier_ciphertext,
                file_id,
            },
            read,
        ))
    }
}

fn version_ok(_version: u32) -> Result<(), HeaderParseError> {
    Err(HeaderParseError::VersionMismatch)
}

fn validate_params(kdf: VaultKdfParams) -> Result<(), HeaderParseError> {
    let good = (KDF_M_MIN_KIB..=KDF_M_MAX_KIB).contains(&kdf.m_cost_kib)
        && (KDF_T_MIN..=KDF_T_MAX).contains(&kdf.t_cost)
        && (KDF_P_MIN..=KDF_P_MAX).contains(&kdf.p_cost);
    if good {
        Ok(())
    } else {
        Err(HeaderParseError::UnreasonableKdfParameters)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header_sample() -> VaultHeaderRaw {
        VaultHeaderRaw {
            version: LAYOUT_VERSION_V1,
            header_len: HEADER_LEN_BYTES_V1,
            kdf: VaultKdfParams {
                m_cost_kib: 8192,
                t_cost: 1,
                p_cost: 1,
            },
            salt: [0xA5; SALT_LEN_V1],
            verifier_nonce: [0x11; VERIFIER_NONCE_LEN],
            verifier_ciphertext: [0x22; VERIFIER_CIPHERTEXT_LEN_V1],
            file_id: [0x33; FILE_ID_LEN],
        }
    }

    #[test]
    fn header_roundtrip_roundtrips_exactly() {
        let h = header_sample();
        let encoded = h.encode().unwrap();
        assert_eq!(encoded.len(), HEADER_LEN_BYTES_V1 as usize);
        let (parsed, consumed) = VaultHeaderRaw::bytes_to_header(&encoded).unwrap();
        assert_eq!(consumed, HEADER_LEN_BYTES_V1 as usize);
        assert_eq!(parsed.kdf, h.kdf);
        assert_eq!(parsed.salt, h.salt);
        assert_eq!(parsed.verifier_nonce, h.verifier_nonce);
        assert_eq!(parsed.verifier_ciphertext, h.verifier_ciphertext);
        assert_eq!(parsed.file_id, h.file_id);
        assert_eq!(parsed.version, h.version);
    }

    #[test]
    fn wrong_version_fails() {
        let mut h = header_sample();
        h.version = 2;
        let encoded = h.encode().unwrap();
        assert_eq!(
            VaultHeaderRaw::bytes_to_header(&encoded).unwrap_err(),
            HeaderParseError::VersionMismatch
        );
    }

    #[test]
    fn wrong_magic_fails() {
        let h = header_sample();
        let mut bad = h.encode().unwrap();
        bad[0] = b'X';
        assert_eq!(
            VaultHeaderRaw::bytes_to_header(&bad).unwrap_err(),
            HeaderParseError::MissingMagic
        );
    }

    #[test]
    fn too_aggressive_or_weird_kdf_params_fail() {
        let mut h = header_sample();
        h.kdf.t_cost = 9_999; // beyond sanity
        assert_eq!(
            h.encode().unwrap_err(),
            HeaderParseError::UnreasonableKdfParameters
        );
        h.kdf = VaultKdfParams {
            m_cost_kib: 4 * 1024 * 1_023, // inside max range
            t_cost: 1,
            p_cost: 1,
        };
        let mut bytes = h.encode().expect("in-range params must encode");
        // Corrupt the on-disk m-cost to below the minimum to simulate a lie.
        bytes[12..16].copy_from_slice(&(4u32).to_le_bytes());
        assert_eq!(
            VaultHeaderRaw::bytes_to_header(&bytes).unwrap_err(),
            HeaderParseError::UnreasonableKdfParameters
        );
    }

    #[test]
    fn wrong_reported_length_fails() {
        let mut h = header_sample();
        h.header_len = 111;
        let bytes = h.encode().unwrap();
        assert_eq!(
            VaultHeaderRaw::bytes_to_header(&bytes).unwrap_err(),
            HeaderParseError::HeaderFixedLengthReportMismatch
        );
    }

    #[test]
    fn short_bytes_fail() {
        let bytes = [0u8; 40];
        assert_eq!(
            VaultHeaderRaw::bytes_to_header(&bytes).unwrap_err(),
            HeaderParseError::TooShortForHeader
        );
    }
}