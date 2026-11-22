//! Two behaviours that consolidation owns, asserted on what they DO rather than on the
//! report that says they ran.
//!
//! WHY THIS FILE EXISTS SEPARATELY FROM `consolidate_integrity`. That suite asks whether
//! consolidation leaves memories findable, which is the question a merge creates. These are the
//! other two things the pass is responsible for, and neither was proved anywhere by an
//! observation:
//!
//!   1. THE ENTITY-EDGE PASS. `build_semantic_and_entity_edges` creates an Entity-view edge
//!      whenever one memory names a proper noun another memory also names. It returns nothing
//!      and logs nothing, so the only way to know it ran is to count the edges it produced.
//!      A zero there is invisible unless something else writes entity edges.
//!
//!   2. RETIREMENT BY DECAY. A memory that is faint and old is archived by the decay engine,
//!      which sets a flag on ITS OWN row. Every read surface filters on the context tree's
//!      lifecycle instead, so for a long time the two engines held different opinions and a
//!      memory archived by decay still answered searches a year later. The handler publishes
//!      the archive decision to the tree now, and this file asserts the consequence at every
//!      read surface rather than the flag.
//!
//! Both are driven through `handle_frame`, so what is under test is the production path a
//! CONSOLIDATE frame takes, not a helper called directly.

use std::sync::Arc;

use chrono::Utc;
use tokio::sync::Mutex;

use cerebrum_core::{ContextEntry, CurateOp, MemoryId};
use cerebrum_server::frame_types;
use cerebrum_server::handler::handle_frame;
use cerebrum_server::state::CerebrumState;
use cerebrum_server::Frame;

async fn fresh_state() -> Arc<Mutex<CerebrumState>> {
    let dir = std::env::temp_dir().join(format!(
        "cerebrum-entity-retirement-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    Arc::new(Mutex::new(CerebrumState::new(dir)))
}

async fn call(state: &Arc<Mutex<CerebrumState>>, frame_type: u8, payload: &[u8]) -> Vec<u8> {
    handle_frame(
        Frame::new(frame_type, 4242, payload.to_vec()),
        state.clone(),
    )
    .await
    .unwrap_or_else(|e| serde_json::to_vec(&serde_json::json!({ "error": e.message })).unwrap())
}

fn add(id: MemoryId, content: &str, domain: &str) -> CurateOp {
    CurateOp::Add {
        entry: ContextEntry {
            memory_id: id,
            domain: domain.into(),
            topic: "entity".into(),
            subtopic: "test".into(),
            content: content.into(),
            relations: vec![],
            provenance: "entity-and-retirement".into(),
            rationale: "entity and retirement contract".into(),
            owner_agent_id: String::new(),
            visibility: String::new(),
            created_at: Utc::now(),
            modified_at: Utc::now(),
        },
        reason: "entity and retirement add".into(),
    }
}

async fn curate(state: &Arc<Mutex<CerebrumState>>, ops: Vec<CurateOp>) {
    let payload = serde_json::to_vec(&ops).unwrap();
    let bytes = call(state, frame_types::CURATE, &payload).await;
    let ack: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(
        ack["executed"]["success"], true,
        "curation must succeed before the pass can be tested: {ack}"
    );
}

async fn consolidate(state: &Arc<Mutex<CerebrumState>>) -> serde_json::Value {
    let bytes = call(state, frame_types::CONSOLIDATE, b"{}").await;
    serde_json::from_slice(&bytes).expect("consolidate always answers with a report")
}

async fn search_contents(state: &Arc<Mutex<CerebrumState>>, query: &str) -> Vec<String> {
    let payload = serde_json::to_vec(&serde_json::json!({ "query": query, "limit": 20 })).unwrap();
    let bytes = call(state, frame_types::SEARCH, &payload).await;
    let hits: Vec<serde_json::Value> = serde_json::from_slice(&bytes).unwrap_or_default();
    hits.iter()
        .filter_map(|hit| hit.get("content").and_then(|c| c.as_str()))
        .map(str::to_string)
        .collect()
}

/// One entity edge exists between two memories that name the same proper noun.
///
/// THE ENTITY PASS IS THE ONLY WRITER of the Entity view's edges during curation, so counting
/// them is the only observation that proves it ran. The content is written with capitalised
/// names on purpose: `entity_words_from` collects words whose first character is uppercase, and
/// the match against other memories is case-insensitive. Lowercase content produces no entity
/// words at all, which is why a test written with lowercase prose would pass whether the pass
/// ran or not.
#[tokio::test]
async fn two_memories_naming_the_same_proper_noun_get_an_entity_edge() {
    let state = fresh_state().await;

    let first = MemoryId::new();
    let second = MemoryId::new();

    curate(
        &state,
        vec![
            add(
                first,
                "Bristol Heating replaced the boiler at the Harbourside flat",
                "home",
            ),
            add(
                second,
                "Bristol Heating quoted four hundred for a new thermostat",
                "home",
            ),
        ],
    )
    .await;

    let (entity_edges, between_the_two) = {
        let locked = state.lock().await;
        let entity = locked.graph_store.entity.all_edges().count();
        // The pair specifically, in either direction. A count alone would pass if some other
        // pair happened to be linked; this asserts the two memories that share the name.
        let linked = locked.graph_store.entity.all_edges().any(|edge| {
            (edge.source == first && edge.target == second)
                || (edge.source == second && edge.target == first)
        });
        (entity, linked)
    };

    assert!(
        entity_edges > 0,
        "the entity pass must create at least one Entity-view edge when two memories share a \
         proper noun; the view is empty, so the pass either did not run or found nothing",
    );
    assert!(
        between_the_two,
        "the two memories that both name Bristol Heating must be linked by an entity edge; \
         {entity_edges} entity edges exist but none joins them",
    );
}

/// An entity edge is walkable, which is the point of having one.
///
/// THE EDGE EXISTING IS NOT THE SAME AS THE EDGE BEING REACHABLE. `find_start_node` chooses a
/// node by matching query text against labels, and traverses only within the requested view. So
/// this asks the Entity view a question from one memory's subject and requires the other memory
/// in the answer. A graph that holds the edge and cannot walk it is a graph that passes an
/// edge-count assertion and fails the user.
#[tokio::test]
async fn an_entity_edge_can_be_walked_from_one_memory_to_the_other() {
    let state = fresh_state().await;

    let first = MemoryId::new();
    let second = MemoryId::new();

    curate(
        &state,
        vec![
            add(
                first,
                "Harbourside Boiler Company services the Harbourside flat radiators",
                "home",
            ),
            add(
                second,
                "Harbourside Boiler Company invoiced the landlord for the repair",
                "home",
            ),
        ],
    )
    .await;

    let payload = serde_json::to_vec(&serde_json::json!({
        "text": "Harbourside Boiler Company",
        "query_type": "Entity",
        "budget": { "max_nodes": 40, "max_depth": 3 }
    }))
    .unwrap();
    let bytes = call(&state, frame_types::GRAPH_TRAVERSE, &payload).await;
    let pairs: Vec<(MemoryId, f32)> =
        serde_json::from_slice(&bytes).expect("an Entity traverse answers with id/score pairs");
    let reached: Vec<MemoryId> = pairs.into_iter().map(|(id, _)| id).collect();

    assert!(
        reached.contains(&first) || reached.contains(&second),
        "an Entity walk from the shared name must reach at least one of the memories that \
         carries it; it reached {reached:?}",
    );
}

/// A memory retired by decay is refused by every read surface, and its trail says why.
///
/// THE DEFECT THIS PINS. Decay archives by setting `archived` on its own entry. The context
/// tree's lifecycle is what every read filters on, and it stayed `Active`, so a memory decay
/// had retired still answered search, still appeared in listings and still resolved by id. Two
/// engines held different opinions about one memory and every read asked only the one that never
/// changed.
///
/// The memory is aged the way ninety days of neglect would age it, then the production
/// CONSOLIDATE frame runs. Everything after that is asserted at the SURFACES: search, listing,
/// retrieve-by-id, and the archive listing that the deduplication pass reads.
#[tokio::test]
async fn a_memory_retired_by_decay_is_refused_everywhere_and_kept_in_the_trail() {
    let state = fresh_state().await;

    let fading = MemoryId::new();
    let keeper = MemoryId::new();

    let fading_text = "the old gate code was four four two one";
    let keeper_text = "the gate code changed last week and the new one is pinned";

    curate(
        &state,
        vec![
            add(fading, fading_text, "home"),
            add(keeper, keeper_text, "home"),
        ],
    )
    .await;

    // Precondition: it is findable before it fades, or the assertion after means nothing.
    assert!(
        search_contents(&state, "gate code four two one")
            .await
            .iter()
            .any(|content| content.contains("gate code")),
        "precondition: the memory must be findable before it is retired",
    );

    // Age it exactly as ninety days of neglect would: faint, and last touched long ago.
    {
        let mut locked = state.lock().await;
        let entry = locked
            .decay_engine
            .entries
            .get_mut(&fading)
            .expect("a curated memory has a decay row");
        entry.strength = 0.0001;
        entry.last_accessed = Utc::now() - chrono::Duration::days(120);
    }

    let report = consolidate(&state).await;
    assert!(
        report["archived"].as_u64().unwrap_or(0) >= 1,
        "the pass must have archived the faint old memory: {report}",
    );

    // 1. The tree agrees, because every read filters on the tree.
    {
        let locked = state.lock().await;
        let lifecycle = locked
            .context_tree
            .find(fading)
            .expect("the row survives retirement; this brain does not delete")
            .lifecycle;
        assert_eq!(
            lifecycle,
            cerebrum_curate::EntryLifecycle::Archived,
            "decay archived it, so the tree must agree; the read surfaces filter on the tree",
        );
    }

    // 2. Search refuses it.
    let after_search = search_contents(&state, "gate code four two one").await;
    assert!(
        !after_search.iter().any(|content| content == fading_text),
        "a memory retired by decay must not be served by search; got {after_search:?}",
    );

    // 3. The live listing refuses it, and the archive listing still shows it.
    let live_payload = serde_json::to_vec(&serde_json::json!({})).unwrap();
    let live: serde_json::Value =
        serde_json::from_slice(&call(&state, frame_types::LIST, &live_payload).await).unwrap();
    let live_ids: Vec<String> = live["memories"]
        .as_array()
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    row.get("memory_id")
                        .and_then(|id| serde_json::from_value::<MemoryId>(id.clone()).ok())
                })
                .map(|id| id.to_string())
                .collect()
        })
        .unwrap_or_default();
    assert!(
        !live_ids.contains(&fading.to_string()),
        "a memory retired by decay must not appear in the live listing",
    );
    assert!(
        live_ids.contains(&keeper.to_string()),
        "and the memory that did not fade must still be listed",
    );

    let archive_payload =
        serde_json::to_vec(&serde_json::json!({ "include_archived": true })).unwrap();
    let archive: serde_json::Value =
        serde_json::from_slice(&call(&state, frame_types::LIST, &archive_payload).await).unwrap();
    let archive_ids: Vec<String> = archive["memories"]
        .as_array()
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    row.get("memory_id")
                        .and_then(|id| serde_json::from_value::<MemoryId>(id.clone()).ok())
                })
                .map(|id| id.to_string())
                .collect()
        })
        .unwrap_or_default();
    assert!(
        archive_ids.contains(&fading.to_string()),
        "the deduplication pass reads the archive listing, so a retired row must still be there",
    );

    // 4. Retrieve-by-id refuses it, which is the lookup delete and update are built on.
    let retrieve_payload = serde_json::to_vec(&serde_json::json!({
        "memory_id": fading.0.to_vec(),
        "level": "Deep"
    }))
    .unwrap();
    let retrieved: serde_json::Value =
        serde_json::from_slice(&call(&state, frame_types::RETRIEVE, &retrieve_payload).await)
            .unwrap();
    assert!(
        retrieved.get("error").is_some(),
        "a retired memory must not resolve by id; got {retrieved}",
    );

    // 5. The trail says it was archived, and why. The question "what happened to this" is asked
    //    precisely when the memory is gone, which is why HISTORY answers here and nothing else
    //    does.
    let history_payload = serde_json::to_vec(&serde_json::json!({
        "memory_id": fading.0.to_vec()
    }))
    .unwrap();
    let history: serde_json::Value =
        serde_json::from_slice(&call(&state, frame_types::HISTORY, &history_payload).await)
            .unwrap();
    assert_eq!(
        history["ever_retired"], true,
        "the trail must record that this memory was retired: {history}",
    );
    assert!(
        history["revisions"]
            .as_array()
            .is_some_and(|rows| rows.iter().any(|row| row["kind"] == "Archived"
                && row["reason"]
                    .as_str()
                    .is_some_and(|reason| reason.contains("decay archive")))),
        "the archival must be a revision carrying its reason, not a silent flag: {history}",
    );
    assert!(
        history["revisions"]
            .as_array()
            .is_some_and(|rows| rows.iter().any(|row| row["content"]
                .as_str()
                .is_some_and(|content| content.contains("four four two one")))),
        "and the text it held must be readable from the trail: {history}",
    );
}
