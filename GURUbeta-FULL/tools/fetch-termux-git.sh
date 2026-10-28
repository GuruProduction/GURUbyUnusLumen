#!/usr/bin/env bash
# GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
# Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
# Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
#
# fetch-termux-git.sh — reproducible fetch of the Termux git package, repackaged
# as the APK asset that TermuxProvider installs into filesDir/termux/usr/.
#
# WHY THIS EXISTS
#   The git previously shipped in jniLibs (libguru_git.so) was built with no HTTP
#   transport: it has no libcurl in NEEDED and no built-in https helper, so every
#   clone/clone/fetch/push spawned a non-existent `git-remote-https` and died
#   (exit 139 / SIGSEGV on device). Termux's git 2.56.0 is a real HTTPS build:
#   bin/git plus libexec/git-core/git-remote-http (which links libcurl), and the
#   app's bundled Termux bootstrap already carries every shared library that
#   helper needs (libcurl, libssl, libcrypto, libz, libnghttp2, libssh2,
#   libpcre2-8, libiconv).
#
# WHAT IT PRODUCES
#   app/src/main/assets/git-termux/git-termux.zip
#     bin/git
#     libexec/git-core/git-remote-http
#     libexec/git-core/git-remote-https   <- REAL COPY of git-remote-http
#     share/git-core/templates/...
#   app/src/main/assets/git-termux/PROVENANCE.md
#
# WHY git-remote-https IS A REAL COPY, NOT A SYMLINK
#   android.util.zip.ZipFile (the extractor TermuxProvider uses, and the one the
#   APK pipeline implies) does not reconstruct Unix symlinks from zip entries:
#   a symlink entry written by `zip -y` arrives as a small regular file whose
#   contents are the target path. git would then exec a text file and fail with
#   ENOEXEC. A second real copy of the 2.1 MB helper is the only form that
#   survives packaging, APK extraction and runtime unzip unchanged.
#
# IDEMPOTENCE
#   The .deb is cached under build/git-termux-cache/ (gitignored) and reused only
#   when its SHA-256 still matches. The asset zip is rebuilt from that verified
#   .deb every run, so re-running this script always converges on the same
#   payload. The .deb itself is never written inside the source tree's assets.
#
# REQUIREMENTS
#   bash, curl, python3 (stdlib only: hashlib, lzma, tarfile, zipfile, io).
#   python3 is used for the .deb/ar parsing and zip writing so the script behaves
#   identically on macOS (bsdtar, BSD ar) and Linux (GNU tar/ar), both of which
#   disagree about ar member names that carry a trailing slash.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$REPO_ROOT/app/src/main/assets/git-termux"
ZIP_OUT="$ASSET_DIR/git-termux.zip"
PROVENANCE_OUT="$ASSET_DIR/PROVENANCE.md"
CACHE_DIR="$REPO_ROOT/build/git-termux-cache"
DEB_PATH="$CACHE_DIR/git_2.56.0_aarch64.deb"

DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/g/git/git_2.56.0_aarch64.deb"
DEB_SHA256="dcdd22c5fdf7bdb5b825a03a17441d43858ed8515fea57fbb37eb42e733ca82f"
GIT_VERSION="2.56.0"

log() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

mkdir -p "$ASSET_DIR" "$CACHE_DIR"

# ── 1. Download (cached) ─────────────────────────────────────────────────────
if [ -f "$DEB_PATH" ]; then
    log "Using cached package: $DEB_PATH"
else
    log "Downloading $DEB_URL"
    rm -f "$DEB_PATH.part"
    curl -fL --retry 3 --retry-delay 2 --proto '=https' -o "$DEB_PATH.part" "$DEB_URL" \
        || die "download failed: $DEB_URL"
    mv "$DEB_PATH.part" "$DEB_PATH"
fi

# ── 2. Verify SHA-256, abort loudly on mismatch ─────────────────────────────
ACTUAL_SHA256="$(python3 - "$DEB_PATH" <<'PY'
import hashlib
import sys

digest = hashlib.sha256()
with open(sys.argv[1], "rb") as handle:
    for block in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(block)
print(digest.hexdigest())
PY
)"

if [ "$ACTUAL_SHA256" != "$DEB_SHA256" ]; then
    rm -f "$DEB_PATH"
    die "SHA-256 MISMATCH for $DEB_URL
  expected: $DEB_SHA256
  actual:   $ACTUAL_SHA256
The downloaded package was deleted. Nothing was written to assets."
fi
log "SHA-256 verified: $ACTUAL_SHA256"

# ── 3. Extract the four payloads and repackage them as the asset zip ────────
python3 - "$DEB_PATH" "$ZIP_OUT" <<'PY'
import io
import lzma
import os
import sys
import tarfile
import zipfile

deb_path, zip_path = sys.argv[1], sys.argv[2]

PREFIX = "./data/data/com.termux/files/usr/"

REQUIRED_EXECUTABLES = {
    "bin/git",
    "libexec/git-core/git-remote-http",
}
REQUIRED_COPY = "libexec/git-core/git-remote-https"
TEMPLATES_PREFIX = "share/git-core/templates/"


def ar_members(blob):
    """Yield (name, payload) for a Debian ar archive, trailing-slash names normalised."""
    if not blob.startswith(b"!<arch>\n"):
        raise SystemExit("not a Debian ar archive")
    offset = 8
    length = len(blob)
    while offset + 60 <= length:
        header = blob[offset:offset + 60]
        name = header[0:16].decode("ascii", "replace").strip()
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError:
            raise SystemExit("malformed ar header")
        start = offset + 60
        end = start + size
        if end > length:
            raise SystemExit("truncated ar member: " + name)
        yield name.rstrip("/"), blob[start:end]
        offset = end + (size % 2)


with open(deb_path, "rb") as handle:
    deb = handle.read()

data_blob = None
for member_name, payload in ar_members(deb):
    if member_name == "data.tar.xz":
        data_blob = payload
        break
if data_blob is None:
    raise SystemExit("data.tar.xz not found inside the .deb")

with tarfile.open(fileobj=io.BytesIO(data_blob), mode="r:xz") as data_tar:
    members = {}
    for info in data_tar:
        name = info.name
        if name.startswith("./"):
            name = name[2:]
        if not name.startswith(PREFIX):
            continue
        rel = name[len(PREFIX):]
        if not rel:
            continue
        members[rel] = info

    for needed in list(REQUIRED_EXECUTABLES) + [REQUIRED_COPY]:
        if needed not in members:
            raise SystemExit("package is missing expected member: " + needed)

    def payload_of(rel):
        info = members[rel]
        if info.issym():
            target = info.linkname
            base = os.path.dirname(rel)
            resolved = _normalise(base + "/" + target)
            return payload_of(resolved)
        if info.isfile():
            extracted = data_tar.extractfile(info)
            if extracted is None:
                raise SystemExit("cannot read member: " + rel)
            return extracted.read()
        raise SystemExit("unexpected member type for: " + rel)

    def _normalise(path):
        parts = []
        for part in path.split("/"):
            if part in ("", "."):
                continue
            if part == "..":
                if not parts:
                    raise SystemExit("symlink escapes the package prefix: " + path)
                parts.pop()
            else:
                parts.append(part)
        return "/".join(parts)

    remote_http_bytes = payload_of("libexec/git-core/git-remote-http")
    git_bytes = payload_of("bin/git")

    if len(git_bytes) < 1_000_000:
        raise SystemExit("bin/git is implausibly small: %d bytes" % len(git_bytes))
    if len(remote_http_bytes) < 1_000_000:
        raise SystemExit("git-remote-http is implausibly small: %d bytes" % len(remote_http_bytes))

    def zipinfo(rel, mode):
        info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
        info.create_system = 3  # Unix
        info.external_attr = (mode & 0xFFFF) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        return info

    template_entries = []
    for rel, info in members.items():
        if rel == TEMPLATES_PREFIX.rstrip("/") or rel.startswith(TEMPLATES_PREFIX):
            template_entries.append((rel, info))
    if not template_entries:
        raise SystemExit("package is missing share/git-core/templates")

    tmp_zip = zip_path + ".part"
    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_DEFLATED) as out:
        out.writestr(zipinfo("bin/git", 0o755), git_bytes)
        out.writestr(zipinfo("libexec/git-core/git-remote-http", 0o755), remote_http_bytes)
        # Byte-for-byte second copy; see the header comment for why a symlink fails.
        out.writestr(zipinfo(REQUIRED_COPY, 0o755), remote_http_bytes)
        written = 3
        for rel, info in sorted(template_entries):
            if info.isdir():
                out.writestr(zipinfo(rel.rstrip("/") + "/", 0o755), b"")
            elif info.isfile():
                payload = data_tar.extractfile(info).read()
                mode = 0o755 if (info.mode & 0o111) else 0o644
                out.writestr(zipinfo(rel, mode), payload)
            # Symlinks inside the templates tree (none in 2.56.0) are skipped: the
            # templates are plain sample hooks, description and info/exclude.
            written += 1
        if written < 10:
            raise SystemExit("unexpectedly few template entries: %d" % written)

    os.replace(tmp_zip, zip_path)
    print("wrote %s (%d bytes)" % (zip_path, os.path.getsize(zip_path)))
PY

# ── 4. Verify the produced zip really carries the payload ────────────────────
python3 - "$ZIP_OUT" <<'PY'
import sys
import zipfile

required = [
    "bin/git",
    "libexec/git-core/git-remote-http",
    "libexec/git-core/git-remote-https",
    "share/git-core/templates/description",
    "share/git-core/templates/info/exclude",
]

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = set(archive.namelist())
    missing = [name for name in required if name not in names]
    if missing:
        raise SystemExit("asset zip is missing: " + ", ".join(missing))
    for name in ("bin/git", "libexec/git-core/git-remote-http", "libexec/git-core/git-remote-https"):
        if archive.getinfo(name).file_size < 1_000_000:
            raise SystemExit("asset zip member implausibly small: " + name)
    templates = [n for n in names if n.startswith("share/git-core/templates/")]
    print("verified %d entries (%d template files)" % (len(names), len(templates)))
PY

# ── 5. Provenance ───────────────────────────────────────────────────────────
EXTRACTED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
ZIP_SHA256="$(python3 - "$ZIP_OUT" <<'PY'
import hashlib
import sys

digest = hashlib.sha256()
with open(sys.argv[1], "rb") as handle:
    for block in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(block)
print(digest.hexdigest())
PY
)"

cat > "$PROVENANCE_OUT" <<EOF
# Bundled git provenance

Termux's git ${GIT_VERSION}, repackaged for GURU's own Termux prefix.
Generated by \`tools/fetch-termux-git.sh\`.

| Field | Value |
|---|---|
| Package URL | ${DEB_URL} |
| Package version | ${GIT_VERSION} (aarch64) |
| Package SHA-256 | \`${ACTUAL_SHA256}\` |
| Asset zip SHA-256 | \`${ZIP_SHA256}\` |
| Extracted (UTC) | ${EXTRACTED_AT} |

## Why this package

The previous bundled git (jniLibs/libguru_git.so) had no HTTP transport: its
NEEDED list held no libcurl and it spawned a \`git-remote-https\` that was never
shipped, so every clone/fetch/push died before a single byte of transport ran.
Termux's git is a real HTTPS build, and the app's bundled Termux bootstrap
already ships every library its remote helper needs.

## Payload in git-termux.zip (relative paths, extracted into filesDir/termux/usr/)

    bin/git                               (ELF aarch64, RUNPATH rewritten at install)
    libexec/git-core/git-remote-http      (ELF aarch64, NEEDED libcurl.so ...)
    libexec/git-core/git-remote-https     (real copy of git-remote-http)
    share/git-core/templates/...          (sample hooks, description, info/exclude)

\`git-remote-https\` is a byte-for-byte second copy, not a symlink. The package
upstream ships it as a symlink to \`git-remote-http\`, but Android's zip readers
(android.util.zip.ZipFile, which TermuxProvider uses) do not reconstruct Unix
symlinks; a symlink entry would arrive as a small text file and git's exec of
it would fail with ENOEXEC. A real copy survives packaging, APK extraction and
runtime unzip unchanged.

## Exact commands this script runs

    curl -fL --retry 3 --retry-delay 2 --proto '=https' \\
         -o build/git-termux-cache/git_2.56.0_aarch64.deb \\
         ${DEB_URL}
    # SHA-256 of the .deb must equal:
    #   ${DEB_SHA256}
    # then, in python3:
    #   - parse the ar container, decompress data.tar.xz (lzma)
    #   - select the members above from ./data/data/com.termux/files/usr/
    #   - write git-termux.zip with the same relative paths and 0755/0644 modes

## Re-running

The script is idempotent: a cached .deb is reused only while its SHA-256 still
matches, and the asset zip is rebuilt from that verified package every run.
The .deb itself is never committed; the cache lives under build/ (gitignored).
EOF

log "Wrote $PROVENANCE_OUT"
log "Asset zip: $ZIP_OUT"
log "Done."
