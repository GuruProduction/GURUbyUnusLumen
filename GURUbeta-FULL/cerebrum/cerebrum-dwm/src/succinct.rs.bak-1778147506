//! SuccinctBitVec - Compressed bit vector with rank/select support.
//!
//! Foundation of the entire DWM data structure (Build Bible §0.2.3).
//!
//! # Operations
//! - `rank(i, b)`: count occurrences of bit b in [0, i) - **O(1)**
//! - `select(k, b)`: position of k-th occurrence of bit b - **O(log n)**
//! - `access(i)`: bit at position i - **O(1)**
//!
//! # Algorithm
//! Two-level rank sampling (Raman et al.):
//! - `rank_blocks[w]` = cumulative 1-count in positions [0, w * 64) (absolute)
//! - rank1(i) = rank_blocks[i/64] + popcount(word[i/64] & mask) - one lookup + one popcount
//!
//! Select via sampled positions + binary search:
//! - `select_blocks_1[s]` = position of (s*64+1)-th 1-bit
//! - `select_blocks_0[s]` = position of (s*64+1)-th 0-bit
//! - Narrow search range using samples, then binary search within range
//!
//! # Struct layout (per Build Bible §0.2.3)
//! ```rust
//! pub struct SuccinctBitVec {
//!     data: Vec<u64>,               // Packed bits
//!     rank_blocks: Vec<u32>,        // Rank support structure
//!     select_blocks_0: Vec<u32>,   // Select support for 0s
//!     select_blocks_1: Vec<u32>,   // Select support for 1s
//!     len: usize,
//! }
//! ```

use serde::{Deserialize, Serialize};

// ============================================================================
// Constants
// ============================================================================

/// Bits per machine word.
const WORD_SIZE: usize = 64;

/// log2(WORD_SIZE) - shift amount for word index.
const WORD_BITS: usize = 6;

/// Mask for bit position within a word.
const WORD_MASK: usize = WORD_SIZE - 1;

/// Select sampling rate: sample every SELECT_RATE-th occurrence.
const SELECT_RATE: usize = 64;

// ============================================================================
// SuccinctBitVec - Bible-exact struct
// ============================================================================

/// Succinct bit vector with O(1) rank and O(log n) select.
///
/// Exact struct layout per Build Bible §0.2.3.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SuccinctBitVec {
    /// Packed bit data stored as 64-bit words.
    /// Word w holds bits [w*64, w*64+63). Bit i is stored at word i/64, position i%64.
    data: Vec<u64>,

    /// Per-word cumulative 1-count for O(1) rank.
    /// rank_blocks[w] = number of 1-bits in positions [0, w*64).
    /// rank1(i) = rank_blocks[i/64] + popcount(data[i/64] & ((1 << (i%64)) - 1))
    rank_blocks: Vec<u32>,

    /// Sampled positions for 0-bit select.
    /// select_blocks_0[s] = position of the (s * SELECT_RATE + 1)-th 0-bit.
    select_blocks_0: Vec<u32>,

    /// Sampled positions for 1-bit select.
    /// select_blocks_1[s] = position of the (s * SELECT_RATE + 1)-th 1-bit.
    select_blocks_1: Vec<u32>,

    /// Total number of bits in the vector.
    len: usize,
}

impl SuccinctBitVec {
    // ========================================================================
    // Construction
    // ========================================================================

    /// Create an empty SuccinctBitVec.
    pub fn new() -> Self {
        Self {
            data: Vec::new(),
            rank_blocks: Vec::new(),
            select_blocks_0: Vec::new(),
            select_blocks_1: Vec::new(),
            len: 0,
        }
    }

    /// Create from a slice of bools (true = 1, false = 0).
    pub fn from_bits(bits: &[bool]) -> Self {
        if bits.is_empty() {
            return Self::new();
        }
        let len = bits.len();
        let num_words = len.div_ceil(WORD_SIZE);
        let mut data = vec![0u64; num_words];
        for (i, &bit) in bits.iter().enumerate() {
            if bit {
                data[i >> WORD_BITS] |= 1u64 << (i & WORD_MASK);
            }
        }
        let mut sv = Self {
            data,
            rank_blocks: Vec::new(),
            select_blocks_0: Vec::new(),
            select_blocks_1: Vec::new(),
            len,
        };
        sv.rebuild_indices();
        sv
    }

    /// Create from bytes (each byte becomes 8 bits, LSB of byte = position 8*i+7, MSB = position 8*i).
    /// Bit i in the vector = (bytes[i/8] >> (7 - i%8)) & 1.
    pub fn from_bytes(bytes: &[u8]) -> Self {
        if bytes.is_empty() {
            return Self::new();
        }
        let len = bytes.len() * 8;
        let mut bits = vec![false; len];
        for (byte_idx, &byte) in bytes.iter().enumerate() {
            for bit_idx in 0..8 {
                bits[byte_idx * 8 + bit_idx] = (byte >> (7 - bit_idx)) & 1 == 1;
            }
        }
        Self::from_bits(&bits)
    }

    /// Create a bit vector of all zeros.
    pub fn zeros(len: usize) -> Self {
        if len == 0 {
            return Self::new();
        }
        let num_words = len.div_ceil(WORD_SIZE);
        let mut sv = Self {
            data: vec![0u64; num_words],
            rank_blocks: Vec::new(),
            select_blocks_0: Vec::new(),
            select_blocks_1: Vec::new(),
            len,
        };
        sv.rebuild_indices();
        sv
    }

    /// Create a bit vector of all ones.
    pub fn ones(len: usize) -> Self {
        if len == 0 {
            return Self::new();
        }
        let num_words = len.div_ceil(WORD_SIZE);
        let mut data = vec![u64::MAX; num_words];
        // Clear trailing bits beyond len
        let remainder = len & WORD_MASK;
        if remainder != 0 {
            data[num_words - 1] &= (1u64 << remainder) - 1;
        }
        let mut sv = Self {
            data,
            rank_blocks: Vec::new(),
            select_blocks_0: Vec::new(),
            select_blocks_1: Vec::new(),
            len,
        };
        sv.rebuild_indices();
        sv
    }

    // ========================================================================
    // Modification
    // ========================================================================

    /// Append a bit to the end.
    pub fn push(&mut self, bit: bool) {
        let bit_pos = self.len & WORD_MASK;
        if bit_pos == 0 {
            // Need a new word
            self.data.push(if bit { 1u64 } else { 0u64 });
        } else if bit {
            *self.data.last_mut().unwrap() |= 1u64 << bit_pos;
        }
        self.len += 1;
        self.rebuild_indices();
    }

    /// Set the bit at position i.
    ///
    /// # Panics
    /// Panics if i >= len.
    pub fn set(&mut self, i: usize, bit: bool) {
        assert!(i < self.len, "index out of bounds: {i} >= {}", self.len);
        let word_idx = i >> WORD_BITS;
        let bit_pos = i & WORD_MASK;
        let mask = 1u64 << bit_pos;

        let old_bit = (self.data[word_idx] >> bit_pos) & 1 == 1;
        if bit && !old_bit {
            self.data[word_idx] |= mask;
        } else if !bit && old_bit {
            self.data[word_idx] &= !mask;
        }
        // Only rebuild if the bit actually changed
        if bit != old_bit {
            self.rebuild_indices();
        }
    }

    /// Insert a bit at position i, shifting all subsequent bits right.
    ///
    /// # Panics
    /// Panics if i > len.
    pub fn insert_bit(&mut self, i: usize, bit: bool) {
        assert!(i <= self.len, "insert_bit: index {i} > len {}", self.len);

        // Collect all bits, insert new bit, rebuild from scratch.
        // This is O(n) but correct. Production optimization: use a gap buffer or
        // copy-shift within words and rebuild_indices only.
        let mut bits: Vec<bool> = (0..self.len).map(|j| self.access(j)).collect();
        bits.insert(i, bit);
        *self = Self::from_bits(&bits);
    }

    /// Delete the bit at position i, shifting all subsequent bits left.
    ///
    /// # Panics
    /// Panics if i >= len.
    pub fn delete_bit(&mut self, i: usize) -> bool {
        assert!(i < self.len, "delete_bit: index {i} >= len {}", self.len);

        let deleted = self.access(i);

        // Collect bits, remove, rebuild
        let mut bits: Vec<bool> = (0..self.len).map(|j| self.access(j)).collect();
        bits.remove(i);
        *self = Self::from_bits(&bits);

        deleted
    }

    // ========================================================================
    // Queries - O(1) access, O(1) rank, O(log n) select
    // ========================================================================

    /// Get the bit at position i. O(1).
    ///
    /// # Panics
    /// Panics if i >= len.
    #[inline]
    pub fn access(&self, i: usize) -> bool {
        debug_assert!(i < self.len, "access: index {i} >= len {}", self.len);
        (self.data[i >> WORD_BITS] >> (i & WORD_MASK)) & 1 == 1
    }

    /// Count occurrences of bit `b` in positions [0, i). O(1).
    ///
    /// # Panics
    /// Panics if i > len.
    #[inline]
    pub fn rank(&self, i: usize, b: bool) -> u64 {
        debug_assert!(i <= self.len, "rank: index {i} > len {}", self.len);
        if i == 0 {
            return 0;
        }
        let ones = self.rank1(i);
        if b { ones } else { i as u64 - ones }
    }

    /// Count 1-bits in [0, i). O(1): one array lookup + one popcount.
    ///
    /// rank_blocks has a sentinel at the end: rank_blocks[num_words] = total 1-bits.
    /// rank_blocks[w] = number of 1-bits in positions [0, w * 64).
    ///
    /// For i on a word boundary (i % 64 == 0):
    ///   rank1(i) = rank_blocks[i / 64]   (just the sentinel lookup)
    /// For i within a word (i % 64 > 0):
    ///   rank1(i) = rank_blocks[i / 64] + popcount(data[i/64] & mask)
    #[inline]
    fn rank1(&self, i: usize) -> u64 {
        debug_assert!(i <= self.len);
        if i == 0 {
            return 0;
        }

        let word_idx = i >> WORD_BITS;
        let remainder = i & WORD_MASK;

        // rank_blocks[word_idx] = cumulative 1-count in positions [0, word_idx * 64)
        let mut count = if word_idx < self.rank_blocks.len() {
            self.rank_blocks[word_idx] as u64
        } else {
            *self.rank_blocks.last().unwrap_or(&0) as u64
        };

        // Partial word: count 1-bits in positions [word_idx * 64, i)
        if remainder > 0 && word_idx < self.data.len() {
            let mask = (1u64 << remainder) - 1;
            count += (self.data[word_idx] & mask).count_ones() as u64;
        }

        count
    }

    /// Find the position of the k-th occurrence of bit `b`. O(log n).
    /// Returns None if there are fewer than k occurrences.
    ///
    /// Uses sampled positions to narrow the binary search range:
    /// - select_blocks_b[s] = position of the (s * SELECT_RATE + 1)-th occurrence of b
    /// - For the k-th occurrence, sample_idx = (k-1) / SELECT_RATE
    /// - blocks[sample_idx] gives a lower bound (the sampled position)
    /// - blocks[sample_idx + 1] (or len) gives an upper bound
    /// - Binary search within [lo, hi) for position where rank(pos+1, b) == k
    #[inline]
    pub fn select(&self, k: usize, b: bool) -> Option<usize> {
        if k == 0 {
            return None;
        }

        let total = if b { self.count_ones() as usize } else { self.count_zeros() as usize };
        if k > total {
            return None;
        }

        let blocks = if b { &self.select_blocks_1 } else { &self.select_blocks_0 };
        let sample_idx = (k - 1) / SELECT_RATE;

        // Lower bound: the sampled position for this sample_idx.
        // blocks[sample_idx] = position of the (sample_idx * SELECT_RATE + 1)-th occurrence.
        // The k-th occurrence is at or after this position.
        //
        // Upper bound: the next sampled position, or len if past all samples.
        // blocks[sample_idx + 1] = position of the ((sample_idx+1) * SELECT_RATE + 1)-th occurrence.
        // The k-th occurrence is before this position.
        let lo = if sample_idx < blocks.len() {
            blocks[sample_idx] as usize
        } else if !blocks.is_empty() {
            *blocks.last().unwrap() as usize
        } else {
            0
        };

        let hi = if sample_idx + 1 < blocks.len() {
            // The next sample position is a tight upper bound.
            (blocks[sample_idx + 1] as usize + 1).min(self.len)
        } else {
            // No next sample — search to the end of the vector.
            self.len
        };

        // Binary search within [lo, hi) for the first position pos
        // such that rank(pos + 1, b) >= k.
        let mut lo = lo;
        let mut hi = hi;
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.rank(mid + 1, b) as usize >= k {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }

        // Verify: the found position must have rank(pos+1, b) == k
        if lo < self.len && self.rank(lo + 1, b) as usize == k {
            Some(lo)
        } else {
            None
        }
    }

    /// Number of bits.
    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Is the vector empty?
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Total 1-bits.
    #[inline]
    pub fn count_ones(&self) -> u64 {
        if self.len == 0 {
            return 0;
        }
        // rank1(len) = total 1-bits
        self.rank1(self.len)
    }

    /// Total 0-bits.
    #[inline]
    pub fn count_zeros(&self) -> u64 {
        self.len as u64 - self.count_ones()
    }

    // ========================================================================
    // Internal index building
    // ========================================================================

    /// Rebuild all index structures from data. O(n).
    pub(crate) fn rebuild_indices(&mut self) {
        self.build_rank_blocks();
        self.build_select_blocks();
    }

    /// Build rank_blocks: cumulative 1-counts per word.
    ///
    /// rank_blocks[w] = number of 1-bits in positions [0, w * 64).
    /// This is the prefix sum of popcount(data[0], data[1], ..., data[w-1]).
    fn build_rank_blocks(&mut self) {
        let num_words = self.data.len();
        self.rank_blocks.clear();
        self.rank_blocks.reserve(num_words);

        let mut cumulative = 0u32;
        for w in 0..num_words {
            self.rank_blocks.push(cumulative);
            cumulative += self.data[w].count_ones();
        }
        // Sentinel: rank_blocks[num_words] = total 1-bits.
        // This allows rank1(i) to handle i on a word boundary without bounds checking.
        // rank1(len) = rank_blocks[num_words] = total ones.
        self.rank_blocks.push(cumulative);
    }

    /// Build select sampling structures.
    ///
    /// select_blocks_1[s] = position of the (s * SELECT_RATE + 1)-th 1-bit.
    /// select_blocks_0[s] = position of the (s * SELECT_RATE + 1)-th 0-bit.
    fn build_select_blocks(&mut self) {
        self.select_blocks_1.clear();
        self.select_blocks_0.clear();

        let mut ones_seen: usize = 0;
        let mut zeros_seen: usize = 0;
        let mut next_one_sample: usize = 1;
        let mut next_zero_sample: usize = 1;

        // Scan all words
        for (word_idx, &word) in self.data.iter().enumerate() {
            let base_pos = word_idx * WORD_SIZE;
            // Process each bit in the word
            let bits_in_word = if word_idx == self.data.len() - 1 {
                // Last word may have fewer than 64 valid bits
                self.len - base_pos
            } else {
                WORD_SIZE
            };
            for bit_idx in 0..bits_in_word {
                let pos = base_pos + bit_idx;
                let is_one = (word >> bit_idx) & 1 == 1;

                if is_one {
                    ones_seen += 1;
                    if ones_seen == next_one_sample {
                        self.select_blocks_1.push(pos as u32);
                        next_one_sample += SELECT_RATE;
                    }
                } else {
                    zeros_seen += 1;
                    if zeros_seen == next_zero_sample {
                        self.select_blocks_0.push(pos as u32);
                        next_zero_sample += SELECT_RATE;
                    }
                }
            }
        }
    }
}

impl Default for SuccinctBitVec {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    // -----------------------------------------------------------------------
    // Construction and access
    // -----------------------------------------------------------------------

    #[test]
    fn test_empty() {
        let sv = SuccinctBitVec::new();
        assert_eq!(sv.len(), 0);
        assert!(sv.is_empty());
        assert_eq!(sv.count_ones(), 0);
        assert_eq!(sv.count_zeros(), 0);
    }

    #[test]
    fn test_from_bits_basic() {
        let sv = SuccinctBitVec::from_bits(&[true, false, true, true, false]);
        assert_eq!(sv.len(), 5);
        assert!(sv.access(0));
        assert!(!sv.access(1));
        assert!(sv.access(2));
        assert!(sv.access(3));
        assert!(!sv.access(4));
        assert_eq!(sv.count_ones(), 3);
        assert_eq!(sv.count_zeros(), 2);
    }

    #[test]
    fn test_from_bits_all_false() {
        let sv = SuccinctBitVec::from_bits(&[false; 200]);
        assert_eq!(sv.len(), 200);
        assert_eq!(sv.count_ones(), 0);
        assert_eq!(sv.count_zeros(), 200);
        for i in 0..200 {
            assert!(!sv.access(i), "position {i} should be 0");
        }
    }

    #[test]
    fn test_from_bits_all_true() {
        let sv = SuccinctBitVec::from_bits(&[true; 200]);
        assert_eq!(sv.len(), 200);
        assert_eq!(sv.count_ones(), 200);
        assert_eq!(sv.count_zeros(), 0);
        for i in 0..200 {
            assert!(sv.access(i), "position {i} should be 1");
        }
    }

    #[test]
    fn test_from_bytes() {
        // 0b10110001 = 0xB1 = 177, has 4 ones
        let sv = SuccinctBitVec::from_bytes(&[0xB1]);
        assert_eq!(sv.len(), 8);
        assert_eq!(sv.count_ones(), 4);
        // MSB first: 1,0,1,1,0,0,0,1
        assert!(sv.access(0));  // MSB
        assert!(!sv.access(1));
        assert!(sv.access(2));
        assert!(sv.access(3));
        assert!(!sv.access(4));
        assert!(!sv.access(5));
        assert!(!sv.access(6));
        assert!(sv.access(7));  // LSB
    }

    #[test]
    fn test_zeros() {
        let sv = SuccinctBitVec::zeros(128);
        assert_eq!(sv.len(), 128);
        assert_eq!(sv.count_ones(), 0);
        assert_eq!(sv.count_zeros(), 128);
    }

    #[test]
    fn test_ones() {
        let sv = SuccinctBitVec::ones(128);
        assert_eq!(sv.len(), 128);
        assert_eq!(sv.count_ones(), 128);
        assert_eq!(sv.count_zeros(), 0);
        for i in 0..128 {
            assert!(sv.access(i), "position {i} should be 1");
        }
    }

    #[test]
    fn test_ones_non_word_aligned() {
        let sv = SuccinctBitVec::ones(100);
        assert_eq!(sv.len(), 100);
        assert_eq!(sv.count_ones(), 100);
        assert_eq!(sv.count_zeros(), 0);
        // Verify no bits set beyond len
        // The second word should only have bits 0..36 set
        assert_eq!(sv.data[1].count_ones(), 36); // 100 - 64 = 36
    }

    // -----------------------------------------------------------------------
    // Push
    // -----------------------------------------------------------------------

    #[test]
    fn test_push() {
        let mut sv = SuccinctBitVec::new();
        sv.push(true);
        sv.push(false);
        sv.push(true);
        assert_eq!(sv.len(), 3);
        assert!(sv.access(0));
        assert!(!sv.access(1));
        assert!(sv.access(2));
    }

    #[test]
    fn test_push_many() {
        let mut sv = SuccinctBitVec::new();
        for i in 0..200 {
            sv.push(i % 3 == 0);
        }
        assert_eq!(sv.len(), 200);
        // Count: every 3rd position starting from 0
        let expected_ones = (0..200).filter(|i| i % 3 == 0).count();
        assert_eq!(sv.count_ones() as usize, expected_ones);
    }

    // -----------------------------------------------------------------------
    // Set
    // -----------------------------------------------------------------------

    #[test]
    fn test_set_0_to_1() {
        let mut sv = SuccinctBitVec::from_bits(&[false, false, false]);
        sv.set(1, true);
        assert!(!sv.access(0));
        assert!(sv.access(1));
        assert!(!sv.access(2));
        assert_eq!(sv.count_ones(), 1);
    }

    #[test]
    fn test_set_1_to_0() {
        let mut sv = SuccinctBitVec::from_bits(&[true, true, true]);
        sv.set(1, false);
        assert!(sv.access(0));
        assert!(!sv.access(1));
        assert!(sv.access(2));
        assert_eq!(sv.count_ones(), 2);
    }

    #[test]
    fn test_set_no_change() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false]);
        sv.set(0, true); // already true - no rebuild needed
        sv.set(1, false); // already false - no rebuild needed
        assert!(sv.access(0));
        assert!(!sv.access(1));
    }

    // -----------------------------------------------------------------------
    // Insert / Delete
    // -----------------------------------------------------------------------

    #[test]
    fn test_insert_bit_middle() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false, true]);
        sv.insert_bit(1, true);
        assert_eq!(sv.len(), 4);
        assert!(sv.access(0));
        assert!(sv.access(1));
        assert!(!sv.access(2));
        assert!(sv.access(3));
    }

    #[test]
    fn test_insert_bit_beginning() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false]);
        sv.insert_bit(0, false);
        assert_eq!(sv.len(), 3);
        assert!(!sv.access(0));
        assert!(sv.access(1));
        assert!(!sv.access(2));
    }

    #[test]
    fn test_insert_bit_end() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false]);
        sv.insert_bit(2, true);
        assert_eq!(sv.len(), 3);
        assert!(sv.access(0));
        assert!(!sv.access(1));
        assert!(sv.access(2));
    }

    #[test]
    fn test_delete_bit_middle() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false, true, false, true]);
        let deleted = sv.delete_bit(1);
        assert!(!deleted);
        assert_eq!(sv.len(), 4);
        assert!(sv.access(0));
        assert!(sv.access(1)); // was at pos 2
        assert!(!sv.access(2)); // was at pos 3
        assert!(sv.access(3)); // was at pos 4
    }

    #[test]
    fn test_delete_bit_beginning() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false, true]);
        let deleted = sv.delete_bit(0);
        assert!(deleted);
        assert_eq!(sv.len(), 2);
        assert!(!sv.access(0));
        assert!(sv.access(1));
    }

    #[test]
    fn test_delete_bit_end() {
        let mut sv = SuccinctBitVec::from_bits(&[true, false, true]);
        let deleted = sv.delete_bit(2);
        assert!(deleted);
        assert_eq!(sv.len(), 2);
        assert!(sv.access(0));
        assert!(!sv.access(1));
    }

    #[test]
    fn test_insert_delete_roundtrip() {
        let mut sv = SuccinctBitVec::new();
        // Insert 100 ones
        for i in 0..100 {
            sv.insert_bit(i, true);
        }
        assert_eq!(sv.len(), 100);
        assert_eq!(sv.count_ones(), 100);

        // Delete them one by one from the end
        for i in (0..100).rev() {
            let deleted = sv.delete_bit(i);
            assert!(deleted);
        }
        assert_eq!(sv.len(), 0);
    }

    // -----------------------------------------------------------------------
    // Rank - the critical O(1) operation
    // -----------------------------------------------------------------------

    #[test]
    fn test_rank_all_zeros() {
        let sv = SuccinctBitVec::zeros(200);
        for i in 0..=200 {
            assert_eq!(sv.rank(i, false), i as u64, "rank_0({i})");
            assert_eq!(sv.rank(i, true), 0, "rank_1({i})");
        }
    }

    #[test]
    fn test_rank_all_ones() {
        let sv = SuccinctBitVec::ones(200);
        for i in 0..=200 {
            assert_eq!(sv.rank(i, true), i as u64, "rank_1({i})");
            assert_eq!(sv.rank(i, false), 0, "rank_0({i})");
        }
    }

    #[test]
    fn test_rank_alternating() {
        // 10101010...
        let bits: Vec<bool> = (0..200).map(|i| i % 2 == 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);
        for i in 0..=200 {
            assert_eq!(sv.rank(i, true), ((i + 1) / 2) as u64, "rank_1({i})");
            assert_eq!(sv.rank(i, false), (i / 2) as u64, "rank_0({i})");
        }
    }

    #[test]
    fn test_rank_small() {
        // 1 0 1 1 0 0 0 1
        let sv = SuccinctBitVec::from_bits(&[true, false, true, true, false, false, false, true]);
        assert_eq!(sv.rank(0, true), 0);
        assert_eq!(sv.rank(1, true), 1); // [1) has 1 one
        assert_eq!(sv.rank(2, true), 1); // [1,0) still 1
        assert_eq!(sv.rank(3, true), 2); // [1,0,1) = 2
        assert_eq!(sv.rank(4, true), 3); // [1,0,1,1) = 3
        assert_eq!(sv.rank(8, true), 4); // all = 4
        assert_eq!(sv.rank(8, false), 4);
    }

    #[test]
    fn test_rank_cross_word_boundary() {
        // Test rank across 64-bit word boundary
        let mut bits = vec![false; 128];
        for i in 0..64 {
            bits[i] = true; // First word all 1s
        }
        // Second word all 0s
        let sv = SuccinctBitVec::from_bits(&bits);
        assert_eq!(sv.rank(64, true), 64);
        assert_eq!(sv.rank(65, true), 64);
        assert_eq!(sv.rank(128, true), 64);
        assert_eq!(sv.rank(128, false), 64);
    }

    #[test]
    fn test_rank_matches_brute_force() {
        // Random-ish pattern, compare against brute-force
        let bits: Vec<bool> = (0..500).map(|i| (i * 7 + 13) % 5 == 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);
        let mut ones = 0u64;
        let mut zeros = 0u64;
        for i in 0..=bits.len() {
            assert_eq!(sv.rank(i, true), ones, "rank_1({i})");
            assert_eq!(sv.rank(i, false), zeros, "rank_0({i})");
            if i < bits.len() {
                if bits[i] { ones += 1; } else { zeros += 1; }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Select
    // -----------------------------------------------------------------------

    #[test]
    fn test_select_all_zeros() {
        let sv = SuccinctBitVec::zeros(200);
        assert_eq!(sv.select(1, true), None);
        assert_eq!(sv.select(1, false), Some(0));
        assert_eq!(sv.select(50, false), Some(49));
        assert_eq!(sv.select(200, false), Some(199));
        assert_eq!(sv.select(201, false), None);
    }

    #[test]
    fn test_select_all_ones() {
        let sv = SuccinctBitVec::ones(200);
        assert_eq!(sv.select(0, true), None);
        assert_eq!(sv.select(1, true), Some(0));
        assert_eq!(sv.select(50, true), Some(49));
        assert_eq!(sv.select(200, true), Some(199));
        assert_eq!(sv.select(201, true), None);
    }

    #[test]
    fn test_select_alternating() {
        let bits: Vec<bool> = (0..200).map(|i| i % 2 == 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);
        assert_eq!(sv.select(1, true), Some(0));
        assert_eq!(sv.select(2, true), Some(2));
        assert_eq!(sv.select(3, true), Some(4));
        assert_eq!(sv.select(1, false), Some(1));
        assert_eq!(sv.select(2, false), Some(3));
    }

    #[test]
    fn test_select_small() {
        // 1 0 1 1 0 0 0 1
        let sv = SuccinctBitVec::from_bits(&[true, false, true, true, false, false, false, true]);
        assert_eq!(sv.select(1, true), Some(0));
        assert_eq!(sv.select(2, true), Some(2));
        assert_eq!(sv.select(3, true), Some(3));
        assert_eq!(sv.select(4, true), Some(7));
        assert_eq!(sv.select(5, true), None);
        assert_eq!(sv.select(1, false), Some(1));
        assert_eq!(sv.select(2, false), Some(4));
        assert_eq!(sv.select(3, false), Some(5));
        assert_eq!(sv.select(4, false), Some(6));
    }

    #[test]
    fn test_select_roundtrip_with_rank() {
        // select(k, b) should return pos such that rank(pos+1, b) == k
        let bits: Vec<bool> = (0..500).map(|i| (i * 7 + 13) % 5 == 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);
        let ones_count = sv.count_ones() as usize;
        let zeros_count = sv.count_zeros() as usize;

        for k in 1..=ones_count {
            let pos = sv.select(k, true).unwrap();
            assert!(sv.access(pos), "select({k}, true) = {pos} but access is false");
            assert_eq!(sv.rank(pos + 1, true) as usize, k, "rank(select({k}, true)+1) != {k}");
        }
        for k in 1..=zeros_count {
            let pos = sv.select(k, false).unwrap();
            assert!(!sv.access(pos), "select({k}, false) = {pos} but access is true");
            assert_eq!(sv.rank(pos + 1, false) as usize, k, "rank(select({k}, false)+1) != {k}");
        }
    }

    // -----------------------------------------------------------------------
    // Property-based testing (10K iterations per Build Bible)
    // -----------------------------------------------------------------------

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(10_000))]

        #[test]
        fn proptest_rank_access_consistent(bits in proptest::collection::vec(any::<bool>(), 0..1000)) {
            let sv = SuccinctBitVec::from_bits(&bits);
            let mut ones_so_far = 0u64;
            let mut zeros_so_far = 0u64;
            for (i, &bit) in bits.iter().enumerate() {
                prop_assert_eq!(sv.access(i), bit);
                if bit { ones_so_far += 1; } else { zeros_so_far += 1; }
                prop_assert_eq!(sv.rank(i + 1, true), ones_so_far, "rank_1({})", i + 1);
                prop_assert_eq!(sv.rank(i + 1, false), zeros_so_far, "rank_0({})", i + 1);
            }
        }

        #[test]
        fn proptest_select_findable(bits in proptest::collection::vec(any::<bool>(), 1..1000)) {
            let sv = SuccinctBitVec::from_bits(&bits);
            let ones_count = sv.count_ones() as usize;
            let zeros_count = sv.count_zeros() as usize;

            for k in 1..=ones_count.min(200) {
                let pos = sv.select(k, true);
                prop_assert!(pos.is_some(), "select({k}, true) = None");
                let pos = pos.unwrap();
                prop_assert!(sv.access(pos), "select({k}, true) returned non-1 position {pos}");
                prop_assert_eq!(sv.rank(pos + 1, true) as usize, k);
            }

            for k in 1..=zeros_count.min(200) {
                let pos = sv.select(k, false);
                prop_assert!(pos.is_some(), "select({k}, false) = None");
                let pos = pos.unwrap();
                prop_assert!(!sv.access(pos), "select({k}, false) returned non-0 position {pos}");
                prop_assert_eq!(sv.rank(pos + 1, false) as usize, k);
            }
        }

        #[test]
        fn proptest_set_preserves_invariants(bits in proptest::collection::vec(any::<bool>(), 10..200)) {
            let mut sv = SuccinctBitVec::from_bits(&bits);
            // Flip every 7th bit
            for i in (0..sv.len()).step_by(7) {
                sv.set(i, !sv.access(i));
            }
            // Verify count_ones matches brute-force
            let mut count = 0u64;
            for i in 0..sv.len() {
                if sv.access(i) { count += 1; }
            }
            prop_assert_eq!(count, sv.count_ones());
            // Verify rank matches brute-force at endpoints
            prop_assert_eq!(sv.rank(sv.len(), true), sv.count_ones());
            prop_assert_eq!(sv.rank(sv.len(), false), sv.count_zeros());
        }

        #[test]
        fn proptest_insert_delete_roundtrip(bits in proptest::collection::vec(any::<bool>(), 1..100)) {
            let sv = SuccinctBitVec::from_bits(&bits);

            // Test: insert at position 0, delete at position 0 = original preserved
            for ins_pos in 0..=bits.len().min(20) {
                let mut sv2 = sv.clone();
                sv2.insert_bit(ins_pos.min(sv2.len()), true);
                prop_assert_eq!(sv2.len(), bits.len() + 1);
                // Delete at same position we inserted
                sv2.delete_bit(ins_pos.min(sv2.len()));
                prop_assert_eq!(sv2.len(), bits.len());
                for i in 0..bits.len() {
                    prop_assert_eq!(sv2.access(i), bits[i]);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Large-scale test
    // -----------------------------------------------------------------------

    #[test]
    fn test_large_bitvec() {
        let bits: Vec<bool> = (0..1_000_000).map(|i| i % 3 != 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);
        assert_eq!(sv.len(), 1_000_000);

        // Count ones by brute force
        let expected_ones = bits.iter().filter(|&&b| b).count() as u64;
        assert_eq!(sv.count_ones(), expected_ones);

        // Verify rank at key points
        assert_eq!(sv.rank(0, true), 0);
        assert_eq!(sv.rank(1_000_000, true), expected_ones);
        assert_eq!(sv.rank(1_000_000, false), 1_000_000 - expected_ones);

        // Verify rank at word boundaries
        assert_eq!(sv.rank(64, true), bits[0..64].iter().filter(|&&b| b).count() as u64);
        assert_eq!(sv.rank(128, true), bits[0..128].iter().filter(|&&b| b).count() as u64);

        // Verify select
        if expected_ones > 0 {
            let first_one = sv.select(1, true).unwrap();
            assert!(sv.access(first_one));
            assert_eq!(sv.rank(first_one + 1, true), 1);
        }
    }

    // -----------------------------------------------------------------------
    // Serialization roundtrip
    // -----------------------------------------------------------------------

    #[test]
    fn test_serde_roundtrip() {
        let bits: Vec<bool> = (0..200).map(|i| i % 3 == 0).collect();
        let sv = SuccinctBitVec::from_bits(&bits);

        let json = serde_json::to_string(&sv).unwrap();
        let sv2: SuccinctBitVec = serde_json::from_str(&json).unwrap();

        assert_eq!(sv2.len(), sv.len());
        assert_eq!(sv2.count_ones(), sv.count_ones());
        assert_eq!(sv2.count_zeros(), sv.count_zeros());
        for i in 0..sv.len() {
            assert_eq!(sv2.access(i), sv.access(i), "bit {i} mismatch after serde");
        }
        // Verify rank/select work on deserialized
        for i in (0..sv.len()).step_by(13) {
            assert_eq!(sv2.rank(i, true), sv.rank(i, true));
            assert_eq!(sv2.rank(i, false), sv.rank(i, false));
        }
    }
}