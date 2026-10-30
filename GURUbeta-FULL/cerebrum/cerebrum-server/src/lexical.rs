//! Lexical retrieval: BM25 over an inverted index.
//!
//! This is the lane that answers "does this memory contain the words the user
//! typed". It replaces the word-overlap fallback that used to live inline in
//! `handle_search`, which counted shared query words and scored them as a
//! fraction of the query — the same idea BM25 encodes properly, minus term
//! frequency saturation, document-length normalisation and IDF.
//!
//! Why BM25 rather than the 256-bit signature lane:
//!
//! The signature is a random projection of hashed words. It has no IDF, no term
//! frequency beyond a raw count, and no length normalisation. Measured against
//! real memories, a fixed Hamming radius cannot separate a relevant query from
//! an irrelevant one: at a radius admitting 2% junk it recalls 57%, and at the
//! radius that recalls 83% it admits 10.4% junk (see
//! `cerebrum-signatures/tests/radius_eval.rs`). BM25 handles all of that with
//! corpus statistics, so it is the better lexical lane and the one that should
//! carry this job.
//!
//! The signature lane is kept for the two jobs BM25 cannot do: symmetric
//! document-to-document comparison (consolidation, clustering) and as a
//! container for binarised dense vectors later. It is no longer the primary
//! retrieval path.

use std::collections::HashMap;

use cerebrum_core::MemoryId;

/// BM25 tuning. Standard defaults; k1 controls term-frequency saturation and b
/// controls how much document length is penalised.
const K1: f32 = 1.2;
const B: f32 = 0.75;

/// One indexed document.
#[derive(Debug, Clone)]
struct Document {
    /// Term frequency per term.
    term_freqs: HashMap<String, u32>,
    /// Number of tokens in the document, for length normalisation.
    length: u32,
}

/// Inverted lexical index with BM25 scoring.
///
/// Maintained alongside the context tree: every curate write, update, merge and
/// delete updates both, so the index can never drift out of step with what the
/// tree holds. A query scores against whatever is currently indexed.
#[derive(Debug, Default)]
pub struct LexicalIndex {
    /// memory id -> document
    documents: HashMap<MemoryId, Document>,
    /// term -> (memory id -> term frequency)
    postings: HashMap<String, HashMap<MemoryId, u32>>,
    /// Sum of all document lengths, for the average.
    total_length: u64,
}

impl LexicalIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Number of indexed documents.
    pub fn len(&self) -> usize {
        self.documents.len()
    }

    pub fn is_empty(&self) -> bool {
        self.documents.is_empty()
    }

    /// Add or replace a document.
    ///
    /// Replacing first removes the old terms so an update can never leave stale
    /// postings behind — that is the same class of bug that made consolidation
    /// remove a memory from every index while leaving its row in the tree.
    pub fn upsert(&mut self, id: MemoryId, content: &str) {
        if self.documents.contains_key(&id) {
            self.remove(id);
        }

        let tokens = tokenize(content);
        if tokens.is_empty() {
            return;
        }

        let mut term_freqs: HashMap<String, u32> = HashMap::new();
        for token in &tokens {
            *term_freqs.entry(token.clone()).or_insert(0) += 1;
        }

        for (term, count) in &term_freqs {
            self.postings
                .entry(term.clone())
                .or_default()
                .insert(id, *count);
        }

        let length = tokens.len() as u32;
        self.total_length += length as u64;
        self.documents.insert(id, Document { term_freqs, length });
    }

    /// Remove a document and every posting that referenced it.
    pub fn remove(&mut self, id: MemoryId) {
        let Some(doc) = self.documents.remove(&id) else {
            return;
        };
        self.total_length = self.total_length.saturating_sub(doc.length as u64);
        for term in doc.term_freqs.keys() {
            if let Some(entry) = self.postings.get_mut(term) {
                entry.remove(&id);
                if entry.is_empty() {
                    self.postings.remove(term);
                }
            }
        }
    }

    /// Rebuild the whole index from the current tree contents.
    ///
    /// Used at boot and after consolidation, so the index is never a claim about
    /// history that the tree cannot back up.
    pub fn rebuild<I>(&mut self, entries: I)
    where
        I: IntoIterator<Item = (MemoryId, String)>,
    {
        self.documents.clear();
        self.postings.clear();
        self.total_length = 0;
        for (id, content) in entries {
            self.upsert(id, &content);
        }
    }

    /// Score one document against a query with BM25.
    fn score(&self, query_terms: &[String], doc: &Document, avg_len: f32) -> f32 {
        let mut total = 0.0f32;
        let doc_len = doc.length as f32;

        for term in query_terms {
            let Some(tf) = doc.term_freqs.get(term) else {
                continue;
            };
            let tf = *tf as f32;

            // IDF over documents that contain the term. The 0.5 + guards against
            // a division by zero when every document holds the term.
            let df = self
                .postings
                .get(term)
                .map(|postings| postings.len())
                .unwrap_or(0) as f32;
            let n = self.documents.len() as f32;
            let idf = (((n - df + 0.5) / (df + 0.5)) + 1.0).ln();

            // Length-normalised saturating term frequency.
            let denom = tf + K1 * (1.0 - B + B * (doc_len / avg_len));
            total += idf * (tf * (K1 + 1.0)) / denom;
        }

        total
    }

    /// Score every document against the query and return the best `limit`.
    ///
    /// Rank-based, not threshold-based. A fixed score or distance cutoff is what
    /// leaked junk in the signature lane: the number of results it admits grows
    /// with corpus size, and its meaning shifts as the corpus changes. Top-k is
    /// stable under both.
    pub fn search(&self, query: &str, limit: usize) -> Vec<(MemoryId, f32)> {
        if self.documents.is_empty() || limit == 0 {
            return Vec::new();
        }

        let query_terms = tokenize(query);
        if query_terms.is_empty() {
            return Vec::new();
        }

        let avg_len = if self.documents.is_empty() {
            1.0
        } else {
            (self.total_length as f32 / self.documents.len() as f32).max(1.0)
        };

        let mut scored: Vec<(MemoryId, f32)> = self
            .documents
            .iter()
            .filter_map(|(id, doc)| {
                let score = self.score(&query_terms, doc, avg_len);
                // A document sharing no query term scores exactly zero; it is not
                // a weak match, it is not a match. Dropping it is the one cutoff
                // that is meaningful without calibration.
                if score > 0.0 {
                    Some((*id, score))
                } else {
                    None
                }
            })
            .collect();

        scored.sort_by(|a, b| {
            b.1.partial_cmp(&a.1)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then_with(|| a.0 .0.cmp(&b.0 .0))
        });
        scored.truncate(limit);
        scored
    }
}

/// Common English words that carry no retrieval signal.
///
/// IDF pushes these down but cannot remove them: in a small corpus a word
/// present in every document still scores above zero, so a query like "the
/// history of the roman empire" matched a router memory purely because both
/// contain "the". Dropping them at index time makes a shared function word
/// count as no match at all, which is what a user means by "unrelated".
const STOP_WORDS: &[&str] = &[
    "the", "and", "for", "that", "with", "this", "was", "are", "were", "has", "had", "have",
    "does", "did", "what", "when", "why", "which", "who", "whom", "you", "your", "our", "from",
    "its", "his", "her", "their", "them", "they", "but", "all", "not", "into", "will", "can",
    "would", "should", "could", "there", "here", "then", "than", "now", "out", "about", "been",
    "being", "some", "any", "more", "most", "other", "such", "only", "own", "same", "too", "very",
    "just", "also", "how", "where", "get", "got", "one", "two", "new", "old",
];

/// Split text into lowercase alphanumeric terms, dropping stop words.
///
/// Three-character minimum removes the shortest function words; the stop list
/// removes the rest. Without it, a query sharing only "the" with a memory scored
/// as a match and surfaced junk.
fn tokenize(text: &str) -> Vec<String> {
    text.to_lowercase()
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| w.len() >= 3)
        .filter(|w| !STOP_WORDS.contains(w))
        .map(|w| w.to_string())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(byte: u8) -> MemoryId {
        MemoryId([byte; 32])
    }

    #[test]
    fn exact_term_match_ranks_first() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the landlord still has not fixed the boiler in the flat");
        index.upsert(id(2), "italian pasta recipe uses fresh tomato and basil");
        index.upsert(id(3), "quantum particles behave probabilistically under measurement");

        let results = index.search("boiler", 3);
        assert_eq!(results.len(), 1, "only the boiler memory shares a term");
        assert_eq!(results[0].0, id(1));
    }

    #[test]
    fn no_shared_term_returns_nothing() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the landlord still has not fixed the boiler in the flat");
        index.upsert(id(2), "italian pasta recipe uses fresh tomato and basil");

        let results = index.search("quantum particles", 5);
        assert!(
            results.is_empty(),
            "a query sharing no term with any document must return nothing, \
             not a list of weak matches"
        );
    }

    #[test]
    fn multi_term_query_prefers_document_sharing_more_terms() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "spare key hangs in the kitchen cupboard behind the microwave");
        index.upsert(id(2), "the kitchen is painted green");
        index.upsert(id(3), "car insurance renews in november");

        let results = index.search("kitchen key cupboard", 3);
        assert!(!results.is_empty());
        assert_eq!(
            results[0].0,
            id(1),
            "the memory sharing three query terms must outrank the one sharing one"
        );
    }

    #[test]
    fn idf_penalises_terms_present_everywhere() {
        let mut index = LexicalIndex::new();
        // "remember" appears in all four, "wedding" in one.
        index.upsert(id(1), "remember the wedding is in bristol");
        index.upsert(id(2), "remember the car insurance renewal");
        index.upsert(id(3), "remember the dentist appointment");
        index.upsert(id(4), "remember the gym membership");

        // A query of only the ubiquitous term should rank all four roughly level;
        // adding the rare term must pull its document to the top.
        let with_rare = index.search("remember wedding", 4);
        assert!(!with_rare.is_empty());
        assert_eq!(
            with_rare[0].0,
            id(1),
            "the rare term must dominate a term present in every document"
        );
    }

    #[test]
    fn update_replaces_old_terms() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the boiler is broken");
        assert_eq!(index.search("boiler", 5).len(), 1);

        index.upsert(id(1), "the boiler has been fixed");
        assert_eq!(index.search("broken", 5).len(), 0, "stale posting survived");
        assert_eq!(index.search("fixed", 5).len(), 1);
    }

    #[test]
    fn remove_clears_postings_and_length() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the boiler is broken");
        index.upsert(id(2), "pasta with basil");
        assert_eq!(index.len(), 2);

        index.remove(id(1));
        assert_eq!(index.len(), 1);
        assert_eq!(index.search("boiler", 5).len(), 0);
        assert_eq!(index.search("basil", 5).len(), 1);
    }

    #[test]
    fn rebuild_matches_incremental_state() {
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the boiler is broken");
        index.upsert(id(2), "pasta with basil");
        index.remove(id(1));

        index.rebuild(vec![
            (id(1), "the boiler is broken".to_string()),
            (id(2), "pasta with basil".to_string()),
        ]);

        assert_eq!(index.len(), 2);
        assert_eq!(index.search("boiler", 5)[0].0, id(1));
        assert_eq!(index.search("basil", 5)[0].0, id(2));
    }

    #[test]
    fn rank_is_stable_under_corpus_growth() {
        // The criticism of a fixed distance threshold was that the count of
        // admitted results grows with corpus size. Top-k must not: the relevant
        // memory stays at rank 1 no matter how much unrelated text surrounds it.
        let mut index = LexicalIndex::new();
        index.upsert(id(1), "the spare key hangs in the kitchen cupboard");
        for i in 2..50u8 {
            index.upsert(id(i), "unrelated memory about something else entirely");
        }
        let results = index.search("kitchen cupboard key", 5);
        assert_eq!(results[0].0, id(1), "relevant memory must stay rank 1 at scale");
        assert!(results.len() <= 5, "top-k must stay capped");
    }
}
