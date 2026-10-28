#!/bin/bash
# fetch_git_termux.sh
# Downloads the Termux git package and repacks its network-capable runtime for
# bundling into Guru. Run this from the project root: ./fetch_git_termux.sh
#
# Why this exists: the previous libguru_git.so was a minimal git 2.45.0 build
# with NO HTTP transport linked and no git-remote-https helper anywhere in the
# APK, so every clone/fetch/pull/push died at exit 139 the moment git tried to
# exec the missing helper. Termux's git 2.56.0 links libcurl and ships the real
# git-remote-http helper. Its library dependencies (libcurl, libssl, libcrypto,
# libnghttp2/3, libngtcp2*, libssh2, libz, libpcre2-8, libiconv) are already
# inside the app's Termux bootstrap at
# app/src/main/assets/termux/termux-bootstrap.zip, so only git itself is
# bundled here. Do not add duplicate libraries.
#
# Output tree (rootless; no data/data/com.termux/files/usr prefix segments):
#   bin/git
#   libexec/git-core/git-remote-http          real helper, links libcurl
#   libexec/git-core/git-remote-https         symlink -> git-remote-http
#   share/git-core/templates/
#
# The ~180 other libexec/git-core entries in the deb are symlinks pointing at
# ../../bin/git. They are builtin subcommand shims that git resolves through
# GIT_EXEC_PATH only for unknown commands, so they are cosmetic for
# clone/fetch/pull/push and are deliberately not shipped.

set -e

PROJECT_DIR="$(pwd)"
ASSETS_DIR="app/src/main/assets/git-termux"
ASSET_ZIP="$ASSETS_DIR/git-termux.zip"
TEMP_DIR="/tmp/guru-git-termux-dl"

GIT_VERSION="2.56.0"
DEB_FILENAME="git_${GIT_VERSION}_aarch64.deb"
DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/g/git/${DEB_FILENAME}"
DEB_SHA256="dcdd22c5fdf7bdb5b825a03a17441d43858ed8515fea57fbb37eb42e733ca82f"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

echo "=== Guru Termux git fetch ==="
echo "Target: $ASSET_ZIP"
echo ""

if [ ! -f "$ASSET_ZIP" ]; then
    mkdir -p "$ASSETS_DIR"
    mkdir -p "$TEMP_DIR"

    # --- Download ---
    echo "[1/4] Downloading Termux git ${GIT_VERSION}..."
    if [ ! -f "$TEMP_DIR/$DEB_FILENAME" ]; then
        curl -L --fail --progress-bar -o "$TEMP_DIR/$DEB_FILENAME" "$DEB_URL"
    else
        echo "  -> $DEB_FILENAME already downloaded"
    fi

    # --- Verify BEFORE extracting anything ---
    echo "[2/4] Verifying SHA-256..."
    ACTUAL_SHA256="$(sha256_of "$TEMP_DIR/$DEB_FILENAME")"
    if [ "$ACTUAL_SHA256" != "$DEB_SHA256" ]; then
        echo "  ERROR: SHA-256 mismatch for $DEB_FILENAME"
        echo "         expected: $DEB_SHA256"
        echo "         actual:   $ACTUAL_SHA256"
        exit 1
    fi
    echo "  -> verified $ACTUAL_SHA256"

    # --- Extract ---
    echo "[3/4] Extracting git, git-remote-http and templates..."
    rm -rf "$TEMP_DIR/deb" "$TEMP_DIR/stage"
    mkdir -p "$TEMP_DIR/deb"
    # GNU ar strips the ar member-name terminator; the cctools ar on macOS
    # keeps the deb's trailing "/" and refuses to write the members, so fall
    # back to bsdtar, which reads the ar container directly.
    if ! ( cd "$TEMP_DIR/deb" && ar x "$TEMP_DIR/$DEB_FILENAME" ) || [ ! -f "$TEMP_DIR/deb/data.tar.xz" ]; then
        rm -rf "$TEMP_DIR/deb"
        mkdir -p "$TEMP_DIR/deb"
        tar -xf "$TEMP_DIR/$DEB_FILENAME" -C "$TEMP_DIR/deb"
    fi
    tar -xf "$TEMP_DIR/deb/data.tar.xz" -C "$TEMP_DIR/deb"

    PKG="$TEMP_DIR/deb/data/data/com.termux/files/usr"
    if [ ! -f "$PKG/bin/git" ] || [ ! -f "$PKG/libexec/git-core/git-remote-http" ]; then
        echo "  ERROR: expected payload missing from the deb"
        exit 1
    fi

    mkdir -p "$TEMP_DIR/stage/bin" "$TEMP_DIR/stage/libexec/git-core" "$TEMP_DIR/stage/share/git-core"
    cp "$PKG/bin/git" "$TEMP_DIR/stage/bin/git"
    cp "$PKG/libexec/git-core/git-remote-http" "$TEMP_DIR/stage/libexec/git-core/git-remote-http"
    ln -s git-remote-http "$TEMP_DIR/stage/libexec/git-core/git-remote-https"
    cp -R "$PKG/share/git-core/templates" "$TEMP_DIR/stage/share/git-core/templates"
    chmod 755 "$TEMP_DIR/stage/bin/git" "$TEMP_DIR/stage/libexec/git-core/git-remote-http"

    # --- Pack rootless, symlinks preserved as symlinks ---
    echo "[4/4] Packing $ASSET_ZIP..."
    rm -f "$PROJECT_DIR/$ASSET_ZIP"
    ( cd "$TEMP_DIR/stage" && zip -r -y -X "$PROJECT_DIR/$ASSET_ZIP" bin libexec share >/dev/null )
else
    echo "  -> git-termux.zip already exists, skipping"
fi

# --- Cleanup ---
rm -rf "$TEMP_DIR"

echo ""
echo "=== Done ==="
echo "Asset: $ASSET_ZIP"
echo "Bytes: $(wc -c < "$PROJECT_DIR/$ASSET_ZIP" | tr -d ' ')"
echo "SHA-256: $(sha256_of "$PROJECT_DIR/$ASSET_ZIP")"
echo ""
echo "Contents:"
( cd "$PROJECT_DIR/$ASSETS_DIR" && unzip -l git-termux.zip )
echo ""
echo "Next steps:"
echo "  1. Run: ./gradlew assembleDebug"
echo "  2. Install on device"
echo "  3. TermuxProvider installs the git runtime into termux/usr/ at init;"
echo "     GitProvider runs it through /system/bin/linker64."
