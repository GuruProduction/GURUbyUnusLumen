# git-termux.zip — provenance

Bundled git runtime for GURU's `git*` tool family, installed into the app's
Termux prefix by `TermuxProvider.installGitRuntime()` at init.

## Source

- Upstream package: Termux `git`, version **2.56.0**, architecture `aarch64`
- Package index entry: `https://packages.termux.dev/apt/termux-main/pool/main/g/git/`
- Source URL: `https://packages.termux.dev/apt/termux-main/pool/main/g/git/git_2.56.0_aarch64.deb`
- Source deb size: 4,541,040 bytes
- Source deb SHA-256: `dcdd22c5fdf7bdb5b825a03a17441d43858ed8515fea57fbb37eb42e733ca82f`
- Extracted: 2026-10-23
- Extracted from: `data/data/com.termux/files/usr/` inside the deb's `data.tar.xz`

## Why this package

The previous bundled `libguru_git.so` was git 2.45.0 built with no HTTP
transport: its ELF `NEEDED` entries were only `libz.so`, `libdl.so`, `libc.so`.
Git reaches HTTPS by spawning the `git-remote-https` helper as a separate
process, and that helper was not present anywhere in the APK, so every network
operation died with exit 139 (SIGSEGV) when the missing helper was exec'd.

This build links libcurl and ships the real `git-remote-http` helper. Its
library dependencies are already inside the app's Termux bootstrap
(`app/src/main/assets/termux/termux-bootstrap.zip`, `lib/`): libcurl.so,
libssl.so.3, libcrypto.so.3, libnghttp2.so, libnghttp3.so, libngtcp2.so,
libngtcp2_crypto_ossl.so, libssh2.so, libz.so.1.3.2, libpcre2-8.so, libiconv.so.
No duplicate libraries are bundled here.

Version-symbol compatibility was verified against the bootstrap copies
(`objdump -p`): `bin/git` and `libexec/git-core/git-remote-http` require
`PCRE2_10.47` from libpcre2-8.so and `OPENSSL_3.0.0` from libcrypto.so.3, and
the bootstrap's libpcre2-8.so defines `PCRE2_10.47` while its libcrypto.so.3
defines `OPENSSL_3.0.0` through `OPENSSL_3.6.0`.

Both binaries carry RUNPATH `/data/data/com.termux/files/usr/lib`, which does
not exist on this install, so `TermuxProvider` patches the RPATH to the real
prefix library directory with the bundled `patchelf` at install time.

## Contents of the zip (rootless: `bin/`, `libexec/`, `share/` at top level)

```
bin/git                                     3,613,096 bytes   git 2.56.0
libexec/git-core/git-remote-http            2,109,120 bytes   real HTTP/HTTPS transport helper
libexec/git-core/git-remote-https           symlink -> git-remote-http
share/git-core/templates/                   72 KB             clone/fetch templates (hooks, info/exclude, description)
```

The deb also ships `bin/git-cvsserver`, `bin/git-shell`, `bin/scalar` and about
180 entries under `libexec/git-core/`. All of the `libexec` entries except
`git-remote-http` are symlinks to `../../bin/git` (builtin subcommand shims), and
`git-remote-ftp`/`git-remote-ftps` are symlinks to `git-remote-http`. None of
them are needed for clone/fetch/pull/push, so they are deliberately not shipped.

## Reproducing

`fetch_git_termux.sh` at the project root downloads, verifies the SHA-256,
extracts, and repacks this asset. It refuses to extract on a checksum mismatch.
The exact commands it runs against the verified deb:

```sh
# ar cannot parse the deb's trimmed member names with cctools ar on macOS;
# bsdtar reads the ar container directly.
mkdir -p "$TEMP_DIR/deb"
tar -xf git_2.56.0_aarch64.deb -C "$TEMP_DIR/deb"
tar -xf "$TEMP_DIR/deb/data.tar.xz" -C "$TEMP_DIR/deb"

PKG="$TEMP_DIR/deb/data/data/com.termux/files/usr"
mkdir -p "$TEMP_DIR/stage/bin" "$TEMP_DIR/stage/libexec/git-core" "$TEMP_DIR/stage/share/git-core"
cp "$PKG/bin/git" "$TEMP_DIR/stage/bin/git"
cp "$PKG/libexec/git-core/git-remote-http" "$TEMP_DIR/stage/libexec/git-core/git-remote-http"
ln -s git-remote-http "$TEMP_DIR/stage/libexec/git-core/git-remote-https"
cp -R "$PKG/share/git-core/templates" "$TEMP_DIR/stage/share/git-core/templates"
chmod 755 "$TEMP_DIR/stage/bin/git" "$TEMP_DIR/stage/libexec/git-core/git-remote-http"

# -y stores symlinks as symlinks instead of dereferencing them
( cd "$TEMP_DIR/stage" && zip -r -y -X git-termux.zip bin libexec share )
```

## Produced asset

- Path: `app/src/main/assets/git-termux/git-termux.zip`
- Size: 2,895,244 bytes
- SHA-256: `85926969dc67f1a4f7ea26558e0cbeebaeb2c6246210581dbab452f61bdde792`
