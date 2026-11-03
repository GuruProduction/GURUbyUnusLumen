#!/bin/bash
# fetch_python_runtime.sh
# Builds the bundled CPython runtime from pinned Termux packages.
#
# Run this from the GURUbeta-FULL directory: ./fetch_python_runtime.sh
#
# WHY THIS EXISTS (issue 175)
#
# Python had never worked in the app. Building the launcher and running it proved
# the interpreter could start, and that exposed three further faults underneath,
# none of which is visible without executing real Python:
#
# 1. THE EXTENSION MODULES COULD NOT LOAD AT ALL. The stdlib archive that shipped
#    was built with 4 KB page alignment on all 46 of its extension modules. This
#    device has 16 KB pages and Android's linker refuses to load a library whose
#    alignment is smaller than the page size:
#
#      lib-dynload/math.cpython-314-...so program alignment (4096) cannot be
#      smaller than system page size (16384)
#
#    That is fatal, not advisory. `_json` is imported during interpreter startup,
#    so even `print("hi")` never ran. The alignment is a property of the build, so
#    the only fix is to ship a runtime that was built for 16 KB pages.
#
# 2. THE INTERPRETER WAS AN UNOFFICIAL BUILD. The shipped libpython3.14.so
#    reported "3.14.5 (tags/v3.14.5-dirty:5607950, May 10 2026)". The "-dirty"
#    suffix means it came from a modified working tree, so there is no commit to
#    reproduce it from. It was also hand-placed in jniLibs with no script that
#    produces it, which is why none of this was discoverable from the repository.
#
# 3. THE TWO HALVES DID NOT MATCH. Substituting a correctly aligned stdlib did
#    not help, because the shipped interpreter could not satisfy its private
#    symbols:
#
#      dlopen failed: cannot locate symbol "_PyType_AllocNoTrack" referenced by
#      array.cpython-314-aarch64-linux-android.so
#
#    A C extension is loadable only by the exact CPython build that defines its
#    private symbols, so the interpreter and its extensions must come from one
#    build. Taking them from two sources is what produced that.
#
# WHAT SHIPS NOW
#
#   lib/libpython3.14.so        the interpreter
#   lib/python3.14/             the standard library
#   lib/python3.14/lib-dynload  the extension modules
#   lib/*.so                    every shared library the above name as DT_NEEDED
#
# The layout is not a choice made here. Running the official python3.14 binary
# with PYTHONHOME set prints these exact sys.path entries:
#
#   <PYTHONHOME>/lib/python314.zip
#   <PYTHONHOME>/lib/python3.14
#   <PYTHONHOME>/lib/python3.14/lib-dynload
#
# and fails with "Failed to import encodings module" when os.py is anywhere else.
#
# WHY TERMUX PACKAGES
#
# They are Android-native, built for this ABI, published with a SHA-256 per file,
# and the current ones are 16 KB aligned. Upstream python.org also publishes
# Android embeddable builds, but its extension modules are not guaranteed aligned
# for 16 KB pages, which is the defect this script exists to prevent.
#
# The library closure is COMPUTED by reading each ELF's DT_NEEDED rather than
# listed by hand, and the alignment of every packaged ELF is checked, so a
# package update that breaks either fails the build here instead of on a phone.

set -e

PROJECT_DIR="$(pwd)"
ASSETS_DIR="app/src/main/assets/python-runtime"
TEMP_DIR="/tmp/guru-python-runtime"
REPO="https://packages-cf.termux.dev/apt/termux-main"

# name|filename|sha256
#
# Every entry is pinned by the hash published in the repository index on
# 2026-11-03. A repository change cannot silently alter what gets bundled: the
# download is verified against this list and the build stops on a mismatch.
PACKAGES=(
  "python|pool/main/p/python/python_3.14.8_aarch64.deb|55fcd7c23a603ba0caeca3ef9e670ec88ff71c1a480101de4983629792563b5e"
  "libandroid-support|pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb|f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"
  "libandroid-posix-semaphore|pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb|0efa8677a0166315ba4e685863712eba0ca0a1732827492f38226e2723730c7a"
  "libbz2|pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb|4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba"
  "openssl|pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb|a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b"
  "libexpat|pool/main/libe/libexpat/libexpat_2.10.0_aarch64.deb|0e4411f01f6fb664f3f04f9420289472f9afc30db01ea1c5d1e57fd9fa2354be"
  "libffi|pool/main/libf/libffi/libffi_3.8.0_aarch64.deb|4f255badf74cd31f6a2801c17fa1444199c84c834b517b7c843e2fe9ebe91d77"
  "gdbm|pool/main/g/gdbm/gdbm_1.26-1_aarch64.deb|c378de833aae3cf41f3028491ea11836479d9833ab653edc27d0c0e0e691d5c5"
  "liblzma|pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb|44e95e6e60dddb3705e60a344a4f009a1085a210797342b721eaff41b44033c0"
  "ncurses|pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb|f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99"
  # ncurses-ui-libs is a separate package and it is not optional: the readline
  # extension names libpanelw.so.6, which lives there. The closure check found
  # this rather than a user finding it as "import readline does not work".
  "ncurses-ui-libs|pool/main/n/ncurses-ui-libs/ncurses-ui-libs_6.6.20260307+really6.5.20250830_aarch64.deb|7393f369009be189b3d4ec1f9b16ebd57621d6a1b22949ae07685573950d1f37"
  "readline|pool/main/r/readline/readline_8.3.6_aarch64.deb|616cc73009bc0223f61caed8dd09455714455aa5ea5e180126e2f69c4c775136"
  "libsqlite|pool/main/libs/libsqlite/libsqlite_3.54.1-1_aarch64.deb|661fb995f7215845007b222a85266ae99d9443e87e0604f8677edfd738e6b81a"
  "zlib|pool/main/z/zlib/zlib_1.3.2_aarch64.deb|75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"
  "zstd|pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb|e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3"
)

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# A colon is legal in a URL path (openssl's version carries an epoch) but curl
# and some proxies mishandle it, so the path is percent-encoded before the
# request.
urlencode_path() {
    python3 - "$1" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1]))
PY
}

echo "=== GURU Python runtime fetch (issue 175) ==="
echo "Target: $ASSETS_DIR/python-runtime-arm64.zip"
echo ""

mkdir -p "$ASSETS_DIR"
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

echo "[1/4] Downloading ${#PACKAGES[@]} pinned packages..."
for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name filename expected <<< "$entry"
    dest="$TEMP_DIR/$(basename "$filename")"
    if [ ! -f "$dest" ]; then
        encoded="$(urlencode_path "$filename")"
        curl -L --fail --silent --show-error --max-time 300 -o "$dest" "$REPO/$encoded"
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

echo ""
echo "[2/4] Extracting every payload into one staging prefix..."
rm -rf "$TEMP_DIR/stage"
mkdir -p "$TEMP_DIR/stage"
for entry in "${PACKAGES[@]}"; do
    IFS='|' read -r name filename expected <<< "$entry"
    deb="$TEMP_DIR/$(basename "$filename")"
    work="$TEMP_DIR/x-$name"
    rm -rf "$work"
    mkdir -p "$work"
    # BSD tar reads the ar container directly, which is what macOS ships; ar is
    # the GNU fallback. macOS cctools ar cannot write members of a Unix archive,
    # so the order matters.
    if ! tar -xf "$deb" -C "$work" 2>/dev/null || [ ! -f "$work/data.tar.xz" ]; then
        rm -rf "$work"
        mkdir -p "$work"
        ( cd "$work" && ar x "$deb" ) >/dev/null 2>&1
    fi
    if [ ! -f "$work/data.tar.xz" ]; then
        echo "  ERROR: could not unpack $(basename "$filename")"
        exit 1
    fi
    tar -xf "$work/data.tar.xz" -C "$work"
    src="$work/data/data/com.termux/files/usr"
    if [ ! -d "$src" ]; then
        echo "  ERROR: $name did not contain a Termux prefix payload"
        exit 1
    fi
    # -a preserves modes. The merge is by package order, so a later package may
    # replace a file from an earlier one.
    cp -a "$src/." "$TEMP_DIR/stage/"
    rm -rf "$work"
done

echo ""
echo "[3/4] Assembling the runtime tree..."
python3 - "$TEMP_DIR/stage" "$TEMP_DIR/runtime" <<'PY'
import os, shutil, sys

stage, out = sys.argv[1], sys.argv[2]
shutil.rmtree(out, ignore_errors=True)
os.makedirs(out)

src_lib = os.path.join(stage, 'lib')
out_lib = os.path.join(out, 'lib')
os.makedirs(out_lib, exist_ok=True)

interpreter = os.path.join(src_lib, 'libpython3.14.so')
if not os.path.exists(interpreter):
    print("  ERROR: libpython3.14.so is not in the python package")
    sys.exit(1)
shutil.copy2(interpreter, os.path.join(out_lib, 'libpython3.14.so'))
print(f"  interpreter: {os.path.getsize(interpreter)} bytes")

# The standard library, at the level getpath searches.
src_stdlib = os.path.join(src_lib, 'python3.14')
if not os.path.isdir(src_stdlib):
    print("  ERROR: lib/python3.14 is not in the python package")
    sys.exit(1)
out_stdlib = os.path.join(out_lib, 'python3.14')
shutil.copytree(src_stdlib, out_stdlib, symlinks=True)

# Cut what a runtime never imports. These are the largest optional trees and
# dropping them is worth tens of megabytes in every APK.
for drop in ('test', 'idlelib', 'turtledemo', 'tkinter', 'ensurepip', 'lib2to3'):
    p = os.path.join(out_stdlib, drop)
    if os.path.isdir(p):
        shutil.rmtree(p)
for root, dirs, _files in os.walk(out_stdlib):
    for cache in [d for d in dirs if d == '__pycache__']:
        shutil.rmtree(os.path.join(root, cache), ignore_errors=True)
        dirs.remove(cache)

module_count = sum(len(files) for _r, _d, files in os.walk(out_stdlib))
print(f"  stdlib:      {module_count} files")
PY

echo ""
echo "[4/4] Computing the library closure and checking every ELF for 16 KB alignment..."
python3 - "$TEMP_DIR/stage" "$TEMP_DIR/runtime" <<'PY'
import os, shutil, struct, subprocess, sys

stage, runtime = sys.argv[1], sys.argv[2]
out_lib = os.path.join(runtime, 'lib')
stage_lib = os.path.join(stage, 'lib')

# Libraries the Android platform supplies itself. libz.so is here but libz.so.1
# is NOT: the platform exports the unversioned name and the extensions ask for
# the versioned one, so a versioned request stays in the bundled closure.
PLATFORM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
            'libstdc++.so'}

def elf_needed(path):
    # The whole file is read, not a prefix. The section-header table for a large
    # library can sit past the first few megabytes (libpython3.14.so is 5.8 MB and
    # its .dynamic is in the last section), and a truncated read raises
    # struct.error rather than returning no dependencies, which would silently
    # under-compute the closure.
    with open(path, 'rb') as fh:
        data = fh.read()
    if data[:4] != b'\x7fELF' or data[4] != 2:
        return []
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
    if not e_shoff or not e_shnum:
        return []
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
    dyn = [s for s in secs if sec_name(s[0]) == '.dynamic']
    if not dyn:
        return []
    dsec = dyn[0]
    dstr = data[secs[dsec[4]][2]:secs[dsec[4]][2] + secs[dsec[4]][3]]
    def dyn_name(off):
        end = dstr.find(b'\0', off)
        return dstr[off:end].decode('ascii', 'ignore')
    needed = []
    off, end = dsec[2], dsec[2] + dsec[3]
    while off < end:
        tag, val = struct.unpack_from('<qQ', data, off)
        off += 16
        if tag == 0:
            break
        if tag == 1:
            needed.append(dyn_name(val))
    return needed

provided = {}
for dirpath, _dirnames, filenames in os.walk(runtime):
    for name in filenames:
        if name.endswith('.so') or '.so.' in name:
            provided.setdefault(name, os.path.join(dirpath, name))

seeds = [os.path.join(out_lib, 'libpython3.14.so')]
dynload = os.path.join(out_lib, 'python3.14', 'lib-dynload')
for name in sorted(os.listdir(dynload)):
    if name.endswith('.so'):
        seeds.append(os.path.join(dynload, name))

missing = set()
queue = list(seeds)
seen = set()
while queue:
    path = queue.pop()
    if path in seen:
        continue
    seen.add(path)
    for name in elf_needed(path):
        if name in PLATFORM:
            continue
        if name in provided:
            queue.append(provided[name])
            continue
        candidate = os.path.join(stage_lib, name)
        if os.path.exists(candidate):
            dest = os.path.join(out_lib, name)
            shutil.copy2(candidate, dest)
            provided[name] = dest
            queue.append(dest)
        else:
            missing.add(name)

if missing:
    print("  ERROR: these libraries are required but no package provided them:")
    for name in sorted(missing):
        print(f"         {name}")
    print("  Add the providing package to PACKAGES in this script.")
    sys.exit(1)

bundled = sorted(n for n in os.listdir(out_lib) if '.so' in n)
print(f"  libraries bundled: {len(bundled)}")

# Alignment. This is the defect this script exists to prevent, so a failure is
# hard rather than a note.
readelf = os.environ.get('LLVM_READELF')
if not readelf:
    for candidate in (
        os.path.expanduser('~/Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf'),
        '/usr/local/bin/llvm-readelf',
        '/opt/homebrew/opt/llvm/bin/llvm-readelf',
    ):
        if os.path.exists(candidate):
            readelf = candidate
            break
if not readelf or not os.path.exists(readelf):
    print("  ERROR: llvm-readelf not found. Set LLVM_READELF to its path.")
    sys.exit(1)

bad = []
checked = 0
for root, _dirnames, filenames in os.walk(runtime):
    for name in filenames:
        if not (name.endswith('.so') or '.so.' in name):
            continue
        path = os.path.join(root, name)
        result = subprocess.run([readelf, '-l', path], capture_output=True, text=True)
        alignments = set()
        for line in result.stdout.splitlines():
            if 'LOAD' in line and '0x' in line:
                try:
                    alignments.add(int(line.split()[-1], 16))
                except (ValueError, IndexError):
                    pass
        checked += 1
        if any(a < 16384 for a in alignments):
            bad.append((os.path.relpath(path, runtime), sorted(alignments)))

print(f"  ELF files checked: {checked}")
if bad:
    print("  ERROR: not 16 KB page aligned, so a 16 KB-page device refuses to load them:")
    for name, al in bad[:20]:
        print(f"         {name} -> {al}")
    sys.exit(1)
print("  every ELF is 16 KB aligned")
PY

echo ""
echo "Packing $ASSETS_DIR/python-runtime-arm64.zip ..."
rm -f "$PROJECT_DIR/$ASSETS_DIR/python-runtime-arm64.zip"
( cd "$TEMP_DIR/runtime" && zip -r -y -X -q "$PROJECT_DIR/$ASSETS_DIR/python-runtime-arm64.zip" . )

echo ""
echo "=== Done ==="
echo "Asset:   $ASSETS_DIR/python-runtime-arm64.zip"
echo "Bytes:   $(wc -c < "$PROJECT_DIR/$ASSETS_DIR/python-runtime-arm64.zip" | tr -d ' ')"
echo "SHA-256: $(sha256_of "$PROJECT_DIR/$ASSETS_DIR/python-runtime-arm64.zip")"
echo ""
echo "PythonProvider extracts this into files/python/ and sets PYTHONHOME to the"
echo "extraction root, so the interpreter resolves lib/python3.14 and"
echo "lib/libpython3.14.so relative to itself."
