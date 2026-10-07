// cdylib-only crate: no real bin; this placeholder satisfies the stray
// main target check so `cargo build -p cerebrum-android` for the actual
// cdylib (libcerebrum_android.so) stays valid while linking binaries is
// never required here.

fn main() {}