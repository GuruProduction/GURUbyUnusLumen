//! Eval: does a single Hamming radius separate relevant from irrelevant, or is
//! the gap in `radius_calibration_probe` a small-sample artefact?
//!
//! The objection being tested: 11 hand-picked pairs is not a distribution. If the
//! unrelated minimum is really ~90 bits (shared vocabulary, no IDF) and the
//! relevant maximum is ~98, then no radius separates them and fixed-distance
//! retrieval leaks junk. If instead the distributions are well apart across many
//! pairs, a radius is defensible.
//!
//! This is a measurement, not a gate. It prints the distributions and the best
//! achievable threshold so the number can be chosen from evidence.

use cerebrum_signatures::SignatureEngine;

/// Memories as they would really be stored: full sentences a user or the hive
/// would write. Kept deliberately varied in length and vocabulary.
const MEMORIES: &[&str] = &[
    "the landlord still has not fixed the boiler in the flat",
    "the spare key hangs in the kitchen cupboard behind the microwave",
    "rust borrow checker enforces safe memory rules at compile stage",
    "my sister is flying to lisbon on thursday for a conference",
    "italian pasta recipe uses fresh tomato and basil",
    "they want to be called steven",
    "the quarterly invoice from the accountant arrived this morning",
    "remember that the wedding is in bristol in september",
    "the car insurance renews on the fourteenth of november",
    "he works best in the morning before anyone else is awake",
    "the gym membership is frozen until march",
    "she does not eat mushrooms and hates coriander",
    "the router is in the hallway cupboard next to the fuse box",
    "guru should always ask before spending money over twenty pounds",
    "the last dentist appointment was a filling on the lower left molar",
    "his favourite album is blue by joni mitchell",
    "the deposit for the new flat is three months rent",
    "the passport expires in april so it needs renewing before the trip",
];

/// Queries that SHOULD find the memory above them. Each is a realistic shortening
/// or paraphrase a user or the agent would actually type.
const RELEVANT: &[(&str, &str)] = &[
    ("the landlord still has not fixed the boiler in the flat", "boiler not fixed"),
    ("the landlord still has not fixed the boiler in the flat", "is the boiler sorted yet"),
    ("the landlord still has not fixed the boiler in the flat", "landlord boiler flat"),
    ("the landlord still has not fixed the boiler in the flat", "heating still broken"),
    ("the spare key hangs in the kitchen cupboard behind the microwave", "where is the spare key"),
    ("the spare key hangs in the kitchen cupboard behind the microwave", "kitchen cupboard key"),
    ("the spare key hangs in the kitchen cupboard behind the microwave", "spare key location"),
    ("rust borrow checker enforces safe memory rules at compile stage", "borrow checker rules"),
    ("rust borrow checker enforces safe memory rules at compile stage", "rust memory safety"),
    ("rust borrow checker enforces safe memory rules at compile stage", "compile time safety"),
    ("my sister is flying lisbon thursday conference", "sister lisbon conference"),
    ("my sister is flying lisbon thursday conference", "when is the lisbon trip"),
    ("italian pasta recipe uses fresh tomato and basil", "pasta tomato basil recipe"),
    ("italian pasta recipe uses fresh tomato and basil", "what goes in the pasta"),
    ("italian pasta recipe uses fresh tomato and basil", "tomato basil recipe"),
    ("the quarterly invoice from the accountant arrived this morning", "accountant invoice"),
    ("the quarterly invoice from the accountant arrived this morning", "quarterly invoice arrived"),
    ("the car insurance renews on the fourteenth of november", "car insurance renewal"),
    ("the car insurance renews on the fourteenth of november", "when does insurance renew"),
    ("she does not eat mushrooms and hates coriander", "mushrooms coriander"),
    ("she does not eat mushrooms and hates coriander", "food she dislikes"),
    ("the router is in the hallway cupboard next to the fuse box", "where is the router"),
    ("the router is in the hallway cupboard next to the fuse box", "router fuse box"),
    ("the passport expires in april so it needs renewing before the trip", "passport expires"),
    ("the passport expires in april so it needs renewing before the trip", "passport renewal"),
    ("the gym membership is frozen until march", "gym membership frozen"),
    ("the deposit for the new flat is three months rent", "flat deposit rent"),
    ("the last dentist appointment was a filling on the lower left molar", "dentist filling molar"),
    ("the last dentist appointment was a filling on the lower left molar", "dentist appointment"),
    ("he works best in the morning before anyone else is awake", "when does he work best"),
];

fn distance(engine: &SignatureEngine, memory: &str, query: &str) -> u32 {
    engine.generate(memory).hamming_distance(&engine.generate(query))
}

fn percentile(sorted: &[u32], p: f64) -> u32 {
    if sorted.is_empty() {
        return 0;
    }
    let idx = ((sorted.len() as f64 - 1.0) * p).round() as usize;
    sorted[idx]
}

#[test]
fn eval_radius_separation_across_pairs() {
    let engine = SignatureEngine::new().unwrap();

    // Every relevant (memory, query) pair.
    let mut rel: Vec<u32> = RELEVANT.iter().map(|(m, q)| distance(&engine, m, q)).collect();

    // Every unrelated (memory, query-that-belongs-to-a-different-memory) pair.
    // This is the realistic junk set: the query is a genuine query, it just is
    // not for this memory.
    let memory_of: Vec<&str> = RELEVANT.iter().map(|(m, _)| *m).collect();
    let mut irr: Vec<u32> = Vec::new();
    for (i, (own_memory, query)) in RELEVANT.iter().enumerate() {
        for memory in MEMORIES {
            if *memory == *own_memory {
                continue;
            }
            // Skip this memory if it is the one this query is for.
            if memory_of.get(i) == Some(memory) {
                continue;
            }
            irr.push(engine.generate(memory).hamming_distance(&engine.generate(query)));
        }
    }

    rel.sort();
    irr.sort();

    println!("\n=== RELEVANT distances (n={}) ===", rel.len());
    println!(
        "  min={} p50={} p90={} p95={} max={}",
        rel.first().unwrap(),
        percentile(&rel, 0.50),
        percentile(&rel, 0.90),
        percentile(&rel, 0.95),
        rel.last().unwrap()
    );

    println!("\n=== UNRELATED distances (n={}) ===", irr.len());
    println!(
        "  min={} p1={} p5={} p50={} max={}",
        irr.first().unwrap(),
        percentile(&irr, 0.01),
        percentile(&irr, 0.05),
        percentile(&irr, 0.50),
        irr.last().unwrap()
    );

    // Best achievable threshold: sweep every candidate radius and count how many
    // relevant pairs it reaches versus how many unrelated pairs it wrongly admits.
    let mut best: Option<(u32, f64, f64)> = None;
    for r in 0u32..=256 {
        let recall = rel.iter().filter(|d| **d <= r).count() as f64 / rel.len() as f64;
        let junk = irr.iter().filter(|d| **d <= r).count() as f64 / irr.len() as f64;
        // Maximise recall subject to admitting under 2% junk.
        if junk <= 0.02 {
            if best.map(|(_, br, _)| recall > br).unwrap_or(true) {
                best = Some((r, recall, junk));
            }
        }
    }

    match best {
        Some((r, recall, junk)) => {
            println!(
                "\n  BEST radius at <=2% junk: r={} recall={:.1}% junk={:.1}%",
                r,
                recall * 100.0,
                junk * 100.0
            );
        }
        None => println!("\n  no radius admits under 2% junk"),
    }

    // Current shipped value.
    let recall_110 = rel.iter().filter(|d| **d <= 110).count() as f64 / rel.len() as f64;
    let junk_110 = irr.iter().filter(|d| **d <= 110).count() as f64 / irr.len() as f64;
    println!(
        "  CURRENT radius 110:      recall={:.1}% junk={:.1}%",
        recall_110 * 100.0,
        junk_110 * 100.0
    );

    let overlap = irr.first() < rel.last();
    println!(
        "\n  distributions overlap: {}  (unrelated min {} vs relevant max {})",
        overlap,
        irr.first().unwrap(),
        rel.last().unwrap()
    );
    println!("=========================================\n");
}
