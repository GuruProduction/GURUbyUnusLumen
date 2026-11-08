# NOTICE — Trademarks and Licensing

GURU by Unus Lumen. Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol, United Kingdom.

GURU's own source is licensed under the GNU Affero General Public License, version 3.0 or later. The full text is in `LICENSE`. This file does not alter that licence, and where the two disagree the licence governs.

## Trademarks

The AGPL grants rights to the code and none to the marks. That covers the name GURU, the name Unus Lumen and the GURU logo, and it means a modified build must not present itself as GURU or as coming from Unus Lumen.

The same applies in reverse: GURU bundles third-party software, and none of those projects endorse GURU or have any relationship with it.

## The shape of what ships

Two facts decide how the rest of this file reads, and both were measured from the APK's own binaries rather than assumed.

First, the sixteen `libguru_*.so` files are not libraries despite the name. Each is a position-independent executable with an ELF interpreter, packaged into `lib/` only because Android's installer will not carry a bare binary. The app extracts and runs them.

Second, they are built statically. That matters because the GNU LGPL's relinking obligation attaches to shared libraries, and a statically linked program falls under the ordinary GPL terms instead.

## The bundled command-line tools

Versions and licences, as read from the shipped binaries.

curl, under the curl licence. FFmpeg 7.x, under the GNU LGPL version 2.1 or later, built without `--enable-gpl` and without `--enable-nonfree` and therefore without any GPL-only component; decoded through the platform's MediaCodec. git 2.45.0, under the GNU GPL version 2, built without an HTTP transport, which is why the separate Termux git runtime below exists. jq, under the MIT licence. GNU Make, under the GNU GPL version 3. OpenSSL 3.3.1, under the Apache License 2.0. rsync, under the GNU GPL version 3. OpenSSH's scp, under its BSD-style licence. SQLite, in the public domain. Tesseract 5.5.0, under the Apache License 2.0. tmux, under the ISC licence. Toybox, under the zero-clause BSD licence. Info-ZIP's zip and unzip, under the Info-ZIP licence.

`libguru_helloworld.so` is ours and falls under the AGPL. `libcerebrum_android.so` is the Rust brain and is ours too.

Each of these is a separate program invoked as a subprocess rather than linked into GURU, so each remains governed by its own terms. The GPL programs here are distributed as independent executables, which the GPL permits; what it forbids is linking them into a proprietary whole, and nothing here does.

## The Termux bootstrap

`app/src/main/assets/termux/termux-bootstrap.zip` is a Termux userland of 79 packages, including bash, coreutils, apt, dpkg, openssl, libcurl, gnupg, tar, xz, zstd and the ncurses family. Each package remains under the licence its upstream granted, in the main the GNU GPL, the GNU LGPL, the MIT licence or a BSD-style licence.

The licence texts ship inside the app under `share/LICENSES/` in that bootstrap, installed there by the `termux-licenses` package, and every package's provenance is recorded by dpkg in `var/lib/dpkg/status` beside it. Both travel with the APK, so the attribution a user is owed is present on their own device, not only on a website.

## The Python runtime

`app/src/main/assets/python-runtime/python-runtime-arm64.zip` carries CPython 3.14 under the Python Software Foundation License, with numpy 2.5.4 under the BSD 3-Clause licence and Pillow 13.0.0 under the HPND licence. Each distribution ships its own licence files inside the archive, and those are preserved rather than stripped.

## The reverse-engineering toolchain

`app/src/main/assets/re-toolchain/re-toolchain.zip` is built from pinned Termux packages: OpenJDK 21 under the GNU GPL version 2 with the Classpath Exception, aapt and aapt2 under the Apache License 2.0, apktool under the Apache License 2.0, and the native libraries those tools name, among them libpng, libjpeg-turbo, zlib, expat, abseil, fmt, libc++, alsa-lib, Little CMS, libiconv and libandroid-support. Licence texts are preserved under `share/doc` and `share/LICENSES` in that archive.

`app/src/main/assets/re-tools` carries Android platform tools, adb and zipalign, under the Apache License 2.0, and jadx under the Apache License 2.0.

## The remaining bundled assets

`git-termux.zip` is git 2.56.0 from the Termux repository, and it is the one that carries the real HTTP transport. Its source package, size and SHA-256 are recorded in `git-termux/FETCH.md` beside it. `toybox/` in the assets carries Info-ZIP's zip and unzip. `tesseract/tessdata/eng.traineddata` is Tesseract's English model under the Apache License 2.0. `cacert.pem` is the Mozilla CA bundle as published by curl.

## Android and Kotlin dependencies

The build resolves through `gradle/libs.versions.toml`, and everything it names keeps its own licence: Kotlin and kotlinx under the Apache License 2.0; AndroidX, Jetpack Compose, Room, WorkManager, Media3, Coil, Lottie, ZXing, Shizuku and the Android Gradle Plugin under the Apache License 2.0; Koin under the Apache License 2.0; Ktor under the Apache License 2.0; Koog under the Apache License 2.0; detekt under the Apache License 2.0; Tor and jtorctl from the Guardian Project under the BSD 3-Clause licence.

## The Rust brain

`GURUbeta-FULL/cerebrum` is twenty-three crates of our own code under the AGPL above. Its dependencies are resolved by Cargo, and `Cargo.lock` in that directory is the exact record of which versions were built.

## The build and test harness

`factory` is TypeScript run by Bun under the MIT licence, and it ships in no release. Its dependencies are `typescript`, `@types/bun` and `@types/node`, all MIT.

## Reproducing and replacing these components

Every archive in `app/src/main/assets` is produced by a script that pins its inputs by SHA-256 and refuses to extract on a mismatch: `fetch_git_termux.sh` for git, `fetch_re_toolchain.sh` for the reverse-engineering toolchain, `fetch_python_runtime.sh` for Python. Each one's provenance is recorded beside it.

Because every component above is published by its upstream, and the versions and checksums are pinned here, each can be rebuilt and substituted. That is the point of the file: a record of what is inside, under what terms, and where to find its source.

## Distribution

The AGPL's network clause applies to GURU's own code: modify it and let others use it over a network, and you owe them the corresponding source. Shipping an APK is distribution, so the ordinary GPL obligations apply there as well, and the source must be made available.

Questions about the licensing of anything here belong in an issue on this repository.
