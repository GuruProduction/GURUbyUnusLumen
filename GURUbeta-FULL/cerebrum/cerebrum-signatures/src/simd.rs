//! SIMD-accelerated Hamming distance for 256-bit binary signatures.
//!
//! Phase 0.2.2 — Per the Build Bible §0.2.2, SIMD path must be ≥4x faster than scalar.
//!
//! Architecture support:
//!   - x86-64 with AVX2: Processes entire 256-bit signature in 1 XOR + 1 popcount
//!     (__m256i → 4×u64 XOR → _mm256_xor_epi32 → horizontal popcount)
//!   - x86-64 with SSE2: Processes 128 bits at a time (2 passes for 256-bit)
//!   - Fallback (ARM, etc.): Uses u64 chunked popcount (scalar but pipelined)
//!
//! The AVX2 path is the key optimization: XOR two 256-bit signatures in a single
//! instruction, then popcount the four u64 lanes. This eliminates the 4-iteration
//! loop of the scalar path.

use cerebrum_core::BinarySignature;

#[cfg(target_arch = "x86_64")]
use std::arch::x86_64::*;

// ============================================================================
// AVX2 Path — 256-bit signature in 1 vector operation
// ============================================================================

/// Hamming distance between two 256-bit signatures using AVX2.
///
/// XOR two __m256i vectors in one instruction, then extract the four u64
/// lanes and popcount each. Total: 1 XOR + 4 popcounts = ~5 instructions.
///
/// SAFETY: Caller must verify AVX2 is available via is_avx2_supported().
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn hamming_distance_avx2(a: &BinarySignature, b: &BinarySignature) -> u32 {
    // Load 256-bit signatures into AVX2 registers
    // __m256i = 256 bits = 32 bytes = entire signature
    let a_vec = _mm256_loadu_si256(a.0.as_ptr() as *const __m256i);
    let b_vec = _mm256_loadu_si256(b.0.as_ptr() as *const __m256i);

    // XOR both vectors in one instruction
    let xor_vec = _mm256_xor_si256(a_vec, b_vec);

    // Extract the four u64 lanes and popcount each
    // _mm256_extract_epi64 extracts a single i64 from a 256-bit vector
    // Lane order: 0 (bits 0-63), 1 (bits 64-127), 2 (bits 128-191), 3 (bits 192-255)
    let lane0 = _mm256_extract_epi64(xor_vec, 0) as u64;
    let lane1 = _mm256_extract_epi64(xor_vec, 1) as u64;
    let lane2 = _mm256_extract_epi64(xor_vec, 2) as u64;
    let lane3 = _mm256_extract_epi64(xor_vec, 3) as u64;

    // Hardware popcount on each lane (compiles to single popcnt instruction each)
    lane0.count_ones() + lane1.count_ones() + lane2.count_ones() + lane3.count_ones()
}

// ============================================================================
// SSE2 Path — 128 bits at a time (2 passes for 256-bit signature)
// ============================================================================

/// Hamming distance using SSE2 (available on all x86-64 CPUs since 2003).
///
/// Processes 128 bits per pass, so two passes for a 256-bit signature.
/// Each pass: 1 XOR + 2 popcounts.
///
/// SAFETY: SSE2 is guaranteed on x86-64.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "sse2")]
#[inline]
unsafe fn hamming_distance_sse2(a: &BinarySignature, b: &BinarySignature) -> u32 {
    let mut dist = 0u32;

    // Process 128 bits at a time (two passes for 256-bit signature)
    for offset in [0, 16] {
        // Load 128 bits
        let a_vec = _mm_loadu_si128(a.0.as_ptr().add(offset) as *const __m128i);
        let b_vec = _mm_loadu_si128(b.0.as_ptr().add(offset) as *const __m128i);

        // XOR
        let xor_vec = _mm_xor_si128(a_vec, b_vec);

        // Extract two u64 lanes and popcount
        let lo = _mm_extract_epi64(xor_vec, 0) as u64;
        let hi = _mm_extract_epi64(xor_vec, 1) as u64;
        dist += lo.count_ones() + hi.count_ones();
    }

    dist
}

// ============================================================================
// Batch Hamming distance — process 8 signatures simultaneously
// ============================================================================

/// Batch Hamming distance: query vs 8 candidates using AVX2.
///
/// This is the hot path for Hamming-ball search. We can process 8 signatures
/// at a time by doing 8 XOR operations and accumulating the popcounts.
///
/// Returns 8 Hamming distances.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn hamming_distance_batch_avx2(query: &BinarySignature, candidates: &[BinarySignature; 8]) -> [u32; 8] {
    let q = _mm256_loadu_si256(query.0.as_ptr() as *const __m256i);

    let mut results = [0u32; 8];

    // Process 2 candidates per iteration (2 AVX2 XOR ops can pipeline)
    for i in (0..8).step_by(2) {
        let a_vec = _mm256_loadu_si256(candidates[i].0.as_ptr() as *const __m256i);
        let b_vec = _mm256_loadu_si256(candidates[i + 1].0.as_ptr() as *const __m256i);

        let xor_a = _mm256_xor_si256(q, a_vec);
        let xor_b = _mm256_xor_si256(q, b_vec);

        // Popcount each XOR result
        results[i] = {
            let lane0 = _mm256_extract_epi64(xor_a, 0) as u64;
            let lane1 = _mm256_extract_epi64(xor_a, 1) as u64;
            let lane2 = _mm256_extract_epi64(xor_a, 2) as u64;
            let lane3 = _mm256_extract_epi64(xor_a, 3) as u64;
            lane0.count_ones() + lane1.count_ones() + lane2.count_ones() + lane3.count_ones()
        };

        results[i + 1] = {
            let lane0 = _mm256_extract_epi64(xor_b, 0) as u64;
            let lane1 = _mm256_extract_epi64(xor_b, 1) as u64;
            let lane2 = _mm256_extract_epi64(xor_b, 2) as u64;
            let lane3 = _mm256_extract_epi64(xor_b, 3) as u64;
            lane0.count_ones() + lane1.count_ones() + lane2.count_ones() + lane3.count_ones()
        };
    }

    results
}

// ============================================================================
// Scalar fallback — portable, works on any platform
// ============================================================================

/// Scalar Hamming distance using u64 chunked popcount.
///
/// This is the baseline that SIMD must beat. Processes 8 bytes (64 bits)
/// at a time using hardware popcnt.
#[inline]
pub fn hamming_distance_scalar(a: &BinarySignature, b: &BinarySignature) -> u32 {
    let mut dist = 0u32;
    let a_chunks = a.0.chunks_exact(8);
    let b_chunks = b.0.chunks_exact(8);
    for (a_chunk, b_chunk) in a_chunks.zip(b_chunks) {
        let a_val = u64::from_ne_bytes(a_chunk.try_into().unwrap());
        let b_val = u64::from_ne_bytes(b_chunk.try_into().unwrap());
        dist += (a_val ^ b_val).count_ones();
    }
    // Handle remainder (for signatures < 256 bits, e.g. 128-bit)
    let a_rem = a.0.chunks_exact(8).remainder();
    let b_rem = b.0.chunks_exact(8).remainder();
    for (&a_byte, &b_byte) in a_rem.iter().zip(b_rem.iter()) {
        dist += (a_byte ^ b_byte).count_ones();
    }
    dist
}

// ============================================================================
// Hamming distance between byte slices (for multi-index substring comparison)
// ============================================================================

/// Hamming distance between two byte slices using SIMD when available.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
#[inline]
unsafe fn hamming_distance_bytes_avx2(a: &[u8], b: &[u8]) -> u32 {
    let mut dist = 0u32;
    let min_len = a.len().min(b.len());

    // Process 32 bytes at a time
    let chunks = min_len / 32;
    for i in 0..chunks {
        let a_vec = _mm256_loadu_si256(a.as_ptr().add(i * 32) as *const __m256i);
        let b_vec = _mm256_loadu_si256(b.as_ptr().add(i * 32) as *const __m256i);
        let xor_vec = _mm256_xor_si256(a_vec, b_vec);
        let lane0 = _mm256_extract_epi64(xor_vec, 0) as u64;
        let lane1 = _mm256_extract_epi64(xor_vec, 1) as u64;
        let lane2 = _mm256_extract_epi64(xor_vec, 2) as u64;
        let lane3 = _mm256_extract_epi64(xor_vec, 3) as u64;
        dist += lane0.count_ones() + lane1.count_ones() + lane2.count_ones() + lane3.count_ones();
    }

    // Process remaining 8-byte chunks
    let offset = chunks * 32;
    let a_rem = &a[offset..min_len];
    let b_rem = &b[offset..min_len];
    for (a_chunk, b_chunk) in a_rem.chunks_exact(8).zip(b_rem.chunks_exact(8)) {
        let a_val = u64::from_ne_bytes(a_chunk.try_into().unwrap());
        let b_val = u64::from_ne_bytes(b_chunk.try_into().unwrap());
        dist += (a_val ^ b_val).count_ones();
    }

    // Process remaining bytes
    let rem_offset = offset + (min_len - offset) / 8 * 8;
    for i in rem_offset..min_len {
        dist += (a[i] ^ b[i]).count_ones();
    }

    dist
}

/// Scalar byte-slice Hamming distance (fallback).
#[inline]
pub fn hamming_distance_bytes_scalar(a: &[u8], b: &[u8]) -> u32 {
    let mut dist = 0u32;
    for (&a_byte, &b_byte) in a.iter().zip(b.iter()) {
        dist += (a_byte ^ b_byte).count_ones();
    }
    dist
}

// ============================================================================
// Runtime feature detection and dispatch
// ============================================================================

/// Check if AVX2 is available at runtime. Result is cached after first call.
#[cfg(target_arch = "x86_64")]
pub fn is_avx2_supported() -> bool {
    static CACHE: std::sync::atomic::AtomicI8 = std::sync::atomic::AtomicI8::new(-1);
    let cached = CACHE.load(std::sync::atomic::Ordering::Relaxed);
    if cached >= 0 {
        return cached != 0;
    }
    let result = is_x86_feature_detected!("avx2");
    CACHE.store(result as i8, std::sync::atomic::Ordering::Relaxed);
    result
}

/// Portable fallback: AVX2 is never available on non-x86_64 architectures.
#[cfg(not(target_arch = "x86_64"))]
pub fn is_avx2_supported() -> bool {
    false
}

/// Check if SSE2 is available at runtime (always true on x86-64).
#[cfg(target_arch = "x86_64")]
#[allow(dead_code)]
pub fn is_sse2_supported() -> bool {
    // SSE2 is part of the x86-64 baseline
    true
}

/// Compute Hamming distance between two 256-bit signatures using the fastest
/// available SIMD path. Dispatches to AVX2, SSE2, or scalar at runtime.
///
/// Per Build Bible §0.2.2: SIMD path must be ≥4x faster than scalar.
/// AVX2 processes the entire 256-bit signature in 1 XOR + 4 popcounts,
/// vs scalar's 4 XOR + 4 popcounts. The 4x target comes from the
/// elimination of loop overhead + better instruction pipelining.
pub fn hamming_distance_fast(a: &BinarySignature, b: &BinarySignature) -> u32 {
    #[cfg(target_arch = "x86_64")]
    {
        if is_avx2_supported() {
            // SAFETY: We just verified AVX2 is supported.
            unsafe { hamming_distance_avx2(a, b) }
        } else {
            // SSE2 is always available on x86-64
            // SAFETY: SSE2 is part of the x86-64 baseline.
            unsafe { hamming_distance_sse2(a, b) }
        }
    }
    #[cfg(not(target_arch = "x86_64"))]
    {
        hamming_distance_scalar(a, b)
    }
}

/// Compute batch Hamming distances using the fastest available SIMD path.
///
/// Processes 8 signatures at once for maximum throughput.
/// Returns 8 Hamming distances.
#[cfg(target_arch = "x86_64")]
pub fn hamming_distance_batch8(query: &BinarySignature, candidates: &[BinarySignature; 8]) -> [u32; 8] {
    if is_avx2_supported() {
        // SAFETY: AVX2 verified available.
        unsafe { hamming_distance_batch_avx2(query, candidates) }
    } else {
        // SSE2 fallback: process individually
        let mut results = [0u32; 8];
        for (i, candidate) in candidates.iter().enumerate() {
            results[i] = hamming_distance_fast(query, candidate);
        }
        results
    }
}

/// Portable fallback for batch Hamming distance on non-x86_64 architectures.
#[cfg(not(target_arch = "x86_64"))]
pub fn hamming_distance_batch8(query: &BinarySignature, candidates: &[BinarySignature; 8]) -> [u32; 8] {
    let mut results = [0u32; 8];
    for (i, candidate) in candidates.iter().enumerate() {
        results[i] = hamming_distance_fast(query, candidate);
    }
    results
}

/// Compute Hamming distance between two byte slices using the fastest
/// available SIMD path. Used for multi-index substring comparison.
pub fn hamming_distance_bytes_fast(a: &[u8], b: &[u8]) -> u32 {
    #[cfg(target_arch = "x86_64")]
    {
        if is_avx2_supported() {
            // SAFETY: AVX2 verified available.
            unsafe { hamming_distance_bytes_avx2(a, b) }
        } else {
            hamming_distance_bytes_scalar(a, b)
        }
    }
    #[cfg(not(target_arch = "x86_64"))]
    {
        hamming_distance_bytes_scalar(a, b)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use cerebrum_core::BinarySignature;

    #[test]
    fn test_avx2_matches_scalar() {
        if !is_avx2_supported() {
            eprintln!("AVX2 not available, skipping AVX2 test");
            return;
        }

        // Test 1000 random signatures
        let mut rng_state: u64 = 12345;
        let mut next_sig = || -> BinarySignature {
            let mut bytes = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                bytes[b] = (rng_state >> 8) as u8;
            }
            BinarySignature(bytes)
        };

        for _ in 0..1000 {
            let a = next_sig();
            let b = next_sig();
            let scalar_dist = hamming_distance_scalar(&a, &b);
            let simd_dist = hamming_distance_fast(&a, &b);
            assert_eq!(scalar_dist, simd_dist,
                "AVX2 distance {} != scalar distance {}", simd_dist, scalar_dist);
        }
    }

    #[test]
    fn test_sse2_matches_scalar() {
        // SSE2 is always available on x86-64
        let a = BinarySignature([0xAA; 32]);
        let b = BinarySignature([0x55; 32]);
        let _scalar = hamming_distance_scalar(&a, &b);
        // SSE2 path is tested implicitly via hamming_distance_fast on non-AVX2 machines
        // On AVX2 machines, hamming_distance_fast uses AVX2, so we test SSE2 directly
        #[cfg(target_arch = "x86_64")]
        {
            let sse2 = unsafe { hamming_distance_sse2(&a, &b) };
            assert_eq!(_scalar, sse2);
        }
    }

    #[test]
    fn test_zero_distance() {
        let sig = BinarySignature([0x42; 32]);
        assert_eq!(hamming_distance_fast(&sig, &sig), 0);
        assert_eq!(hamming_distance_scalar(&sig, &sig), 0);
    }

    #[test]
    fn test_max_distance() {
        let a = BinarySignature([0x00; 32]);
        let b = BinarySignature([0xFF; 32]);
        assert_eq!(hamming_distance_fast(&a, &b), 256);
        assert_eq!(hamming_distance_scalar(&a, &b), 256);
    }

    #[test]
    fn test_single_bit_difference() {
        let mut a = [0u8; 32];
        let mut b = [0u8; 32];
        a[16] = 0b0000_0001;
        b[16] = 0b0000_0010;
        assert_eq!(hamming_distance_fast(&BinarySignature(a), &BinarySignature(b)), 2);
    }

    #[test]
    fn test_batch8_matches_individual() {
        if !is_avx2_supported() {
            eprintln!("AVX2 not available, skipping batch8 test");
            return;
        }

        let mut rng_state: u64 = 54321;
        let mut next_sig = || -> BinarySignature {
            let mut bytes = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                bytes[b] = (rng_state >> 8) as u8;
            }
            BinarySignature(bytes)
        };

        let query = next_sig();
        let candidates: [BinarySignature; 8] = [
            next_sig(), next_sig(), next_sig(), next_sig(),
            next_sig(), next_sig(), next_sig(), next_sig(),
        ];

        let batch_results = hamming_distance_batch8(&query, &candidates);
        for i in 0..8 {
            let individual = hamming_distance_fast(&query, &candidates[i]);
            assert_eq!(batch_results[i], individual,
                "Batch result {} ({}) != individual result ({})",
                i, batch_results[i], individual);
        }
    }

    #[test]
    fn test_bytes_fast_matches_scalar() {
        let a: Vec<u8> = (0..32).map(|i| (i * 7) as u8).collect();
        let b: Vec<u8> = (0..32).map(|i| (i * 13 + 5) as u8).collect();
        let scalar = hamming_distance_bytes_scalar(&a, &b);
        let fast = hamming_distance_bytes_fast(&a, &b);
        assert_eq!(scalar, fast);
    }
}