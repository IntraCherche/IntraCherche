#!/bin/bash
# build-distrib.sh — assemble the two distributable archives.
#
# Run on an internet-connected Linux x86-64 machine from the project root:
#   ./distribution/build-distrib.sh [--no-checksum] [--no-model]
#
#   --no-checksum   Skip generating the .sha256 files. Hashing the multi-GB
#                   archives is slow; skip it while iterating on the install
#                   process, then do a final run without the flag to ship.
#   --no-model      Build only the base archive. Use when the LLM has not
#                   changed: the client keeps its installed model archive.
#
# Output (in dist/):
#   intracherche-X.Y.Z-base.tar.gz  +  .sha256
#   intracherche-ollama-qwen3.5-9b.tar.gz  +  .sha256  (name follows OLLAMA_MODEL)
set -euo pipefail
cd "$(dirname "$0")/.."   # always run from project root

# ── Flags ─────────────────────────────────────────────────────────────────────
SKIP_CHECKSUM=false
SKIP_MODEL=false
for arg in "$@"; do
    case "$arg" in
        --no-checksum|--skip-checksum) SKIP_CHECKSUM=true ;;
        --no-model|--skip-model) SKIP_MODEL=true ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# ── Pinned versions of external components ────────────────────────────────────
SOLR_VERSION="9.10.1"
OLLAMA_VERSION="0.24.0"     # check: github.com/ollama/ollama/releases
UV_VERSION="0.7.8"          # check: github.com/astral-sh/uv/releases
JRE_VERSION="21.0.12.1+1"    # Temurin LTS — check: adoptium.net/temurin/releases/?version=21
JRE_SHA256="2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500"
OLLAMA_MODEL="qwen3.5:9b"
PYTHON_VERSION="3.12"

# ── Download URLs — the single place to update when a mirror or layout moves ──
# Note: Apache keeps only the latest release on downloads.apache.org; older
# versions move to archive.apache.org/dist/. GitHub asset names also change
# between Ollama versions. All URLs are HEAD-checked in pre-flight (fail fast).
SOLR_URL="https://downloads.apache.org/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.tgz"
OLLAMA_URL="https://github.com/ollama/ollama/releases/download/v${OLLAMA_VERSION}/ollama-linux-amd64.tar.zst"
UV_URL="https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"
# Temurin asset: tag jdk-21.0.12.1+1 (+ → %2B in the URL), file uses 21.0.12.1_1.
JRE_PKGVER="${JRE_VERSION/+/_}"
JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${JRE_VERSION/+/%2B}/OpenJDK21U-jre_x64_linux_hotspot_${JRE_PKGVER}.tar.gz"

# ── Release version ───────────────────────────────────────────────────────────
# Strips -SNAPSHOT; override with: VERSION=1.2.0 ./distribution/build-distrib.sh
RAW_VERSION=$(grep -m1 '<version>' pom.xml | sed 's/.*<version>//;s/<\/version>.*//' | tr -d ' ')
VERSION="${VERSION:-${RAW_VERSION/-SNAPSHOT/}}"
BASE_NAME="intracherche-${VERSION}"
# Archive name follows the model id (qwen3.5:9b -> qwen3.5-9b) so bumping
# OLLAMA_MODEL above is the only edit needed here.
MODEL_NAME="intracherche-ollama-$(echo "$OLLAMA_MODEL" | tr ':' '-')"

DIST="dist"
STAGE="$DIST/$BASE_NAME"     # base archive staging dir (wiped on every run)
MODEL_STAGE="$DIST/model-stage"  # Ollama model staging dir
CACHE="$DIST/cache"          # downloaded artifacts; survives re-builds

# The guide shipped inside the base archive (it is named INSTALL.md there, whatever its source).
GUIDE_SOURCE="doc/INSTALL.md"

# Compress with pigz (parallel gzip) when available — most of the payload is
# already-compressed data (wheels, models, the nested Solr .tgz), so single-
# threaded gzip just burns one core for no size gain. pigz output is plain
# gzip, so the archives stay .tar.gz and the client's `tar -xzf` is unchanged.
if command -v pigz &>/dev/null; then GZIP_PROG="pigz"; else GZIP_PROG="gzip"; fi

# Cached download targets (version in the name so a pin bump re-downloads)
SOLR_CACHED="$CACHE/solr-${SOLR_VERSION}.tgz"
OLLAMA_CACHED="$CACHE/ollama-linux-amd64-${OLLAMA_VERSION}.tar.zst"
UV_CACHED="$CACHE/uv-x86_64-unknown-linux-gnu-${UV_VERSION}.tar.gz"
JRE_CACHED="$CACHE/OpenJDK21U-jre_x64_linux_hotspot_${JRE_PKGVER}.tar.gz"

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; BOLD='\033[1m'; NC='\033[0m'
info()  { echo -e "${GREEN}▶${NC} $*"; }
step()  { echo ""; echo -e "${BOLD}── $* ──${NC}"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*"; }
die()   { echo -e "${RED}✗${NC}  $*" >&2; exit 1; }

# ── 1. Pre-flight ─────────────────────────────────────────────────────────────
step "Pre-flight checks"

[[ "$(uname -m)" == "x86_64" ]] || die "Build must run on x86-64 (wheels are platform-specific)."

check() { command -v "$1" &>/dev/null || die "'$1' not found — install it first."; }
check mvn
check node
check npm
check python3
check pandoc
# LibreOffice turns the generated guide into a PDF (build_guide.py looks in the same places).
[[ -x "${SOFFICE:-}" ]] || command -v soffice &>/dev/null || command -v libreoffice &>/dev/null \
    || compgen -G "/opt/libreoffice*/program/soffice" >/dev/null \
    || die "LibreOffice not found — needed to produce INSTALL.pdf (set SOFFICE=/path/to/soffice)."
check uv
check pip
[[ "$SKIP_MODEL" == true ]] || check ollama
check curl
check tar
check sha256sum
check zstd      # Ollama release archives are .tar.zst

# Fail fast on stale download URLs, before the slow build steps run.
# Only checked when the artifact is not already cached, so a fully cached
# re-build still works offline.
check_url() {
    curl -fsIL --max-time 20 -o /dev/null "$1" \
        || die "Download URL not reachable: $1
   Check the version pins / URLs at the top of this script."
}
info "Checking download URLs…"
[[ -f "$SOLR_CACHED" ]]   || { check_url "$SOLR_URL"; check_url "${SOLR_URL}.sha512"; }
[[ -f "$OLLAMA_CACHED" ]] || check_url "$OLLAMA_URL"
[[ -f "$UV_CACHED" ]]     || check_url "$UV_URL"
[[ -f "$JRE_CACHED" ]]    || check_url "$JRE_URL"
info "  All download URLs reachable."

info "Building version: $VERSION"
info "Tools: $(mvn -version 2>&1 | head -1), Node $(node -v), $(uv version)"

# ── 2. Frontend + JAR ─────────────────────────────────────────────────────────
step "Building frontend"
(cd src/ui-vue && npm ci --silent && npm run build)
info "Frontend built → src/main/resources/static/"

step "Building JAR"
mvn clean package -DskipTests -q
JAR=$(find target -maxdepth 1 -name "intracherche-*.jar" ! -name "*-sources.jar" | head -1)
[[ -n "$JAR" ]] || die "JAR not found in target/ after mvn package."
info "JAR: $JAR"

# ── 3. Scaffold staging directory ─────────────────────────────────────────────
step "Scaffolding staging directory"
rm -rf "$STAGE"
mkdir -p \
  "$STAGE/app" \
  "$STAGE/config/solr/configset" \
  "$STAGE/python-services" \
  "$STAGE/wheels/"{embedding,reranker,ner,ocr}"-service" \
  "$STAGE/models/"{embedding,reranker} \
  "$STAGE/solr" \
  "$STAGE/ollama" \
  "$STAGE/uv/bin" \
  "$STAGE/jre" \
  "$STAGE/python"
mkdir -p "$CACHE"

# ── 4. Application artifacts ──────────────────────────────────────────────────
step "Copying application artifacts"
cp "$JAR" "$STAGE/app/intracherche.jar"
cp -r src/main/resources/solr/configset/. "$STAGE/config/solr/configset/"
cp src/main/resources/config/indexes.yaml "$STAGE/config/indexes.yaml.template"
# Every setting with its default and what it does, installed beside the site's application.yaml.
cp src/main/resources/config/application.yaml "$STAGE/config/application.yaml.reference"
cp distribution/install.sh "$STAGE/install.sh"
chmod +x "$STAGE/install.sh"
# Inside the archive the guide is always called INSTALL.md, the name the application's log messages point to.
cp "$GUIDE_SOURCE" "$STAGE/INSTALL.md"
# The short guide to running Sherlock, reranking and OCR on a separate GPU machine (optional).
cp doc/LISEZMOI-GPU.md "$STAGE/LISEZMOI-GPU.md"
# What changed in each version, for the administrator deciding whether to update.
cp CHANGELOG.md "$STAGE/CHANGELOG.md"
# Reading copies of the same guide (INSTALL.odt, INSTALL.pdf), generated: INSTALL.md stays the copy-paste source.
python3 -I distribution/guide/build_guide.py "$STAGE/INSTALL.md" "$STAGE" \
    || die "Could not generate INSTALL.odt / INSTALL.pdf (needs pandoc and LibreOffice; SOFFICE=/path/to/soffice if it is not on the PATH)."
# A copy next to INSTALL.md for whoever reads it from the repository (ignored by git: always as new as the archive).
cp "$STAGE/INSTALL.odt" "$STAGE/INSTALL.pdf" doc/
cp distribution/generate-certificate.sh "$STAGE/generate-certificate.sh"
chmod +x "$STAGE/generate-certificate.sh"
cp distribution/NOTICE "$STAGE/NOTICE" 2>/dev/null || warn "NOTICE file not found — create distribution/NOTICE before releasing."
info "Application artifacts copied."

# ── 5. Python services ────────────────────────────────────────────────────────
step "Copying Python services"
for SVC in embedding-service reranker-service ner-service ocr-service; do
    # Copy source, excluding dev artefacts. The embedding/reranker `model/`
    # dirs are deliberately excluded: those models are bundled once under
    # models/ (steps 7-8) and the installer wires them from there — copying
    # them here too duplicated ~2.5 GB into the archive.
    rsync -a --exclude='.venv' --exclude='__pycache__' --exclude='*.pyc' \
        --exclude='model' \
        "$SVC/" "$STAGE/python-services/$SVC/"
    info "  $SVC copied."
done

# ── 6. Python wheels (offline install) ───────────────────────────────────────
step "Downloading Python wheels for Linux x86-64"
# Wheels are downloaded for the deployment target (Linux x86-64, CPython 3.12)
# so the installer can run pip/uv without internet access.
#
# sentence-transformers depends on torch. The default torch wheel on PyPI is
# the CUDA build, which drags in ~2.5 GB of nvidia_* + triton wheels per
# service — dead weight in this CPU-only bundle. PyTorch publishes CPU-only
# builds (torch…+cpu, ~180 MB, no CUDA deps) on a dedicated index, so resolve
# against it first and fall back to PyPI for everything else.
PIP_INDEX_ARGS=(
    --index-url https://download.pytorch.org/whl/cpu
    --extra-index-url https://pypi.org/simple
)
for SVC in embedding-service reranker-service ner-service ocr-service; do
    info "  Exporting requirements for $SVC…"
    WHEEL_DIR="$STAGE/wheels/$SVC"
    # Ship the pinned requirements next to the wheels. install.sh installs with
    # `uv pip install --no-index --find-links … -r requirements.txt`, which
    # honours find-links for fetching. (`uv sync` cannot be used offline: it
    # fetches from the registry URLs recorded in uv.lock and ignores
    # --find-links, so it always reaches the network.)
    REQ_FILE="$WHEEL_DIR/requirements.txt"
    RAW_REQ="$WHEEL_DIR/requirements.raw.txt"
    # Export twice from one resolution:
    #   • RAW_REQ keeps direct-URL wheel references ("name @ https://…/name-VER-
    #     py3….whl", e.g. the spaCy French models) so `pip download` below can
    #     fetch those wheels from their URL — they are not on any index.
    #   • REQ_FILE (shipped) rewrites them to "name==VER" so the offline
    #     install.sh resolves them from the bundled wheel via --find-links
    #     --no-index. A direct URL would force uv to re-fetch the URL even
    #     under --offline, defeating the offline install.
    uv export --project "$SVC" --no-hashes --format requirements-txt > "$RAW_REQ"
    sed -E 's| @ https?://[^ ]+/[A-Za-z0-9_]+-([0-9][^-]*)-py3[^ ]*\.whl|==\1|' \
        "$RAW_REQ" > "$REQ_FILE"
    # Packages published only as source (no wheel on PyPI, e.g. antlr4-python3-
    # runtime, pulled in by rapidocr → omegaconf) would have to be built on the
    # target, which is offline and has no build backend. Build their wheels here
    # instead, and keep them out of the --only-binary download below: one such
    # package fails it, and the unrestricted fallback fetches wheels for the
    # build host's Python rather than the target's.
    SDIST_ONLY=$(python3 - "$SVC/uv.lock" <<'PYEOF'
import sys, tomllib
lock = tomllib.load(open(sys.argv[1], "rb"))
for p in lock.get("package", []):
    if "sdist" in p and not p.get("wheels") and p["source"].get("registry"):
        print(f"{p['name']}=={p['version']}")
PYEOF
)
    for PKG in $SDIST_ONLY; do
        info "  Building wheel for source-only package $PKG…"
        pip wheel --quiet --no-deps "${PIP_INDEX_ARGS[@]}" --wheel-dir "$WHEEL_DIR" "$PKG"
        NAME="${PKG%%==*}"
        ls "$WHEEL_DIR"/"${NAME//-/_}"-*-py3-none-any.whl >/dev/null 2>&1 \
            || die "$PKG did not build to a pure-Python wheel; it cannot be bundled for another platform."
        grep -viE "^${NAME}==" "$RAW_REQ" > "$RAW_REQ.tmp" && mv "$RAW_REQ.tmp" "$RAW_REQ"
    done
    # --no-deps: the export already lists the full locked set. Letting pip resolve
    # dependencies again would bypass uv's override-dependencies and bundle, e.g.,
    # the GUI opencv-python that ocr-service deliberately replaces with the headless build.
    pip download \
        --quiet \
        --no-deps \
        "${PIP_INDEX_ARGS[@]}" \
        --requirement "$RAW_REQ" \
        --dest "$WHEEL_DIR" \
        --python-version "$PYTHON_VERSION" \
        --platform manylinux_2_28_x86_64 \
        --platform manylinux_2_17_x86_64 \
        --platform linux_x86_64 \
        --implementation cp \
        --abi "cp${PYTHON_VERSION//./}" \
        --only-binary :all: \
        2>/dev/null \
    || {
        warn "  Some packages have no binary wheel — retrying without platform restriction."
        pip download --quiet --no-deps "${PIP_INDEX_ARGS[@]}" --requirement "$RAW_REQ" --dest "$WHEEL_DIR"
    }
    rm -f "$RAW_REQ"
    # The fallback above fetches wheels for the BUILD host's Python (a network hiccup is enough to trigger it), which the
    # target cannot install offline ("no wheels with a matching Python ABI tag", found on a demo upgrade). Refuse to ship them.
    WRONG_ABI=$(ls "$WHEEL_DIR" | grep -E -- '-cp3[0-9]+-cp3[0-9]+-' | grep -v -- "-cp${PYTHON_VERSION//./}-cp${PYTHON_VERSION//./}-" || true)
    [[ -z "$WRONG_ABI" ]] || die "$SVC: wheels for another Python than $PYTHON_VERSION were downloaded (the strict download failed and the fallback fetched the build host's): $(echo "$WRONG_ABI" | head -3 | tr '\n' ' ')… Run the build again."
    WHEEL_COUNT=$(find "$WHEEL_DIR" -name "*.whl" -o -name "*.tar.gz" | wc -l)
    info "  $SVC: $WHEEL_COUNT packages."
done

# ── 7. Embedding model ────────────────────────────────────────────────────────
step "Downloading embedding model"
if [[ -d embedding-service/model ]] && find embedding-service/model -name "*.onnx" -quit 2>/dev/null; then
    info "  Embedding model already present in embedding-service/model/"
else
    info "  Downloading intfloat/multilingual-e5-base (ONNX INT8)…"
    (cd embedding-service && uv run python download_model.py)
fi
cp -r embedding-service/model/. "$STAGE/models/embedding/"
info "  Embedding model bundled."

# ── 8. Reranker model ─────────────────────────────────────────────────────────
step "Downloading reranker model"
if [[ -d reranker-service/model ]] && [[ -n "$(ls reranker-service/model 2>/dev/null)" ]]; then
    info "  Reranker model already present in reranker-service/model/"
else
    info "  Downloading BAAI/bge-reranker-v2-m3…"
    (cd reranker-service && uv run python download_model.py)
fi
cp -r reranker-service/model/. "$STAGE/models/reranker/"
info "  Reranker model bundled."

# ── 9. Apache Solr ────────────────────────────────────────────────────────────
step "Downloading Apache Solr $SOLR_VERSION"
if [[ -f "$SOLR_CACHED" ]]; then
    info "  Solr tarball already in $CACHE."
else
    info "  Downloading $SOLR_URL …"
    curl -fL --progress-bar "$SOLR_URL" -o "$SOLR_CACHED"
    # Verify the Apache download with its published SHA512.
    # The .sha512 file references the bare filename, so check from the cache dir.
    curl -fsSL "${SOLR_URL}.sha512" -o "$SOLR_CACHED.sha512"
    (cd "$CACHE" && sha512sum -c "$(basename "$SOLR_CACHED").sha512") \
        || die "Solr tarball checksum failed."
    rm "$SOLR_CACHED.sha512"
fi
cp "$SOLR_CACHED" "$STAGE/solr/"
info "  Solr $SOLR_VERSION bundled."

# ── 10. Ollama binary ─────────────────────────────────────────────────────────
step "Downloading Ollama $OLLAMA_VERSION (Linux CPU)"
if [[ -f "$OLLAMA_CACHED" ]]; then
    info "  Ollama archive already in $CACHE."
else
    info "  Downloading $OLLAMA_URL …"
    curl -fL --progress-bar "$OLLAMA_URL" -o "$OLLAMA_CACHED"
fi
# Archive layout: bin/ollama + lib/ollama (ggml runtime libs).
# The CUDA libs in lib/ollama/cuda_* are useless in this CPU-only bundle.
tar --zstd -xf "$OLLAMA_CACHED" -C "$STAGE/ollama" --exclude='lib/ollama/cuda_*'
chmod +x "$STAGE/ollama/bin/ollama"
info "  Ollama $OLLAMA_VERSION bundled."

# ── 11. uv binary ────────────────────────────────────────────────────────────
step "Downloading uv $UV_VERSION"
if [[ -f "$UV_CACHED" ]]; then
    info "  uv tarball already in $CACHE."
else
    info "  Downloading $UV_URL …"
    curl -fL --progress-bar "$UV_URL" -o "$UV_CACHED"
fi
UV_TMP=$(mktemp -d)
tar -xzf "$UV_CACHED" -C "$UV_TMP"
cp "$UV_TMP"/uv-x86_64-unknown-linux-gnu/uv "$STAGE/uv/bin/uv"
chmod +x "$STAGE/uv/bin/uv"
rm -rf "$UV_TMP"
info "  uv $UV_VERSION bundled."

# ── 11b. Java runtime (Temurin JRE) ───────────────────────────────────────────
# Bundled so the installer never needs apt — the target may be air-gapped.
# install.sh uses $BUNDLE_DIR/jre when present and only falls back to apt
# (which fails offline) when it is missing.
step "Downloading Temurin JRE $JRE_VERSION"
if [[ -f "$JRE_CACHED" ]]; then
    info "  JRE tarball already in $CACHE."
else
    info "  Downloading $JRE_URL …"
    curl -fL --progress-bar "$JRE_URL" -o "$JRE_CACHED"
fi
echo "$JRE_SHA256  $JRE_CACHED" | sha256sum -c - >/dev/null \
    || die "JRE tarball checksum failed (expected $JRE_SHA256)."
# Temurin archives unpack into a single jdk-…-jre/ directory; strip it so the
# runtime lands directly under jre/ (giving jre/bin/java).
tar -xzf "$JRE_CACHED" --strip-components=1 -C "$STAGE/jre"
[[ -x "$STAGE/jre/bin/java" ]] || die "Bundled JRE missing bin/java after extract."
info "  Temurin JRE $JRE_VERSION bundled."

# ── 11c. Python runtime (python-build-standalone via uv) ──────────────────────
# The venvs created on the target must use the same CPython ABI the wheels were
# built for (cp${PYTHON_VERSION//./}). The host's python3 is unreliable: Debian 12
# ships 3.11, Debian 13 ships 3.13 — neither matches the cp312 torch wheel, which
# (unlike most packages) ships no version-agnostic abi3 build. Bundle a
# relocatable interpreter so install.sh never depends on the host's Python.
step "Bundling CPython $PYTHON_VERSION (python-build-standalone)"
export UV_PYTHON_INSTALL_DIR="$DIST/python-install"   # persists across rebuilds
uv python install "$PYTHON_VERSION"
PY_ROOT=$(find "$UV_PYTHON_INSTALL_DIR" -maxdepth 1 -type d -name "cpython-${PYTHON_VERSION}*" | sort -V | tail -1)
[[ -n "$PY_ROOT" && -x "$PY_ROOT/bin/python3" ]] \
    || die "Bundled CPython not found under $UV_PYTHON_INSTALL_DIR after 'uv python install $PYTHON_VERSION'."
cp -r "$PY_ROOT/." "$STAGE/python/"
[[ -x "$STAGE/python/bin/python3" ]] || die "Bundled Python missing bin/python3 after copy."
info "  CPython $("$STAGE/python/bin/python3" --version 2>&1 | awk '{print $2}') bundled."

# ── 12. Ollama model ──────────────────────────────────────────────────────────
if [[ "$SKIP_MODEL" == true ]]; then
    warn "Skipping Ollama model (--no-model)."
else
    step "Pulling Ollama model $OLLAMA_MODEL"
    # Cache is model-aware: look for THIS model's manifest, not merely that some
    # model was pulled before. A bare "blobs/ exists" check would reuse a stale
    # stage after OLLAMA_MODEL changes and repackage the old model under the new
    # archive name ($MODEL_NAME) — e.g. a qwen3.5-9b.tar.gz holding qwen2.5:7b.
    MODEL_REPO="${OLLAMA_MODEL%:*}"; MODEL_TAG="${OLLAMA_MODEL##*:}"
    if find "$MODEL_STAGE/manifests" -type f -path "*/${MODEL_REPO}/${MODEL_TAG}" 2>/dev/null | grep -q .; then
        info "  $OLLAMA_MODEL already pulled into $MODEL_STAGE"
    else
        # Wipe any stale stage (e.g. a previously-bundled model) so the archive
        # contains only $OLLAMA_MODEL's blobs, not leftovers from an older pull.
        rm -rf "$MODEL_STAGE"
        mkdir -p "$MODEL_STAGE"
        info "  This will take several minutes — the model is ~5 GB."
        # `ollama pull` is only a client: it asks the *server* to download, and the
        # server writes into its own OLLAMA_MODELS — setting the variable on the
        # client does nothing. Spawn a throwaway server on a private port whose
        # store is MODEL_STAGE, pull through it, then shut it down.
        PULL_HOST="127.0.0.1:11501"
        OLLAMA_MODELS="$(pwd)/$MODEL_STAGE" OLLAMA_HOST="$PULL_HOST" \
            ollama serve &> "$DIST/ollama-pull.log" &
        PULL_PID=$!
        trap 'kill "$PULL_PID" 2>/dev/null || true' EXIT
        for i in {1..30}; do
            curl -sf "http://$PULL_HOST/api/version" &>/dev/null && break
            [[ $i -eq 30 ]] && die "Temporary Ollama server did not start (see $DIST/ollama-pull.log)."
            sleep 1
        done
        OLLAMA_HOST="$PULL_HOST" ollama pull "$OLLAMA_MODEL"
        kill "$PULL_PID" 2>/dev/null || true
        wait "$PULL_PID" 2>/dev/null || true
        trap - EXIT
    fi
    BLOB_COUNT=$(find "$MODEL_STAGE/blobs" -type f | wc -l)
    info "  $OLLAMA_MODEL pulled ($BLOB_COUNT blobs)."
fi

# ── 13. Package base archive ──────────────────────────────────────────────────
step "Packaging base archive"
BASE_ARCHIVE="$DIST/${BASE_NAME}-base.tar.gz"
info "  Compressing with $GZIP_PROG…"
tar --use-compress-program="$GZIP_PROG" -cf "$BASE_ARCHIVE" -C "$DIST" "$BASE_NAME/"
# Checksum from inside $DIST so the .sha256 records the bare filename, not
# "dist/…". The client receives the archive and its .sha256 side by side, so
# `sha256sum -c` must look for the file in the current dir.
if [[ "$SKIP_CHECKSUM" == true ]]; then
    warn "  Skipping checksum (--no-checksum)."
else
    (cd "$DIST" && sha256sum "${BASE_NAME}-base.tar.gz" > "${BASE_NAME}-base.tar.gz.sha256")
fi
BASE_SIZE=$(du -sh "$BASE_ARCHIVE" | cut -f1)
info "  $BASE_ARCHIVE ($BASE_SIZE)"
[[ "$SKIP_CHECKSUM" == true ]] || info "  ${BASE_ARCHIVE}.sha256"

# ── 14. Package model archive ─────────────────────────────────────────────────
if [[ "$SKIP_MODEL" != true ]]; then
    step "Packaging model archive"
    MODEL_ARCHIVE="$DIST/${MODEL_NAME}.tar.gz"
    # Tarball extracts as ollama/blobs/ and ollama/manifests/
    # install.sh does: tar -xzf model.tar.gz -C /opt/intracherche/models/
    # Result: /opt/intracherche/models/ollama/blobs/ ✓
    mkdir -p "$DIST/model-pkg"
    cp -r "$MODEL_STAGE" "$DIST/model-pkg/ollama"
    tar --use-compress-program="$GZIP_PROG" -cf "$MODEL_ARCHIVE" -C "$DIST/model-pkg" ollama/
    # Bare filename in the checksum (see base archive note above).
    if [[ "$SKIP_CHECKSUM" == true ]]; then
        warn "  Skipping checksum (--no-checksum)."
    else
        (cd "$DIST" && sha256sum "${MODEL_NAME}.tar.gz" > "${MODEL_NAME}.tar.gz.sha256")
    fi
    rm -rf "$DIST/model-pkg"
    MODEL_SIZE=$(du -sh "$MODEL_ARCHIVE" | cut -f1)
    info "  $MODEL_ARCHIVE ($MODEL_SIZE)"
    [[ "$SKIP_CHECKSUM" == true ]] || info "  ${MODEL_ARCHIVE}.sha256"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo -e "  ${GREEN}Build complete — IntraCherche $VERSION${NC}"
echo "═══════════════════════════════════════════════════════"
echo ""
if [[ "$SKIP_CHECKSUM" == true ]]; then
    warn "  Checksums skipped (--no-checksum) — re-run without the flag before shipping."
    echo ""
fi
echo "  Deliver to the client:"
echo "    $BASE_ARCHIVE  ($BASE_SIZE)"
[[ "$SKIP_CHECKSUM" == true ]] || echo "    ${BASE_ARCHIVE}.sha256"
echo ""
if [[ "$SKIP_MODEL" != true ]]; then
    echo "  Deliver only on first install or model change:"
    echo "    $MODEL_ARCHIVE  ($MODEL_SIZE)"
    [[ "$SKIP_CHECKSUM" == true ]] || echo "    ${MODEL_ARCHIVE}.sha256"
    echo ""
fi
if [[ "$SKIP_CHECKSUM" != true && -f distribution/sign-release.sh ]]; then
    echo "  Sign the archives before delivering them (asks for the key passphrase once):"
    echo "    SIGNING_KEY=${SIGNING_KEY:-/path/to/intracherche.key} ./distribution/sign-release.sh $VERSION"
    echo "  then deliver each archive with its .sha256 and .minisig."
    echo ""
fi
echo "  Client install:"
if [[ "$SKIP_CHECKSUM" != true ]]; then
    echo "    sha256sum -c ${BASE_NAME}-base.tar.gz.sha256"
    [[ "$SKIP_MODEL" == true ]] || echo "    sha256sum -c ${MODEL_NAME}.tar.gz.sha256"
fi
echo "    tar -xzf ${BASE_NAME}-base.tar.gz"
echo "    sudo ./${BASE_NAME}/install.sh"
echo ""
