//! Cerebrum Token — Token-ID compression, vocabulary mapping, and reconstruction.
//!
//! Phase 0.2.4 of the Guru Build Bible.
//!
//! # Architecture
//!
//! ```text
//! text → tokenize → Vec<u32> (raw token IDs)
//!     → delta encode → Vec<i32> (differences)
//!     → varint encode → Vec<u8> (compact bytes)
//!     → CompressedTokenSequence
//!
//! CompressedTokenSequence
//!     → varint decode → Vec<i32> (deltas)
//!     → delta decode → Vec<u32> (raw token IDs)
//!     → detokenize → text
//! ```
//!
//! # Key properties
//! - **Lossless**: every token ID preserved exactly
//! - **Compact**: small deltas → short varints → fewer bytes
//! - **Fast**: <1µs per encode/decode per the Bible spec
//!
//! # Vocabulary support
//! - JSON vocabulary files (token → ID mapping)
//! - BPE merge file format
//! - Simple string-split tokenizer (word-level, for testing)
//! - Configurable vocabulary sizes: 32K–128K tokens

#![warn(missing_docs)]
#![warn(clippy::all)]

pub mod compress;
pub mod error;
pub mod reconstruct;
pub mod tokenizer;
pub mod varint;
pub mod vocabulary;

pub use compress::{decode_deltas, encode_deltas, CompressedTokenSequence, TokenCompressor};
pub use error::{TokenError, TokenResult};
pub use reconstruct::{detokenize, reconstruct_sequence};
pub use tokenizer::{simple_tokenize, SimpleTokenizer, Tokenizer};
pub use varint::{decode_varint, decode_varint_sequence, encode_varint, encode_varint_sequence};
pub use vocabulary::{TokenVocabulary, VocabularyLoader};
