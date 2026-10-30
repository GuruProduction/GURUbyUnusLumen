//! Probe: calibrate the search radius against real signatures.
//!
//! Before the binarize fix, signatures only occupied 32 of 256 bits, so
//! unrelated text landed 10 to 14 bits apart and a radius of 64 matched
//! everything. That radius was never meaningful; it was compensating for a
//! broken bit layout. Now that all 256 bits carry signal, the radius has to be
//! derived from measurement instead of inherited from a bug.

use cerebrum_signatures::SignatureEngine;

/// (stored memory, query that SHOULD find it)
const RELEVANT: &[(&str, &str)] = &[
    (
        "the landlord still has not fixed the boiler in the flat",
        "boiler not fixed",
    ),
    (
        "the landlord still has not fixed the boiler in the flat",
        "is the boiler sorted yet",
    ),
    (
        "the landlord still has not fixed the boiler in the flat",
        "landlord boiler flat",
    ),
    (
        "the spare key hangs in the kitchen cupboard behind the microwave",
        "where is the spare key",
    ),
    (
        "the spare key hangs in the kitchen cupboard behind the microwave",
        "kitchen cupboard key",
    ),
    (
        "rust borrow checker enforces safe memory rules at compile stage",
        "borrow checker rules",
    ),
    (
        "my sister is flying to lisbon on thursday for a conference",
        "sister lisbon conference",
    ),
    (
        "italian pasta recipe uses fresh tomato and basil",
        "pasta tomato basil recipe",
    ),
];

/// Unrelated memory/query pairs: these must NOT match.
const IRRELEVANT: &[(&str, &str)] = &[
    (
        "the landlord still has not fixed the boiler in the flat",
        "quantum particles behave probabilistically under measurement",
    ),
    (
        "the spare key hangs in the kitchen cupboard behind the microwave",
        "italian pasta recipe uses fresh tomato and basil",
    ),
    (
        "rust borrow checker enforces safe memory rules at compile stage",
        "my sister is flying to lisbon on thursday for a conference",
    ),
];

fn distances(pairs: &[(&str, &str)], engine: &SignatureEngine) -> Vec<u32> {
    pairs
        .iter()
        .map(|(memory, query)| {
            engine
                .generate(memory)
                .hamming_distance(&engine.generate(query))
        })
        .collect()
}

#[test]
fn probe_radius_separation() {
    let engine = SignatureEngine::new().unwrap();

    let mut rel = distances(RELEVANT, &engine);
    let mut irr = distances(IRRELEVANT, &engine);
    rel.sort();
    irr.sort();

    println!("\n=== RELEVANT query -> memory distances ===");
    for (pair, d) in RELEVANT.iter().zip(
        RELEVANT
            .iter()
            .map(|(m, q)| engine.generate(m).hamming_distance(&engine.generate(q))),
    ) {
        println!("  {:3}  [{}] <- \"{}\"", d, pair.0, pair.1);
    }
    println!("  min={} max={}", rel.first().unwrap(), rel.last().unwrap());

    println!("\n=== IRRELEVANT query -> memory distances ===");
    for (pair, d) in IRRELEVANT.iter().zip(
        IRRELEVANT
            .iter()
            .map(|(m, q)| engine.generate(m).hamming_distance(&engine.generate(q))),
    ) {
        println!("  {:3}  [{}] <- \"{}\"", d, pair.0, pair.1);
    }
    println!("  min={} max={}", irr.first().unwrap(), irr.last().unwrap());

    let worst_relevant = *rel.last().unwrap();
    let best_irrelevant = *irr.first().unwrap();
    println!(
        "\n  separation window: {} .. {}  (width {})",
        worst_relevant,
        best_irrelevant,
        best_irrelevant.saturating_sub(worst_relevant)
    );
    println!("  current search radius: 64");
    println!("=========================================\n");

    assert!(
        best_irrelevant > worst_relevant,
        "relevant and irrelevant overlap: worst relevant {} vs best irrelevant {}. \
         No single radius separates them, which is the argument for a second \
         retrieval lane rather than tuning this one.",
        worst_relevant,
        best_irrelevant
    );

    assert!(
        worst_relevant > 64,
        "the current radius of 64 cannot reach the worst relevant query ({} bits). \
         Retrieval is too tight and needs recalibrating to fit the measured window.",
        worst_relevant
    );
}
