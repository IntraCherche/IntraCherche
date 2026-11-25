#!/bin/bash
# IntraCherche installer — Debian 12+, x86-64, run as root.
set -euo pipefail

# ── Paths ────────────────────────────────────────────────────────────────────
INSTALL_DIR="/opt/intracherche"
CONFIG_DIR="/etc/intracherche"
DATA_DIR="/var/lib/intracherche"
LOG_DIR="/var/log/intracherche"
SOLR_DIR="$INSTALL_DIR/solr"
BUNDLE_DIR="$(cd "$(dirname "$0")" && pwd)"
OLLAMA_MODEL="qwen3.5:9b"
# Archive name follows the model id (qwen3.5:9b -> qwen3.5-9b); must match build-distrib.sh.
MODEL_BASENAME="intracherche-ollama-$(echo "$OLLAMA_MODEL" | tr ':' '-')"
MODEL_TARBALL="$(dirname "$BUNDLE_DIR")/${MODEL_BASENAME}.tar.gz"
MODEL_SUM="${MODEL_TARBALL}.sha256"
IC_USER="intracherche"

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; NC='\033[0m'
info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()     { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }
confirm() { read -rp "$1 [y/N] " ans; [[ "${ans,,}" == "y" ]]; }

# HTTP GET on localhost through bash's /dev/tcp: curl is not part of a minimal
# Debian install. Prints the raw response; fails if nothing listens on the port.
http_get() {
    timeout 5 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/$0" || exit 1
        printf "GET %s HTTP/1.0\r\nHost: localhost\r\n\r\n" "$1" >&3
        cat <&3' "$1" "$2" 2>/dev/null
}
http_ok() { [[ "$(http_get "$1" "$2" | head -1)" == *" 200 "* ]]; }

# ── 1. Pre-flight checks ─────────────────────────────────────────────────────
[[ $EUID -eq 0 ]] || die "Run this script as root (sudo ./install.sh)."
[[ "$(uname -m)" == "x86_64" ]] || die "Only x86-64 is supported."
[[ -f /etc/debian_version ]] || warn "This system does not appear to be Debian — proceeding anyway."
[[ -f "$BUNDLE_DIR/app/intracherche.jar" ]] || die "intracherche.jar not found in $BUNDLE_DIR/app/. Extract the base archive first."

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  IntraCherche — Installation"
echo "═══════════════════════════════════════════════════════"
echo ""

# ── 1b. Stop a running instance ──────────────────────────────────────────────
# Every file below is copied into place over the previous one, and a binary that a
# running process holds cannot be overwritten — the kernel answers ETXTBSY ("Text file
# busy") and, under `set -e`, the install stops half-applied. The documented upgrade is
# "extract and run install.sh" on a live server, so stopping is this script's job, not
# the admin's. systemd takes the Python services down with the app: they are its
# children, and uv, the JRE and the service code are exactly the files being replaced.
# The unit is written again below and started again at the end, as on a first install.
if systemctl is-active --quiet intracherche 2>/dev/null; then
    info "Stopping the running IntraCherche before replacing its files."
    systemctl stop intracherche
    # systemd returns as soon as the main process is gone; a Python service caught mid
    # start-up can outlive it by a moment, still holding uv open. Give them that moment.
    for _ in $(seq 1 10); do
        pgrep -u "$IC_USER" -f "$INSTALL_DIR/(uv|python)" >/dev/null 2>&1 || break
        sleep 1
    done
fi

# ── 2. Service user ──────────────────────────────────────────────────────────
if id "$IC_USER" &>/dev/null; then
    info "User '$IC_USER' already exists — will run IntraCherche as that user."
else
    info "Creating system user '$IC_USER'."
    useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin "$IC_USER"
fi
# The service needs a writable home: `uv run` keeps its cache in ~/.cache/uv and
# refuses to start the Python services without it, and the benchmark writes to
# ~/.IntraCherche. Earlier installers created the user with a home that was never
# created, so check the home recorded in /etc/passwd rather than assuming one.
IC_HOME="$(getent passwd "$IC_USER" | cut -d: -f6)"
case "$IC_HOME" in
    ""|/|/nonexistent)
        die "User '$IC_USER' has no usable home ('$IC_HOME'). Fix it with: usermod -d $DATA_DIR $IC_USER" ;;
esac
if [[ ! -d "$IC_HOME" ]]; then
    info "Creating home directory $IC_HOME for '$IC_USER'."
    install -d -o "$IC_USER" -g "$IC_USER" -m 750 "$IC_HOME"
elif ! runuser -u "$IC_USER" -- test -w "$IC_HOME"; then
    die "Home directory $IC_HOME is not writable by '$IC_USER'. Fix it with: chown $IC_USER:$IC_USER $IC_HOME"
fi

# ── 3. Directory layout ──────────────────────────────────────────────────────
info "Creating directory structure."
mkdir -p "$INSTALL_DIR"/{app,python-services,config/solr,models/{embedding,reranker,ollama},wheels}
mkdir -p "$CONFIG_DIR" "$DATA_DIR" "$LOG_DIR"
chown "$IC_USER:$IC_USER" "$DATA_DIR" "$LOG_DIR"

# ── 4. Java 21 ───────────────────────────────────────────────────────────────
JAVA_BIN=""
if [[ -d "$BUNDLE_DIR/jre" ]]; then
    info "Using bundled JRE."
    # Replace wholesale: copying over an existing jre/ would keep the old
    # version's files on an upgrade (or mix two versions).
    rm -rf "$INSTALL_DIR/jre.old"
    [[ -d "$INSTALL_DIR/jre" ]] && mv "$INSTALL_DIR/jre" "$INSTALL_DIR/jre.old"
    cp -r "$BUNDLE_DIR/jre" "$INSTALL_DIR/jre"
    JAVA_BIN="$INSTALL_DIR/jre/bin/java"
    # Carry over certificates the admin imported into the old truststore
    # (e.g. the enterprise root CA for LDAPS, INSTALL.md §2d). JDK-shipped CAs
    # all carry a " [jdk]" alias suffix and are skipped, so CAs a newer JRE
    # dropped (distrusted/expired) are not resurrected.
    OLD_CACERTS="$INSTALL_DIR/jre.old/lib/security/cacerts"
    NEW_CACERTS="$INSTALL_DIR/jre/lib/security/cacerts"
    if [[ -f "$OLD_CACERTS" ]]; then
        KEYTOOL="$INSTALL_DIR/jre/bin/keytool"
        list_aliases() {
            "$KEYTOOL" -J-Duser.language=en -list -v -keystore "$1" -storepass changeit 2>/dev/null \
                | sed -n 's/^Alias name: //p' | grep -v ' \[jdk\]$' | sort || true
        }
        while IFS= read -r alias; do
            [[ -n "$alias" ]] || continue
            "$KEYTOOL" -importkeystore -noprompt \
                -srckeystore "$OLD_CACERTS" -srcstorepass changeit -srcalias "$alias" \
                -destkeystore "$NEW_CACERTS" -deststorepass changeit >/dev/null 2>&1 \
                || die "Could not carry certificate '$alias' over to the new JRE truststore."
            info "  Kept imported certificate '$alias' in the JRE truststore."
        done < <(comm -23 <(list_aliases "$OLD_CACERTS") <(list_aliases "$NEW_CACERTS"))
    fi
    rm -rf "$INSTALL_DIR/jre.old"
else
    JAVA_BIN="$(command -v java 2>/dev/null || true)"
    if [[ -z "$JAVA_BIN" ]]; then
        info "Java not found — installing openjdk-21-jre-headless via apt."
        apt-get install -y openjdk-21-jre-headless
        JAVA_BIN="$(command -v java)"
    fi
    JAVA_VER=$("$JAVA_BIN" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
    [[ "$JAVA_VER" -ge 21 ]] || die "Java 21+ required (found version $JAVA_VER). Install openjdk-21-jre-headless."
fi
info "Java: $JAVA_BIN"

# ── 4b. Python runtime ─────────────────────────────────────────────────────────
# The Python venvs must use the same CPython ABI the bundled wheels were built
# for (cp312). The host's python3 is unreliable: Debian 12 ships 3.11, Debian 13
# ships 3.13 — neither matches the cp312 torch wheel. Prefer the bundled
# relocatable interpreter; fall back to host python3 only if none was bundled.
PY_BIN=""
if [[ -d "$BUNDLE_DIR/python" ]]; then
    info "Using bundled Python."
    # Replace wholesale, like the JRE: `cp -n` would keep the old interpreter's
    # files on an upgrade. The venvs are rebuilt against it below.
    rm -rf "$INSTALL_DIR/python"
    cp -r "$BUNDLE_DIR/python" "$INSTALL_DIR/python"
    PY_BIN="$INSTALL_DIR/python/bin/python3"
else
    PY_BIN="$(command -v python3 2>/dev/null || true)"
    [[ -n "$PY_BIN" ]] || die "No bundled Python in $BUNDLE_DIR/python/ and no python3 on host."
    warn "No bundled Python — falling back to host $("$PY_BIN" --version 2>&1)."
    warn "Offline wheel install will fail if its version differs from the bundled wheels (expects CPython 3.12)."
fi
info "Python: $PY_BIN ($("$PY_BIN" --version 2>&1))"

# ── 5. Application JAR ───────────────────────────────────────────────────────
info "Installing application JAR."
cp "$BUNDLE_DIR/app/intracherche.jar" "$INSTALL_DIR/app/"
# Kept installed: the admin runs it again to renew the certificate or add a server name.
install -m 750 "$BUNDLE_DIR/generate-certificate.sh" "$INSTALL_DIR/generate-certificate.sh"

# ── 6. Solr configset files ──────────────────────────────────────────────────
info "Installing Solr configset."
cp -r "$BUNDLE_DIR/config/solr/." "$INSTALL_DIR/config/solr/"

# ── 7. Python services ───────────────────────────────────────────────────────
info "Installing Python services."
# Replace each service's code and wheels wholesale, like the JRE: copying over
# the previous release would keep files it no longer ships — the OCR models it
# replaced, the wheels of packages it dropped. The venvs are rebuilt below, so
# nothing under python-services/ needs preserving.
for SERVICE_SRC in "$BUNDLE_DIR"/python-services/*/; do
    SERVICE=$(basename "$SERVICE_SRC")
    rm -rf "$INSTALL_DIR/python-services/$SERVICE"
    cp -r "$SERVICE_SRC" "$INSTALL_DIR/python-services/$SERVICE"
done
for WHEELS_SRC in "$BUNDLE_DIR"/wheels/*/; do
    SERVICE=$(basename "$WHEELS_SRC")
    rm -rf "$INSTALL_DIR/wheels/$SERVICE"
    cp -r "$WHEELS_SRC" "$INSTALL_DIR/wheels/$SERVICE"
done

UV_BIN="$INSTALL_DIR/uv/bin/uv"
if [[ -f "$BUNDLE_DIR/uv/bin/uv" ]]; then
    info "Installing bundled uv."
    mkdir -p "$INSTALL_DIR/uv/bin"
    cp "$BUNDLE_DIR/uv/bin/uv" "$UV_BIN"
    chmod +x "$UV_BIN"
else
    UV_BIN="$(command -v uv 2>/dev/null || true)"
    [[ -n "$UV_BIN" ]] || die "uv not found and no bundled uv included. Cannot install Python services."
fi
info "uv: $UV_BIN"

# Build the venvs with the bundled interpreter. UV_PYTHON_DOWNLOADS=never keeps
# uv from trying to fetch a Python from the network on an air-gapped host.
export UV_PYTHON_DOWNLOADS=never
for SERVICE in embedding-service reranker-service ner-service ocr-service; do
    SVC_DIR="$INSTALL_DIR/python-services/$SERVICE"
    WHEEL_DIR="$INSTALL_DIR/wheels/$SERVICE"
    info "Setting up $SERVICE."
    if [[ -d "$WHEEL_DIR" && -f "$WHEEL_DIR/requirements.txt" ]]; then
        # Offline install. `uv pip install --no-index --find-links` resolves and
        # fetches strictly from the bundled wheel dir — unlike `uv sync`, which
        # fetches from the registry URLs recorded in uv.lock and ignores
        # --find-links (so it always needs the network). --offline is belt-and-
        # suspenders: it forbids any network access outright. --no-deps installs
        # exactly the locked set in requirements.txt: re-resolving would ignore
        # uv's override-dependencies (ocr-service swaps opencv-python, which needs
        # libGL, for opencv-python-headless). The venv is rebuilt from scratch so
        # an upgrade cannot leave a package the new lock dropped beside its
        # replacement (both opencv builds install the same cv2 module).
        rm -rf "$SVC_DIR/.venv"
        "$UV_BIN" venv --python "$PY_BIN" "$SVC_DIR/.venv"
        "$UV_BIN" pip install --python "$SVC_DIR/.venv" \
            --offline --no-index --no-deps --find-links "$WHEEL_DIR" \
            --requirement "$WHEEL_DIR/requirements.txt"
    else
        # No bundled wheels — resolve from the network (development / online use).
        "$UV_BIN" sync --project "$SVC_DIR" --python "$PY_BIN"
    fi
done

# ── 8. AI models ─────────────────────────────────────────────────────────────
# Replace wholesale, like the JRE: `cp -n` would keep an older model's files
# when a release ships an updated model under the same file names. The services
# only read these (offline), so nothing in them needs preserving.
for MODEL in embedding reranker; do
    if [[ -d "$BUNDLE_DIR/models/$MODEL" ]]; then
        info "Installing $MODEL model."
        rm -rf "$INSTALL_DIR/models/$MODEL"
        cp -r "$BUNDLE_DIR/models/$MODEL" "$INSTALL_DIR/models/$MODEL"
    fi
done

# ── 9. Apache Solr ───────────────────────────────────────────────────────────
SOLR_TARBALL=$(find "$BUNDLE_DIR/solr" -name "solr-*.tgz" 2>/dev/null | head -1 || true)
if [[ -d "$SOLR_DIR/bin" ]]; then
    info "Solr already installed at $SOLR_DIR — skipping."
elif [[ -n "$SOLR_TARBALL" ]]; then
    info "Extracting Solr."
    SOLR_TMP=$(mktemp -d)
    tar -xzf "$SOLR_TARBALL" -C "$SOLR_TMP"
    mv "$SOLR_TMP"/solr-*/ "$SOLR_DIR"
    rm -rf "$SOLR_TMP"
    chown -R "$IC_USER:$IC_USER" "$SOLR_DIR"
    info "Solr installed at $SOLR_DIR."
else
    die "Solr tarball not found in $BUNDLE_DIR/solr/ and Solr is not already installed."
fi

# ── 10. Ollama ───────────────────────────────────────────────────────────────
OLLAMA_URL="http://localhost:11434"
EXISTING_OLLAMA=false
INSTALL_BUNDLED_OLLAMA=false
CREATE_OLLAMA_UNIT=false
OLLAMA_BIN="/usr/local/bin/ollama"

# Sherlock switched off by the admin in an existing config (llm.enabled: false): an update must
# not bring Ollama back. It would be installed, enabled at boot and started again — several GB of
# RAM for a model nobody asks anything — undoing the "systemctl disable --now intracherche-ollama"
# the guide recommends. An Ollama the admin left running or enabled is left as it is.
sherlock_disabled() {
    [[ -f "$CONFIG_DIR/application.yaml" ]] || return 1
    awk '/^llm:/ {in_llm=1; next}
         /^[^[:space:]#]/ {in_llm=0}
         in_llm && /^[[:space:]]+enabled:[[:space:]]*false([[:space:]]|#|$)/ {found=1}
         END {exit !found}' "$CONFIG_DIR/application.yaml"
}
SKIP_OLLAMA=false

if sherlock_disabled; then
    info "Sherlock is switched off (llm.enabled: false): Ollama is neither installed, enabled nor started."
    SKIP_OLLAMA=true
elif http_ok 11434 /api/tags; then
    info "Existing Ollama detected at $OLLAMA_URL — will use it."
    EXISTING_OLLAMA=true
else
    INSTALL_BUNDLED_OLLAMA=true
    CREATE_OLLAMA_UNIT=true
fi

if [[ "$INSTALL_BUNDLED_OLLAMA" == true ]]; then
    info "Installing bundled CPU Ollama."
    [[ -f "$BUNDLE_DIR/ollama/bin/ollama" ]] || die "Ollama binary not found in $BUNDLE_DIR/ollama/bin/."
    cp "$BUNDLE_DIR/ollama/bin/ollama" /usr/local/bin/ollama
    chmod +x /usr/local/bin/ollama
    # The binary loads its ggml runtime libs from ../lib/ollama relative to itself
    rm -rf /usr/local/lib/ollama
    cp -r "$BUNDLE_DIR/ollama/lib/ollama" /usr/local/lib/ollama
    useradd --system --home-dir /usr/share/ollama --shell /bin/false ollama 2>/dev/null || true
    # useradd does not create the home dir; on startup `ollama serve` writes its
    # signing key to ~/.ollama and crash-loops if it can't mkdir its home.
    install -d -o ollama -g ollama /usr/share/ollama
fi

# An existing Ollama must survive reboots, otherwise Sherlock silently stays
# offline after the next restart. Detect manual `ollama serve` setups and offer
# to put them under systemd.
if [[ "$EXISTING_OLLAMA" == true ]]; then
    if systemctl is-enabled --quiet ollama 2>/dev/null \
       || systemctl is-enabled --quiet intracherche-ollama 2>/dev/null; then
        info "Existing Ollama is enabled at boot via systemd."
    else
        warn "The running Ollama is NOT enabled at boot (no enabled 'ollama' or 'intracherche-ollama' systemd unit)."
        warn "After a reboot, Sherlock would stay offline until someone starts Ollama by hand."
        read -rp "Create and enable a systemd unit for it? [Y/n] " OLLAMA_UNIT_ANSWER
        if [[ ! "$OLLAMA_UNIT_ANSWER" =~ ^[Nn] ]]; then
            CREATE_OLLAMA_UNIT=true
            OLLAMA_BIN="$(command -v ollama || echo /usr/local/bin/ollama)"
            useradd --system --home-dir /usr/share/ollama --shell /bin/false ollama 2>/dev/null || true
        else
            warn "Skipping. Make sure Ollama is started at boot by other means, or Sherlock will be unavailable."
        fi
    fi
fi

# Where the Ollama answering on localhost:11434 keeps its models: its OLLAMA_MODELS, or
# ~/.ollama/models of the user it runs as (Ollama's default). Sets OLLAMA_STORE and
# OLLAMA_STORE_OWNER (globals: a $(...) subshell would lose the second).
# A unit this installer is about to create for a hand-started Ollama runs as 'ollama' with
# no OLLAMA_MODELS, so from the next boot on the model has to be where that unit looks.
existing_ollama_models_dir() {
    local pid store="" user=""
    if [[ "$CREATE_OLLAMA_UNIT" != true ]]; then
        # The listener localhost reaches (several may share the port, one per address).
        pid="$(ss -Hltnp 'sport = :11434' 2>/dev/null \
            | awk '$4 ~ /^(127\.0\.0\.1|0\.0\.0\.0|\*|\[::\]|\[::1\]):/' \
            | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1)"
    fi
    if [[ -n "$pid" ]]; then
        store="$(tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null | sed -n 's/^OLLAMA_MODELS=//p' | head -1)"
        user="$(ps -o user= -p "$pid" 2>/dev/null | xargs)"
    fi
    OLLAMA_STORE_OWNER="${user:-ollama}"
    if [[ -z "$store" ]]; then
        store="$(getent passwd "$OLLAMA_STORE_OWNER" | cut -d: -f6)"
        store="${store:-/usr/share/ollama}/.ollama/models"
    fi
    OLLAMA_STORE="$store"
}

# Install/import the Qwen model
install_ollama_model() {
    [[ "$SKIP_OLLAMA" == true ]] && return
    local blobs_src="$INSTALL_DIR/models/ollama"

    # Unpack model tarball if not already done
    if [[ ! -d "$blobs_src/blobs" ]]; then
        if [[ -f "$MODEL_TARBALL" ]]; then
            if [[ -f "$MODEL_SUM" ]]; then
                info "Verifying model archive checksum."
                # The .sha256 records the bare file name: check from the archive's folder.
                (cd "$(dirname "$MODEL_TARBALL")" && sha256sum --quiet -c "$(basename "$MODEL_SUM")") \
                    || die "Model archive checksum mismatch — the archive is corrupt or incomplete. Copy it again."
            else
                warn "No checksum file found — skipping verification."
            fi
            info "Extracting Ollama model (this may take a moment)."
            tar -xzf "$MODEL_TARBALL" -C "$INSTALL_DIR/models/"
        else
            warn "Ollama model tarball not found at $(dirname "$MODEL_TARBALL")/"
            warn "Place ${MODEL_BASENAME}.tar.gz there and re-run, or pull $OLLAMA_MODEL manually."
            return
        fi
    else
        info "Ollama model already extracted."
    fi

    if [[ "$EXISTING_OLLAMA" == true ]]; then
        # Check if model is already pulled in the existing instance
        if [[ "$(http_get 11434 /api/tags)" == *"\"$OLLAMA_MODEL\""* ]]; then
            info "$OLLAMA_MODEL already available in existing Ollama."
            return
        fi
        # Into the models directory that Ollama actually reads. Earlier installers copied to
        # ~ollama/.ollama/{blobs,manifests}, one level above it, where Ollama never looks.
        existing_ollama_models_dir
        local store="$OLLAMA_STORE"
        info "Importing $OLLAMA_MODEL into the existing Ollama ($store)."
        mkdir -p "$store/blobs" "$store/manifests"
        cp -rn "$blobs_src/blobs/." "$store/blobs/"
        cp -rn "$blobs_src/manifests/." "$store/manifests/"
        chown -R "$OLLAMA_STORE_OWNER:" "$store/blobs" "$store/manifests" 2>/dev/null \
            || warn "Could not give $store to '$OLLAMA_STORE_OWNER': check that Ollama can read it."
        if [[ "$CREATE_OLLAMA_UNIT" == true ]]; then
            info "$OLLAMA_MODEL imported; the Ollama unit created below reads it from the next start."
        elif [[ "$(http_get 11434 /api/tags)" == *"\"$OLLAMA_MODEL\""* ]]; then
            info "$OLLAMA_MODEL imported into existing Ollama."
        else
            warn "Ollama does not list $OLLAMA_MODEL after the import into $store."
            warn "Check where that Ollama keeps its models (OLLAMA_MODELS), or run: ollama pull $OLLAMA_MODEL"
        fi
    fi
    # For the bundled Ollama, OLLAMA_MODELS points to $INSTALL_DIR/models/ollama (set in systemd unit).
}

install_ollama_model

# ── 11. Site configuration (/etc/intracherche/application.yaml) ───────────────
# Generated once, with a freshly-generated JWT secret and safe defaults. The
# admin fills in the CHANGE_ME placeholders afterwards (LDAP is required, SMTP
# optional) and then starts the service. A re-install never overwrites it.
if [[ "$EXISTING_OLLAMA" == true ]]; then
    CONFIGURED_OLLAMA_URL="$OLLAMA_URL"
else
    CONFIGURED_OLLAMA_URL="http://localhost:11434"
fi

if [[ -f "$CONFIG_DIR/application.yaml" ]]; then
    info "Existing $CONFIG_DIR/application.yaml found — leaving it untouched."
    CONFIG_GENERATED=false
    if ! grep -qE '^[[:space:]]+key-store:' "$CONFIG_DIR/application.yaml"; then
        warn "This server is served in plain HTTP: passwords travel in clear on the network."
        warn "To switch to HTTPS: INSTALL.md, \"Accès sécurisé (HTTPS)\"."
    fi
else
    info "Generating $CONFIG_DIR/application.yaml with a fresh JWT secret."
    # Kernel CSPRNG + coreutils: openssl is not part of a minimal Debian install.
    JWT_SECRET=$(head -c 64 /dev/urandom | base64 -w0)
    CONFIG_GENERATED=true
    # HTTPS from the first start, with a self-signed certificate for this server's names and
    # addresses: browsers warn until it is deployed on the workstations (INSTALL.md), but
    # passwords never travel in clear. Port 8080 stays open and only redirects to 8443.
    info "Generating a self-signed HTTPS certificate for this server."
    CERT_OUTPUT=$(KEYTOOL="$(dirname "$JAVA_BIN")/keytool" "$INSTALL_DIR/generate-certificate.sh") \
        || die "Could not generate the HTTPS certificate."
    echo "$CERT_OUTPUT" | grep -v '^KEYSTORE_PASSWORD=' | while read -r line; do info "  $line"; done
    KEYSTORE_PASSWORD=$(echo "$CERT_OUTPUT" | sed -n 's/^KEYSTORE_PASSWORD=//p')
    cat > "$CONFIG_DIR/application.yaml" <<YAML
# IntraCherche — site configuration
# Loaded on top of the bundled defaults. Restart after any change:
#   systemctl restart intracherche
#
# ─── SET THESE UP FIRST: replace every CHANGE_ME before the first start ───
# Without them nobody can log in. Once they are right, everything else is set
# from the administration panel (Administration > Configuration).

# Active Directory, e.g. ldaps://dc.corp.local:636
spring.ldap.urls: "ldaps://CHANGE_ME:636"
# e.g. dc=corp,dc=local
spring.ldap.base: "CHANGE_ME"
# Bind account, e.g. ldap-reader@corp.local
spring.ldap.username: "CHANGE_ME"
spring.ldap.password: "CHANGE_ME"
# Where the users are looked up, e.g. CN=Users
spring.security.ldap.user.search-base: "CHANGE_ME"
spring.security.ldap.user.search-filter: "(sAMAccountName={0})"
# SID of the AD group allowed into the administration panel:
#   samba-tool group show "<group>" | grep objectSid   (or: Get-ADGroup <group> in PowerShell)
admin.adminGroupSid: "CHANGE_ME"

# ─── Everything below is generated with sensible values; change it later if needed ───

jwt:
  # Auto-generated 64-byte secret. Changing it invalidates all active sessions.
  secret: "${JWT_SECRET}"

registry-config:
  dbPath: ${DATA_DIR}/registry.db

indexing-config:
  indexesConfigPath: ${CONFIG_DIR}/indexes.yaml

solr-config:
  solrBaseUrl: "http://localhost:8983/solr"
  configSetSourcePath: "config/solr/configset"

alerts:
  email:
    # Public URL of this server, used in alert email links.
    base-url: "https://$(hostname -f):8443"

app:
  cookie:
    # true = cookies marked Secure, as HTTPS requires. Set to false only if you turn HTTPS off.
    secure: true

# HTTPS with the self-signed certificate generated for this server. Renew it, or add a name
# users type to reach the server: /opt/intracherche/generate-certificate.sh --name <alias>
# Port 8080 (app.https.redirect-from-port) only redirects to HTTPS. INSTALL.md, "Accès sécurisé".
server:
  port: 8443
  ssl:
    enabled: true
    key-store: ${CONFIG_DIR}/intracherche.p12
    key-store-type: PKCS12
    key-store-password: "${KEYSTORE_PASSWORD}"
    key-alias: intracherche

# ─── OPTIONAL: SMTP for alert emails. Uncomment and fill in to enable. ───
# spring:
#   mail:
#     host: smtp.corp.local
#     port: 587
#     username: "alerts@corp.local"
#     password: "CHANGE_ME"

llm:
  ollamaUrl: "${CONFIGURED_OLLAMA_URL}"
  model: "${OLLAMA_MODEL}"
YAML
    chmod 640 "$CONFIG_DIR/application.yaml"
    chown root:"$IC_USER" "$CONFIG_DIR/application.yaml"
fi

# Every setting with its default and what it does, rewritten on each install: the site's
# application.yaml is never touched on an upgrade, so this is where new settings show up.
# The previous one is kept, so that a diff of the two lists what the upgrade added.
if [[ -f "$CONFIG_DIR/application.yaml.reference" ]]; then
    mv -f "$CONFIG_DIR/application.yaml.reference" "$CONFIG_DIR/application.yaml.reference.previous"
fi
{
    echo "# $(basename "$BUNDLE_DIR") — every setting, with its default and what it does."
    echo "# FOR REFERENCE ONLY: this file is not read. Copy the lines you want to change into"
    echo "# $CONFIG_DIR/application.yaml, then: systemctl restart intracherche"
    echo "# A setting absent from application.yaml keeps the default shown here."
    echo "# What this version added: diff application.yaml.reference.previous application.yaml.reference"
    echo "#"
    cat "$BUNDLE_DIR/config/application.yaml.reference"
} > "$CONFIG_DIR/application.yaml.reference"
chmod 644 "$CONFIG_DIR/application.yaml.reference"

# ── 13. indexes.yaml template ─────────────────────────────────────────────────
if [[ ! -f "$CONFIG_DIR/indexes.yaml" ]]; then
    info "Installing indexes.yaml template."
    cp "$BUNDLE_DIR/config/indexes.yaml.template" "$CONFIG_DIR/indexes.yaml"
    chown root:"$IC_USER" "$CONFIG_DIR/indexes.yaml"
    chmod 640 "$CONFIG_DIR/indexes.yaml"
    warn "Edit $CONFIG_DIR/indexes.yaml to define your document sources before starting IntraCherche."
else
    # visibleToGroups became required in 3.3.0: without it the application refuses to start.
    # An index that predates it was visible to every user, so write that down explicitly,
    # keeping the previous file, rather than leave the upgrade with a service that won't start.
    MISSING_VISIBILITY=$(awk '
        function flush() { if (core != "" && !seen) print core }
        /^[[:space:]]*-[[:space:]]*coreName:/ { flush(); core = $0; sub(/.*coreName:[[:space:]]*/, "", core); seen = 0; next }
        /^[[:space:]]*visibleToGroups:/ { seen = 1 }
        END { flush() }' "$CONFIG_DIR/indexes.yaml")
    if [[ -n "$MISSING_VISIBILITY" ]]; then
        cp -p "$CONFIG_DIR/indexes.yaml" "$CONFIG_DIR/indexes.yaml.previous"
        awk '
            function flush(   i) {
                for (i = 1; i <= n; i++) {
                    print buf[i]
                    if (i == 1 && entry && !seen) print indent "visibleToGroups: ALL"
                }
                n = 0
            }
            /^[[:space:]]*-[[:space:]]*coreName:/ {
                flush(); entry = 1; seen = 0
                match($0, /^[[:space:]]*/); indent = substr($0, 1, RLENGTH) "  "
            }
            /^[[:space:]]*visibleToGroups:/ { seen = 1 }
            { buf[++n] = $0 }
            END { flush() }' "$CONFIG_DIR/indexes.yaml.previous" > "$CONFIG_DIR/indexes.yaml"
        warn "indexes.yaml: visibleToGroups is now required. Set to ALL (every user, as before) for: $(echo $MISSING_VISIBILITY)"
        warn "To restrict an index to some AD groups, see INSTALL.md (previous file: $CONFIG_DIR/indexes.yaml.previous)."
    fi
fi

# ── 13b. Write access for the administration panel ──────────────────────────
# The panel (Administration > Configuration) rewrites application.yaml, indexes.yaml and jvm.conf, and
# keeps a backup of each. It replaces a file by renaming a new one over it, so the service user needs
# write access to the DIRECTORY, not to the files: they stay root:intracherche 640 (they hold passwords),
# and the panel gives every file it writes the permissions of the one it replaces. The service user
# already owns the application itself ($INSTALL_DIR), so this widens nothing that matters.
chown root:"$IC_USER" "$CONFIG_DIR"
chmod 770 "$CONFIG_DIR"
for f in application.yaml indexes.yaml jvm.conf; do
    if [[ -f "$CONFIG_DIR/$f" ]]; then
        chown root:"$IC_USER" "$CONFIG_DIR/$f"
        chmod 640 "$CONFIG_DIR/$f"
    fi
done

# ── 14. Ownership ─────────────────────────────────────────────────────────────
chown -R "$IC_USER:$IC_USER" "$INSTALL_DIR"
# The bundled Ollama unit runs as the 'ollama' user, but the chown above just gave
# its model store to $IC_USER. Hand the model dir back or `ollama serve` can't read it.
[[ "$INSTALL_BUNDLED_OLLAMA" == true ]] && chown -R ollama:ollama "$INSTALL_DIR/models/ollama"

# ── 15. systemd units ─────────────────────────────────────────────────────────
info "Installing systemd units."

# Ollama (bundled install, or an existing instance the admin chose to put under systemd).
# The bundled binary serves models from the install dir; an adopted existing Ollama
# uses its default location (~ollama/.ollama, where install_ollama_model imports blobs).
if [[ "$CREATE_OLLAMA_UNIT" == true ]]; then
cat > /etc/systemd/system/intracherche-ollama.service <<UNIT
[Unit]
Description=Ollama (IntraCherche)
After=network.target
Before=intracherche.service

[Service]
Type=simple
User=ollama
Group=ollama
$(if [[ "$INSTALL_BUNDLED_OLLAMA" == true ]]; then echo "Environment=OLLAMA_MODELS=${INSTALL_DIR}/models/ollama"; fi)
Environment=OLLAMA_HOST=127.0.0.1:11434
ExecStart=${OLLAMA_BIN} serve
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
if [[ "$EXISTING_OLLAMA" == true ]]; then
    # The unit runs as the 'ollama' system user — it must own its model store
    chown -R ollama:ollama /usr/share/ollama 2>/dev/null || true
fi
fi

# Heap for the application. Java renders every scanned page to an image before
# sending it for OCR — a 300-DPI A4 page is ~35 MB of BufferedImage, and several
# render at once — so the heap has to follow the machine, not a constant. The
# fixed 1 GB this replaces died with "Java heap space" on scanned PDFs, on a
# 16 GB server. One eighth of RAM, never below 1 GB, never above 8 GB: past that
# the page cache and the Python model services need the memory more than G1 does.
# An unreadable /proc/meminfo falls back to the floor rather than to an empty
# arithmetic expression, which under `set -e` would end the install here.
TOTAL_RAM_MB=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo 2>/dev/null || true)
[[ "$TOTAL_RAM_MB" =~ ^[0-9]+$ ]] || TOTAL_RAM_MB=0
APP_HEAP_MB=$(( TOTAL_RAM_MB / 8 ))
if (( APP_HEAP_MB < 1024 )); then APP_HEAP_MB=1024; fi
if (( APP_HEAP_MB > 8192 )); then APP_HEAP_MB=8192; fi
info "Application heap: ${APP_HEAP_MB} MB (server has ${TOTAL_RAM_MB} MB of RAM)."
if [[ -f "$CONFIG_DIR/jvm.conf" ]]; then
    warn "$CONFIG_DIR/jvm.conf sets the heap and overrides that value — it is left untouched."
fi

# Heap for Solr. Left at its bundled 512 MB, Solr's heap filled during indexing of long
# documents (Full GC at the ceiling, update requests timing out). One sixteenth of RAM,
# never below 1 GB, never above 4 GB: Solr keeps its index in the page cache, not in its heap.
# The application reads the same value to size the Python services, so it is written where
# Solr reads it. A SOLR_HEAP or SOLR_JAVA_MEM an admin set by hand is left alone.
SOLR_IN_SH="$SOLR_DIR/bin/solr.in.sh"
SOLR_HEAP_SUMMARY=""
SOLR_HEAP_MB=$(( TOTAL_RAM_MB / 16 ))
if (( SOLR_HEAP_MB < 1024 )); then SOLR_HEAP_MB=1024; fi
if (( SOLR_HEAP_MB > 4096 )); then SOLR_HEAP_MB=4096; fi
if [[ ! -f "$SOLR_IN_SH" ]]; then
    warn "$SOLR_IN_SH not found — Solr keeps its default 512 MB heap."
else
    # Whatever stands outside our own block is the admin's.
    if sed '/^# BEGIN IntraCherche heap/,/^# END IntraCherche heap/d' "$SOLR_IN_SH" \
            | grep -qE '^[[:space:]]*(SOLR_HEAP|SOLR_JAVA_MEM)='; then
        warn "$SOLR_IN_SH already sets SOLR_HEAP or SOLR_JAVA_MEM — Solr's heap is left untouched."
    else
        sed -i '/^# BEGIN IntraCherche heap/,/^# END IntraCherche heap/d' "$SOLR_IN_SH"
        {
            echo "# BEGIN IntraCherche heap (written by install.sh; delete this block and set your own SOLR_HEAP to override)"
            echo "SOLR_HEAP=\"${SOLR_HEAP_MB}m\""
            echo "# END IntraCherche heap"
        } >> "$SOLR_IN_SH"
        info "Solr heap: ${SOLR_HEAP_MB} MB."
        SOLR_HEAP_SUMMARY="  Solr heap:     ${SOLR_HEAP_MB} MB  (set in ${SOLR_IN_SH})"
    fi
fi

# Sherlock's model is held in RAM next to the application: the application reserves it at start-up
# (ResourceValidator) and refuses to start when what is left cannot hold one worker of each Python
# service. Said here, where the server is being sized, rather than only in a crash loop afterwards.
# The figures follow ResourceValidator: ~7.8 GB for the 9B model, ~3.8 GB for one worker per
# service, 1 GB for the OS. An Ollama on another host, or Sherlock switched off, needs none of it.
SHERLOCK_RAM_WARNING=""
if [[ "$SKIP_OLLAMA" != true && ( "$INSTALL_BUNDLED_OLLAMA" == true || "$EXISTING_OLLAMA" == true ) ]]; then
    SHERLOCK_NEEDS_MB=$(( APP_HEAP_MB + SOLR_HEAP_MB + 1024 + 7936 + 3800 ))
    if (( TOTAL_RAM_MB > 0 && TOTAL_RAM_MB < SHERLOCK_NEEDS_MB )); then
        SHERLOCK_RAM_WARNING="With Sherlock on, this server needs about $(( (SHERLOCK_NEEDS_MB + 1023) / 1024 )) GB of RAM and has $(( TOTAL_RAM_MB / 1024 )) GB: IntraCherche will refuse to start (\"Insufficient RAM\" in app.log). Add RAM, move Ollama to another host (llm.ollamaUrl), or set llm.enabled: false in ${CONFIG_DIR}/application.yaml and re-run this installer."
        warn "$SHERLOCK_RAM_WARNING"
    fi
fi

# Main application
cat > /etc/systemd/system/intracherche.service <<UNIT
[Unit]
Description=IntraCherche
After=network.target
$(if [[ "$CREATE_OLLAMA_UNIT" == true ]]; then echo "After=intracherche-ollama.service"; fi)

[Service]
Type=simple
User=${IC_USER}
Group=${IC_USER}
WorkingDirectory=${INSTALL_DIR}
Environment=HF_HUB_OFFLINE=1
# The heap this installer sized from the server's RAM, and the one place an admin can
# change it for good: jvm.conf is read after this default and wins, is never written by
# the installer, and survives every upgrade. Missing or empty, the default below applies
# — that is why the file is optional ("-") and the value is not only in the file.
Environment=IC_HEAP_MB=${APP_HEAP_MB}
EnvironmentFile=-${CONFIG_DIR}/jvm.conf
# ExitOnOutOfMemoryError: a JVM that runs out of heap on one thread stays alive but damaged
# (its HTTP client to Solr dies, and every file then fails until someone restarts it).
# Exiting makes systemd's Restart=on-failure bring it back within RestartSec.
ExecStart=${JAVA_BIN} \
  -Xms256m -Xmx\${IC_HEAP_MB}m \
  -XX:+ExitOnOutOfMemoryError \
  -jar ${INSTALL_DIR}/app/intracherche.jar \
  --spring.config.additional-location=file:${CONFIG_DIR}/application.yaml
Restart=on-failure
RestartSec=15
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload

# ── 16. Enable and start services ─────────────────────────────────────────────
info "Enabling services."
[[ "$CREATE_OLLAMA_UNIT" == true ]] && systemctl enable intracherche-ollama
systemctl enable intracherche

if [[ "$INSTALL_BUNDLED_OLLAMA" == true ]]; then
    info "Starting Ollama."
    systemctl start intracherche-ollama
elif [[ "$CREATE_OLLAMA_UNIT" == true ]]; then
    # Don't start the unit now: the manually-launched instance still holds port 11434.
    info "intracherche-ollama enabled for next boot — current Ollama instance left running."
fi

# Auto-start only when the config is actually filled in. An existing config from
# a previous (possibly aborted) install may still carry CHANGE_ME placeholders,
# so don't trust "a file exists" — check for placeholders on active (non-comment)
# lines. Commented-out optional blocks (e.g. the SMTP example) keep their own
# CHANGE_ME and must not count, hence the `grep -vE '^[[:space:]]*#'` filter.
if grep -vE '^[[:space:]]*#' "$CONFIG_DIR/application.yaml" | grep -q 'CHANGE_ME'; then
    # Placeholders remain — starting now would only fail (e.g. on LDAP). The unit
    # is enabled (it starts on boot), but leave it stopped until the admin has
    # filled in application.yaml.
    CONFIG_READY=false
    warn "Not starting IntraCherche: $CONFIG_DIR/application.yaml still has CHANGE_ME placeholders."
    warn "Fill it in, then run: systemctl start intracherche"
else
    CONFIG_READY=true
    info "Starting IntraCherche (this will also start Solr and all Python services)."
    systemctl start intracherche

    # ── 17. Health check ──────────────────────────────────────────────────────
    echo ""
    info "Waiting for the IntraCherche web interface (up to 60 s)..."
    for i in $(seq 1 12); do
        # "/" is public; /api/admin/health needs a login and would always answer 401. With
        # HTTPS, 8080 answers with a redirect to 8443: that too means the application is up.
        if [[ "$(http_get 8080 / | head -1)" =~ \ (200|301|302|307|308)\  ]]; then
            echo ""
            info "IntraCherche is up. Solr and the Python services may need a few more minutes (INSTALL.md, step 4)."
            break
        fi
        [[ $i -lt 12 ]] && sleep 5 || warn "Service did not respond in time — check: journalctl -u intracherche -n 50"
    done
fi

# ── 18. Post-install summary ──────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Installation complete"
echo "═══════════════════════════════════════════════════════"
echo ""
if grep -qE '^[[:space:]]+key-store:' "$CONFIG_DIR/application.yaml"; then
echo "  Application:   https://$(hostname -f):8443   (self-signed certificate: INSTALL.md, \"Accès sécurisé\")"
else
echo "  Application:   http://$(hostname -f):8080"
fi
echo "  Config:        ${CONFIG_DIR}/application.yaml"
echo "  Indexes:       ${CONFIG_DIR}/indexes.yaml"
echo "  Logs:          ${LOG_DIR}/app.log"
echo "  Data:          ${DATA_DIR}/registry.db"
echo "  Java heap:     ${APP_HEAP_MB} MB  (of ${TOTAL_RAM_MB} MB RAM; re-run this installer after adding RAM,"
echo "                 or set IC_HEAP_MB in ${CONFIG_DIR}/jvm.conf to choose it yourself)"
if [[ -n "$SOLR_HEAP_SUMMARY" ]]; then echo "$SOLR_HEAP_SUMMARY"; fi
if [[ -n "$SHERLOCK_RAM_WARNING" ]]; then
    echo ""
    echo -e "${YELLOW}[WARN]${NC}  $SHERLOCK_RAM_WARNING"
fi
echo ""
echo "  IntraCherche is enabled at boot and will start automatically on reboot."
echo ""
echo "  Next steps:"
if [[ "$CONFIG_READY" != true ]]; then
echo "  1. Edit ${CONFIG_DIR}/application.yaml — replace every CHANGE_ME (LDAP required, SMTP optional)."
echo "  2. Edit ${CONFIG_DIR}/indexes.yaml to define your document sources."
echo "  3. Start: systemctl start intracherche"
else
echo "  Existing configuration kept. Review ${CONFIG_DIR}/application.yaml and"
echo "  ${CONFIG_DIR}/indexes.yaml if anything changed, then:"
echo "    systemctl restart intracherche"
if [[ -f "$CONFIG_DIR/application.yaml.reference.previous" ]]; then
    echo "  Settings this version added or changed:"
    echo "    diff ${CONFIG_DIR}/application.yaml.reference.previous ${CONFIG_DIR}/application.yaml.reference"
fi
fi
echo ""
echo "  Useful commands:"
echo "    systemctl status intracherche"
echo "    journalctl -u intracherche -f"
[[ "$INSTALL_BUNDLED_OLLAMA" == true ]] && echo "    journalctl -u intracherche-ollama -f"
echo ""
