#!/bin/bash
# download_re_tools.sh
# Downloads and prepares reverse engineering tool binaries for bundling into Guru.
# Run this from the project root: ./download_re_tools.sh
#
# Tools bundled:
#   aapt2  - Android Asset Packaging Tool v2 (from Google build-tools)
#   apktool - APK decompiler/recompiler (JAR, platform-independent)
#   jadx    - DEX to Java decompiler (native ARM64 binary)
#   dex2jar - DEX to JAR converter (Java tool suite, zipped)

set -e

ASSETS_DIR="app/src/main/assets/re-tools"
TEMP_DIR="/tmp/guru-re-tools-dl"

echo "=== Guru RE Tools Downloader ==="
echo "Target: $ASSETS_DIR"
echo ""

mkdir -p "$ASSETS_DIR"
mkdir -p "$TEMP_DIR"

# --- aapt2 (from Google's Maven) ---
echo "[1/4] Downloading aapt2..."

AAPT2_VERSION="8.7.3"
AAPT2_URL_ARM64="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/${AAPT2_VERSION}/aapt2-${AAPT2_VERSION}-linux-arm64.jar"
AAPT2_URL_X86_64="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/${AAPT2_VERSION}/aapt2-${AAPT2_VERSION}-linux-x86_64.jar"

if [ ! -f "$ASSETS_DIR/aapt2-arm64" ]; then
    echo "  Fetching ARM64 aapt2..."
    curl -L --progress-bar -o "$TEMP_DIR/aapt2-arm64.jar" "$AAPT2_URL_ARM64"
    # aapt2 is packaged as a jar but the actual binary is inside
    cd "$TEMP_DIR"
    unzip -o aapt2-arm64.jar "aapt2" 2>/dev/null
    if [ -f "aapt2" ]; then
        cp aapt2 "$ASSETS_DIR/aapt2-arm64"
        chmod +x "$ASSETS_DIR/aapt2-arm64"
        echo "  -> aapt2-arm64 extracted"
    else
        # Try extracting the full jar contents
        unzip -o aapt2-arm64.jar -d aapt2_extracted 2>/dev/null
        find aapt2_extracted -name "aapt2" -type f -exec cp {} "$ASSETS_DIR/aapt2-arm64" \;
        if [ -f "$ASSETS_DIR/aapt2-arm64" ]; then
            chmod +x "$ASSETS_DIR/aapt2-arm64"
            echo "  -> aapt2-arm64 extracted (deep search)"
        else
            echo "  WARNING: Could not find aapt2 binary inside the jar. Check the Google Maven URL."
        fi
    fi
    cd - > /dev/null
else
    echo "  -> aapt2-arm64 already exists, skipping"
fi

if [ ! -f "$ASSETS_DIR/aapt2-x86_64" ]; then
    echo "  Fetching x86_64 aapt2..."
    curl -L --progress-bar -o "$TEMP_DIR/aapt2-x86_64.jar" "$AAPT2_URL_X86_64"
    cd "$TEMP_DIR"
    unzip -o aapt2-x86_64.jar "aapt2" 2>/dev/null
    if [ -f "aapt2" ]; then
        cp aapt2 "$ASSETS_DIR/aapt2-x86_64"
        chmod +x "$ASSETS_DIR/aapt2-x86_64"
        echo "  -> aapt2-x86_64 extracted"
    fi
    cd - > /dev/null
else
    echo "  -> aapt2-x86_64 already exists, skipping"
fi

# --- apktool (JAR, platform-independent) ---
echo "[2/4] Downloading apktool..."

APKTOOL_VERSION="2.10.0"
APKTOOL_URL="https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar"

if [ ! -f "$ASSETS_DIR/apktool.jar" ]; then
    echo "  Fetching apktool ${APKTOOL_VERSION}..."
    curl -L --progress-bar -o "$ASSETS_DIR/apktool.jar" "$APKTOOL_URL"
    echo "  -> apktool.jar downloaded"
else
    echo "  -> apktool.jar already exists, skipping"
fi

# --- jadx (native binary) ---
echo "[3/4] Downloading jadx..."

JADX_VERSION="1.5.1"
JADX_URL_ARM64="https://github.com/skylot/jadx/releases/download/v${JADX_VERSION}/jadx-${JADX_VERSION}.zip"

if [ ! -f "$ASSETS_DIR/jadx-arm64" ]; then
    echo "  Fetching jadx ${JADX_VERSION}..."
    curl -L --progress-bar -o "$TEMP_DIR/jadx.zip" "$JADX_URL_ARM64"
    cd "$TEMP_DIR"
    unzip -o jadx.zip "bin/jadx" 2>/dev/null
    if [ -f "bin/jadx" ]; then
        cp bin/jadx "$ASSETS_DIR/jadx-arm64"
        chmod +x "$ASSETS_DIR/jadx-arm64"
        echo "  -> jadx-arm64 extracted"
    else
        # jadx might have different structure, try finding the binary
        unzip -o jadx.zip -d jadx_extracted 2>/dev/null
        find jadx_extracted -name "jadx" -type f ! -name "*.bat" -exec cp {} "$ASSETS_DIR/jadx-arm64" \;
        if [ -f "$ASSETS_DIR/jadx-arm64" ]; then
            chmod +x "$ASSETS_DIR/jadx-arm64"
            echo "  -> jadx-arm64 extracted (deep search)"
        else
            echo "  WARNING: Could not find jadx binary. Check the jadx release structure."
        fi
    fi
    cd - > /dev/null
else
    echo "  -> jadx-arm64 already exists, skipping"
fi

# jadx x86_64 (same zip, just rename if architecture matches)
if [ ! -f "$ASSETS_DIR/jadx-x86_64" ] && [ -f "$ASSETS_DIR/jadx-arm64" ]; then
    cp "$ASSETS_DIR/jadx-arm64" "$ASSETS_DIR/jadx-x86_64"
    echo "  -> jadx-x86_64 copied from arm64 (jadx is often a fat binary, rebuild for x86 if needed)"
fi

# --- dex2jar (Java tool suite, zipped) ---
echo "[4/4] Downloading dex2jar..."

DEX2JAR_URL="https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"

if [ ! -f "$ASSETS_DIR/dex2jar.zip" ]; then
    echo "  Fetching dex2jar..."
    curl -L --progress-bar -o "$ASSETS_DIR/dex2jar.zip" "$DEX2JAR_URL"
    echo "  -> dex2jar.zip downloaded"
else
    echo "  -> dex2jar.zip already exists, skipping"
fi

# --- Cleanup ---
rm -rf "$TEMP_DIR"

echo ""
echo "=== Done ==="
echo "Files in $ASSETS_DIR:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "Next steps:"
echo "  1. Run: ./gradlew assembleDebug"
echo "  2. Install on device"
echo "  3. Guru's ReverseEngineeringToolSet will auto-detect and use bundled tools"
