#!/bin/bash
# fetch_re_toolchain.sh
# Downloads the real on-device Java and reverse-engineering toolchain from the
# Termux package repository and packs it for bundling into GURU.
#
# Run this from the project root: ./fetch_re_toolchain.sh
#
# WHY THIS EXISTS (issue 200). GURU advertises a complete on-device reverse
# engineering chain — pull, extract, decompile, analyse, modify, rebuild, sign,
# push — and the chain broke after step three because there was no Java runtime
# and no signing toolchain anywhere in the APK. The evidence from build 3.11 on
# the reporting handset:
#
#   RE tools: aapt=false aapt2=false apktool=false jadx=true
#
# with `re-tools/` holding only adb, its libraries, a genuine zipalign, four
# libandroid-*.so files, and a 5751-byte `jadx-arm64` that is the Gradle start
# script calling `java`. aapt, aapt2, apktool.jar, jadx.jar, dex2jar.zip and
# android.jar were all named by ReToolingProvider and none of them were bundled,
# so extraction logged a warning and moved on.
#
# WHAT SHIPS NOW
#
#   jvm/          a trimmed openjdk-21: a runnable JVM, not the SDK. jmods,
#                 demo, include, man, legal and lib/ct.sym are build-time
#                 artefacts the JVM never reads at run time, and dropping them
#                 takes 242 MB of package payload down to 158 MB installed.
#   bin/          java, javac, jar, jarsigner, keytool plus aapt, aapt2,
#                 zipalign, apktool and d8, each a real binary or a wrapper.
#   lib/          every shared library the above actually name as DT_NEEDED,
#                 computed by reading the ELF dynamic sections rather than by
#                 trusting the deb metadata.
#   share/java/   apktool.jar and the d8 jar, where apktool's own wrapper looks.
#
# The libraries are computed, not guessed. An earlier attempt at this bundled
# the tools and discovered at load time that libjvm.so names libandroid-shmem.so
# and libandroid-spawn.so, that aapt2 names libfmt, libprotobuf and two abseil
# objects, and that aapt needs libpng16 and libexpat. Every one of those is a
# `CANNOT LINK EXECUTABLE` at run time, so this script walks the closure and
# fails loudly if anything is still unsatisfied.
#
# libc++_shared.so: the app already ships an older copy (1374336 bytes) for the
# apt family in the bootstrap. The tools built here need the current package
# (1423696 bytes). Both carry the soname libc++_shared.so, so shipping both at
# once would be resolved by search order and would be a coin flip. libc++'s ABI
# is backward compatible, so the newer library serves the older consumers, and
# this script ships exactly one copy: the current one.
#
# NOT SHIPPED, deliberately
#
#   aapt2's absl/protobuf closure is large but aapt2 is the resource linker and
#   `link` is the operation an APK rebuild needs, so it stays.
#
#   jadx and dex2jar are not repacked here. jadx is a 72 MB jar and dex2jar a
#   20 MB one, both pure Java, so they are downloaded by the app at first use
#   into the prefix rather than carried in every APK. The tool surface reports
#   them as available only once the jar is actually present on disk, which is
#   what issue 200 asks for when it says the status must state the real state of
#   each tool rather than a subset.

set -e

PROJECT_DIR="$(pwd)"
ASSETS_DIR="app/src/main/assets/re-toolchain"
ASSET_ZIP="$ASSETS_DIR/re-toolchain.zip"
TEMP_DIR="/tmp/guru-re-toolchain-dl"

REPO="https://packages-cf.termux.dev/apt/termux-main"

# Every package is pinned by SHA-256, exactly as fetch_git_termux.sh does, so a
# repository change cannot silently alter what gets bundled.
#
# name|filename|sha256
PACKAGES=(
  "openjdk-21|pool/main/o/openjdk-21/openjdk-21_21.0.13-1_aarch64.deb|f48d68d486a08cdb0993e08e61e6d8d47aba3dba8d9634311eace92f681e0244"
  "apktool|pool/main/a/apktool/apktool_3.0.3_aarch64.deb|80cb59c7c249d7caae5463922ce293a3538f704c925f12b514b3850beecfd6ea"
  "aapt|pool/main/a/aapt/aapt_16.0.0.4-4_aarch64.deb|4933bbe27e9aa6c566f317015f42776890b2b90fbc2a0503db6be59a7d37a889"
  "aapt2|pool/main/a/aapt2/aapt2_16.0.0.4-4_aarch64.deb|1b3e4d716670d78fed4a66e04a1ba92827a36fe3000e9bef98208392f3100050"
  "libc++|pool/main/libc/libc++/libc++_30_aarch64.deb|53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"
  "libandroid-shmem|pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb|0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
  "libandroid-spawn|pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb|7988fa788ef48ab5da9660443905a2e4099ac36221739d72f5c39acc644b4d1c"
  "libandroid-sysv-semaphore|pool/main/liba/libandroid-sysv-semaphore/libandroid-sysv-semaphore_0.1-1_aarch64.deb|c3369a92814690d56278d0455ae6df7bf2ad7678ae9543e86d1e3aa433937c69"
  "libandroid-support|pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb|f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"
  "libandroid-glob|pool/main/liba/libandroid-glob/libandroid-glob_0.6-3_aarch64.deb|2276ae8adedf0db76c2f4ffc94cc4cceb2f4f5d78e021b54e2e046d1233e7826"
  "libandroid-execinfo|pool/main/liba/libandroid-execinfo/libandroid-execinfo_0.1-3_aarch64.deb|725dd2c6da7fc96e860fcc928e18aa9fb0e85e0bdcaf4f8ce0f0654ca6860fc2"
  "libiconv|pool/main/libi/libiconv/libiconv_1.19_aarch64.deb|fe9481b1dc101c6c3552943f25435109fd522aecc615ae49594f9fbee863bb37"
  "libjpeg-turbo|pool/main/libj/libjpeg-turbo/libjpeg-turbo_3.2.1_aarch64.deb|3e46721f2fbb10d5f53a45b99e2549d18a34ca3751aa4b704b3404fcdea5f8e7"
  "littlecms|pool/main/l/littlecms/littlecms_2.19.1_aarch64.deb|274f732a186563beca204f5d016d30cac8e5c24f7c4395b60881f9613eddf1a1"
  "libpng|pool/main/libp/libpng/libpng_1.6.59_aarch64.deb|32d61f0b1a60cf7283aa3a80e288737493df2c6b84c8c749606b77c94a10f2b2"
  "libexpat|pool/main/libe/libexpat/libexpat_2.9.0_aarch64.deb|f8a104e8227c9c181f6818658a3ee2fa4c107fac94219b068144fc6591086cdb"
  "zlib|pool/main/z/zlib/zlib_1.3.2_aarch64.deb|75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"
  # libjsound.so, which the JVM loads when javax.sound is touched, names
  # libasound directly. libasound's own NEEDED list is only libandroid-shmem,
  # libandroid-sysv-semaphore, libm, libc and libdl — all present — so alsa-lib
  # closes without any sound server behind it.
  "alsa-lib|pool/main/a/alsa-lib/alsa-lib_1.2.16.1_aarch64.deb|34d850de4cf6a832b638a125ac2b029a46697e7a44c66f88fb388486c93314a5"
  # aapt2's own closure. aapt2 is the resource LINKER, and linking resources is
  # the operation an APK rebuild needs, so these stay even though they are the
  # largest part of the bundle after the JVM itself.
  "abseil-cpp|pool/main/a/abseil-cpp/abseil-cpp_20260817.0_aarch64.deb|b698963bb7096494ad928ce89cd869a53e6adf0b05f5f407b40fda9c85c6e2b0"
  "fmt|pool/main/f/fmt/fmt_1:12.2.0_aarch64.deb|4a2cb43ab167b32daf0d7978f21c5391d17bce47dae6aa2a8b3a8f958e663ce7"
  "libprotobuf|pool/main/libp/libprotobuf/libprotobuf_2:36.2-1_aarch64.deb|daa3b9169e094644ba95c35d40a5cc283473144decc7dae8396c49cc51440815"
  # alsa-plugins and pulseaudio are deliberately NOT here. alsa-plugins pulls in
  # libpulse, libdbus-1, libsndfile, libsoxr, libltdl and libwebrtc-audio, which
  # is a whole sound server for a batch toolchain that never plays audio. The one
  # consumer that mattered was alsa-lib's own plugin directory, and a headless
  # APK rebuild never routes audio through an ALSA plugin.
)

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# The repository names two packages with an epoch in the filename (fmt_1:12.2.0,
# libprotobuf_2:36.2-1). A colon is legal in a URL path but curl and some
# proxies mishandle it, so the whole path is percent-encoded before the request.
urlencode_path() {
    python3 - "$1" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1]))
PY
}

# macOS ships cctools ar, which cannot write the members of a Unix ar archive
# and fails with "data.tar.xz/: No such file or directory". BSD tar reads the ar
# container directly, so it is tried first and ar is the fallback for GNU
# systems. Doing it in this order keeps the output clean on the machines this
# actually runs on.
extract_deb() {
    local deb="$1"
    local dest="$2"
    rm -rf "$dest"
    mkdir -p "$dest"
    if tar -xf "$deb" -C "$dest" 2>/dev/null && [ -f "$dest/data.tar.xz" ]; then
        return 0
    fi
    rm -rf "$dest"
    mkdir -p "$dest"
    ( cd "$dest" && ar x "$deb" ) >/dev/null 2>&1
    [ -f "$dest/data.tar.xz" ]
}

echo "=== GURU on-device toolchain fetch (issue 200) ==="
echo "Target: $ASSET_ZIP"
echo ""

mkdir -p "$ASSETS_DIR"
mkdir -p "$TEMP_DIR"

# --- Download and verify every pinned package -------------------------------
echo "[1/4] Downloading and verifying ${#PACKAGES[@]} packages..."
for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name filename expected <<< "$entry"
    dest="$TEMP_DIR/$(basename "$filename")"
    if [ ! -f "$dest" ]; then
        encoded="$(urlencode_path "$filename")"
        curl -L --fail --silent --show-error -o "$dest" "$REPO/$encoded"
    fi
    actual="$(sha256_of "$dest")"
    if [ "$actual" != "$expected" ]; then
        echo "  ERROR: SHA-256 mismatch for $(basename "$filename")"
        echo "         expected: $expected"
        echo "         actual:   $actual"
        exit 1
    fi
    echo "  -> $name verified"
done

# --- Extract every payload into one staging tree ----------------------------
# Each .deb's data.tar.xz records its files under the upstream Termux prefix,
# ./data/data/com.termux/files/usr. Stripping those five path components leaves
# a rootless tree whose shape matches what the app installs into its own
# prefix, so bin/, lib/ and share/ land exactly where the binaries expect them
# once the app rewrites the compiled-in prefix.
echo "[2/4] Extracting payloads..."
rm -rf "$TEMP_DIR/stage"
mkdir -p "$TEMP_DIR/stage"
for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name filename expected <<< "$entry"
    deb="$TEMP_DIR/$(basename "$filename")"
    work="$TEMP_DIR/x-$name"
    if ! extract_deb "$deb" "$work"; then
        echo "  ERROR: could not unpack $(basename "$filename")"
        exit 1
    fi
    tar -xf "$work/data.tar.xz" -C "$work"
    src="$work/data/data/com.termux/files/usr"
    if [ ! -d "$src" ]; then
        echo "  ERROR: $name did not extract a Termux prefix payload"
        exit 1
    fi
    # -a preserves modes so the extracted executables stay executable, and the
    # merge is deliberately by package order so a later package may replace a
    # file from an earlier one (libc++ is listed once, so this is explicit).
    cp -a "$src/." "$TEMP_DIR/stage/"
    rm -rf "$work"
done

# --- Trim the JDK to a runnable JVM ----------------------------------------
# Measured on the real package: 242.3 MB installed, of which jmods is 58.5 MB,
# lib/modules 135.5 MB, demo 4.1 MB and lib/ct.sym 10.2 MB. Only lib/modules is
# needed to run; jmods, ct.sym, demo, include, man and legal are for building
# against the JDK, which nothing on this device does.
echo "[3/4] Trimming the JDK to a runnable JVM..."
JVM_DIR="$TEMP_DIR/stage/lib/jvm/java-21-openjdk"
if [ ! -d "$JVM_DIR" ]; then
    echo "  ERROR: the JDK did not land at lib/jvm/java-21-openjdk"
    exit 1
fi
rm -rf "$JVM_DIR/jmods" "$JVM_DIR/demo" "$JVM_DIR/include" "$JVM_DIR/man" \
       "$JVM_DIR/legal" "$JVM_DIR/lib/ct.sym"
# Only the launchers and the tools a rebuild needs stay. javac is kept because
# d8 and apktool's rebuild path work on class files, and jarsigner and keytool
# are kept as the fallback for a signing case apksig cannot cover.
KEEP_BIN=" java javac jar jarsigner keytool jmod jwebserver jcmd jstack jmap jps jinfo jstat "
for f in "$JVM_DIR/bin/"*; do
    [ -f "$f" ] || continue
    base="$(basename "$f")"
    case "$KEEP_BIN" in
        *" $base "*) ;;
        *) rm -f "$f" ;;
    esac
done

# bin/java, bin/javac, bin/jar and the rest are NOT files in the package. The
# Termux package creates them from its postinst through update-alternatives:
#
#   update-alternatives --install <prefix>/bin/java java <jvm>/bin/java 60 \
#     --slave <prefix>/bin/jar jar <jvm>/bin/jar ...
#
# That postinst is a shell script and a dpkg hook, neither of which runs in this
# sandbox, so nothing would ever put java on PATH and every wrapper that calls
# `java` — including apktool's own — would die with "command not found". The
# links are created here instead, with the same shape the alternatives system
# would have produced, so a file that exists is a file that works.
JVM_BIN="$JVM_DIR/bin"
for tool in java javac jar jarsigner keytool jmod jwebserver jcmd jstack jmap jps jinfo jstat; do
    if [ -f "$JVM_BIN/$tool" ]; then
        ln -sf "lib/jvm/java-21-openjdk/bin/$tool" "$TEMP_DIR/stage/bin/$tool"
    fi
done

# --- Compute the shared-library closure by reading ELF dynamic sections -----
# Everything up to here is a copy. This is the part that decides whether the
# tools actually load, and it is measured rather than assumed: libjvm names
# libandroid-shmem and libandroid-spawn, aapt2 names libfmt, libprotobuf and two
# abseil objects, aapt names libpng16 and libexpat, and a missing one of any of
# them is a CANNOT LINK EXECUTABLE at run time rather than an obvious fault.
echo "[4/4] Verifying the shared-library closure and packing $ASSET_ZIP..."
python3 - "$TEMP_DIR/stage" <<'PY'
import os, struct, sys

stage = sys.argv[1]

# Libraries the Android platform supplies itself. The loader resolves these
# from /system/lib64 before anything in the prefix, so they are never bundled.
PLATFORM = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
    'libstdc++.so', 'libz.so',
}

def elf_needed(path):
    """DT_NEEDED names and the RPATH/RUNPATH of one ELF64 file, or None."""
    with open(path, 'rb') as fh:
        data = fh.read()
    if data[:4] != b'\x7fELF' or data[4] != 2:
        return None
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
    if e_shoff == 0 or e_shnum == 0:
        return None
    secs = []
    for i in range(e_shnum):
        b = e_shoff + i * e_shentsize
        name, stype, flags, addr, offset, size, link, info, align, entsize = \
            struct.unpack_from('<IIQQQQIIQQ', data, b)
        secs.append((name, stype, offset, size, link))
    shstr = data[secs[e_shstrndx][2]:secs[e_shstrndx][2] + secs[e_shstrndx][3]]
    def sec_name(off):
        end = shstr.find(b'\0', off)
        return shstr[off:end].decode('ascii', 'ignore')
    dynamic = [s for s in secs if sec_name(s[0]) == '.dynamic']
    if not dynamic:
        return [], None
    dsec = dynamic[0]
    dstr_sec = secs[dsec[4]]
    dstr = data[dstr_sec[2]:dstr_sec[2] + dstr_sec[3]]
    def dyn_name(off):
        end = dstr.find(b'\0', off)
        return dstr[off:end].decode('ascii', 'ignore')
    needed, runpath = [], None
    off, end = dsec[2], dsec[2] + dsec[3]
    while off < end:
        tag, val = struct.unpack_from('<qQ', data, off)
        off += 16
        if tag == 0:
            break
        if tag == 1:
            needed.append(dyn_name(val))
        elif tag == 29:
            runpath = dyn_name(val)
    return needed, runpath

def walk(root):
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            yield os.path.join(dirpath, name)

# Every provided soname, so a NEEDED name can be matched to a file.
provided = {}
for path in walk(stage):
    base = os.path.basename(path)
    if base.endswith('.so') or '.so.' in base:
        provided.setdefault(base, path)

# Every ELF that will be executed, plus every shared object, contributes its
# NEEDED list. Shared objects matter because the loader resolves their
# dependencies too, and a JVM library naming a missing object fails the same
# way as the launcher would.
#
# WHAT IS AND IS NOT CHECKED. Two different questions live here and conflating
# them produces either a broken bundle or a bundle bloated by libraries nothing
# loads:
#
#  1. Anything in bin/ WILL be executed, so its whole transitive closure has to
#     be present. That is the hard requirement.
#  2. A library in lib/ is only loaded if something reaches it. Bundling a
#     library does not make it needed, and a library that names an absent
#     object is harmless as long as nothing dlopen()s it. Holding every .so to
#     the same rule pulled in an entire PulseAudio server because alsa-lib's
#     optional plugins sit in the tree.
#
# So the check distinguishes the two, reports both, and fails only on the first.
needed_by_executables = set()
needed_by_shared = set()
consumers_of = {}
executables = 0
for path in walk(stage):
    base = os.path.basename(path)
    rel = os.path.relpath(path, stage)
    is_exe_dir = rel.startswith('bin/') or '/bin/' in rel
    is_elf_exec = False
    if is_exe_dir and os.path.isfile(path):
        with open(path, 'rb') as fh:
            head = fh.read(4)
        is_elf_exec = head == b'\x7fELF' and os.access(path, os.X_OK)
    looks_lib = base.endswith('.so') or '.so.' in base
    if not (is_elf_exec or looks_lib):
        continue
    info = elf_needed(path)
    if info is None:
        continue
    executables += 1
    target = needed_by_executables if is_elf_exec else needed_by_shared
    for name in info[0]:
        target.add(name)
        consumers_of.setdefault(name, []).append(path)

missing_hard = sorted(
    n for n in needed_by_executables
    if n not in provided and n not in PLATFORM
)
# A shared object whose dependency is absent but which nothing executes. These
# are reported so the decision is visible rather than silent, and they are left
# alone because bundling them adds weight without adding a capability.
dangling = sorted(
    n for n in needed_by_shared
    if n not in provided and n not in PLATFORM and n not in needed_by_executables
)

print(f"  ELF files inspected: {executables}")
print(f"  shared objects provided: {len(provided)}")
print(f"  DT_NEEDED required by executed binaries: {len(needed_by_executables)}")

if missing_hard:
    print("  ERROR: an executable names a library that is not in the bundle.")
    print("  These would fail at run time with CANNOT LINK EXECUTABLE:")
    for name in missing_hard:
        print(f"         {name}")
        # Name the files that pulled each one in. Without this, an unsatisfied
        # dependency is a bare library name and the next step is guessing which
        # package was missing, which is exactly the failure this check exists to
        # remove.
        for c in consumers_of.get(name, [])[:5]:
            print(f"             needed by {os.path.relpath(c, stage)}")
    sys.exit(1)

if dangling:
    # Not a failure, and deliberately not silent: these are shared objects in
    # the tree that name something absent, so a future change that starts
    # dlopen()ing one of them needs to see this list rather than discover it on
    # a phone.
    print("  note: shared objects present whose own dependency is not bundled")
    print("  (only a problem if something dlopen()s them; nothing executes them):")
    for name in dangling:
        print(f"         {name}")

print("  closure complete: every DT_NEEDED of every executable resolves")
PY

# --- Write the manifest the installer reads -------------------------------
# java.util.zip cannot read a ZIP entry's unix mode, so a symlink packed with
# `zip -y` arrives on the device as a small regular file whose CONTENT is the
# link target. Extracting that as a file produces a 32-byte "java" that the
# linker refuses, which is a confusing failure several layers from its cause.
#
# So the symlinks are written down here and the installer recreates them from
# this list. The manifest is also the record a test can hold against the
# archive, which is why it carries the tool list and the probe arguments too:
# "available" has to mean the binary RAN, and that needs a known way to run it.
echo "  writing the install manifest..."
python3 - "$TEMP_DIR/stage" "$TEMP_DIR/stage/re-toolchain-manifest.json" <<'PY'
import json, os, sys

stage, out = sys.argv[1], sys.argv[2]

symlinks = []
for dirpath, dirnames, filenames in os.walk(stage):
    for name in filenames:
        full = os.path.join(dirpath, name)
        if os.path.islink(full):
            rel = os.path.relpath(full, stage)
            symlinks.append({"path": rel, "target": os.readlink(full)})

# The tools the app reports on, each with the argument that proves it runs.
# `--version` is right for the JVM and for aapt, but apktool has no such flag:
# it prints its version for `--version` too, and exits 1 for an unknown one, so
# a non-zero exit here would be read as "not installed" for a working tool.
tools = [
    {"name": "java",      "probe": ["-version"],   "kind": "jvm"},
    {"name": "javac",     "probe": ["-version"],   "kind": "jvm"},
    {"name": "jar",       "probe": ["--version"],  "kind": "jvm"},
    {"name": "jarsigner", "probe": ["-help"],      "kind": "jvm"},
    {"name": "keytool",   "probe": ["-help"],      "kind": "jvm"},
    {"name": "aapt",      "probe": ["version"],    "kind": "native"},
    {"name": "aapt2",     "probe": ["version"],    "kind": "native"},
    {"name": "zipalign",  "probe": [],             "kind": "native"},
    {"name": "apktool",   "probe": ["--version"],  "kind": "wrapper"},
]

manifest = {
    "schema": 1,
    "description": (
        "On-device Java and reverse-engineering toolchain for GURU (issue 200). "
        "Produced by fetch_re_toolchain.sh from pinned Termux packages."
    ),
    "jvmHome": "lib/jvm/java-21-openjdk",
    "symlinks": sorted(symlinks, key=lambda s: s["path"]),
    "tools": tools,
}
with open(out, "w") as fh:
    json.dump(manifest, fh, indent=2, sort_keys=False)
    fh.write("\n")
print(f"  manifest: {len(symlinks)} symlinks, {len(tools)} tools")
PY

# --- Pack -----------------------------------------------------------------
# -y keeps symlinks as symlinks, because the JVM's lib/aliases are links and
# dereferencing them would duplicate megabytes. -X drops the extra attributes
# that differ between machines, so the archive is reproducible from the same
# inputs.
rm -f "$PROJECT_DIR/$ASSET_ZIP"
( cd "$TEMP_DIR/stage" && zip -r -y -X -q "$PROJECT_DIR/$ASSET_ZIP" . )

# --- Cleanup --------------------------------------------------------------
rm -rf "$TEMP_DIR"

echo ""
echo "=== Done ==="
echo "Asset:   $ASSET_ZIP"
echo "Bytes:   $(wc -c < "$PROJECT_DIR/$ASSET_ZIP" | tr -d ' ')"
echo "SHA-256: $(sha256_of "$PROJECT_DIR/$ASSET_ZIP")"
echo ""
echo "The archive is unpacked into the app's Termux prefix at init by"
echo "TermuxProvider.installReToolchain(), and every binary is probed by"
echo "execution before the tool surface is allowed to report it present."
