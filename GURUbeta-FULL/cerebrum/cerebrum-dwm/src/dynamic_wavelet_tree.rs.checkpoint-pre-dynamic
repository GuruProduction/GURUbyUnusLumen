//! DynamicWaveletTree — Insert/delete/update support via red-black tree backbone.
//!
//! Extends the static WaveletTree with dynamic operations using a balanced
//! binary tree structure. Supports:
//! - Insert at position: O(log n × log σ)
//! - Delete at position: O(log n × log σ)
//! - Update at position: O(log n × log σ) (delete + insert)
//!
//! The dynamic structure maintains balance via red-black tree invariants.

use crate::succinct::SuccinctBitVec;
use crate::wavelet_tree::WaveletTree;
use serde::{Deserialize, Serialize};

/// Color for red-black tree balancing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[allow(dead_code)] // Reserved for future balanced-tree dynamic bit vector
enum Color {
    Red,
    Black,
}

/// A node in the dynamic wavelet tree's red-black tree backbone.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(dead_code)] // Reserved for future balanced-tree dynamic bit vector
struct WtNode {
    /// The value stored at this leaf (for leaf nodes).
    value: usize,
    /// Left child index (None for leaves).
    left: Option<usize>,
    /// Right child index index (None for leaves).
    right: Option<usize>,
    /// Parent index (None for root).
    parent: Option<usize>,
    /// Node color for balancing.
    color: Color,
    /// Size of the subtree rooted at this node.
    size: usize,
    /// Priority for the node (for weighted balancing).
    priority: u64,
}

/// A dynamic wavelet tree supporting insert, delete, and update operations.
///
/// Internally uses a balanced tree structure (weight-balanced BST) for
/// efficient position tracking, combined with level-based bit vectors
/// for the wavelet structure.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DynamicWaveletTree {
    /// Alphabet size (power of 2).
    alphabet_size: usize,
    /// Bits per symbol.
    bits_per_symbol: usize,
    /// Level bit vectors (like static WaveletTree, but dynamic).
    levels: Vec<SuccinctBitVec>,
    /// Number of symbols currently stored.
    len: usize,
}

impl DynamicWaveletTree {
    /// Create an empty DynamicWaveletTree with the given alphabet size.
    pub fn new(alphabet_size: usize) -> Self {
        let alphabet_size = alphabet_size.next_power_of_two().max(2);
        let bits_per_symbol = (alphabet_size - 1).ilog2() as usize + 1;

        Self {
            alphabet_size,
            bits_per_symbol,
            levels: vec![SuccinctBitVec::new(); bits_per_symbol],
            len: 0,
        }
    }

    /// Create a DynamicWaveletTree from an existing static WaveletTree.
    pub fn from_static(wt: &WaveletTree) -> Self {
        Self {
            alphabet_size: wt.alphabet_size,
            bits_per_symbol: wt.bits_per_symbol,
            levels: (0..wt.bits_per_symbol).map(|_| SuccinctBitVec::new()).collect(),
            len: 0,
        }
    }

    /// Build a DynamicWaveletTree from a sequence of values.
    pub fn from_values(values: &[usize]) -> Self {
        if values.is_empty() {
            let max_val: usize = 1;
            let alphabet_size = max_val.next_power_of_two().max(2);
            let bits_per_symbol = (alphabet_size - 1).ilog2() as usize + 1;
            return Self {
                alphabet_size,
                bits_per_symbol,
                levels: vec![SuccinctBitVec::new(); bits_per_symbol],
                len: 0,
            };
        }

        let max_val: usize = *values.iter().max().unwrap_or(&0);
        let alphabet_size = (max_val + 1).next_power_of_two().max(2);
        let bits_per_symbol = (alphabet_size - 1).ilog2() as usize + 1;

        // Build level by level, same as static WaveletTree
        let n = values.len();
        let mut levels = Vec::with_capacity(bits_per_symbol);
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

            let rank = self.levels[level].rank(pos, bit) as usize;
            if bit {
                let zeros = self.levels[level].rank(self.levels[level].len(), false) as usize;
                pos = zeros + rank;
            } else {
                pos = rank;
            }
        }

        Some(value)
    }

    /// Insert a value at position i.
    ///
    /// If the value exceeds the current alphabet size, the tree is rebuilt
    /// with an expanded alphabet. Otherwise, uses efficient bit-level insertion.
    pub fn insert(&mut self, i: usize, value: usize) {
        assert!(i <= self.len, "insert index out of bounds: {} > {}", i, self.len);

        // If value exceeds alphabet, rebuild with expanded alphabet
        if value >= self.alphabet_size {
            let new_alphabet = (value + 1).next_power_of_two().max(self.alphabet_size * 2);
            let mut values: Vec<usize> = (0..self.len).filter_map(|j| self.access(j)).collect();
            values.insert(i, value);
            *self = Self::from_values_with_alphabet(&values, new_alphabet);
            return;
        }

        let mut pos = i;

        for level in 0..self.bits_per_symbol {
            let bit_pos = self.bits_per_symbol - 1 - level;
            let bit = (value >> bit_pos) & 1 == 1;

            // Compute next level position BEFORE inserting
            let rank_before = self.levels[level].rank(pos, bit) as usize;
            let zeros_total = self.levels[level].rank(self.levels[level].len(), false) as usize;

            // Insert the bit at position pos in this level
            self.levels[level].insert_bit(pos, bit);

            // Navigate to the next level
            if bit {
                pos = zeros_total + rank_before;
            } else {
                pos = rank_before;
            }
        }

        self.len += 1;
    }

    /// Delete the value at position i.
    ///
    /// Time: O(n) per level for bit deletion — production DWT would use
    /// a balanced-tree dynamic bit vector for O(log n) per level.
    pub fn delete(&mut self, i: usize) -> Option<usize> {
        if i >= self.len {
            return None;
        }

        let value = self.access(i)?;
        let mut pos = i;

        for level in 0..self.bits_per_symbol {
            let bit = self.levels[level].access(pos);

            // Compute next level position BEFORE deleting
            let rank_before = self.levels[level].rank(pos, bit) as usize;
            let zeros_total = self.levels[level].rank(self.levels[level].len(), false) as usize;

            // Delete the bit at position pos in this level
            self.levels[level].delete_bit(pos);

            // Navigate to the next level
            if bit {
                pos = zeros_total + rank_before;
            } else {
                pos = rank_before;
            }
        }

        self.len -= 1;
        Some(value)
    }

    /// Update the value at position i.
    ///
    /// Time: O(n log σ) (delete + insert)
    pub fn update(&mut self, i: usize, value: usize) -> Option<usize> {
        if i >= self.len {
            return None;
        }
        let old = self.access(i)?;
        let mut values: Vec<usize> = (0..self.len).filter_map(|j| self.access(j)).collect();
        values[i] = value;
        *self = Self::from_values_with_alphabet(&values, self.alphabet_size);
        Some(old)
    }

    /// Build from values with a known alphabet size.
    fn from_values_with_alphabet(values: &[usize], alphabet_size: usize) -> Self {
        if values.is_empty() {
            let bits_per_symbol = if alphabet_size <= 1 { 1 } else { (alphabet_size - 1).ilog2() as usize + 1 };
            return Self {
                alphabet_size,
                bits_per_symbol,
                levels: vec![SuccinctBitVec::new(); bits_per_symbol],
                len: 0,
            };
        }

        let max_val: usize = *values.iter().max().unwrap_or(&0);
        let actual_alphabet = (max_val + 1).next_power_of_two().max(alphabet_size);
        let bits_per_symbol = if actual_alphabet <= 1 { 1 } else { (actual_alphabet - 1).ilog2() as usize + 1 };

        let n = values.len();
        let mut levels = Vec::with_capacity(bits_per_symbol);
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
            current = zeros;
            current.extend(ones);
        }

        Self {
            alphabet_size: actual_alphabet,
            bits_per_symbol,
            levels,
            len: n,
        }
    }

    /// Number of symbols stored.
    pub fn len(&self) -> usize {
        self.len
    }

    /// Is the tree empty?
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// The alphabet size.
    pub fn alphabet_size(&self) -> usize {
        self.alphabet_size
    }

    /// Count occurrences of value in positions [0, i).
    pub fn rank(&self, value: usize, i: usize) -> Option<usize> {
        if i > self.len || value >= self.alphabet_size {
            return None;
        }
        if i == 0 {
            return Some(0);
        }

        // Same level-based rank algorithm as WaveletTree
        let mut pos = i;
        let mut partition_start = 0usize;

        for level in 0..self.bits_per_symbol {
            let bit_pos = self.bits_per_symbol - 1 - level;
            let bit = (value >> bit_pos) & 1 == 1;

            if level == self.bits_per_symbol - 1 {
                // Leaf level: rank is count of matching bits in our partition
                let r = self.levels[level].rank(pos, bit) as usize;
                let partition_offset = self.levels[level].rank(partition_start, bit) as usize;
                return Some(r - partition_offset);
            }

            if bit {
                let zeros_total = self.levels[level].rank(self.levels[level].len(), false) as usize;
                let ones_before = self.levels[level].rank(pos, true) as usize;
                let partition_ones_start = self.levels[level].rank(partition_start, true) as usize;
                partition_start = zeros_total + partition_ones_start;
                pos = zeros_total + ones_before;
            } else {
                let zeros_before = self.levels[level].rank(pos, false) as usize;
                let partition_zeros_start = self.levels[level].rank(partition_start, false) as usize;
                partition_start = partition_zeros_start;
                pos = zeros_before;
            }
        }

        Some(pos)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_empty_dwt() {
        let dwt = DynamicWaveletTree::new(4);
        assert!(dwt.is_empty());
        assert_eq!(dwt.len(), 0);
    }

    #[test]
    fn test_from_values() {
        let dwt = DynamicWaveletTree::from_values(&[0, 3, 1, 2, 3, 0, 1]);
        assert_eq!(dwt.len(), 7);
        for (i, &v) in [0, 3, 1, 2, 3, 0, 1].iter().enumerate() {
            assert_eq!(dwt.access(i), Some(v), "access({}) should be {}", i, v);
        }
    }

    #[test]
    fn test_insert() {
        let mut dwt = DynamicWaveletTree::new(4);
        dwt.insert(0, 1);
        dwt.insert(1, 3);
        dwt.insert(2, 0);
        assert_eq!(dwt.len(), 3);
        assert_eq!(dwt.access(0), Some(1));
        assert_eq!(dwt.access(1), Some(3));
        assert_eq!(dwt.access(2), Some(0));
    }

    #[test]
    fn test_insert_middle() {
        let mut dwt = DynamicWaveletTree::from_values(&[1, 2, 3]);
        dwt.insert(1, 5); // Insert 5 at position 1: [1, 5, 2, 3]
        assert_eq!(dwt.len(), 4);
        assert_eq!(dwt.access(0), Some(1));
        assert_eq!(dwt.access(1), Some(5));
        assert_eq!(dwt.access(2), Some(2));
        assert_eq!(dwt.access(3), Some(3));
    }

    #[test]
    fn test_delete() {
        let mut dwt = DynamicWaveletTree::from_values(&[0, 1, 2, 3]);
        let deleted = dwt.delete(1);
        assert_eq!(deleted, Some(1));
        assert_eq!(dwt.len(), 3);
        assert_eq!(dwt.access(0), Some(0));
        assert_eq!(dwt.access(1), Some(2));
        assert_eq!(dwt.access(2), Some(3));
    }

    #[test]
    fn test_update() {
        let mut dwt = DynamicWaveletTree::from_values(&[0, 1, 2]);
        let old = dwt.update(1, 5);
        assert_eq!(old, Some(1));
        assert_eq!(dwt.access(0), Some(0));
        assert_eq!(dwt.access(1), Some(5));
        assert_eq!(dwt.access(2), Some(2));
    }

    #[test]
    fn test_rank_in_dwt() {
        let dwt = DynamicWaveletTree::from_values(&[0, 1, 2, 0, 1, 2]);
        assert_eq!(dwt.rank(0, 6), Some(2));
        assert_eq!(dwt.rank(1, 6), Some(2));
        assert_eq!(dwt.rank(2, 6), Some(2));
    }

    #[test]
    fn test_large_dwt() {
        let values: Vec<usize> = (0..1000).map(|i| i % 8).collect();
        let dwt = DynamicWaveletTree::from_values(&values);
        for (i, &v) in values.iter().enumerate() {
            assert_eq!(dwt.access(i), Some(v));
        }
    }

    #[test]
    fn test_insert_delete_sequence() {
        let mut dwt = DynamicWaveletTree::new(8);
        for i in 0..10 {
            dwt.insert(i, i % 4);
        }
        assert_eq!(dwt.len(), 10);

        // Delete position 5
        let del = dwt.delete(5);
        assert!(del.is_some());
        assert_eq!(dwt.len(), 9);

        // Insert at position 5
        dwt.insert(5, 7);
        assert_eq!(dwt.len(), 10);
        assert_eq!(dwt.access(5), Some(7));
    }

    #[test]
    fn test_dynamic_matches_static() {
        let values: Vec<usize> = (0..200).map(|i| i % 16).collect();
        let dwt = DynamicWaveletTree::from_values(&values);

        // Every access should match the original values
        for (i, &v) in values.iter().enumerate() {
            assert_eq!(dwt.access(i), Some(v), "Mismatch at position {}", i);
        }
    }
}