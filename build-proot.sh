#!/usr/bin/env bash
set -euo pipefail
# build-proot.sh - Build proot native binaries for the Agora Android app.
# Invoked from build.ps1 / build-googleplay.ps1 / build_fdroid.ps1.
# Must run inside WSL Arch (or any Linux with NDK 28.2.13676358).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORCE="${1:-}"

# ── NDK auto-detection ────────────────────────────────────────
# Priority: ANDROID_NDK_HOME env var, then well-known paths
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK="$ANDROID_NDK_HOME"
elif [ -d "/home/newoether/android-sdk/ndk/28.2.13676358" ]; then
    NDK="/home/newoether/android-sdk/ndk/28.2.13676358"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    # F-Droid buildserver: pick the latest installed NDK
    NDK=$(ls -d "$ANDROID_HOME/ndk/"*/ 2>/dev/null | sort -V | tail -1)
    NDK="${NDK%/}"
else
    echo "ERROR: NDK not found. Set ANDROID_NDK_HOME or install NDK 28.2.13676358."
    exit 1
fi

# Toolchain: NDK r28 on Linux uses llvm/prebuilt/linux-x86_64
TC_PREFIX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CROSS_PREFIX="aarch64-linux-android26"
CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
STRIP="${TC_PREFIX}/llvm-strip"
export PATH="${TC_PREFIX}:$PATH"

echo "=== build-proot.sh: NDK=$NDK ==="

# ── Paths ──────────────────────────────────────────────────────
TALLOC_SRC="$SCRIPT_DIR/thirdparty/talloc"
PROOT_SRC="$SCRIPT_DIR/thirdparty/proot/src"
BLD="$SCRIPT_DIR/.build-proot"
SYSROOT="$BLD/sysroot/arm64-v8a"
SYSROOT_LIB="$SYSROOT/lib"
SYSROOT_INC="$SYSROOT/include"
BLD_DIR="$BLD/build-proot-arm64-v8a"
LOADER_OUT="$BLD_DIR/loader-out"
JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
FDROID_JNILIBS="$SCRIPT_DIR/app/src/fdroid/jniLibs/arm64-v8a"

# ── Ensure readelf is available (GNUmakefile needs it) ─────────
if ! command -v readelf &>/dev/null; then
    if command -v llvm-readelf &>/dev/null; then
        READELF_DIR="$BLD/.tmp-bin"
        mkdir -p "$READELF_DIR"
        ln -sf "$(command -v llvm-readelf)" "$READELF_DIR/readelf"
        export PATH="$READELF_DIR:$PATH"
    fi
fi

# ── Hash-based incremental detection ───────────────────────────
HASH_FILE="$BLD_DIR/.source_hashes"
need_rebuild=false

calc_hashes() {
    local hash=""
    # Hash talloc source files
    hash+=$(md5sum "$TALLOC_SRC/talloc.c" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/talloc.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/config.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    hash+=$(md5sum "$TALLOC_SRC/replace.h" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    # Hash proot Makefile
    hash+=$(md5sum "$PROOT_SRC/GNUmakefile" 2>/dev/null | cut -d' ' -f1)
    hash+="-"
    # Hash NDK path (rebuild if NDK changes)
    hash+=$(echo "$NDK" | md5sum | cut -d' ' -f1)
    echo "$hash"
}

if [ "$FORCE" = "--force" ]; then
    need_rebuild=true
    echo "  (forced rebuild)"
elif [ ! -f "$HASH_FILE" ]; then
    need_rebuild=true
    echo "  (first build, no hash file)"
elif [ ! -f "$JNILIBS/libproot_exec.so" ] || \
     [ ! -f "$JNILIBS/libproot_loader.so" ] || \
     [ ! -f "$JNILIBS/libtalloc.so" ]; then
    need_rebuild=true
    echo "  (missing jniLibs output)"
else
    new_hash=$(calc_hashes)
    old_hash=$(cat "$HASH_FILE")
    if [ "$new_hash" != "$old_hash" ]; then
        need_rebuild=true
        echo "  (source changed)"
    else
        echo "  (up to date, skipping)"
    fi
fi

if [ "$need_rebuild" = false ]; then
    echo "=== build-proot.sh: All binaries up to date ==="
    exit 0
fi

echo "=== build-proot.sh: Building proot binaries ==="

# ── Create directories ─────────────────────────────────────────
mkdir -p "$SYSROOT_LIB" "$SYSROOT_INC" "$LOADER_OUT" \
         "$JNILIBS" "$FDROID_JNILIBS" "$BLD_DIR/loader"

# ── Step 1: Build talloc ───────────────────────────────────────
echo "  [1/4] Building libtalloc.so..."
"$CC" -fPIC -O2 -shared \
    -Wl,-soname,libtalloc.so \
    -o "$SYSROOT_LIB/libtalloc.so" \
    "$TALLOC_SRC/talloc.c" \
    -DHAVE_CONFIG_H \
    -I"$TALLOC_SRC"
cp "$TALLOC_SRC/talloc.h" "$SYSROOT_INC/"
echo "  [1/4] Done: $(stat -c%s "$SYSROOT_LIB/libtalloc.so") bytes"

# ── Step 2: Build proot via GNUmakefile ────────────────────────
echo "  [2/4] Building proot (GNUmakefile)..."
(
    cd "$BLD_DIR"
    export SOURCE_DATE_EPOCH=0
    export CPPFLAGS="-I${SYSROOT_INC} -DSYS_SECCOMP=1"
    export LDFLAGS="-L${SYSROOT_LIB}"
    export CC="${TC_PREFIX}/${CROSS_PREFIX}-clang"
    # Out-of-tree build using make -f (works without src/ symlink)
    rm -f proot loader/loader 2>/dev/null || true
    make -f "$PROOT_SRC/GNUmakefile" \
        CROSS_COMPILE="${CROSS_PREFIX}-" \
        PROOT_UNBUNDLE_LOADER="$LOADER_OUT" \
        proot
)
echo "  [2/4] Done: $(stat -c%s "$BLD_DIR/proot") bytes"

# ── Step 3: Strip and deploy binaries to jniLibs ───────────────
echo "  [3/4] Stripping and deploying..."

# proot PIE executable -> libproot_exec.so
"$STRIP" --strip-all \
    "$BLD_DIR/src/proot" \
    -o "$JNILIBS/libproot_exec.so"

# Loader (static ELF, already stripped by make) -> libproot_loader.so
LOADER_SRC="$BLD_DIR/src/loader/loader"
if [ ! -f "$LOADER_SRC" ]; then
    echo "ERROR: loader binary not found at $LOADER_SRC"
    exit 1
fi
cp "$LOADER_SRC" "$JNILIBS/libproot_loader.so"

# talloc -> jniLibs (strip)
"$STRIP" --strip-all \
    "$SYSROOT_LIB/libtalloc.so" \
    -o "$JNILIBS/libtalloc.so"

echo "  [3/4] Binaries deployed:"
echo "    $(stat -c%s "$JNILIBS/libproot_exec.so") bytes  libproot_exec.so"
echo "    $(stat -c%s "$JNILIBS/libproot_loader.so") bytes  libproot_loader.so"
echo "    $(stat -c%s "$JNILIBS/libtalloc.so") bytes  libtalloc.so"

# ── Step 4: Sync to fdroid jniLibs flavor ──────────────────────
echo "  [4/4] Syncing to fdroid jniLibs..."
cp "$JNILIBS/libproot_exec.so" "$FDROID_JNILIBS/libproot_exec.so"
cp "$JNILIBS/libproot_loader.so" "$FDROID_JNILIBS/libproot_loader.so"
cp "$JNILIBS/libtalloc.so" "$FDROID_JNILIBS/libtalloc.so"

# ── Save hash for next incremental check ───────────────────────
calc_hashes > "$HASH_FILE"

echo "=== build-proot.sh: Complete ==="
