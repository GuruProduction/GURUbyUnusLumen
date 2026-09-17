//! WaveletTree — Static integer sequence with rank/select/access in O(log σ).
//!
//! A wavelet tree stores a sequence of integers over alphabet [0, σ)
//! and supports rank, select, and access operations in O(log σ) time.
//! Each node stores a SuccinctBitVec representing the bit at that level.
//!
//! This is the static version — no insertions or deletions.
//! The dynamic version (DynamicWaveletTree) builds on top of this.

use crate::succinct::SuccinctBitVec;
use serde::{Deserialize, Serialize};

/// A static wavelet tree over an integer sequence.
///
/// Supports rank, select, and access in O(log σ) time where σ is the alphabet size.
/// Space: O(n log σ) bits for a sequence of n symbols over alphabet σ.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WaveletTree {
    /// The alphabet size (must be a power of 2 for balanced tree).
    pub alphabet_size: usize,
    /// Number of bits needed to represent values in [0, alphabet_size).
    pub bits_per_symbol: usize,
    /// Bit vectors for each level of the tree.
    /// Level 0 is the root, level bits_per_symbol-1 is the deepest.
    levels: Vec<SuccinctBitVec>,
    /// Length of the original sequence.
    len: usize,
}

impl WaveletTree {
    // ========================================================================
    // Construction
    // ========================================================================

    /// Build a WaveletTree from a sequence of integers.
    ///
    /// The alphabet size is inferred as max(values) + 1, rounded up to
    /// the next power of 2.
    pub fn from_values(values: &[usize]) -> Self {
        if values.is_empty() {
            return Self {
                alphabet_size: 2,
                bits_per_symbol: 1,
                levels: vec![SuccinctBitVec::zeros(0)],
                len: 0,
            };
        }

        let max_val = *values.iter().max().unwrap_or(&0);
        let alphabet_size = (max_val + 1).next_power_of_two().max(2);
        let _bits_per_symbol = (alphabet_size - 1).count_ones() as usize + 1;
        // Actually: bits needed = ceil(log2(alphabet_size))
        let bits_per_symbol = if alphabet_size.is_power_of_two() && alphabet_size > 1 {
            (alphabet_size - 1).ilog2() as usize + 1
        } else {
            alphabet_size.ilog2() as usize + 1
        };

        let n = values.len();
        let mut levels = Vec::with_capacity(bits_per_symbol);

        // Build level by level
        let mut current = values.to_vec();
        for level in 0..bits_per_symbol {
            let bit_pos = bits_per_symbol - 1 - level;
            let mut level_bits = SuccinctBitVec::new();
            let mut zeros = Vec::new();
            let mut ones = Vec::new();

            for &val in &current {
                let bit = (val >> bit_pos) & 1 == 1;
                level_bits.push(bit);
                if bit {
                    ones.push(val);
                } else {
                    zeros.push(val);
                }
            }
            level_bits.rebuild_indices();
            levels.push(level_bits);

            // Prepare for next level: interleave zeros and ones
            current = zeros;
            current.extend(ones);
        }

        Self {
            alphabet_size,
            bits_per_symbol,
            levels,
            len: n,
        }
    }

    /// Create a WaveletTree with a known alphabet size.
    pub fn with_alphabet(values: &[usize], alphabet_size: usize) -> Self {
        if values.is_empty() {
            let bps = if alphabet_size <= 1 { 1 } else { (alphabet_size - 1).ilog2() as usize + 1 };
            return Self {
                alphabet_size: alphabet_size.max(2),
                bits_per_symbol: bps,
                levels: vec![SuccinctBitVec::zeros(0); bps],
                len: 0,
            };
        }
        // For now, just use from_values
        let mut wt = Self::from_values(values);
        wt.alphabet_size = alphabet_size.max(wt.alphabet_size);
        wt
    }

    // ========================================================================
    // Queries
    // ========================================================================

    /// Access the value at position i.
    ///
    /// Time: O(log σ)
    pub fn access(&self, i: usize) -> Option<usize> {
        if i >= self.len {
            return None;
        }

        let mut value = 0usize;
        let mut pos = i;

        for level in 0..self.bits_per_symbol {
            let bit = self.levels[level].access(pos);
            let bit_val = if bit { 1usize } else { 0usize };
            value = (value << 1) | bit_val;

            // Navigate to the next level
            let rank = self.levels[level].rank(pos, bit) as usize;
            if bit {
                // Go to the right child: position is rank_1 + zeros_count
                let zeros = self.levels[level].rank(self.levels[level].len(), false) as usize;
                pos = zeros + rank;
            } else {
                // Go to the left child: position is rank_0
                pos = rank;
            }
        }

        Some(value)
    }

    /// Count the number of occurrences of value `v` in positions [0, i).
    ///
    /// Time: O(log σ)
    pub fn rank(&self, value: usize, i: usize) -> Option<usize> {
        if i > self.len || value >= self.alphabet_size {
            return None;
        }
        if i == 0 {
            return Some(0);
        }

        // Standard wavelet tree rank algorithm (level-based).
        // Navigate from root to leaf, tracking position at each level.
        //
        // At each level, the bit vector stores bits for elements at that level.
        // When navigating to the right child (bit=1), we add zeros_total offset.
        // The final rank is computed relative to the partition at the leaf level.
        //
        // We track: pos (position in the current level) and partition_start
        // (where the current partition begins in the current level).
        let mut pos = i;
        let mut partition_start = 0usize;

        for level in 0..self.bits_per_symbol {
            let bit_pos = self.bits_per_symbol - 1 - level;
            let bit = (value >> bit_pos) & 1 == 1;

            if level == self.bits_per_symbol - 1 {
                // Leaf level: rank is the count of matching bits in our partition
                let r = self.levels[level].rank(pos, bit) as usize;
                let partition_offset = self.levels[level].rank(partition_start, bit) as usize;
                return Some(r - partition_offset);
            }

            if bit {
                // Right child: offset by total zeros at this level
                let zeros_total = self.levels[level].rank(self.levels[level].len(), false) as usize;
                let ones_before = self.levels[level].rank(pos, true) as usize;
                let partition_ones_start = self.levels[level].rank(partition_start, true) as usize;
                partition_start = zeros_total + partition_ones_start;
                pos = zeros_total + ones_before;
            } else {
                // Left child: partition is at the beginning
                let zeros_before = self.levels[level].rank(pos, false) as usize;
                let partition_zeros_start = self.levels[level].rank(partition_start, false) as usize;
                partition_start = partition_zeros_start;
                pos = zeros_before;
            }
        }

        // Unreachable for bits_per_symbol >= 1
        Some(pos)
    }

    /// Find the position of the k-th occurrence of value `v`.
    ///
    /// Time: O(log² n) due to select on SuccinctBitVec
    pub fn select(&self, value: usize, k: usize) -> Option<usize> {
        if k == 0 || value >= self.alphabet_size {
            return None;
        }

        // Start from the bottom level and work up
        let total = self.rank(value, self.len)?;
        if k > total {
            return None;
        }

        // Navigate from bottom level upward
        let mut pos = k; // k-th occurrence in the bottom-level partition

        for level in (0..self.bits_per_symbol).rev() {
            let bit_pos = self.bits_per_symbol - 1 - level;
            let bit = (value >> bit_pos) & 1 == 1;

            // Convert position in child partition to position in parent level
            let sel = self.levels[level].select(pos, bit)?;
            pos = sel + 1; // select is 0-indexed
        }

        if pos > 0 && (pos - 1) < self.len {
            Some(pos - 1)
        } else {
            None
        }
    }

    /// Number of symbols in the sequence.
    pub fn len(&self) -> usize {
        self.len
    }

    /// Is the sequence empty?
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// The alphabet size.
    pub fn alphabet_size(&self) -> usize {
        self.alphabet_size
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_empty_wavelet_tree() {
        let wt = WaveletTree::from_values(&[]);
        assert!(wt.is_empty());
        assert_eq!(wt.len(), 0);
    }

    #[test]
    fn test_single_element() {
        let wt = WaveletTree::from_values(&[5]);
        assert_eq!(wt.len(), 1);
        assert_eq!(wt.access(0), Some(5));
        assert_eq!(wt.rank(5, 1), Some(1));
    }

    #[test]
    fn test_binary_values() {
        let wt = WaveletTree::from_values(&[0, 1, 0, 1, 1, 0]);
        assert_eq!(wt.len(), 6);
        assert_eq!(wt.access(0), Some(0));
        assert_eq!(wt.access(1), Some(1));
        assert_eq!(wt.access(2), Some(0));
        assert_eq!(wt.access(3), Some(1));
        assert_eq!(wt.access(4), Some(1));
        assert_eq!(wt.access(5), Some(0));
    }

    #[test]
    fn test_rank_binary() {
        let wt = WaveletTree::from_values(&[0, 1, 0, 1, 1, 0]);
        // rank(0, i) = number of 0s before position i
        assert_eq!(wt.rank(0, 0), Some(0));
        assert_eq!(wt.rank(0, 1), Some(1));
        assert_eq!(wt.rank(0, 2), Some(1));
        assert_eq!(wt.rank(0, 3), Some(2));
        assert_eq!(wt.rank(0, 6), Some(3));
        // rank(1, i) = number of 1s before position i
        assert_eq!(wt.rank(1, 0), Some(0));
        assert_eq!(wt.rank(1, 1), Some(0));
        assert_eq!(wt.rank(1, 2), Some(1));
        assert_eq!(wt.rank(1, 6), Some(3));
    }

    #[test]
    fn test_larger_alphabet() {
        // Values: 0, 3, 1, 2, 3, 0, 1 (alphabet size 4)
        let wt = WaveletTree::from_values(&[0, 3, 1, 2, 3, 0, 1]);
        for (i, &v) in [0, 3, 1, 2, 3, 0, 1].iter().enumerate() {
            assert_eq!(wt.access(i), Some(v), "access({}) should be {}", i, v);
        }
    }

    #[test]
    fn test_rank_larger_alphabet() {
        let wt = WaveletTree::from_values(&[0, 3, 1, 2, 3, 0, 1]);
        assert_eq!(wt.rank(0, 7), Some(2)); // Two 0s total
        assert_eq!(wt.rank(3, 7), Some(2)); // Two 3s total
        assert_eq!(wt.rank(1, 7), Some(2)); // Two 1s total
        assert_eq!(wt.rank(2, 7), Some(1)); // One 2 total
    }

    #[test]
    fn test_all_same_value() {
        let wt = WaveletTree::from_values(&[5, 5, 5, 5, 5]);
        for i in 0..5 {
            assert_eq!(wt.access(i), Some(5));
        }
        assert_eq!(wt.rank(5, 5), Some(5));
    }

    #[test]
    fn test_sequential_values() {
        let values: Vec<usize> = (0..100).collect();
        let wt = WaveletTree::from_values(&values);
        for i in 0..100 {
            assert_eq!(wt.access(i), Some(i));
        }
    }

    #[test]
    fn test_repeated_pattern() {
        // Pattern: 0, 1, 2, 0, 1, 2, 0, 1, 2
        let values = [0, 1, 2, 0, 1, 2, 0, 1, 2];
        let wt = WaveletTree::from_values(&values);
        for (i, &v) in values.iter().enumerate() {
            assert_eq!(wt.access(i), Some(v), "access({}) should be {}", i, v);
        }
        assert_eq!(wt.rank(0, 9), Some(3));
        assert_eq!(wt.rank(1, 9), Some(3));
        assert_eq!(wt.rank(2, 9), Some(3));
    }
}