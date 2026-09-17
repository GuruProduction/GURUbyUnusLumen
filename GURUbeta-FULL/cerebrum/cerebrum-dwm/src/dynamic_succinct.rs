//! DynamicSuccinctBitVec — Dynamic bit vector with O(log n) insert/delete and O(1)-ish rank.
//!
//! The static `SuccinctBitVec` from `succinct.rs` has O(n) insert/delete because every
//! mutation triggers a full collect-and-rebuild. This is catastrophic for the
//! DynamicWaveletMatrix, which calls insert_bit on every level per token insertion —
//! turning what should be O(log n × log σ) into O(n²).
//!
//! This module provides a block-based, Fenwick-tree-indexed bit vector that supports
//! truly dynamic operations. It is NOT a replacement for SuccinctBitVec (that stays
//! for static use cases). It IS the insertion-capable bit vector the
//! DynamicWaveletMatrix will migrate to.
//!
//! # Data Structure
//!
//! ```text
//! DynamicSuccinctBitVec {
//!     blocks: Vec<Block>,
//!     fenwick: FenwickTree,           // prefix sum over block ones counts
//!     block_length_prefix: Vec<u32>,  // prefix sum over block lengths
//!     num_bits: usize,
//! }
//!
//! Block {
//!     data: [u64; 8],  // 512 bits = 8 × u64
//!     len: u16,        // valid bits (≤ 512)
//!     ones: u16,       // popcount of valid bits
//! }
//! ```
//!
//! # Complexity (n = total bits, B = n / BLOCK_SIZE blocks)
//!
//! | Operation | Complexity | Notes |
//! |-----------|-----------|-------|
//! | access(i) | O(log B) | Binary search on block-length prefix sums |
//! | rank(i, b) | O(log B) | Fenwick prefix sum + local popcount |
//! | select(k, 1) | O(log B + BLOCK_SIZE) | Fenwick lower-bound + local scan |
//! | select(k, 0) | O(log n × log B) | Binary search on rank |
//! | insert_bit(i, bit) | O(BLOCK_SIZE + log B) | Shift + split + fenwick update |
//! | delete_bit(i) | O(BLOCK_SIZE + log B) | Shift + merge + fenwick update |
//! | push(bit) | O(1) amortized | Append to last block, occasional split |
//! | set(i, bit) | O(log B) | Fenwick update if ones count changes |
//!
//! For n = 100K, B ≈ 196, log B ≈ 8. Insert is ~512 word ops + 8 fenwick ops,
//! roughly 500× faster than the O(n) rebuild of SuccinctBitVec.

use serde::{Deserialize, Serialize};

// ============================================================================
// Constants
// ============================================================================

/// Bits per block. Must be a multiple of 64 (word size).
/// 512 = 8 u64 words = one cache line.
const BLOCK_SIZE: usize = 512;

/// Number of u64 words per block.
const WORDS_PER_BLOCK: usize = BLOCK_SIZE / 64; // 8

/// log2(64) — shift amount for word index.
const WORD_BITS: usize = 6;

/// Mask for bit position within a word.
const WORD_MASK: usize = 63;

// ============================================================================
// Fenwick Tree (Binary Indexed Tree)
// ============================================================================

/// Fenwick tree over block one-counts for O(log B) prefix sum queries and updates.
///
/// Stores cumulative sums. Index 0 is a dummy sentinel; real data starts at index 1.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct FenwickTree {
    tree: Vec<u32>,
}

impl FenwickTree {
    /// Create an empty fenwick tree.
    fn new() -> Self {
        FenwickTree { tree: vec![0] }
    }

    /// Build from a slice of block one-counts. O(B).
    fn from_values(values: &[u32]) -> Self {
        let n = values.len();
        let mut tree = vec![0u32; n + 1];

        // Copy into tree at positions 1..n
        tree[1..n + 1].copy_from_slice(values);

        // Propagate each i to i + lsb(i)
        for i in 1..=n {
            let j = i + lsb(i);
            if j <= n {
                let v = tree[i];
                tree[j] = tree[j].wrapping_add(v);
            }
        }
        FenwickTree { tree }
    }

    /// Number of elements (excluding sentinel).
    fn len(&self) -> usize {
        self.tree.len().saturating_sub(1)
    }

    /// Prefix sum of first `idx` elements: sum of values[0..idx].
    #[inline]
    fn prefix_sum(&self, mut idx: usize) -> u32 {
        debug_assert!(
            idx <= self.len(),
            "prefix_sum: idx {idx} > len {}",
            self.len()
        );
        let mut sum: u32 = 0;
        while idx > 0 {
            sum = sum.wrapping_add(self.tree[idx]);
            idx &= idx.wrapping_sub(1);
        }
        sum
    }

    /// Get the value at index `idx` (0-based).
    #[allow(dead_code)]
    #[inline]
    fn get(&self, idx: usize) -> u32 {
        debug_assert!(idx < self.len(), "get: idx {idx} >= len {}", self.len());
        self.prefix_sum(idx + 1) - self.prefix_sum(idx)
    }

    /// Add `delta` to the value at index `idx` (0-based). O(log B).
    fn add(&mut self, mut idx: usize, delta: i32) {
        debug_assert!(idx < self.len(), "add: idx {idx} >= len {}", self.len());
        idx += 1;
        let n = self.tree.len();
        while idx < n {
            self.tree[idx] = self.tree[idx].wrapping_add(delta as u32);
            idx += lsb(idx);
        }
    }

    /// Append a new value to the end. O(log B).
    fn push(&mut self, value: u32) {
        let idx = self.len(); // 0-based insertion index
        self.tree.push(0); // extend with placeholder

        let i = idx + 1; // 1-based
        // The interval (i - lsb(i), i] contains elements that are already stored
        // in ancestors. We need: tree[i] = value + sum of already-stored values in range.
        // The already-stored values in the range are:
        //   prefix_sum(idx) - prefix_sum(i - lsb(i))
        let start = i.wrapping_sub(lsb(i));
        let existing = self.prefix_sum(idx).wrapping_sub(self.prefix_sum(start));
        self.tree[i] = existing.wrapping_add(value);

        // Propagate value to ancestors: i + lsb(i), (i+lsb(i)) + lsb(i+lsb(i)), ...
        let mut cur = i;
        let n = self.tree.len();
        loop {
            let next = cur + lsb(cur);
            if next >= n {
                break;
            }
            self.tree[next] = self.tree[next].wrapping_add(value);
            cur = next;
        }
    }

    /// Rebuild the entire tree from new values.
    fn rebuild(&mut self, values: &[u32]) {
        *self = FenwickTree::from_values(values);
    }

    /// Search for the smallest 1-based index where prefix_sum(idx) >= target.
    /// Returns (idx, prefix_sum(idx)). idx ∈ 1..=len.
    ///
    /// If target exceeds total sum, returns (len + 1, total_sum).
    #[inline]
    fn lower_bound(&self, target: u32) -> (usize, u32) {
        let n = self.len();
        let total = self.prefix_sum(n);

        // Shortcut if target bigger than everything
        if target > total {
            return (n + 1, total);
        }

        let mut idx = 0usize;
        let mut prefix: u32 = 0;

        // Find largest power of two <= n
        let mut step = 1usize << (usize::BITS - n.leading_zeros() - 1);

        while step > 0 {
            let next = idx + step;
            if next <= n {
                let candidate = prefix.wrapping_add(self.tree[next]);
                if candidate < target {
                    idx = next;
                    prefix = candidate;
                }
            }
            step >>= 1;
        }

        // idx = largest with prefix_sum < target
        // so idx + 1 = first with prefix_sum >= target
        let answer = idx + 1;
        (answer, self.prefix_sum(answer))
    }
}

/// Isolate least significant set bit.
#[inline(always)]
const fn lsb(x: usize) -> usize {
    x & x.wrapping_neg()
}

// ============================================================================
// Block
// ============================================================================

/// A fixed-size block of bits — 512 bits = 8 u64 words.
///
/// Each block tracks its own length, ones count, and per-word prefix sums
/// for O(1) local rank1 queries. This is critical for DWM search performance —
/// the old loop-based rank1 was O(words) and dominated query time at scale.
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
struct Block {
    /// Packed bit data. Unused bits (beyond `len`) are always zero.
    data: [u64; WORDS_PER_BLOCK],

    /// Per-word prefix sum: word_ones[w] = number of 1-bits in data[0..w].
    /// Maintained on every mutation so rank1() is O(1).
    word_ones: [u16; WORDS_PER_BLOCK],

    /// Number of valid bits in this block, 0..BLOCK_SIZE.
    len: u16,

    /// Number of 1-bits among the valid bits.
    ones: u16,
}

impl Block {
    /// Create an empty block with all bits zeroed.
    fn new() -> Self {
        Block {
            data: [0u64; WORDS_PER_BLOCK],
            word_ones: [0u16; WORDS_PER_BLOCK],
            len: 0,
            ones: 0,
        }
    }

    /// Rebuild per-word prefix sums from scratch. O(WORDS_PER_BLOCK).
    /// Call after any mutation that changes word data.
    #[inline]
    fn rebuild_word_ones(&mut self) {
        let mut running: u16 = 0;
        for w in 0..WORDS_PER_BLOCK {
            self.word_ones[w] = running;
            running += self.data[w].count_ones() as u16;
        }
    }

    /// Create a block from a slice of bools. Asserts slice.len() ≤ BLOCK_SIZE.
    /// Builds word_ones prefix sums in one pass at the end — O(BLOCK_SIZE).
    fn from_bits(bits: &[bool]) -> Self {
        debug_assert!(bits.len() <= BLOCK_SIZE);
        let mut block = Block::new();
        for &bit in bits {
            let pos = block.len as usize;
            if bit {
                block.data[pos >> WORD_BITS] |= 1u64 << (pos & WORD_MASK);
                block.ones += 1;
            }
            block.len += 1;
        }
        // One rebuild instead of N incremental ones
        block.rebuild_word_ones();
        block
    }

    /// Access bit at local position `pos`. Caller ensures pos < len.
    #[inline]
    fn access(&self, pos: usize) -> bool {
        debug_assert!(pos < self.len as usize);
        let word = self.data[pos >> WORD_BITS];
        ((word >> (pos & WORD_MASK)) & 1) == 1
    }

    /// Append a bit to this block. Caller ensures block is not full.
    /// Maintains word_ones incrementally — O(1) typical, O(WORDS_PER_BLOCK) at word boundary.
    #[inline]
    fn push(&mut self, bit: bool) {
        debug_assert!((self.len as usize) < BLOCK_SIZE);
        let pos = self.len as usize;
        let old_word_idx = if pos > 0 { (pos - 1) >> WORD_BITS } else { 0 };
        if bit {
            self.data[pos >> WORD_BITS] |= 1u64 << (pos & WORD_MASK);
            self.ones += 1;
        }
        self.len += 1;
        let new_word_idx = pos >> WORD_BITS;
        // If we crossed a word boundary, all prefix sums from new_word_idx onward shift
        if new_word_idx > old_word_idx || pos == 0 {
            // Recompute from new_word_idx onwards
            let mut running: u16 = if new_word_idx > 0 {
                // word_ones[new_word_idx] should = sum of popcounts of data[0..new_word_idx]
                // word_ones[new_word_idx-1] + popcount(data[new_word_idx-1])
                self.word_ones[new_word_idx - 1] + self.data[new_word_idx - 1].count_ones() as u16
            } else {
                0
            };
            for w in new_word_idx..WORDS_PER_BLOCK {
                self.word_ones[w] = running;
                running += self.data[w].count_ones() as u16;
            }
        } else {
            // Same word — only the partial popcount within this word changed,
            // but word_ones stores prefix BEFORE each word, so word_ones
            // for words > new_word_idx stays the same.
            // Actually wait — word_ones[w] = cumulative popcount of data[0..w).
            // If we're still in the same word, the prefix before each
            // subsequent word hasn't changed. So no update needed at all!
            // Only the rank1 within the current word will see the new bit.
        }
    }

    /// Set the bit at local position `pos`. Returns old bit value.
    /// Maintains word_ones prefix sums.
    #[inline]
    fn set(&mut self, pos: usize, bit: bool) -> bool {
        debug_assert!(pos < self.len as usize);
        let word_idx = pos >> WORD_BITS;
        let bit_mask = 1u64 << (pos & WORD_MASK);
        let old = (self.data[word_idx] & bit_mask) != 0;

        if bit && !old {
            self.data[word_idx] |= bit_mask;
            self.ones += 1;
        } else if !bit && old {
            self.data[word_idx] &= !bit_mask;
            self.ones -= 1;
        }
        // Rebuild prefix sums — O(8) = O(1)
        self.rebuild_word_ones();
        old
    }

    /// Insert a bit at local position `pos`, shifting subsequent bits right.
    /// Caller ensures block is not full.
    /// Uses bitwise shift-and-carry across u64 words — O(WORDS_PER_BLOCK) with no heap alloc.
    fn insert(&mut self, pos: usize, bit: bool) {
        debug_assert!(
            (self.len as usize) < BLOCK_SIZE,
            "insert into full block"
        );
        debug_assert!(pos <= self.len as usize);

        let old_len = self.len as usize;

        // Append via fast push() path
        if pos == old_len {
            self.push(bit);
            return;
        }

        let word_idx = pos >> WORD_BITS;
        let bit_off = pos & WORD_MASK;
        let last_word = if old_len > 0 {
            (old_len - 1) >> WORD_BITS
        } else {
            0
        };

        // ----- Target word: split, insert bit, save carry -----
        let low_mask = (1u64 << bit_off).wrapping_sub(1);
        let low = self.data[word_idx] & low_mask;
        let high = self.data[word_idx] & !low_mask;
        let carry = (self.data[word_idx] >> 63) & 1;

        // Rebuild: low stays put, new bit at bit_off, high shifted left by 1.
        // Mask out bit 0 of (high << 1) since the MSB of the original word
        // wraps to bit 0 via Rust's wrapping shift — that bit is carried below.
        self.data[word_idx] = low
            | ((bit as u64) << bit_off)
            | ((high << 1) & !1u64);

        // ----- Propagate carry through subsequent words -----
        let mut c = carry;
        for w in (word_idx + 1)..=last_word {
            let next_carry = (self.data[w] >> 63) & 1;
            self.data[w] = (self.data[w] << 1) | c;
            c = next_carry;
        }
        // If carry spilled past the last valid word, set it in the next word.
        // This is always safe because the block isn't full (len < 512).
        if c != 0 && last_word + 1 < WORDS_PER_BLOCK {
            self.data[last_word + 1] |= 1;
        }

        // ----- Update metadata -----
        self.len += 1;
        if bit {
            self.ones += 1;
        }
        self.rebuild_word_ones();
    }

    /// Delete the bit at local position `pos`, returning the deleted bit.
    /// Uses bitwise shift-and-carry across u64 words — O(WORDS_PER_BLOCK) with no heap alloc.
    fn delete(&mut self, pos: usize) -> bool {
        debug_assert!(pos < self.len as usize);
        debug_assert!(self.len > 0);

        let old_len = self.len as usize;
        let old_bit = self.access(pos);

        if old_len == 1 {
            // Deleting the only bit — just clear
            self.data = [0u64; WORDS_PER_BLOCK];
            self.word_ones = [0u16; WORDS_PER_BLOCK];
            self.len = 0;
            self.ones = 0;
            return old_bit;
        }

        let word_idx = pos >> WORD_BITS;
        let bit_off = pos & WORD_MASK;
        let last_word = (old_len - 1) >> WORD_BITS;

        // ----- Phase 1: shift words *after* the target right by 1, with carry -----
        // Process from last_word down to word_idx + 1.
        // Carry flows leftward: the LSB of word w (before shift) becomes the
        // MSB of word w-1 (after shift).
        let mut c: u64 = 0;
        for w in (word_idx + 1..=last_word).rev() {
            let lsb = self.data[w] & 1;
            self.data[w] = (self.data[w] >> 1) | c;
            c = lsb << 63;
        }

        // ----- Phase 2: target word — delete the specific bit -----
        let low_mask = (1u64 << bit_off).wrapping_sub(1);
        let low = self.data[word_idx] & low_mask;
        // Bits above `pos` shift right by 1. Avoid >> 64 overflow when bit_off == 63.
        let above = if bit_off < 63 {
            self.data[word_idx] >> (bit_off + 1)
        } else {
            0
        };
        // Place above bits back starting at bit_off; OR in the carry from phase 1
        // (which is the LSB of word word_idx+1 promoted to bit 63).
        self.data[word_idx] = low | (above << bit_off) | c;

        // ----- Update metadata -----
        self.len -= 1;
        if old_bit {
            self.ones -= 1;
        }
        self.rebuild_word_ones();

        old_bit
    }

    /// Count 1-bits in local positions [0, pos).
    /// Uses per-word prefix sums for O(1) lookup instead of loop.
    /// word_ones[w] = cumulative popcount of data[0..w), so:
    ///   rank1(pos) = word_ones[word_idx] + popcount(data[word_idx] & mask)
    /// When pos == len, word_idx may equal WORDS_PER_BLOCK, so we
    /// return self.ones directly in that case.
    #[inline]
    fn rank1(&self, pos: usize) -> u32 {
        debug_assert!(pos <= self.len as usize);
        if pos == 0 {
            return 0;
        }
        if pos >= self.len as usize {
            // pos == len: total ones in the block
            return self.ones as u32;
        }
        let word_idx = pos >> WORD_BITS;
        let remainder = pos & WORD_MASK;

        // word_ones[word_idx] = prefix sum up to (but not including) word_idx
        // word_idx < WORDS_PER_BLOCK guaranteed since pos < len
        debug_assert!(word_idx < WORDS_PER_BLOCK, "word_idx {word_idx} >= {WORDS_PER_BLOCK}, pos={pos}, len={}", self.len);
        let count = self.word_ones[word_idx] as u32;

        // Partial popcount within word_idx
        if remainder > 0 {
            let mask = (1u64 << remainder) - 1;
            count + (self.data[word_idx] & mask).count_ones()
        } else {
            count
        }
    }

    /// Find position of the k-th 1-bit (1-indexed) within this block.
    /// Returns None if fewer than k ones exist.
    fn select1(&self, k: usize) -> Option<usize> {
        if k == 0 || k > self.ones as usize {
            return None;
        }
        let mut remaining = k;
        let full_words = self.len as usize / 64;
        let extra_bits = self.len as usize % 64;

        for w in 0..full_words {
            let wc = self.data[w].count_ones() as usize;
            if remaining <= wc {
                return Some(w * 64 + select_in_word(self.data[w], remaining));
            }
            remaining -= wc;
        }
        // Partial last word
        if extra_bits > 0 && full_words < WORDS_PER_BLOCK {
            let mask = (1u64 << extra_bits).wrapping_sub(1);
            let wc = (self.data[full_words] & mask).count_ones() as usize;
            if remaining <= wc {
                return Some(
                    full_words * 64
                        + select_in_word(self.data[full_words] & mask, remaining),
                );
            }
        }
        None
    }

    /// Split this block at position `mid`.
    /// Returns new block with bits [mid, len); this block truncated to [0, mid).
    fn split_at(&mut self, mid: usize) -> Block {
        debug_assert!(mid > 0 && mid < self.len as usize);
        let old_len = self.len as usize;
        let mut new_block = Block::new();

        // Extract bits [mid, old_len) before we modify this block
        for i in mid..old_len {
            new_block.push(self.access(i));
        }
        new_block.rebuild_word_ones(); // O(8) batch update

        // Truncate this block: clear bits from mid onwards
        let start_word = mid >> WORD_BITS;
        let start_bit = mid & WORD_MASK;

        // Clear partial word
        if start_bit > 0 && start_word < WORDS_PER_BLOCK {
            self.data[start_word] &= (1u64 << start_bit).wrapping_sub(1);
        } else if start_bit == 0 && start_word < WORDS_PER_BLOCK {
            self.data[start_word] = 0;
        }

        // Clear all subsequent words
        for w in (start_word + 1)..WORDS_PER_BLOCK {
            self.data[w] = 0;
        }

        self.len = mid as u16;
        // Rebuild prefix sums first (data is already truncated but word_ones is stale)
        self.rebuild_word_ones();
        // Now compute ones from the fresh prefix sums.
        // NOTE: We cannot use self.rank1(mid) here because rank1 has an
        // early return when pos >= self.len that returns self.ones (the stale value).
        // Instead, compute the total ones directly from word_ones.
        // After rebuild_word_ones, word_ones[w] = popcount sum of data[0..w-1].
        // Total ones = word_ones[full_words] + popcount(partial word)
        let full_words = mid / 64;
        let remainder = mid % 64;
        let mut count = self.word_ones[full_words] as u32;
        if remainder > 0 {
            let mask = (1u64 << remainder) - 1;
            count += (self.data[full_words] & mask).count_ones();
        }
        self.ones = count as u16;

        new_block
    }
}

/// Find the position of the k-th set bit (1-indexed) in a single u64.
#[inline]
fn select_in_word(word: u64, k: usize) -> usize {
    debug_assert!(k > 0);
    let mut w = word;
    let mut remaining = k;
    while remaining > 1 {
        w &= w.wrapping_sub(1); // clear LSB
        remaining -= 1;
    }
    w.trailing_zeros() as usize
}

impl Default for Block {
    fn default() -> Self {
        Block::new()
    }
}

// ============================================================================
// DynamicSuccinctBitVec
// ============================================================================

/// Dynamic succinct bit vector with O(log n) insert/delete and O(1)-ish rank.
///
/// Uses block-based storage (512-bit blocks) with a Fenwick tree over block
/// one-counts for fast rank queries and dynamic prefix sum maintenance.
/// A separate prefix-sum array over block *lengths* enables fast binary-search
/// location of any bit index.
///
/// # Examples
///
/// ```
/// use cerebrum_dwm::DynamicSuccinctBitVec;
///
/// let mut dv = DynamicSuccinctBitVec::new();
/// dv.push(true);
/// dv.push(false);
/// dv.push(true);
/// assert_eq!(dv.access(0), true);
/// assert_eq!(dv.rank(3, true), 2);
/// dv.insert_bit(1, true);
/// assert_eq!(dv.len(), 4);
/// ```
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DynamicSuccinctBitVec {
    /// Leaf blocks of bits.
    blocks: Vec<Block>,

    /// Fenwick tree over block one-counts for O(log B) prefix sum queries.
    fenwick: FenwickTree,

    /// Fenwick tree over block lengths for O(log B) locate and length updates.
    /// Replaces the old `block_len_prefix: Vec<u32>` to give O(log B) updates
    /// instead of O(B) linear scans on every insert/delete.
    #[serde(default)]
    len_fenwick: FenwickTree,

    /// Total number of bits across all blocks.
    num_bits: usize,
}

impl DynamicSuccinctBitVec {
    // ========================================================================
    // Construction
    // ========================================================================

    /// Create an empty bit vector.
    pub fn new() -> Self {
        DynamicSuccinctBitVec {
            blocks: Vec::new(),
            fenwick: FenwickTree::new(),
            len_fenwick: FenwickTree::new(),
            num_bits: 0,
        }
    }

    /// Construct from a slice of bools (true = 1, false = 0).
    pub fn from_bits(bits: &[bool]) -> Self {
        if bits.is_empty() {
            return Self::new();
        }

        let num_blocks = bits.len().div_ceil(BLOCK_SIZE);
        let mut blocks = Vec::with_capacity(num_blocks);
        let mut block_ones = Vec::with_capacity(num_blocks);
        let mut block_lens = Vec::with_capacity(num_blocks);

        for chunk_idx in 0..num_blocks {
            let start = chunk_idx * BLOCK_SIZE;
            let end = (start + BLOCK_SIZE).min(bits.len());
            let block = Block::from_bits(&bits[start..end]);
            block_ones.push(block.ones as u32);
            block_lens.push(block.len as u32);
            blocks.push(block);
        }

        DynamicSuccinctBitVec {
            fenwick: FenwickTree::from_values(&block_ones),
            len_fenwick: FenwickTree::from_values(&block_lens),
            blocks,
            num_bits: bits.len(),
        }
    }

    /// Construct from bytes (MSB-first: bit i = (bytes[i/8] >> (7 - i%8)) & 1).
    pub fn from_bytes(bytes: &[u8]) -> Self {
        let bits: Vec<bool> = bytes
            .iter()
            .flat_map(|b| (0..8).rev().map(move |bit| (b >> bit) & 1 != 0))
            .collect();
        Self::from_bits(&bits)
    }

    /// Rebuild the entire vector from new bits.
    /// Used by `DynamicWaveletMatrix` for initial construction.
    pub fn rebuild_from_bits(&mut self, bits: &[bool]) {
        *self = Self::from_bits(bits);
    }

    /// Create a bit vector of all zeros, length `len`.
    pub fn zeros(len: usize) -> Self {
        Self::from_bits(&vec![false; len])
    }

    /// Create a bit vector of all ones, length `len`.
    pub fn ones(len: usize) -> Self {
        Self::from_bits(&vec![true; len])
    }

    // ========================================================================
    // Queries
    // ========================================================================

    /// Get the bit at position `i`. O(log B).
    ///
    /// # Debug-panics
    /// Panics if i ≥ len.
    #[inline]
    pub fn access(&self, i: usize) -> bool {
        debug_assert!(i < self.num_bits, "access: index {i} >= len {}", self.num_bits);
        let (block_idx, local_pos) = self.locate(i);
        self.blocks[block_idx].access(local_pos)
    }

    /// Count occurrences of bit `b` in positions [0, i). O(log B).
    ///
    /// # Debug-panics
    /// Panics if i > len.
    #[inline]
    pub fn rank(&self, i: usize, b: bool) -> u64 {
        debug_assert!(i <= self.num_bits, "rank: index {i} > len {}", self.num_bits);
        if i == 0 {
            return 0;
        }
        let (block_idx, local_pos) = self.locate(i);

        let prefix_ones = if block_idx > 0 {
            self.fenwick.prefix_sum(block_idx)
        } else {
            0
        };
        let local_ones = self.blocks[block_idx].rank1(local_pos);
        let total_ones = prefix_ones + local_ones;

        if b {
            total_ones as u64
        } else {
            i as u64 - total_ones as u64
        }
    }

    /// Find the position of the k-th occurrence of bit `b` (1-indexed).
    /// Returns None if fewer than k. O(log² n) in general.
    pub fn select(&self, k: usize, b: bool) -> Option<usize> {
        if k == 0 {
            return None;
        }
        let total = if b {
            self.count_ones() as usize
        } else {
            self.count_zeros() as usize
        };
        if k > total {
            return None;
        }

        if b {
            // Use Fenwick lower_bound
            let (one_idx, _pfx) = self.fenwick.lower_bound(k as u32);
            let block_idx = one_idx.saturating_sub(1); // 0-based

            let prefix_ones = if block_idx > 0 {
                self.fenwick.prefix_sum(block_idx) as usize
            } else {
                0
            };
            let local_k = k - prefix_ones;
            let local_pos = self.blocks[block_idx].select1(local_k)?;

            // Global offset: sum of lengths of all blocks before block_idx
            let global_offset = if block_idx > 0 {
                self.len_fenwick.prefix_sum(block_idx) as usize
            } else {
                0
            };
            Some(global_offset + local_pos)
        } else {
            // Binary search on rank0
            let mut lo = 0usize;
            let mut hi = self.num_bits;
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                if (self.rank(mid + 1, false) as usize) >= k {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            if lo < self.num_bits
                && self.rank(lo + 1, false) as usize == k
                && !self.access(lo)
            {
                Some(lo)
            } else {
                None
            }
        }
    }

    /// Number of bits in the vector. O(1).
    #[inline]
    pub fn len(&self) -> usize {
        self.num_bits
    }

    /// Is the vector empty? O(1).
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.num_bits == 0
    }

    /// Total number of 1-bits. O(1).
    #[inline]
    pub fn count_ones(&self) -> u64 {
        if self.blocks.is_empty() {
            return 0;
        }
        self.fenwick.prefix_sum(self.blocks.len()) as u64
    }

    /// Total number of 0-bits. O(1).
    #[inline]
    pub fn count_zeros(&self) -> u64 {
        self.num_bits as u64 - self.count_ones()
    }

    // ========================================================================
    // Modification
    // ========================================================================

    /// Append a bit to the end. O(1) amortized.
    pub fn push(&mut self, bit: bool) {
        if self.blocks.is_empty()
            || self.blocks.last().is_none_or(|b| b.len as usize >= BLOCK_SIZE)
        {
            let mut new_block = Block::new();
            new_block.push(bit); // maintains word_ones incrementally
            self.blocks.push(new_block);
            self.fenwick.push(if bit { 1 } else { 0 });
            self.len_fenwick.push(1);
        } else {
            let last_idx = self.blocks.len() - 1;
            let old_ones = self.blocks[last_idx].ones;
            self.blocks[last_idx].push(bit); // maintains word_ones incrementally
            let new_ones = self.blocks[last_idx].ones;
            if new_ones != old_ones {
                let delta = new_ones as i32 - old_ones as i32;
                self.fenwick.add(last_idx, delta);
            }
            self.len_fenwick.add(last_idx, 1);
        }
        self.num_bits += 1;
    }

    /// Set the bit at position `i`. O(log B).
    ///
    /// # Debug-panics
    /// Panics if i ≥ len.
    pub fn set(&mut self, i: usize, bit: bool) {
        debug_assert!(i < self.num_bits, "set: index {i} >= len {}", self.num_bits);
        let (block_idx, local_pos) = self.locate(i);
        let old_ones = self.blocks[block_idx].ones;
        let _ = self.blocks[block_idx].set(local_pos, bit);
        let new_ones = self.blocks[block_idx].ones;
        if new_ones != old_ones {
            let delta = new_ones as i32 - old_ones as i32;
            self.fenwick.add(block_idx, delta);
        }
    }

    /// Insert a bit at position `i`, shifting subsequent bits right.
    /// O(BLOCK_SIZE + log B).
    ///
    /// If the target block overflows, it is split first.
    ///
    /// # Debug-panics
    /// Panics if i > len.
    pub fn insert_bit(&mut self, i: usize, bit: bool) {
        debug_assert!(
            i <= self.num_bits,
            "insert_bit: index {i} > len {}",
            self.num_bits
        );

        if i == self.num_bits {
            self.push(bit);
            return;
        }

        let (block_idx, local_pos) = self.locate(i);

        // If the target block is full, split it and retry
        if self.blocks[block_idx].len as usize >= BLOCK_SIZE {
            self.split_block(block_idx);
            // Re-locate after the split (blocks have moved)
            let (new_bi, new_lp) = self.locate(i);
            self.insert_into_block(new_bi, new_lp, bit);
            // The split added a new block. Increment length of the affected block.
            // All blocks from new_bi onward need +1 in their cumulative length,
            // which len_fenwick.add achieves in O(log B).
            self.len_fenwick.add(new_bi, 1);
        } else {
            self.insert_into_block(block_idx, local_pos, bit);
            // Update length fenwick: all blocks from block_idx onward need +1
            self.len_fenwick.add(block_idx, 1);
        }

        self.num_bits += 1;
    }

    /// Delete the bit at position `i`, returning the deleted bit.
    /// O(BLOCK_SIZE + log B).
    ///
    /// If the block becomes too small (< BLOCK_SIZE/4), tries to merge
    /// with a neighbor.
    ///
    /// # Debug-panics
    /// Panics if i ≥ len.
    pub fn delete_bit(&mut self, i: usize) -> bool {
        debug_assert!(i < self.num_bits, "delete_bit: index {i} >= len {}", self.num_bits);

        let (block_idx, local_pos) = self.locate(i);
        let old_ones = self.blocks[block_idx].ones;
        let deleted = self.blocks[block_idx].delete(local_pos);
        let new_ones = self.blocks[block_idx].ones;
        if new_ones != old_ones {
            let delta = new_ones as i32 - old_ones as i32;
            self.fenwick.add(block_idx, delta);
        }

        self.num_bits -= 1;
        // Decrement block length in len_fenwick: O(log B)
        self.len_fenwick.add(block_idx, -1);

        // If block too empty, try merging
        let min_size = BLOCK_SIZE / 4;
        if self.blocks[block_idx].len as usize <= min_size && self.blocks.len() > 1 {
            self.try_merge(block_idx);
        }

        deleted
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /// Find the block index and local bit position for global position `i`.
    ///
    /// Uses `len_fenwick.lower_bound` for O(log B) lookup.
    /// `i` must be < num_bits or == num_bits (maps to last block end).
    #[inline]
    fn locate(&self, i: usize) -> (usize, usize) {
        debug_assert!(i <= self.num_bits);
        if i == self.num_bits && !self.blocks.is_empty() {
            let last = self.blocks.len() - 1;
            return (last, self.blocks[last].len as usize);
        }

        // Use Fenwick tree lower_bound: find first block where prefix_sum >= i+1
        let target = (i + 1) as u32;
        let (one_idx, _) = self.len_fenwick.lower_bound(target);
        let block_idx = one_idx.saturating_sub(1).min(self.blocks.len().saturating_sub(1));

        let offset = if block_idx > 0 {
            self.len_fenwick.prefix_sum(block_idx) as usize
        } else {
            0
        };
        let local_pos = i - offset;
        (block_idx, local_pos)
    }

    /// Insert a bit into block `block_idx` at local position `local_pos`.
    /// The block is guaranteed to have room.
    fn insert_into_block(&mut self, block_idx: usize, local_pos: usize, bit: bool) {
        let old_ones = self.blocks[block_idx].ones;
        self.blocks[block_idx].insert(local_pos, bit);
        let new_ones = self.blocks[block_idx].ones;
        if new_ones != old_ones {
            let delta = new_ones as i32 - old_ones as i32;
            self.fenwick.add(block_idx, delta);
        }
    }

    /// Split block `block_idx` at its midpoint. Inserts the right half as a new block.
    /// Does NOT update fenwick/len_fenwick - caller must do that.
    fn split_block(&mut self, block_idx: usize) {
        let mid = self.blocks[block_idx].len as usize / 2;
        let new_block = self.blocks[block_idx].split_at(mid);

        // Insert new block after block_idx
        self.blocks.insert(block_idx + 1, new_block);

        // Rebuild fenwick and length prefix
        self.rebuild_aux();
    }

    /// Try to merge block `block_idx` with a neighbor if it's too small.
    fn try_merge(&mut self, block_idx: usize) {
        // Prefer predecessor
        if block_idx > 0 {
            let prev_len = self.blocks[block_idx - 1].len as usize;
            let cur_len = self.blocks[block_idx].len as usize;
            if prev_len + cur_len <= BLOCK_SIZE {
                self.merge_into(block_idx - 1, block_idx);
                return;
            }
        }
        // Try successor
        if block_idx + 1 < self.blocks.len() {
            let cur_len = self.blocks[block_idx].len as usize;
            let next_len = self.blocks[block_idx + 1].len as usize;
            if cur_len + next_len <= BLOCK_SIZE {
                self.merge_into(block_idx, block_idx + 1);
            }
        }
    }

    /// Merge `right` block into `left` block, remove `right`.
    fn merge_into(&mut self, left_idx: usize, right_idx: usize) {
        debug_assert!(left_idx < right_idx);
        debug_assert!(right_idx < self.blocks.len());

        for i in 0..self.blocks[right_idx].len as usize {
            let bit = self.blocks[right_idx].access(i);
            self.blocks[left_idx].push(bit);
        }

        self.blocks.remove(right_idx);
        self.rebuild_aux();
    }

    /// Rebuild fenwick trees and len_fenwick from current blocks.
    fn rebuild_aux(&mut self) {
        let values: Vec<u32> = self.blocks.iter().map(|b| b.ones as u32).collect();
        self.fenwick.rebuild(&values);

        let block_lens: Vec<u32> = self.blocks.iter().map(|b| b.len as u32).collect();
        self.len_fenwick.rebuild(&block_lens);
    }
}

impl Default for DynamicSuccinctBitVec {
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
    // Construction & access
    // -----------------------------------------------------------------------

    #[test]
    fn test_empty() {
        let dv = DynamicSuccinctBitVec::new();
        assert_eq!(dv.len(), 0);
        assert!(dv.is_empty());
        assert_eq!(dv.count_ones(), 0);
        assert_eq!(dv.count_zeros(), 0);
    }

    #[test]
    fn test_from_bits_basic() {
        let dv = DynamicSuccinctBitVec::from_bits(&[true, false, true, true, false]);
        assert_eq!(dv.len(), 5);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert!(dv.access(2));
        assert!(dv.access(3));
        assert!(!dv.access(4));
        assert_eq!(dv.count_ones(), 3);
        assert_eq!(dv.count_zeros(), 2);
    }

    #[test]
    fn test_from_bits_all_false() {
        let dv = DynamicSuccinctBitVec::from_bits(&[false; 200]);
        assert_eq!(dv.len(), 200);
        assert_eq!(dv.count_ones(), 0);
        assert_eq!(dv.count_zeros(), 200);
        for i in 0..200 {
            assert!(!dv.access(i), "pos {i} should be 0");
        }
    }

    #[test]
    fn test_from_bits_all_true() {
        let dv = DynamicSuccinctBitVec::from_bits(&[true; 200]);
        assert_eq!(dv.len(), 200);
        assert_eq!(dv.count_ones(), 200);
        assert_eq!(dv.count_zeros(), 0);
        for i in 0..200 {
            assert!(dv.access(i), "pos {i} should be 1");
        }
    }

    #[test]
    fn test_from_bytes() {
        // 0xB1 = 1011_0001 → bits: 1 0 1 1 0 0 0 1
        let dv = DynamicSuccinctBitVec::from_bytes(&[0xB1]);
        assert_eq!(dv.len(), 8);
        assert_eq!(dv.count_ones(), 4);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert!(dv.access(2));
        assert!(dv.access(3));
        assert!(!dv.access(4));
        assert!(!dv.access(5));
        assert!(!dv.access(6));
        assert!(dv.access(7));
    }

    #[test]
    fn test_zeros() {
        let dv = DynamicSuccinctBitVec::zeros(128);
        assert_eq!(dv.len(), 128);
        assert_eq!(dv.count_ones(), 0);
        assert_eq!(dv.count_zeros(), 128);
    }

    #[test]
    fn test_ones() {
        let dv = DynamicSuccinctBitVec::ones(128);
        assert_eq!(dv.len(), 128);
        assert_eq!(dv.count_ones(), 128);
        for i in 0..128 {
            assert!(dv.access(i));
        }
    }

    // -----------------------------------------------------------------------
    // Push
    // -----------------------------------------------------------------------

    #[test]
    fn test_push_small() {
        let mut dv = DynamicSuccinctBitVec::new();
        dv.push(true);
        dv.push(false);
        dv.push(true);
        assert_eq!(dv.len(), 3);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert!(dv.access(2));
    }

    #[test]
    fn test_push_many() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 200;
        for i in 0..n {
            dv.push(i % 3 == 0);
        }
        assert_eq!(dv.len(), n);
        let expected_ones = (0..n).filter(|i| i % 3 == 0).count();
        assert_eq!(dv.count_ones() as usize, expected_ones);
    }

    #[test]
    fn test_push_across_block_boundary() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 600;
        for i in 0..n {
            dv.push(i % 2 == 0);
        }
        assert_eq!(dv.len(), n);
        assert_eq!(dv.count_ones(), n as u64 / 2);
        for i in 0..n {
            assert_eq!(dv.access(i), i % 2 == 0, "mismatch at {i}");
        }
    }

    // -----------------------------------------------------------------------
    // Set
    // -----------------------------------------------------------------------

    #[test]
    fn test_set_0_to_1() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[false, false, false]);
        dv.set(1, true);
        assert!(!dv.access(0));
        assert!(dv.access(1));
        assert!(!dv.access(2));
        assert_eq!(dv.count_ones(), 1);
    }

    #[test]
    fn test_set_1_to_0() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, true, true]);
        dv.set(1, false);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert!(dv.access(2));
        assert_eq!(dv.count_ones(), 2);
    }

    #[test]
    fn test_set_no_change() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false]);
        dv.set(0, true);
        dv.set(1, false);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert_eq!(dv.count_ones(), 1);
    }

    // -----------------------------------------------------------------------
    // Insert / Delete
    // -----------------------------------------------------------------------

    #[test]
    fn test_insert_bit_middle() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false, true]);
        dv.insert_bit(1, true);
        assert_eq!(dv.len(), 4);
        assert!(dv.access(0));
        assert!(dv.access(1)); // the inserted true
        assert!(!dv.access(2)); // the original false shifted right
        assert!(dv.access(3)); // the original true shifted right
    }

    #[test]
    fn test_insert_bit_beginning() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false]);
        dv.insert_bit(0, false);
        assert_eq!(dv.len(), 3);
        assert!(!dv.access(0));
        assert!(dv.access(1));
        assert!(!dv.access(2));
    }

    #[test]
    fn test_insert_bit_end() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false]);
        dv.insert_bit(2, true);
        assert_eq!(dv.len(), 3);
        assert!(dv.access(0));
        assert!(!dv.access(1));
        assert!(dv.access(2));
    }

    #[test]
    fn test_delete_bit_middle() {
        let mut dv =
            DynamicSuccinctBitVec::from_bits(&[true, false, true, false, true]);
        let deleted = dv.delete_bit(1);
        assert!(!deleted);
        assert_eq!(dv.len(), 4);
        assert!(dv.access(0));
        assert!(dv.access(1)); // was pos 2
        assert!(!dv.access(2)); // was pos 3
        assert!(dv.access(3)); // was pos 4
    }

    #[test]
    fn test_delete_bit_beginning() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false, true]);
        let deleted = dv.delete_bit(0);
        assert!(deleted);
        assert_eq!(dv.len(), 2);
        assert!(!dv.access(0));
        assert!(dv.access(1));
    }

    #[test]
    fn test_delete_bit_end() {
        let mut dv = DynamicSuccinctBitVec::from_bits(&[true, false, true]);
        let deleted = dv.delete_bit(2);
        assert!(deleted);
        assert_eq!(dv.len(), 2);
        assert!(dv.access(0));
        assert!(!dv.access(1));
    }

    #[test]
    fn test_insert_delete_roundtrip() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 200;
        for _i in 0..n {
            dv.insert_bit(dv.len(), true); // insert at end = push
        }
        assert_eq!(dv.len(), n);
        assert_eq!(dv.count_ones(), n as u64);

        for i in (0..n).rev() {
            let deleted = dv.delete_bit(i);
            assert!(deleted);
        }
        assert_eq!(dv.len(), 0);
    }

    // -----------------------------------------------------------------------
    // Rank
    // -----------------------------------------------------------------------

    #[test]
    fn test_rank_all_zeros() {
        let dv = DynamicSuccinctBitVec::zeros(200);
        for i in 0..=200 {
            assert_eq!(dv.rank(i, false), i as u64);
            assert_eq!(dv.rank(i, true), 0);
        }
    }

    #[test]
    fn test_rank_all_ones() {
        let dv = DynamicSuccinctBitVec::ones(200);
        for i in 0..=200 {
            assert_eq!(dv.rank(i, true), i as u64);
            assert_eq!(dv.rank(i, false), 0);
        }
    }

    #[test]
    fn test_rank_alternating() {
        let bits: Vec<bool> = (0..200).map(|i| i % 2 == 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        for i in 0..=200 {
            assert_eq!(dv.rank(i, true), ((i + 1) / 2) as u64);
            assert_eq!(dv.rank(i, false), (i / 2) as u64);
        }
    }

    #[test]
    fn test_rank_small() {
        let dv = DynamicSuccinctBitVec::from_bits(&[
            true, false, true, true, false, false, false, true,
        ]);
        assert_eq!(dv.rank(0, true), 0);
        assert_eq!(dv.rank(1, true), 1);
        assert_eq!(dv.rank(2, true), 1);
        assert_eq!(dv.rank(3, true), 2);
        assert_eq!(dv.rank(4, true), 3);
        assert_eq!(dv.rank(8, true), 4);
        assert_eq!(dv.rank(8, false), 4);
    }

    #[test]
    fn test_rank_cross_block_boundary() {
        let mut bits = vec![false; 600];
        for i in 0..512 {
            bits[i] = true;
        }
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        assert_eq!(dv.rank(512, true), 512);
        assert_eq!(dv.rank(513, true), 512);
        assert_eq!(dv.rank(600, true), 512);
        assert_eq!(dv.rank(600, false), 88);
    }

    #[test]
    fn test_rank_matches_brute_force() {
        let bits: Vec<bool> = (0..500).map(|i| (i * 7 + 13) % 5 == 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        let mut ones = 0u64;
        let mut zeros = 0u64;
        for i in 0..=bits.len() {
            assert_eq!(dv.rank(i, true), ones);
            assert_eq!(dv.rank(i, false), zeros);
            if i < bits.len() {
                if bits[i] {
                    ones += 1;
                } else {
                    zeros += 1;
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Select
    // -----------------------------------------------------------------------

    #[test]
    fn test_select_all_zeros() {
        let dv = DynamicSuccinctBitVec::zeros(200);
        assert_eq!(dv.select(1, true), None);
        assert_eq!(dv.select(1, false), Some(0));
        assert_eq!(dv.select(50, false), Some(49));
        assert_eq!(dv.select(200, false), Some(199));
        assert_eq!(dv.select(201, false), None);
    }

    #[test]
    fn test_select_all_ones() {
        let dv = DynamicSuccinctBitVec::ones(200);
        assert_eq!(dv.select(0, true), None);
        assert_eq!(dv.select(1, true), Some(0));
        assert_eq!(dv.select(50, true), Some(49));
        assert_eq!(dv.select(200, true), Some(199));
        assert_eq!(dv.select(201, true), None);
    }

    #[test]
    fn test_select_alternating() {
        let bits: Vec<bool> = (0..200).map(|i| i % 2 == 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        assert_eq!(dv.select(1, true), Some(0));
        assert_eq!(dv.select(2, true), Some(2));
        assert_eq!(dv.select(3, true), Some(4));
        assert_eq!(dv.select(1, false), Some(1));
        assert_eq!(dv.select(2, false), Some(3));
    }

    #[test]
    fn test_select_small() {
        let dv = DynamicSuccinctBitVec::from_bits(&[
            true, false, true, true, false, false, false, true,
        ]);
        assert_eq!(dv.select(1, true), Some(0));
        assert_eq!(dv.select(2, true), Some(2));
        assert_eq!(dv.select(3, true), Some(3));
        assert_eq!(dv.select(4, true), Some(7));
        assert_eq!(dv.select(5, true), None);
        assert_eq!(dv.select(1, false), Some(1));
        assert_eq!(dv.select(2, false), Some(4));
        assert_eq!(dv.select(3, false), Some(5));
        assert_eq!(dv.select(4, false), Some(6));
    }

    #[test]
    fn test_select_roundtrip_with_rank() {
        let bits: Vec<bool> = (0..500).map(|i| (i * 7 + 13) % 5 == 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        let ones_count = dv.count_ones() as usize;
        let zeros_count = dv.count_zeros() as usize;

        for k in 1..=ones_count {
            let pos = dv.select(k, true).unwrap();
            assert!(dv.access(pos));
            assert_eq!(dv.rank(pos + 1, true) as usize, k);
        }
        for k in 1..=zeros_count {
            let pos = dv.select(k, false).unwrap();
            assert!(!dv.access(pos));
            assert_eq!(dv.rank(pos + 1, false) as usize, k);
        }
    }

    // -----------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------

    #[test]
    fn test_serde_roundtrip() {
        let bits: Vec<bool> = (0..200).map(|i| i % 3 == 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);

        let json = serde_json::to_string(&dv).unwrap();
        let dv2: DynamicSuccinctBitVec = serde_json::from_str(&json).unwrap();

        assert_eq!(dv2.len(), dv.len());
        assert_eq!(dv2.count_ones(), dv.count_ones());
        for i in 0..dv.len() {
            assert_eq!(dv2.access(i), dv.access(i));
        }
        for i in (0..dv.len()).step_by(13) {
            assert_eq!(dv2.rank(i, true), dv.rank(i, true));
            assert_eq!(dv2.rank(i, false), dv.rank(i, false));
        }
    }

    #[test]
    fn test_serde_roundtrip_after_modifications() {
        let mut dv =
            DynamicSuccinctBitVec::from_bits(&[true, false, true, true, false]);
        dv.insert_bit(2, false);
        dv.set(0, false);
        dv.push(true);

        let json = serde_json::to_string(&dv).unwrap();
        let dv2: DynamicSuccinctBitVec = serde_json::from_str(&json).unwrap();

        assert_eq!(dv2.len(), dv.len());
        for i in 0..dv.len() {
            assert_eq!(dv2.access(i), dv.access(i));
        }
        assert_eq!(dv2.count_ones(), dv.count_ones());
    }

    // -----------------------------------------------------------------------
    // Dynamic-specific: large insert/delete
    // -----------------------------------------------------------------------

    #[test]
    fn test_insert_100k_bits_and_verify() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 100_000;

        // Insert at the end (same as push) so order is preserved
        for i in 0..n {
            dv.insert_bit(dv.len(), i % 2 == 0);
        }

        assert_eq!(dv.len(), n);

        for i in (0..n).step_by(1000) {
            assert_eq!(dv.access(i), i % 2 == 0, "access({i})");
            let expected_ones = (0..i + 1).filter(|j| j % 2 == 0).count();
            assert_eq!(dv.rank(i + 1, true) as usize, expected_ones);
        }
    }

    #[test]
    fn test_insert_at_random_positions() {
        use rand::Rng;
        let mut rng = rand::rng();

        let mut dv = DynamicSuccinctBitVec::new();
        let mut oracle: Vec<bool> = Vec::new();

        let n = 5000;
        for _ in 0..n {
            let pos = if oracle.is_empty() {
                0
            } else {
                rng.random_range(0..=oracle.len())
            };
            let bit: bool = rng.random();
            oracle.insert(pos, bit);
            dv.insert_bit(pos, bit);
        }

        assert_eq!(dv.len(), oracle.len());
        for i in 0..oracle.len() {
            assert_eq!(dv.access(i), oracle[i]);
        }
        for i in 0..=oracle.len() {
            let expected_ones = oracle[..i].iter().filter(|&&b| b).count();
            assert_eq!(dv.rank(i, true) as usize, expected_ones);
            assert_eq!(dv.rank(i, false) as usize, i - expected_ones);
        }
    }

    #[test]
    fn test_delete_50k_from_100k() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 100_000;
        for i in 0..n {
            dv.push(i % 3 == 0);
        }
        assert_eq!(dv.len(), n);

        let mut deleted_count = 0;
        let mut i = 0;
        while i < dv.len() && deleted_count < 50_000 {
            let _ = dv.delete_bit(i);
            deleted_count += 1;
            i += 1;
        }

        assert_eq!(dv.len(), n - deleted_count);

        let mut ones = 0u64;
        for i in 0..dv.len() {
            if dv.access(i) {
                ones += 1;
            }
        }
        assert_eq!(dv.count_ones(), ones);
        assert_eq!(dv.count_zeros(), dv.len() as u64 - ones);
    }

    #[test]
    fn test_insert_delete_interleaved() {
        use rand::Rng;
        let mut rng = rand::rng();

        let mut dv = DynamicSuccinctBitVec::new();
        let mut oracle: Vec<bool> = Vec::new();

        for _ in 0..2000 {
            if oracle.is_empty() || rng.random::<f64>() < 0.6 {
                let pos = if oracle.is_empty() {
                    0
                } else {
                    rng.random_range(0..=oracle.len())
                };
                let bit: bool = rng.random();
                oracle.insert(pos, bit);
                dv.insert_bit(pos, bit);
            } else {
                let pos = rng.random_range(0..oracle.len());
                oracle.remove(pos);
                dv.delete_bit(pos);
            }
        }

        assert_eq!(dv.len(), oracle.len());
        for i in 0..oracle.len() {
            assert_eq!(dv.access(i), oracle[i]);
        }
        for i in 0..=oracle.len() {
            let expected_ones = oracle[..i].iter().filter(|&&b| b).count();
            assert_eq!(dv.rank(i, true) as usize, expected_ones);
        }
    }

    #[test]
    fn test_select_after_insertions() {
        let mut dv = DynamicSuccinctBitVec::new();
        for i in 0..100 {
            dv.push(i % 2 == 0);
        }

        dv.insert_bit(0, true);
        dv.insert_bit(50, false);
        dv.insert_bit(dv.len(), true);

        let ones = dv.count_ones() as usize;
        for k in 1..=ones {
            let pos = dv.select(k, true).expect("should exist");
            assert!(dv.access(pos));
            assert_eq!(dv.rank(pos + 1, true) as usize, k);
        }
    }

    #[test]
    fn test_block_split_and_merge() {
        let mut dv = DynamicSuccinctBitVec::new();
        for i in 0..BLOCK_SIZE {
            dv.push(i % 2 == 0);
        }
        assert_eq!(dv.len(), BLOCK_SIZE);
        assert_eq!(dv.blocks.len(), 1);

        // Insert into the (full) block, forcing a split
        dv.insert_bit(BLOCK_SIZE / 2, true);
        assert_eq!(dv.len(), BLOCK_SIZE + 1);
        assert!(
            dv.blocks.len() >= 2,
            "should have split: {} blocks",
            dv.blocks.len()
        );

        // Verify all bits still accessible
        for i in 0..dv.len() {
            let _ = dv.access(i);
        }

        // Delete most bits from the front, triggering merges
        while dv.len() > 10 {
            dv.delete_bit(0);
        }
        assert_eq!(dv.len(), 10);

        let expected_ones = (0..dv.len()).filter(|&i| dv.access(i)).count() as u64;
        assert_eq!(dv.count_ones(), expected_ones);
    }

    #[test]
    fn test_large_push_then_rank() {
        let n = 50_000;
        let mut dv = DynamicSuccinctBitVec::new();
        for i in 0..n {
            dv.push((i * 7 + 3) % 11 == 0);
        }
        assert_eq!(dv.len(), n);

        let mut ones = 0u64;
        for i in 0..=n {
            assert_eq!(dv.rank(i, true), ones);
            if i < n && dv.access(i) {
                ones += 1;
            }
        }
        assert_eq!(dv.count_ones(), ones);
    }

    #[test]
    fn test_rebuild_from_bits() {
        let mut dv = DynamicSuccinctBitVec::new();
        dv.push(true);

        let new_bits: Vec<bool> = (0..100).map(|i| i % 3 == 0).collect();
        dv.rebuild_from_bits(&new_bits);

        assert_eq!(dv.len(), 100);
        for i in 0..100 {
            assert_eq!(dv.access(i), new_bits[i]);
        }
    }

    // -----------------------------------------------------------------------
    // Fenwick tree unit tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_fenwick_lower_bound() {
        let ft = FenwickTree::from_values(&[3, 1, 4, 1, 5, 9, 2, 6]);
        // prefix sums: [3, 4, 8, 9, 14, 23, 25, 31]

        assert_eq!(ft.lower_bound(0), (1, 3));
        assert_eq!(ft.lower_bound(1), (1, 3));
        assert_eq!(ft.lower_bound(3), (1, 3));
        assert_eq!(ft.lower_bound(4), (2, 4));
        assert_eq!(ft.lower_bound(8), (3, 8));
        assert_eq!(ft.lower_bound(9), (4, 9));
        assert_eq!(ft.lower_bound(14), (5, 14));
        assert_eq!(ft.lower_bound(23), (6, 23));
        assert_eq!(ft.lower_bound(25), (7, 25));
        assert_eq!(ft.lower_bound(31), (8, 31));
        // Beyond total
        assert_eq!(ft.lower_bound(32), (9, 31));
        assert_eq!(ft.lower_bound(1000), (9, 31));
    }

    #[test]
    fn test_fenwick_push() {
        let mut ft = FenwickTree::from_values(&[1, 2, 3]);
        assert_eq!(ft.prefix_sum(3), 6);
        ft.push(4);
        assert_eq!(ft.prefix_sum(4), 10);
        assert_eq!(ft.get(3), 4);
    }

    #[test]
    fn test_fenwick_add() {
        let mut ft = FenwickTree::from_values(&[1, 2, 3]);
        ft.add(1, 5); // 2 → 7
        assert_eq!(ft.get(1), 7);
        assert_eq!(ft.prefix_sum(3), 11); // 1+7+3

        ft.add(0, -1); // 1 → 0
        assert_eq!(ft.get(0), 0);
        assert_eq!(ft.prefix_sum(3), 10);
    }

    #[test]
    fn test_fenwick_empty() {
        let ft = FenwickTree::new();
        assert_eq!(ft.len(), 0);

        let empty_ft = FenwickTree::from_values(&[]);
        assert_eq!(empty_ft.len(), 0);
    }

    // -----------------------------------------------------------------------
    // Property-based testing (10K iterations)
    // -----------------------------------------------------------------------

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(10_000))]

        #[test]
        fn proptest_rank_access_consistent(
            bits in proptest::collection::vec(any::<bool>(), 0..1000),
        ) {
            let dv = DynamicSuccinctBitVec::from_bits(&bits);
            let mut ones = 0u64;
            let mut zeros = 0u64;
            for (i, &bit) in bits.iter().enumerate() {
                prop_assert_eq!(dv.access(i), bit);
                if bit { ones += 1; } else { zeros += 1; }
                prop_assert_eq!(dv.rank(i + 1, true), ones);
                prop_assert_eq!(dv.rank(i + 1, false), zeros);
            }
        }

        #[test]
        fn proptest_select_findable(
            bits in proptest::collection::vec(any::<bool>(), 1..1000),
        ) {
            let dv = DynamicSuccinctBitVec::from_bits(&bits);
            let ones_count = dv.count_ones() as usize;
            let zeros_count = dv.count_zeros() as usize;

            for k in 1..=ones_count.min(200) {
                let pos = dv.select(k, true);
                prop_assert!(pos.is_some(), "select({k}, true)=None ones={ones_count}");
                let pos = pos.unwrap();
                prop_assert!(dv.access(pos));
                prop_assert_eq!(dv.rank(pos + 1, true) as usize, k);
            }
            for k in 1..=zeros_count.min(200) {
                let pos = dv.select(k, false);
                prop_assert!(pos.is_some(), "select({k}, false)=None zeros={zeros_count}");
                let pos = pos.unwrap();
                prop_assert!(!dv.access(pos));
                prop_assert_eq!(dv.rank(pos + 1, false) as usize, k);
            }
        }

        #[test]
        fn proptest_set_preserves_invariants(
            bits in proptest::collection::vec(any::<bool>(), 10..200),
        ) {
            let mut dv = DynamicSuccinctBitVec::from_bits(&bits);
            for i in (0..dv.len()).step_by(7) {
                dv.set(i, !dv.access(i));
            }
            let mut count = 0u64;
            for i in 0..dv.len() {
                if dv.access(i) { count += 1; }
            }
            prop_assert_eq!(count, dv.count_ones());
            prop_assert_eq!(dv.rank(dv.len(), true), dv.count_ones());
            prop_assert_eq!(dv.rank(dv.len(), false), dv.count_zeros());
        }

        #[test]
        fn proptest_insert_delete_roundtrip(
            bits in proptest::collection::vec(any::<bool>(), 1..100),
        ) {
            let dv = DynamicSuccinctBitVec::from_bits(&bits);
            for ins_pos in 0..=bits.len().min(20) {
                let mut dv2 = dv.clone();
                let safe_pos = ins_pos.min(dv2.len());
                dv2.insert_bit(safe_pos, true);
                prop_assert_eq!(dv2.len(), bits.len() + 1);
                dv2.delete_bit(safe_pos);
                prop_assert_eq!(dv2.len(), bits.len());
                for i in 0..bits.len() {
                    prop_assert_eq!(dv2.access(i), bits[i]);
                }
            }
        }

        #[test]
        fn proptest_dynamic_insert_oracle(
            bits in proptest::collection::vec(any::<bool>(), 1..50),
            insertions in proptest::collection::vec(
                (0usize..100, any::<bool>()), 0..20,
            ),
        ) {
            let mut dv = DynamicSuccinctBitVec::from_bits(&bits);
            let mut oracle = bits.clone();
            for (pos, bit) in &insertions {
                let pos = (*pos).min(oracle.len());
                oracle.insert(pos, *bit);
                dv.insert_bit(pos, *bit);
            }
            prop_assert_eq!(dv.len(), oracle.len());
            for i in 0..oracle.len() {
                prop_assert_eq!(dv.access(i), oracle[i]);
            }
            for j in 0..=oracle.len() {
                let expected_ones = oracle[..j].iter().filter(|&&b| b).count() as u64;
                prop_assert_eq!(dv.rank(j, true), expected_ones);
            }
        }

        #[test]
        fn proptest_dynamic_delete_oracle(
            bits in proptest::collection::vec(any::<bool>(), 5..50),
            deletions in proptest::collection::vec(0usize..100, 0..10),
        ) {
            let mut dv = DynamicSuccinctBitVec::from_bits(&bits);
            let mut oracle = bits.clone();
            for &pos in &deletions {
                if oracle.is_empty() { break; }
                let pos = pos.min(oracle.len() - 1);
                oracle.remove(pos);
                dv.delete_bit(pos);
            }
            prop_assert_eq!(dv.len(), oracle.len());
            for i in 0..oracle.len() {
                prop_assert_eq!(dv.access(i), oracle[i]);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Large-scale test
    // -----------------------------------------------------------------------

    #[test]
    fn test_large_bitvec() {
        let bits: Vec<bool> = (0..1_000_000).map(|i| i % 3 != 0).collect();
        let dv = DynamicSuccinctBitVec::from_bits(&bits);
        assert_eq!(dv.len(), 1_000_000);

        let expected_ones = bits.iter().filter(|&&b| b).count() as u64;
        assert_eq!(dv.count_ones(), expected_ones);
        assert_eq!(dv.rank(0, true), 0);
        assert_eq!(dv.rank(1_000_000, true), expected_ones);

        assert_eq!(
            dv.rank(512, true),
            bits[0..512].iter().filter(|&&b| b).count() as u64
        );
        assert_eq!(
            dv.rank(1024, true),
            bits[0..1024].iter().filter(|&&b| b).count() as u64
        );

        if expected_ones > 0 {
            let first_one = dv.select(1, true).unwrap();
            assert!(dv.access(first_one));
            assert_eq!(dv.rank(first_one + 1, true), 1);
        }
    }

    // -----------------------------------------------------------------------
    // Performance smoke tests
    // -----------------------------------------------------------------------

    #[test]
    fn test_push_100k_performance() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 100_000;

        let start = std::time::Instant::now();
        for i in 0..n {
            dv.push(i % 2 == 0);
        }
        let elapsed = start.elapsed();

        assert_eq!(dv.len(), n);
        assert!(
            elapsed.as_millis() < 500,
            "push 100K: {}ms (target <500ms)",
            elapsed.as_millis()
        );
    }

    #[test]
    fn test_delete_50k_performance() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 100_000;
        for i in 0..n {
            dv.push(i % 2 == 0);
        }

        let start = std::time::Instant::now();
        for _ in 0..50_000 {
            dv.delete_bit(0);
        }
        let elapsed = start.elapsed();

        assert_eq!(dv.len(), 50_000);
        assert!(
            elapsed.as_millis() < 600,
            "delete 50K: {}ms (target <600ms)",
            elapsed.as_millis()
        );
    }

    #[test]
    fn test_insert_100k_performance() {
        let mut dv = DynamicSuccinctBitVec::new();
        let n = 100_000;

        // Insert at end only
        let start = std::time::Instant::now();
        for _i in 0..n {
            dv.insert_bit(dv.len(), _i % 2 != 0);
        }
        let elapsed = start.elapsed();

        assert_eq!(dv.len(), n);
        assert!(
            elapsed.as_millis() < 500,
            "insert 100K: {}ms (target <500ms)",
            elapsed.as_millis()
        );
    }
}

    #[test]
    fn test_dwm_pattern_rank_consistency() {
        // Simulate the DWM pattern: insert bits at various positions
        let mut bv = DynamicSuccinctBitVec::new();
        let mut oracle: Vec<bool> = Vec::new();
        let mut rng_state: u64 = 12345;
        
        for i in 0..600 {
            let pos = if oracle.is_empty() {
                0
            } else {
                rng_state = rng_state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                (rng_state as usize) % (oracle.len() + 1)
            };
            rng_state = rng_state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let bit = (rng_state & 1) == 1;
            
            oracle.insert(pos, bit);
            bv.insert_bit(pos, bit);
            
            // Verify count_ones after each insert
            let expected_ones = oracle.iter().filter(|&&b| b).count() as u64;
            let actual_ones = bv.count_ones();
            if actual_ones != expected_ones {
                panic!(
                    "count_ones mismatch at i={}: actual={} expected={} len={}",
                    i, actual_ones, expected_ones, bv.len()
                );
            }
            
            // Verify rank(len, false) = count_zeros
            let expected_zeros = oracle.len() as u64 - expected_ones;
            let actual_zeros = bv.rank(bv.len(), false);
            if actual_zeros != expected_zeros {
                panic!(
                    "rank(len, false) mismatch at i={}: actual={} expected={} len={}",
                    i, actual_zeros, expected_zeros, bv.len()
                );
            }
        }
    }

    #[test]
    fn test_dwm_insert_bit_count_ones_vs_brute_force() {
        // Reproduce exact DWM insert pattern: insert bits at calculated positions
        // Use same LCG as the DWM test
        let mut rng_state: u64 = 98765;
        let mut next_sig = || -> [u8; 32] {
            let mut bytes = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                bytes[b] = (rng_state >> 8) as u8;
            }
            bytes
        };

        // Simulate level 0 of DWM: insert bits in sorted order at positions
        // determined by find_insert_position (binary search on sorted sigs)
        let mut bv = DynamicSuccinctBitVec::new();
        let mut sorted_sigs: Vec<[u8; 32]> = Vec::new();

        for i in 0..512 {
            let sig = next_sig();
            
            // Find insertion position (same as DWM's find_insert_position)
            let pos = sorted_sigs.binary_search(&sig).unwrap_or_else(|x| x);
            sorted_sigs.insert(pos, sig);
            
            // Level-0 bit is (sig[0] >> 0) & 1
            let bit = (sig[0] & 1) == 1;
            bv.insert_bit(pos, bit);
            
            // Brute-force count zeros
            let mut bf_zeros = 0usize;
            let mut bf_ones = 0usize;
            for j in 0..bv.len() {
                if bv.access(j) {
                    bf_ones += 1;
                } else {
                    bf_zeros += 1;
                }
            }
            
            let co = bv.count_ones();
            let cz = bv.rank(bv.len(), false);
            
            if bf_ones != co as usize {
                panic!(
                    "count_ones mismatch at i={}: brute_force_ones={} count_ones={} len={}",
                    i, bf_ones, co, bv.len()
                );
            }
            if bf_zeros != cz as usize {
                panic!(
                    "rank(len,false) mismatch at i={}: brute_force_zeros={} rank_zeros={} len={}",
                    i, bf_zeros, cz, bv.len()
                );
            }
        }
    }

    #[test]
    fn test_count_ones_after_many_block_splitsting_inserts() {
        // Insert 1000 bits via insert_bit (which triggers block splits)
        // and verify count_ones() matches brute force at every step
        let mut bv = DynamicSuccinctBitVec::new();
        let mut oracle: Vec<bool> = Vec::new();
        let mut rng_state: u64 = 77777;

        for i in 0..1500 {
            let pos = if oracle.is_empty() {
                0
            } else {
                rng_state = rng_state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                (rng_state as usize) % (oracle.len() + 1)
            };
            rng_state = rng_state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let bit = (rng_state & 1) == 1;

            oracle.insert(pos, bit);
            bv.insert_bit(pos, bit);

            // Brute-force count_ones
            let mut bf_ones = 0usize;
            for j in 0..bv.len() {
                if bv.access(j) {
                    bf_ones += 1;
                }
            }

            let co = bv.count_ones() as usize;
            if co != bf_ones {
                panic!(
                    "count_ones mismatch at i={}: count_ones={} brute_force={} len={} num_blocks={}",
                    i, co, bf_ones, bv.len(), bv.blocks.len()
                );
            }
        }
    }

    #[test]
    fn test_rank_after_many_inserts_with_splits() {
        // Replicate the DWM pattern: insert at sorted positions
        let mut bv = DynamicSuccinctBitVec::new();
        let mut oracle: Vec<bool> = Vec::new();
        let mut rng_state: u64 = 98765;

        for i in 0..600 {
            let mut sig = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                sig[b] = (rng_state >> 8) as u8;
            }
            
            // Find insertion position (binary search on sorted sigs)
            let pos = if oracle.is_empty() { 0 } else {
                // Simulate DWM's find_insert_position
                let lo = 0;
                let hi = oracle.len();
                // Just use a position based on i for determinism
                lo + ((hi - lo) * (i as usize) % (oracle.len() + 1).max(1)).min(hi - lo)
            };
            
            let bit = (sig[0] & 1) == 1;
            
            if pos <= oracle.len() {
                oracle.insert(pos, bit);
                bv.insert_bit(pos, bit);
            }
            
            // Verify rank at multiple positions
            for q in [0, bv.len() / 4, bv.len() / 2, 3 * bv.len() / 4, bv.len()] {
                if q <= bv.len() {
                    let rank_true = bv.rank(q, true) as usize;
                    let rank_false = bv.rank(q, false) as usize;
                    
                    let mut bf_true = 0;
                    let mut bf_false = 0;
                    for j in 0..q.min(bv.len()) {
                        if oracle[j] { bf_true += 1; } else { bf_false += 1; }
                    }
                    
                    assert_eq!(rank_true, bf_true, "rank({},{}) mismatch at i={}: rank={} bf={}", q, true, i, rank_true, bf_true);
                    assert_eq!(rank_false, bf_false, "rank({},{}) mismatch at i={}: rank={} bf={}", q, false, i, rank_false, bf_false);
                }
            }
        }
    }

    #[test]
    fn test_rank_exact_dwm_pattern() {
        // Exactly replicate what insert_bits_across_levels does:
        // For each of N entries, find insertion position via binary search on signatures,
        // then insert a bit at that position in the level-0 bitvec.
        // Then verify rank matches brute force.
        let mut bv = DynamicSuccinctBitVec::new();
        let mut rng_state: u64 = 98765;
        
        // Store sorted signatures and their level-0 bits
        let mut sorted_sigs: Vec<[u8; 32]> = Vec::new();
        let mut sorted_bits: Vec<bool> = Vec::new();
        
        for i in 0..600 {
            // Generate signature (same LCG as DWM test)
            let mut sig = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                sig[b] = (rng_state >> 8) as u8;
            }
            
            // Level-0 bit
            let bit = (sig[0] & 1) == 1;
            
            // Find insertion position (binary search on sorted sigs)
            let pos = sorted_sigs.binary_search(&sig).unwrap_or_else(|x| x);
            
            sorted_sigs.insert(pos, sig);
            sorted_bits.insert(pos, bit);
            bv.insert_bit(pos, bit);
            
            // Verify every position with rank
            if i % 50 == 0 || i < 10 {
                for q in 0..=bv.len() {
                    let rank_true = bv.rank(q, true) as usize;
                    let rank_false = bv.rank(q, false) as usize;
                    
                    let mut bf_true = 0;
                    let mut bf_false = 0;
                    for j in 0..q {
                        if sorted_bits[j] { bf_true += 1; } else { bf_false += 1; }
                    }
                    
                    assert_eq!(rank_true, bf_true, "rank({},{}) mismatch at i={} pos={}: rank={} bf={}", q, true, i, pos, rank_true, bf_true);
                    assert_eq!(rank_false, bf_false, "rank({},{}) mismatch at i={} pos={}: rank={} bf={}", q, false, i, pos, rank_false, bf_false);
                }
            }
        }
    }

    #[test]
    fn test_rank_dwm_multi_level_simulation() {
        // Simulate what insert_bits_across_levels does: multiple independent bitvecs,
        // each getting one insert_bit per DWM entry, at positions determined by rank navigation.
        // With 8 levels and 600 entries.
        let num_levels = 8;
        let mut bitvecs: Vec<DynamicSuccinctBitVec> = (0..num_levels).map(|_| DynamicSuccinctBitVec::new()).collect();
        let mut boundaries: Vec<usize> = vec![0; num_levels];
        
        let mut rng_state: u64 = 98765;
        let mut sorted_sigs: Vec<[u8; 32]> = Vec::new();
        
        for i in 0..600 {
            // Generate signature
            let mut sig = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                sig[b] = (rng_state >> 8) as u8;
            }
            
            // Find level-0 insertion position
            let p0 = sorted_sigs.binary_search(&sig).unwrap_or_else(|x| x);
            sorted_sigs.insert(p0, sig);
            
            // Insert bits across levels (same as insert_bits_across_levels)
            let mut p = p0;
            for level in 0..num_levels {
                let bit = (sig[level / 8] >> (level % 8)) & 1 == 1;
                let insert_pos = p; // Save insertion position BEFORE computing next
                
                // Insert bit
                bitvecs[level].insert_bit(insert_pos, bit);
                
                // Read boundary BEFORE update
                let boundary = boundaries[level];
                
                // Update boundary for 0-bit
                if !bit {
                    boundaries[level] += 1;
                }
                
                // Compute rank at insertion position
                let rank_at_insert = bitvecs[level].rank(insert_pos + 1, bit) as usize;
                
                // Compute next-level position
                if bit {
                    p = boundary + rank_at_insert - 1;
                } else {
                    p = rank_at_insert - 1;
                }
                
                // Brute-force verify rank
                let mut bf_count = 0usize;
                for j in 0..bitvecs[level].len().min(insert_pos + 1) {
                    if bitvecs[level].access(j) == bit {
                        bf_count += 1;
                    }
                }
                if rank_at_insert != bf_count {
                    let mut contents = String::new();
                    for j in 0..bitvecs[level].len() {
                        contents.push(if bitvecs[level].access(j) { '1' } else { '0' });
                    }
                    panic!(
                        "RANK BUG at level={} i={}: rank({},{})={} but bf={} len={} insert_pos={} bit={} contents={}",
                        level, i, insert_pos + 1, bit, rank_at_insert, bf_count, bitvecs[level].len(), insert_pos, bit, contents
                    );
                }
            }
        }
    }

    #[test]
    fn test_rank_after_two_inserts_at_front() {
        let mut bv = DynamicSuccinctBitVec::new();
        
        // First: insert true at position 0
        bv.insert_bit(0, true);
        println!("After insert(0, true): len={}, access(0)={}", bv.len(), bv.access(0));
        assert_eq!(bv.len(), 1);
        assert!(bv.access(0));
        assert_eq!(bv.rank(1, true), 1);
        assert_eq!(bv.rank(1, false), 0);
        
        // Second: insert false at position 0 (push existing true to position 1)
        bv.insert_bit(0, false);
        println!("After insert(0, false): len={}, access(0)={}, access(1)={}", bv.len(), bv.access(0), bv.access(1));
        assert_eq!(bv.len(), 2);
        assert!(!bv.access(0));  // position 0 = false
        assert!(bv.access(1));   // position 1 = true
        
        // rank(1, true) = count of true in [0,1) = just position 0 = false = 0
        assert_eq!(bv.rank(1, true), 0, "rank(1,true) should be 0 but got {}", bv.rank(1, true));
        // rank(1, false) = count of false in [0,1) = just position 0 = false = 1
        assert_eq!(bv.rank(1, false), 1);
        // rank(2, true) = count of true in [0,2) = 1
        assert_eq!(bv.rank(2, true), 1);
        // rank(2, false) = count of false in [0,2) = 1
        assert_eq!(bv.rank(2, false), 1);
    }

    #[test]
    fn test_rank_exact_multi_level_sequence() {
        // Reproduce the exact sequence from test_rank_dwm_multi_level_simulation
        // that triggers RANK BUG at level=1 i=1
        let mut bitvecs: Vec<DynamicSuccinctBitVec> = (0..8).map(|_| DynamicSuccinctBitVec::new()).collect();
        
        let mut rng_state: u64 = 98765;
        
        // i=0
        let mut sig0 = [0u8; 32];
        for b in 0..32 {
            rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
            sig0[b] = (rng_state >> 8) as u8;
        }
        // sig0 = the first signature from the LCG
        
        // i=1
        let mut sig1 = [0u8; 32];
        for b in 0..32 {
            rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
            sig1[b] = (rng_state >> 8) as u8;
        }
        
        // Manually compute sorted order
        // sig0 and sig1 need to be compared
        println!("sig0[0]={:08b}, sig1[0]={:08b}", sig0[0], sig1[0]);
        println!("sig0={:?}...", &sig0[..4]);
        println!("sig1={:?}...", &sig1[..4]);
        
        // Find insertion positions
        // The first signature always goes at position 0
        let p0_0 = 0;
        
        // For i=0: insert bits across levels starting at p0=0
        let mut p = p0_0;
        for level in 0..8 {
            let bit = (sig0[level / 8] >> (level % 8)) & 1 == 1;
            bitvecs[level].insert_bit(p, bit);
            
            let boundary = 0; // Initially 0
            let rank_before = bitvecs[level].rank(p + 1, bit) as usize;
            
            // Brute-force verify
            let mut bf_count = 0usize;
            for j in 0..=p.min(bitvecs[level].len() - 1) {
                if bitvecs[level].access(j) == bit { bf_count += 1; }
            }
            assert_eq!(rank_before, bf_count, "i=0 level={}: rank({},{})={} but bf={}", level, p+1, bit, rank_before, bf_count);
            
            if bit {
                p = boundary + rank_before - 1;
            } else {
                p = rank_before - 1;
            }
        }
        
        // Now i=1
        // Find where sig1 goes in sorted order relative to sig0
        let p0_1 = if sig1 < sig0 { 0 } else { 1 };
        let mut p = p0_1;
        
        for level in 0..8 {
            let bit = (sig1[level / 8] >> (level % 8)) & 1 == 1;
            bitvecs[level].insert_bit(p, bit);
            
            let rank_before = bitvecs[level].rank(p + 1, bit) as usize;
            
            // Brute-force verify
            let mut bf_count = 0usize;
            for j in 0..bitvecs[level].len().min(p + 1) {
                if bitvecs[level].access(j) == bit { bf_count += 1; }
            }
            assert_eq!(rank_before, bf_count, "i=1 level={}: rank({},{})={} but bf={}", level, p+1, bit, rank_before, bf_count);
            
            let boundary = if bit { 0 } else { 1 }; // updated boundary for level 0/1
            if bit {
                p = boundary + rank_before - 1;
            } else {
                p = rank_before - 1;
            }
        }
    }

    #[test]
    fn test_rank_insert_true_at_0_then_false_at_1() {
        // Exact sequence from the multi-level simulation:
        // Level 1 bitvec: insert true at position 0, then insert false at position 1
        // Expected: bitvec = [true, false] = "10"
        // rank(1, false) should be 0 (position 0 is true, not false)
        // rank(2, false) should be 1 (position 1 is false)
        // rank(1, true) should be 1 (position 0 is true)
        let mut bv = DynamicSuccinctBitVec::new();
        
        bv.insert_bit(0, true);
        eprintln!("After insert(0, true): len={}", bv.len());
        for i in 0..bv.len() {
            eprintln!("  access({}) = {}", i, bv.access(i));
        }
        eprintln!("  rank(0, true)={}, rank(1, true)={}", bv.rank(0, true), bv.rank(1, true));
        eprintln!("  rank(0, false)={}, rank(1, false)={}", bv.rank(0, false), bv.rank(1, false));
        eprintln!("  count_ones={}", bv.count_ones());
        
        assert_eq!(bv.len(), 1);
        assert!(bv.access(0), "position 0 should be true");
        assert_eq!(bv.rank(1, true), 1, "rank(1,true) after first insert");
        assert_eq!(bv.rank(1, false), 0, "rank(1,false) after first insert");
        
        bv.insert_bit(1, false);
        eprintln!("After insert(1, false): len={}", bv.len());
        for i in 0..bv.len() {
            eprintln!("  access({}) = {}", i, bv.access(i));
        }
        eprintln!("  rank(0, true)={}, rank(1, true)={}, rank(2, true)={}", bv.rank(0, true), bv.rank(1, true), bv.rank(2, true));
        eprintln!("  rank(0, false)={}, rank(1, false)={}, rank(2, false)={}", bv.rank(0, false), bv.rank(1, false), bv.rank(2, false));
        eprintln!("  count_ones={}", bv.count_ones());
        
        assert_eq!(bv.len(), 2);
        assert!(bv.access(0), "position 0 should be true");
        assert!(!bv.access(1), "position 1 should be false");
        assert_eq!(bv.rank(1, true), 1, "rank(1,true) = count of true in [0,1)");
        assert_eq!(bv.rank(1, false), 0, "rank(1,false) = count of false in [0,1)");
        assert_eq!(bv.rank(2, true), 1, "rank(2,true) = count of true in [0,2)");
        assert_eq!(bv.rank(2, false), 1, "rank(2,false) = count of false in [0,2)");
        assert_eq!(bv.count_ones(), 1, "count_ones should be 1");
    }

    #[test]
    fn test_rank_insert_true_at_0_then_false_at_1_append() {
        // Exact sequence from the multi-level simulation:
        // Level 1: insert true at position 0, then insert false at position 1 (appending)
        // Result: [true, false] = "10"
        let mut bv = DynamicSuccinctBitVec::new();
        
        bv.insert_bit(0, true);   // [true]
        bv.insert_bit(1, false);  // [true, false] — appending at the end
        
        // Verify contents
        assert_eq!(bv.len(), 2);
        assert!(bv.access(0), "position 0 should be true");
        assert!(!bv.access(1), "position 1 should be false");
        
        // Verify rank
        let r0t = bv.rank(0, true); let r1t = bv.rank(1, true); let r2t = bv.rank(2, true);
        let r0f = bv.rank(0, false); let r1f = bv.rank(1, false); let r2f = bv.rank(2, false);
        
        eprintln!("rank(0,true)={} rank(1,true)={} rank(2,true)={}", r0t, r1t, r2t);
        eprintln!("rank(0,false)={} rank(1,false)={} rank(2,false)={}", r0f, r1f, r2f);
        eprintln!("count_ones={}", bv.count_ones());
        
        // Brute force: [true, false]
        // rank(0,true) = 0, rank(1,true) = 1, rank(2,true) = 1
        // rank(0,false) = 0, rank(1,false) = 0, rank(2,false) = 1
        assert_eq!(r0t, 0, "rank(0,true)");
        assert_eq!(r1t, 1, "rank(1,true)");
        assert_eq!(r2t, 1, "rank(2,true)");
        assert_eq!(r0f, 0, "rank(0,false)");
        assert_eq!(r1f, 0, "rank(1,false)");  // <-- THIS is the bug
        assert_eq!(r2f, 1, "rank(2,false)");
    }

    #[test]
    fn test_rank_exact_level0_sequence() {
        // Reproduce the EXACT level-0 insert sequence from the multi-level simulation
        // until the rank bug appears (i=512, level=0)
        let mut bv = DynamicSuccinctBitVec::new();
        let mut rng_state: u64 = 98765;
        let mut sorted_sigs: Vec<[u8; 32]> = Vec::new();
        
        for i in 0..513 {
            let mut sig = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                sig[b] = (rng_state >> 8) as u8;
            }
            
            let p0 = sorted_sigs.binary_search(&sig).unwrap_or_else(|x| x);
            sorted_sigs.insert(p0, sig);
            
            let bit = (sig[0] & 1) == 1;
            bv.insert_bit(p0, bit);
            
            // Verify rank at every position after each insert
            if i >= 510 {
                for q in 0..=bv.len() {
                    let rank_true = bv.rank(q, true) as usize;
                    let rank_false = bv.rank(q, false) as usize;
                    
                    let mut bf_true = 0;
                    let mut bf_false = 0;
                    for j in 0..q {
                        if bv.access(j) { bf_true += 1; } else { bf_false += 1; }
                    }
                    
                    assert_eq!(rank_true, bf_true, "rank({},{}) mismatch at i={}: rank={} bf={}", q, true, i, rank_true, bf_true);
                    assert_eq!(rank_false, bf_false, "rank({},{}) mismatch at i={}: rank={} bf={}", q, false, i, rank_false, bf_false);
                }
            }
        }
    }

    #[test]
    fn test_rank_and_fenwick_after_513_inserts() {
        let mut bv = DynamicSuccinctBitVec::new();
        let mut rng_state: u64 = 98765;
        let mut sorted_sigs: Vec<[u8; 32]> = Vec::new();
        let mut oracle: Vec<bool> = Vec::new();
        
        for _i in 0..513 {
            let mut sig = [0u8; 32];
            for b in 0..32 {
                rng_state = rng_state.wrapping_mul(1103515245).wrapping_add(12345);
                sig[b] = (rng_state >> 8) as u8;
            }
            
            let p0 = sorted_sigs.binary_search(&sig).unwrap_or_else(|x| x);
            sorted_sigs.insert(p0, sig);
            
            let bit = (sig[0] & 1) == 1;
            oracle.insert(p0, bit);
            bv.insert_bit(p0, bit);
        }
        
        // Dump block structure
        eprintln!("BitVec: num_bits={}, num_blocks={}", bv.num_bits, bv.blocks.len());
        for (bi, block) in bv.blocks.iter().enumerate() {
            eprintln!("  Block {}: len={}, ones={}", bi, block.len, block.ones);
        }
        eprintln!("len_fenwick prefix_sum(B): {}", bv.len_fenwick.prefix_sum(bv.blocks.len()));
        
        // Check fenwick tree against brute-force block ones
        let mut cum_ones = 0u32;
        for bi in 0..bv.blocks.len() {
            let fenwick_sum = bv.fenwick.prefix_sum(bi + 1); // 1-indexed
            cum_ones += bv.blocks[bi].ones as u32;
            eprintln!("  fenwick.prefix_sum({})={}, cum_ones={}", bi + 1, fenwick_sum, cum_ones);
            assert_eq!(fenwick_sum, cum_ones, "fenwick sum mismatch at block {}", bi);
        }
        
        // Check locate function
        eprintln!("\nLocate checks:");
        for pos in [0, 1, 255, 256, 257, 511, 512] {
            if pos <= bv.len() {
                let (bi, lp) = bv.locate(pos);
                eprintln!("  locate({}) = (block={}, local={})", pos, bi, lp);
            }
        }
        
        // Check rank at key positions
        for pos in [256, 257] {
            if pos <= bv.len() {
                let rank_true = bv.rank(pos, true) as usize;
                let rank_false = bv.rank(pos, false) as usize;
                let mut bf_true = 0;
                let mut bf_false = 0;
                for j in 0..pos {
                    if oracle[j] { bf_true += 1; } else { bf_false += 1; }
                }
                eprintln!("rank({},{}) = {} (bf={}), rank({},{}) = {} (bf={})", 
                    pos, true, rank_true, bf_true, pos, false, rank_false, bf_false);
                assert_eq!(rank_true, bf_true, "rank({},{}) mismatch", pos, true);
                assert_eq!(rank_false, bf_false, "rank({},{}) mismatch", pos, false);
            }
        }
    }
