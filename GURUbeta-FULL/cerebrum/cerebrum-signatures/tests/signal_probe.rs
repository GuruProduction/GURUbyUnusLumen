//! Probe: how much of the 256-bit signature actually carries information?
//!
//! Written after a device measurement showed unrelated sentences scoring 0.945
//! to 0.961 similarity (1 - hamming/256), i.e. only 10 to 14 bits differing out
//! of 256. Random bit strings agree on about half their bits, so unrelated text
//! should score near 0.5. A score near 1.0 means the bits barely vary.

use cerebrum_signatures::SignatureEngine;

fn popcount_per_byte(sig: &[u8; 32]) -> Vec<u32> {
    sig.iter().map(|b| b.count_ones()).collect()
}

#[test]
fn probe_which_bytes_carry_information() {
    let engine = SignatureEngine::new().unwrap();

    let samples = [
        "the spare key hangs in the kitchen cupboard behind the microwave",
        "quantum particles behave probabilistically under measurement",
        "italian pasta recipe uses fresh tomato and basil",
        "the landlord still has not fixed the boiler in the flat",
        "my sister is flying to lisbon on thursday for a conference",
        "rust borrow checker enforces safe memory rules at compile stage",
    ];

    println!("\n=== POPCOUNT PER BYTE (32 bytes = 256 bits) ===");
    let mut per_byte_nonzero = vec![0u32; 32];
    for s in samples {
        let sig = engine.generate(s);
        let counts = popcount_per_byte(&sig.0);
        for (i, c) in counts.iter().enumerate() {
            if *c > 0 {
                per_byte_nonzero[i] += 1;
            }
        }
        println!("  total_popcount={:3}  {:?}", sig.popcount(), counts);
    }

    println!("\n=== BYTES THAT ARE EVER NON-ZERO ACROSS SAMPLES ===");
    let live: Vec<usize> = per_byte_nonzero
        .iter()
        .enumerate()
        .filter(|(_, c)| **c > 0)
        .map(|(i, _)| i)
        .collect();
    println!("  live byte indices: {:?}", live);
    println!("  live byte count: {} of 32", live.len());
    println!("  live BIT count:  {} of 256", live.len() * 8);
    println!("================================================\n");

    assert!(
        live.len() > 16,
        "ONLY {} OF 32 BYTES EVER CARRY A SET BIT. The signature is not a \
         256-bit vector in practice: the binarize step writes 8 bits per \
         projected value but the projection only produces sig_bytes values, \
         so everything past the first sig_bytes/8 * 8 bits is forced to zero. \
         Live bit count: {} of 256.",
        live.len(),
        live.len() * 8
    );
}

#[test]
fn probe_unrelated_similarity_distribution() {
    let engine = SignatureEngine::new().unwrap();

    let samples = [
        "italian pasta recipe uses fresh tomato and basil",
        "quantum particles behave probabilistically under measurement",
        "tennis racquet grip affects serve spin and control",
        "the landlord still has not fixed the boiler in the flat",
        "my sister is flying to lisbon on thursday for a conference",
        "the quarterly invoice from the accountant arrived this morning",
    ];

    let sigs: Vec<_> = samples.iter().map(|s| engine.generate(s)).collect();
    let mut distances = Vec::new();
    for i in 0..sigs.len() {
        for j in (i + 1)..sigs.len() {
            distances.push(sigs[i].hamming_distance(&sigs[j]));
        }
    }
    distances.sort();
    let mean: f64 = distances.iter().map(|d| *d as f64).sum::<f64>() / distances.len() as f64;

    println!("\n=== UNRELATED PAIR HAMMING DISTANCES ===");
    println!("  min={} max={} mean={:.1} (random expectation = 128)",
        distances.first().unwrap(), distances.last().unwrap(), mean);
    println!("========================================\n");

    assert!(
        mean > 100.0,
        "unrelated text averages only {:.1} bits apart, against 128 for random \
         bit strings. The signature carries almost no distinguishing signal",
        mean
    );
}
