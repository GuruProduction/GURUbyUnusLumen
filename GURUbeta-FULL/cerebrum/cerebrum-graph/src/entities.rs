//! Cerebrum Entity Resolution (Phase E: real graph-side reconciliation).
//!
//! Same-referent, different-spelling label nodes reconcile via the dream
//! cycle: canonical = lexicographically smallest id in a canonicalized-label
//! group; duplicate edges rewire to canonical; the retired label stays as an
//! alias row so any spelling keeps resolving. Touches ONLY GraphStore state.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::{Edge, GraphStore, MemoryId, QueryType};

/// One reconciliation row; hex ids keep the shape wire-friendly.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EntityReconciliationDetail {
    pub kept_id_hex: String,
    pub retired_id_hex: String,
    pub alias_row: String,
    pub edges_rewired: usize,
}

/// Case-fold + punctuation-collapse canonicalization for identity compare.
pub fn canonicalize_label(input_label: &str) -> String {
    let lowered = input_label.to_lowercase();
    let mut rebuilt = String::with_capacity(lowered.len());
    let mut needs_space = false;
    for character in lowered.chars() {
        if character.is_alphanumeric() {
            rebuilt.push(character);
            needs_space = false;
        } else if needs_space == false && rebuilt.is_empty() == false {
            rebuilt.push(' ');
            needs_space = true;
        }
    }
    rebuilt.trim().to_string()
}

/// Full sweep across every view; one row per real merge; deterministic via
/// id-bytes sorted canonical pick.
pub fn reconcile_entities(store: &mut GraphStore) -> Vec<EntityReconciliationDetail> {
    let label_rows: Vec<(MemoryId, String)> = store
        .labels_entries()
        .map(|(memory_id, label_text)| (memory_id, label_text.to_string()))
        .collect();

    let mut groups: HashMap<String, Vec<MemoryId>> = HashMap::new();
    for (memory_id, raw_label) in label_rows.iter() {
        let canonical_key = canonicalize_label(raw_label);
        if canonical_key.is_empty() {
            continue;
        }
        groups.entry(canonical_key).or_default().push(*memory_id);
    }

    let mut details: Vec<EntityReconciliationDetail> = Vec::new();
    for raw_group_ids in groups.into_values() {
        if raw_group_ids.len() < 2 {
            continue;
        }
        let mut stable_group_ids = raw_group_ids;
        stable_group_ids.sort_by_key(|memory_id| memory_id.0);
        let kept_id = stable_group_ids[0];

        for retired_id in stable_group_ids.into_iter().skip(1) {
            let alias_text = store.label_of(retired_id).unwrap_or("").to_string();
            let moved_count = rewire_and_retire(store, kept_id, retired_id);
            if alias_text.is_empty() == false {
                store.add_alias_row(kept_id, alias_text.clone());
                store.remove_label(retired_id);
            }
            details.push(EntityReconciliationDetail {
                kept_id_hex: bytes_hex(&kept_id.0),
                retired_id_hex: bytes_hex(&retired_id.0),
                alias_row: alias_text,
                edges_rewired: moved_count,
            });
        }
    }
    details
}

fn bytes_hex(bytes_value: &[u8]) -> String {
    bytes_value.iter().map(|byte| format!("{:02x}", byte)).collect()
}

/// Rewire (source or target) rows onto canonical per view; retired id then
/// gets retired from that view. Real moved-edge count returned.
fn rewire_and_retire(store: &mut GraphStore, canonical: MemoryId, retired: MemoryId) -> usize {
    let mut moved_count_all_views = 0usize;

    for view_shape in [QueryType::Semantic, QueryType::Temporal, QueryType::Causal, QueryType::Entity] {
        let view_rows: Vec<Edge> = match store.graph_for(view_shape) {
            Some(view) => view.all_edges().cloned().collect(),
            None => continue,
        };

        // Removal first (drop all rows the retired id appears in), then
        // direct reinsertions for non-collapsed rows.
        {
            let Some(rewriter) = store.graph_for_mut(view_shape) else { continue };

            for row in &view_rows {
                let source_is = row.source == retired;
                let target_is = row.target == retired;
                if source_is == false && target_is == false {
                    continue;
                }
                rewriter.remove_edge(row);
            }
        }
        {
            let Some(adder) = store.graph_for_mut(view_shape) else { continue };
            for row in &view_rows {
                let source_is = row.source == retired;
                let target_is = row.target == retired;
                if source_is == false && target_is == false {
                    continue;
                }
                // True collapsed self-loop rows simply vanish.
                if (source_is && target_is)
                    || (source_is && row.target == canonical)
                    || (target_is && row.source == canonical)
                {
                    continue;
                }
                let (new_source, new_target) = if source_is {
                    (canonical, row.target)
                } else {
                    (row.source, canonical)
                };
                let mut rebuilt = Edge::new(new_source, new_target, row.relation, row.weight);
                rebuilt.timestamp = row.timestamp;
                rebuilt.metadata = row.metadata.clone();
                adder.add_edge(rebuilt);
                moved_count_all_views += 1;
            }
            adder.retire_node(retired);
        }
    }
    moved_count_all_views
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{GraphStore, SemanticRelation, TemporalRelation};

    #[test]
    fn two_spellings_collapse_to_one_canonical_with_real_rewires() {
        let mut store = GraphStore::new();

        let id_alpha = MemoryId([7u8; 32]);
        let id_beta = MemoryId([9u8; 32]);
        store.add_label(id_alpha, "Steven Newman");
        store.add_label(id_beta, "steven newman??");

        let witness_memory = MemoryId([41u8; 32]);
        // Out-row against the retired-node spellings.
        store.semantic.add_edge(Edge::new(
            id_beta,
            witness_memory,
            crate::Relation::SemanticEdge(SemanticRelation::RelatedTo),
            0.7,
        ));
        // In-row of the real temporal shape.
        store.temporal.add_edge(Edge::new(
            witness_memory,
            id_beta,
            crate::Relation::TemporalEdge(TemporalRelation::Before),
            1.0,
        ));

        let details = reconcile_entities(&mut store);
        assert_eq!(details.len(), 1, "one real merge row");
        let detail = details.first().unwrap();
        assert_eq!(detail.edges_rewired, 2, "both cross-view rows rewire");

        // The graph now carries NO retired-id shape on any view.
        assert_eq!(
            store.semantic.all_edges().any(|row| row.source == id_beta || row.target == id_beta),
            false
        );
        assert_eq!(
            store.temporal.all_edges().any(|row| row.source == id_beta || row.target == id_beta),
            false
        );
        // The retired id is gone from node sets and carries no label rows.
        assert!(store.label_of(id_beta).is_none());

        // Canonical alias row recorded.
        let canonical_hex = detail.kept_id_hex.clone();
        let canonical_id = crate::decode_hex_to_memory(&canonical_hex).expect("canonical");
        assert!(
            store.alias_rows_for(canonical_id).contains(&"steven newman??".to_string()),
            "alias text of retired spelling is recorded"
        );
    }
}

/// Hex -> MemoryId reader (engine-side); matches the engine's bytes-hex.
pub fn decode_hex_to_memory(input_hex: &str) -> Option<MemoryId> {
    let normalized = input_hex.to_lowercase();
    if normalized.len() != 64 {
        return None;
    }
    if normalized.chars().all(|char_value| char_value.is_ascii_hexdigit()) == false {
        return None;
    }
    let raw = hex::decode(normalized).ok()?;
    Some(MemoryId(raw.try_into().ok()?))
}