//! Build script for the Cerebrum Android host library.
//!
//! TWO JOBS, AND BOTH ARE ABOUT MAKING THE BINARY HONEST ABOUT ITSELF.
//!
//! 1. The build identity. The shipped `libcerebrum_android.so` used to be a
//!    hand-copied artefact, so nothing could say which Cerebrum revision was in
//!    a released APK. Three merged fixes were absent from the app and no check
//!    caught it. `CEREBRUM_BUILD_REV` is set by the Gradle task that runs this
//!    build, and it is baked into the library as `cerebrum-build-rev=<rev>`. A
//!    build without it still compiles and says "unknown", which is the truth.
//!
//! 2. Staleness. Cargo will happily reuse a compiled object when only a
//!    non-Rust input changed, and it has no idea that the revision it embeds
//!    changed. Emitting the revision as a `cargo:rerun-if-env-changed` value and
//!    touching nothing else means a differing revision forces a rebuild of this
//!    crate, so the marker in the binary always matches the commit it was built
//!    from rather than the commit before it.

use std::env;

fn main() {
    // Where the revision comes from. Gradle sets this; a bare `cargo build` on a
    // developer's machine may not, and that is supported rather than refused.
    println!("cargo:rerun-if-env-changed=CEREBRUM_BUILD_REV");

    let revision = env::var("CEREBRUM_BUILD_REV")
        .ok()
        .map(|raw| raw.trim().to_string())
        .filter(|trimmed| !trimmed.is_empty())
        .unwrap_or_else(|| "unknown".to_string());

    // Reject a value that would break the string literal or smuggle in a
    // newline. A revision is hex or a short tag; anything else is a mistake by
    // the caller and is better refused at build time than embedded broken.
    let safe_revision: String = revision
        .chars()
        .filter(|character| {
            character.is_ascii_alphanumeric()
                || *character == '-'
                || *character == '_'
                || *character == '.'
        })
        .collect();
    let safe_revision = if safe_revision.is_empty() {
        "unknown".to_string()
    } else {
        safe_revision
    };

    println!("cargo:rustc-env=CEREBRUM_BUILD_REV={}", safe_revision);

    // Any change to this crate's own build script is a rebuild, which is cargo's
    // default for build scripts and is stated here so it is not a surprise later.
    println!("cargo:rerun-if-changed=build.rs");
}
