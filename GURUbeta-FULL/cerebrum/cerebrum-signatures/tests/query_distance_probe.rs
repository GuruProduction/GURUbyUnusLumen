//! Probe: what Hamming distance separates a real query from the memory it
//! should find, now that signatures use the full 256 bits?
//!
//! Before the binarize fix every signature lived inside 32 bits, so a radius of
//! 64 matched almost everything and retrieval "worked" by accident. With real
//! spread, the radius actually has to mean something.

use cerebrum_signatures::SignatureEngine;

#[test]
fn probe_query_to_content_distance() {
    let engine = SignatureEngine::new().unwrap();

    let content = "the landlord still has not fixed the boiler in the flat";

    // Queries a user would plausibly type, from exact to loose.
    let queries = [
        "the landlord still has not fixed the boiler in the flat",
        "landlord boiler flat",
        "boiler not fixed",
        "is the boiler sorted yet",
        "has he fixed the heating",
        "quantum particles behave probabilistically under measurement",
    ];

    let content_sig = engine.generate(content);
    println!("\n=== QUERY -> CONTENT HAMMING DISTANCE ===");
    for q in queries {
        let qs = engine.generate(q);
        let d = content_sig.hamming_distance(&qs);
        let sim = 1.0 - d as f32 / 256.0;
        println!("  dist {:3}  sim {:.3}  {}", d, sim, q);
    }
    println!("  (search radius default: 64, semantic edge radius: 16)");
    println!("=========================================\n");
}
