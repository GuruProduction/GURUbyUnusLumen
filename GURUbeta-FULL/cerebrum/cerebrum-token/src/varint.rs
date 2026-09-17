//! Variable-length integer encoding (varint / LEB128).
//!
//! Varint encoding stores integers in 7-bit chunks with a continuation bit.
//! Small values use 1 byte, larger values use more.  This is ideal for delta-
//! encoded token sequences where most deltas are small.
//!
//! # Encoding scheme (unsigned LEB128)
//! Each byte has 7 data bits and 1 continuation bit:
//! - bit 7 = 1 → more bytes follow
//! - bit 7 = 0 → this is the last byte
//!
//! # Signed extension (zigzag)
//! Signed values use zigzag encoding: `encode((n << 1) ^ (n >> 31))`.
//! This maps small signed values to small unsigned values.

use crate::error::{TokenError, TokenResult};

// ============================================================================
// Unsigned Varint
// ============================================================================

/// Encode a `u64` as an unsigned LEB128 varint.
#[inline]
pub fn encode_varint_u64(value: u64) -> Vec<u8> {
    let mut value = value;
    let mut bytes = Vec::with_capacity(10);
    loop {
        let mut byte = (value & 0x7F) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        bytes.push(byte);
        if value == 0 {
            break;
        }
    }
    bytes
}

/// Decode a single unsigned LEB128 varint from a byte slice to u64.
pub fn decode_varint_u64(data: &[u8]) -> TokenResult<(u64, usize)> {
    let mut value: u64 = 0;
    let mut shift: u32 = 0;
    let mut i: usize = 0;

    for &byte in data {
        i += 1;
        let chunk = (byte & 0x7F) as u64;

        // Check for overflow: u64 max bits = 64, so shift must be < 64. 10 bytes max (7*10 = 70).
        if shift >= 70 {
            return Err(TokenError::VarintDecode(
                "varint exceeds u64 range".into(),
            ));
        }

        value |= chunk << shift;
        shift += 7;

        if byte & 0x80 == 0 {
            return Ok((value, i));
        }

        if i >= 10 {
            return Err(TokenError::VarintDecode(
                "varint too long (max 10 bytes for u64)".into(),
            ));
        }
    }

    Err(TokenError::VarintDecode(
        "truncated varint: unexpected end of data".into(),
    ))
}

/// Encode a `u32` as an unsigned LEB128 varint.
///
/// # Examples
/// ```
/// use cerebrum_token::varint::encode_varint;
///
/// assert_eq!(encode_varint(0), vec![0x00]);
/// assert_eq!(encode_varint(127), vec![0x7F]);
/// assert_eq!(encode_varint(128), vec![0x80, 0x01]);
/// assert_eq!(encode_varint(16383), vec![0xFF, 0x7F]);
/// assert_eq!(encode_varint(16384), vec![0x80, 0x80, 0x01]);
/// ```
#[inline]
pub fn encode_varint(value: u32) -> Vec<u8> {
    let mut value = value;
    let mut bytes = Vec::with_capacity(5);
    loop {
        let mut byte = (value & 0x7F) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        bytes.push(byte);
        if value == 0 {
            break;
        }
    }
    bytes
}

/// Decode a single unsigned LEB128 varint from a byte slice.
///
/// Returns `(value, bytes_consumed)`.
///
/// # Errors
/// Returns [`TokenError::VarintDecode`] if the varint is truncated (more than
/// 5 bytes needed for a u32, or continuation bit set on the 5th byte).
///
/// # Examples
/// ```
/// use cerebrum_token::varint::decode_varint;
///
/// assert_eq!(decode_varint(&[0x00]).unwrap(), (0, 1));
/// assert_eq!(decode_varint(&[0x7F]).unwrap(), (127, 1));
/// assert_eq!(decode_varint(&[0x80, 0x01]).unwrap(), (128, 2));
/// ```
pub fn decode_varint(data: &[u8]) -> TokenResult<(u32, usize)> {
    let mut value: u32 = 0;
    let mut shift: u32 = 0;
    let mut i: usize = 0;

    for &byte in data {
        i += 1;
        let chunk = (byte & 0x7F) as u32;

        // Check for overflow: if shift >= 35, we'd exceed u32 on the next shift.
        // u32 max bits = 32, so shift must be < 32. 5 bytes max (7*5 = 35).
        if shift >= 35 {
            return Err(TokenError::VarintDecode(
            "varint exceeds u32 range".into(),
            ));
        }

        value |= chunk << shift;
        shift += 7;

        if byte & 0x80 == 0 {
            return Ok((value, i));
        }

        if i >= 5 {
            return Err(TokenError::VarintDecode(
                "varint too long (max 5 bytes for u32)".into(),
            ));
        }
    }

    Err(TokenError::VarintDecode(
        "truncated varint: unexpected end of data".into(),
    ))
}

// ============================================================================
// Signed Varint (Zigzag)
// ============================================================================

/// Zigzag-encode a signed `i64` → unsigned `u64`.
///
/// Maps: 0→0, -1→1, 1→2, -2→3, 2→4, ...
/// Small signed values become small unsigned values.
#[inline]
pub fn zigzag_encode_i64(value: i64) -> u64 {
    ((value << 1) ^ (value >> 63)) as u64
}

/// Zigzag-decode an unsigned `u64` → signed `i64`.
#[inline]
pub fn zigzag_decode_i64(value: u64) -> i64 {
    ((value >> 1) as i64) ^ -((value & 1) as i64)
}

/// Zigzag-encode a signed `i32` → unsigned `u32`.
///
/// Maps: 0→0, -1→1, 1→2, -2→3, 2→4, ...
/// Small signed values become small unsigned values.
#[inline]
pub fn zigzag_encode(value: i32) -> u32 {
    ((value << 1) ^ (value >> 31)) as u32
}

/// Zigzag-decode an unsigned `u32` → signed `i32`.
#[inline]
pub fn zigzag_decode(value: u32) -> i32 {
    ((value >> 1) as i32) ^ -((value & 1) as i32)
}

/// Encode a signed `i32` as a zigzag + varint.
#[inline]
pub fn encode_signed_varint(value: i32) -> Vec<u8> {
    encode_varint(zigzag_encode(value))
}

/// Decode a signed `i32` from a zigzag + varint byte stream.
///
/// Returns `(value, bytes_consumed)`.
pub fn decode_signed_varint(data: &[u8]) -> TokenResult<(i32, usize)> {
    let (encoded, consumed) = decode_varint(data)?;
    Ok((zigzag_decode(encoded), consumed))
}

// ============================================================================
// Batch operations
// ============================================================================

/// Encode a sequence of unsigned `u32` values as consecutive varints.
///
/// This is a convenience wrapper; for delta-encoded data, prefer
/// [`encode_deltas`](crate::compress::encode_deltas).
pub fn encode_varint_sequence(values: &[u32]) -> Vec<u8> {
    let mut result = Vec::new();
    for &v in values {
        result.extend_from_slice(&encode_varint(v));
    }
    result
}

/// Decode a sequence of consecutive varints from a byte slice.
///
/// Returns `Ok(values)` on success, [`TokenError::VarintDecode`] on any
/// decoding failure.
pub fn decode_varint_sequence(data: &[u8]) -> TokenResult<Vec<u32>> {
    let mut values = Vec::new();
    let mut pos: usize = 0;

    while pos < data.len() {
        let (value, consumed) = decode_varint(&data[pos..])?;
        values.push(value);
        pos += consumed;
    }

    Ok(values)
}

/// Encode a sequence of signed `i32` values as consecutive zigzag+varints.
pub fn encode_signed_varint_sequence(values: &[i32]) -> Vec<u8> {
    let mut result = Vec::new();
    for &v in values {
        result.extend_from_slice(&encode_signed_varint(v));
    }
    result
}

/// Decode a sequence of consecutive signed zigzag+varints.
pub fn decode_signed_varint_sequence(data: &[u8]) -> TokenResult<Vec<i32>> {
    let mut values = Vec::new();
    let mut pos: usize = 0;

    while pos < data.len() {
        let (value, consumed) = decode_signed_varint(&data[pos..])?;
        values.push(value);
        pos += consumed;
    }

    Ok(values)
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_varint_zero() {
        assert_eq!(encode_varint(0), vec![0x00]);
        assert_eq!(decode_varint(&[0x00]).unwrap(), (0, 1));
    }

    #[test]
    fn test_varint_small() {
        assert_eq!(encode_varint(1), vec![0x01]);
        assert_eq!(encode_varint(63), vec![0x3F]);
        assert_eq!(encode_varint(127), vec![0x7F]);
    }

    #[test]
    fn test_varint_boundary() {
        // 128 = 0x80 needs 2 bytes
        assert_eq!(encode_varint(128), vec![0x80, 0x01]);
        assert_eq!(decode_varint(&[0x80, 0x01]).unwrap(), (128, 2));

        // 16383 = 0x3FFF max 2-byte value
        assert_eq!(encode_varint(16383), vec![0xFF, 0x7F]);
        assert_eq!(decode_varint(&[0xFF, 0x7F]).unwrap(), (16383, 2));

        // 16384 = 0x4000 needs 3 bytes
        assert_eq!(encode_varint(16384), vec![0x80, 0x80, 0x01]);
        assert_eq!(decode_varint(&[0x80, 0x80, 0x01]).unwrap(), (16384, 3));
    }

    #[test]
    fn test_varint_large() {
        assert_eq!(encode_varint(u32::MAX), vec![0xFF, 0xFF, 0xFF, 0xFF, 0x0F]);
        assert_eq!(
            decode_varint(&[0xFF, 0xFF, 0xFF, 0xFF, 0x0F]).unwrap(),
            (u32::MAX, 5)
        );
    }

    #[test]
    fn test_varint_roundtrip_all_u32() {
        // Test a representative sample of the full u32 range.
        // We can't test all 2^32 values, but we hit every power of 2,
        // every power of 2 ± 1, and a few random-looking values.
        let test_values: Vec<u32> = {
            let mut v = Vec::new();
            for i in 0..=32u32 {
                if i < 32 {
                    v.push(1u32 << i);
                    v.push((1u32 << i).wrapping_sub(1));
                    v.push((1u32 << i).wrapping_add(1));
                } else {
                    v.push(u32::MAX);
                }
            }
            v
        };

        for &value in &test_values {
            let encoded = encode_varint(value);
            let (decoded, consumed) = decode_varint(&encoded).unwrap();
            assert_eq!(decoded, value, "roundtrip failed for {}", value);
            assert_eq!(consumed, encoded.len(), "consumed mismatch for {}", value);
        }
    }

    #[test]
    fn test_varint_sequence() {
        let values = vec![0u32, 1, 127, 128, 16383, 16384, 100000, u32::MAX];
        let encoded = encode_varint_sequence(&values);
        let decoded = decode_varint_sequence(&encoded).unwrap();
        assert_eq!(decoded, values);
    }

    #[test]
    fn test_varint_decode_truncated() {
        // Continuation bit set but no following byte
        assert!(decode_varint(&[0x80]).is_err());
        assert!(decode_varint(&[0xFF, 0xFF]).is_err());
    }

    #[test]
    fn test_varint_decode_too_long() {
        // 6 bytes with continuation — exceeds u32
        let data = [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01];
        assert!(decode_varint(&data).is_err());
    }

    #[test]
    fn test_zigzag_small_values() {
        assert_eq!(zigzag_encode(0), 0);
        assert_eq!(zigzag_encode(-1), 1);
        assert_eq!(zigzag_encode(1), 2);
        assert_eq!(zigzag_encode(-2), 3);
        assert_eq!(zigzag_encode(2), 4);
    }

    #[test]
    fn test_zigzag_roundtrip() {
        for i in i32::MIN..=i32::MAX {
            // Can't test all, test a representative sample
            if i % 1_000_000 != 0 && i != i32::MIN && i != i32::MAX {
                continue;
            }
            let encoded = zigzag_encode(i);
            let decoded = zigzag_decode(encoded);
            assert_eq!(decoded, i, "zigzag roundtrip failed for {}", i);
        }
    }

    #[test]
    fn test_signed_varint_roundtrip() {
        let values = vec![0i32, -1, 1, -127, 127, -128, 128, -1000, 1000, i32::MIN, i32::MAX];
        let encoded = encode_signed_varint_sequence(&values);
        let decoded = decode_signed_varint_sequence(&encoded).unwrap();
        assert_eq!(decoded, values);
    }

    #[test]
    fn test_varint_sequence_empty() {
        let encoded = encode_varint_sequence(&[]);
        assert!(encoded.is_empty());
        let decoded = decode_varint_sequence(&encoded).unwrap();
        assert!(decoded.is_empty());
    }

    #[test]
    fn test_signed_varint_sequence_empty() {
        let encoded = encode_signed_varint_sequence(&[]);
        assert!(encoded.is_empty());
        let decoded = decode_signed_varint_sequence(&encoded).unwrap();
        assert!(decoded.is_empty());
    }
}
