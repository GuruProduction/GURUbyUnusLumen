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

  # ---------------------------------------------------------------- IMAGE WORK
  # Issue 317's second half: pixel work had no path at all. processFile returned
  # dimensions and OCR text, so image handling looked supported, and the moment a
  # task needed the actual pixels — crop, zoom, tile, compare, downscale a
  # 4000x3000 JPEG — there was nothing to call. PIL, numpy and cv2 were all
  # ModuleNotFoundError, and termuxInstall("python-pillow") was the only route to
  # one, which meant image work depended on the component that was broken.
  #
  # So Pillow ships INSIDE the runtime. Measured before adding: every one of its
  # seven compiled extensions has all LOAD segments at 0x4000, so a 16 KB device
  # loads them. That check is repeated by this script and fails the build if a
  # repository update ever breaks it.
  #
  # numpy is here for the arithmetic that PixelSense-style work needs and Pillow
  # does not do: per-channel statistics, masks, thresholding, differencing two
  # frames. It is one ABI-matched wheel against the same interpreter, so it can
  # load the private symbols libpython defines.
  "python-pillow|pool/main/p/python-pillow/python-pillow_13.0.0_aarch64.deb|6aabed29cc98cec8ea380214194297d95f861f9a27c0f5486b0a82c6a4799050"
  "python-numpy|pool/main/p/python-numpy/python-numpy_2.5.4_aarch64.deb|a99ae20cd33c77553cc94d402aefc4bed96b7ec9f18e9fb6e2e5a7943693c802"
)

# The roots whose FULL transitive dependency closure is resolved from the
# repository index.
#
# WHY THIS IS COMPUTED AND NOT LISTED. Hand-listing Pillow's dependencies took
# four build iterations, each one failing on a layer further down: the four
# image codecs first, then the AV1 encoders libavif pulls, then harfbuzz's own
# glib and libgraphite. Every one of those was a real missing library the check
# caught, and every one cost a full re-run to discover. Walking `Depends`
# transitively asks the repository the question it already knows the answer to,
# so the list is complete before the first download rather than after the fifth
# failure.
#
# The two roots stay pinned in PACKAGES because their versions are
# load-bearing: a Pillow or numpy built for a different Python minor cannot
# load into this interpreter, so a silent upgrade must fail the build rather
# than ship.
CLOSURE_ROOTS=(python-pillow python-numpy)

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

echo "[1/5] Resolving the full dependency closure of ${CLOSURE_ROOTS[*]} ..."
INDEX="$TEMP_DIR/Packages"
if [ ! -f "$INDEX" ]; then
    curl -L --fail --silent --show-error --max-time 300 -o "$INDEX" \
        "$REPO/dists/stable/main/binary-aarch64/Packages"
fi

# One program, one pass, the whole transitive closure.
#
# THREE THINGS THIS HAS TO GET RIGHT, and each was a real failure while it was
# being written:
#
#  1. A Depends field is a comma-separated list of ALTERNATIVES, and each
#     alternative may carry a version constraint: "python (>= 3.11.1-1)". Only
#     the first alternative is taken, because installing every branch of an
#     "a | b" choice pulls in two packages that provide the same symbol.
#  2. Packages already satisfied by the pinned list or by the Android platform
#     must not be re-added, or the closure walks back into libc and tries to
#     download an entire userland.
#  3. A dependency name that is not in the index has to FAIL the build with its
#     name. Silently skipping it is how a runtime ships that cannot import its
#     own image extension.
#
# PLATFORM_BACKED lists what Android itself provides, so the walk stops there.
#
# The resolver is run to a FILE, not through a process substitution.
#
# WHY: `while ... done < <(cmd)` runs cmd in a background subshell whose exit
# status the loop cannot see, so `$?` afterwards reports the last `read`, which
# is always 0. That made every failure look like success. Writing to a file
# first gives the resolver's real status, which is the whole point of it
# refusing to continue.
#
# AND WHY `set +e` AROUND IT. This script runs under `set -e`, which exits on
# the first non-zero status. Without the guard, a resolver failure killed the
# script before the error handler below could NAME the unresolved packages, so
# the useful message never printed. The status is captured explicitly instead,
# which is the point of having an error path at all.
set +e
python3 - "$INDEX" "${CLOSURE_ROOTS[@]}" "${PACKAGES[@]}" > "$TEMP_DIR/closure.txt" 2> "$TEMP_DIR/closure.err" <<'PY'
import sys
index = sys.argv[1]
roots = sys.argv[2:4]
already = set()
for entry in sys.argv[4:]:
    already.add(entry.split('|', 1)[0])

# Names the platform supplies. Walking past these would pull an entire Linux
# userland into an Android app.
PLATFORM_BACKED = {
    'libc', 'libm', 'libdl', 'liblog', 'libandroid', 'libstdc++',
    'libz', 'libzstd', 'libbz2', 'liblzma',           # platform + already bundled
    'python', 'libffi', 'openssl', 'libexpat', 'gdbm',
    'ncurses', 'ncurses-ui-libs', 'readline', 'libsqlite',
    'libandroid-support', 'libandroid-posix-semaphore',
}

# NOT RUNTIME DEPENDENCIES, and the distinction is load-bearing.
#
# A `.deb`'s Depends lists what the PACKAGE MANAGER needs for the package to be
# installable, which is a wider set than what the compiled code links against.
# python-pillow Depends on python-pip because you would normally pip-install
# into it — but pip ships no library any extension here names, and following it
# drags pip, its vendored dependencies and a certifi symlink into
# site-packages, where it collides with the stdlib copy and adds megabytes of
# installer to a runtime that never installs anything.
#
# This is an exclusion list with a reason per entry, not a convenience. The ELF
# closure check below remains the authority on what is actually REQUIRED: it
# walks DT_NEEDED from the real binaries and fails the build on anything it
# cannot find, so excluding a package that genuinely provides a needed library
# surfaces as that named library, not as a silent absence.
NOT_RUNTIME = {
    'python-pip',        # package installer; Pillow's Depends names it for pip use
}

paras = {}
para = []
with open(index, encoding='utf-8', errors='replace') as fh:
    for line in fh:
        line = line.rstrip('\n')
        if line.strip():
            para.append(line)
            continue
        if para:
            d = dict(l.split(': ', 1) for l in para if ': ' in l)
            if 'Package' in d:
                paras.setdefault(d['Package'], d)
            para = []
if para:
    d = dict(l.split(': ', 1) for l in para if ': ' in l)
    if 'Package' in d:
        paras.setdefault(d['Package'], d)


def dep_names(depends):
    """The first alternative of each comma-separated clause, without a version."""
    out = []
    for clause in depends.split(','):
        clause = clause.strip()
        if not clause:
            continue
        first = clause.split('|')[0].strip()
        name = first.split('(')[0].strip()
        name = name.split(':')[0].strip()          # drop a :any / :native suffix
        if name:
            out.append(name)
    return out


missing = []
# THE ROOTS ARE SEEDED AS DEPENDENCIES, NOT AS PACKAGES.
#
# Both roots are already in PACKAGES — they are the two pinned entries — so
# `already` contains them. Seeding the queue with the root NAMES therefore
# matches `name in seen` on the first hop and walks nothing at all, which is a
# resolver that silently resolves zero dependencies and looks like success.
# Starting from each root's Depends is the correct walk: the roots are fetched
# from the pinned list, and everything they pull in is what this computes.
seen = set(already)
queue = []
for root in roots:
    d = paras.get(root)
    if d is None:
        missing.append(root)
    else:
        queue.extend(dep_names(d.get('Depends', '')))
resolved = []
while queue:
    name = queue.pop(0)
    if name in seen or name in PLATFORM_BACKED or name in NOT_RUNTIME:
        continue
    seen.add(name)
    d = paras.get(name)
    if d is None:
        missing.append(name)
        continue
    resolved.append(name)
    queue.extend(dep_names(d.get('Depends', '')))

if missing:
    # Report every unresolved name at once rather than one per build.
    sys.stderr.write("MISSING:" + ",".join(sorted(set(missing))) + "\n")
    sys.exit(3)

for name in resolved:
    d = paras[name]
    print(f"{name}|{d.get('Filename', '')}|{d.get('SHA256', '')}")
PY

resolve_status=$?
set -e
if [ "$resolve_status" -ne 0 ]; then
    echo "  ERROR: the dependency closure names packages the repository index does not have:"
    # The resolver writes MISSING:name,name on stderr. Split it with parameter
    # expansion rather than an external tool, so the names are printed plainly
    # and this stays one piece of logic. A name that is wrong or retired is a
    # real gap; shipping past it would produce a runtime whose image extension
    # fails to import on a phone.
    missing_line="$(while IFS= read -r l; do case "$l" in MISSING:*) printf '%s' "${l#MISSING:}" ;; esac; done < "$TEMP_DIR/closure.err")"
    old_ifs="$IFS"; IFS=','
    for missing_name in $missing_line; do
        echo "         $missing_name"
    done
    IFS="$old_ifs"
    exit 1
fi

while IFS='|' read -r rname rfile rhash; do
    [ -z "$rname" ] && continue
    if [ -z "$rfile" ] || [ -z "$rhash" ]; then
        echo "  ERROR: '$rname' has no Filename or SHA256 in the repository index."
        echo "         Either the name is wrong or the repository moved it."
        exit 1
    fi
    PACKAGES+=("$rname|$rfile|$rhash")
    echo "  -> $rname"
done < "$TEMP_DIR/closure.txt"

echo ""
echo "[2/5] Downloading ${#PACKAGES[@]} packages..."
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
echo "[3/5] Extracting every payload into one staging prefix..."
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
echo "[4/5] Assembling the runtime tree..."
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

# ------------------------------------------------------------------ IMAGE WORK
# site-packages is where a wheel-installed package lives, and CPython's own
# getpath puts it at <stdlib>/site-packages. It is copied here because the
# runtime now carries Pillow and numpy (issue 317): without this directory the
# imports fail with ModuleNotFoundError exactly as they did before, and the
# archives would be in the APK achieving nothing.
#
# A .pth file is written for the same reason it exists at all: site.py processes
# every *.pth in site-packages at startup and appends each listed directory to
# sys.path. numpy's own package layout expects that, so dropping the tree in
# without it produces "No module named numpy.core._multiarray_umath".
src_site = os.path.join(src_stdlib, 'site-packages')
out_site = os.path.join(out_stdlib, 'site-packages')
if os.path.isdir(src_site):
    # dirs_exist_ok=True because the stdlib copytree above already carried the
    # directory across: site-packages ships inside the python package as an
    # empty container, and the installed packages are merged into it. Without
    # this flag the copy raises FileExistsError and the whole build stops here.
    shutil.copytree(src_site, out_site, symlinks=True, dirs_exist_ok=True)
    # Byte-code trees are rebuilt on the device and only bloat the APK.
    for root, dirs, _files in os.walk(out_site):
        for cache in [d for d in dirs if d == '__pycache__']:
            shutil.rmtree(os.path.join(root, cache), ignore_errors=True)
            dirs.remove(cache)
    pkgs = sorted(d for d in os.listdir(out_site)
                  if os.path.isdir(os.path.join(out_site, d)))
    print(f"  site-packages: {len(pkgs)} packages -> {', '.join(pkgs)}")
else:
    # Not optional any more. A runtime without site-packages cannot do image
    # work, and shipping silently without it is what made this a gap nobody
    # could see from the outside.
    print("  ERROR: site-packages is not in the assembled tree, which means the")
    print("         Pillow or numpy payload did not land. Image work would be")
    print("         absent again. Check PACKAGES and RESOLVED_PACKAGES.")
    sys.exit(1)
PY

echo ""
echo "[5/5] Computing the library closure and checking every ELF for 16 KB alignment..."
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

# Pillow's and numpy's compiled extensions are seeds too (issue 317). Each names
# libraries the stdlib never touches — libjpeg, libtiff, libwebp, libpng,
# libopenblas — and if they are not walked into the closure the whole build
# succeeds and the first `import PIL` on a phone fails with "cannot locate
# symbol". That is the exact class of fault this script exists to catch here
# rather than three releases later.
site_packages = os.path.join(out_lib, 'python3.14', 'site-packages')
if os.path.isdir(site_packages):
    for root, _dirnames, filenames in os.walk(site_packages):
        for name in filenames:
            if name.endswith('.so'):
                seeds.append(os.path.join(root, name))

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
