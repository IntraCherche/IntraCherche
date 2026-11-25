#!/usr/bin/env bash
# IntraCherche — self-signed HTTPS certificate for this server.
#
# Called by install.sh on a first install; run it again yourself to renew the certificate or
# to add a name users type to reach the server (a DNS alias, another address):
#
#   sudo /opt/intracherche/generate-certificate.sh                     # renew, same names
#   sudo /opt/intracherche/generate-certificate.sh --name recherche.corp.local --ip 10.0.0.5
#   sudo systemctl restart intracherche
#
# The certificate names the server's short name, its FQDN, localhost and every address it
# has, plus anything given with --name/--ip: a browser warns about any name not in it.
# It lasts 825 days, the longest every browser accepts once the certificate is trusted.
# Also written: intracherche.crt, the public certificate to deploy on workstations (GPO)
# so browsers stop warning. See INSTALL.md, "Accès sécurisé (HTTPS)".
set -euo pipefail

CONFIG_DIR="/etc/intracherche"
KEYSTORE="$CONFIG_DIR/intracherche.p12"
PUBLIC_CERT="$CONFIG_DIR/intracherche.crt"
ALIAS="intracherche"
IC_USER="intracherche"
VALIDITY_DAYS=825

KEYTOOL="${KEYTOOL:-}"
[[ -n "$KEYTOOL" ]] || KEYTOOL="/opt/intracherche/jre/bin/keytool"
[[ -x "$KEYTOOL" ]] || KEYTOOL="$(command -v keytool 2>/dev/null || true)"
[[ -n "$KEYTOOL" ]] || { echo "keytool not found (bundled JRE or PATH)." >&2; exit 1; }
[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }

extra_names=()
extra_ips=()
password=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --name) extra_names+=("$2"); shift 2 ;;
        --ip) extra_ips+=("$2"); shift 2 ;;
        --password) password="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# Renewal keeps the password application.yaml already names, so nothing else has to change.
if [[ -z "$password" && -f "$CONFIG_DIR/application.yaml" ]]; then
    password=$(sed -n 's/^[[:space:]]*key-store-password:[[:space:]]*"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' \
        "$CONFIG_DIR/application.yaml" | head -1)
fi
[[ -n "$password" ]] || password=$(head -c 24 /dev/urandom | base64 -w0 | tr -d '/+=')

# Names from a previous certificate are kept, so a renewal never drops an alias added by hand.
declare -A seen=()
san=""
add() { [[ -n "$1" && -z "${seen[$1]:-}" ]] || return 0; seen[$1]=1; san+="${san:+,}$1"; }
add "DNS:$(hostname -s)"
add "DNS:$(hostname -f 2>/dev/null || hostname)"
add "DNS:localhost"
add "IP:127.0.0.1"
for ip in $(hostname -I 2>/dev/null); do
    [[ "$ip" == fe80:* ]] || add "IP:$ip"     # link-local addresses carry no meaning off this link
done
for n in "${extra_names[@]}"; do add "DNS:$n"; done
for ip in "${extra_ips[@]}"; do add "IP:$ip"; done
if [[ -f "$KEYSTORE" ]]; then
    while read -r entry; do add "$entry"; done < <(
        "$KEYTOOL" -J-Duser.language=en -list -v -keystore "$KEYSTORE" -storepass "$password" -alias "$ALIAS" 2>/dev/null \
            | sed -n 's/^[[:space:]]*\(DNSName\|IPAddress\): \(.*\)$/\1 \2/p' \
            | sed 's/^DNSName /DNS:/; s/^IPAddress /IP:/')
    cp -p "$KEYSTORE" "$KEYSTORE.previous"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
"$KEYTOOL" -genkeypair -noprompt \
    -alias "$ALIAS" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
    -validity "$VALIDITY_DAYS" \
    -dname "CN=$(hostname -f 2>/dev/null || hostname), O=IntraCherche" \
    -ext "SAN=$san" -ext "EKU=serverAuth" -ext "KU=digitalSignature,keyEncipherment" \
    -keystore "$tmp/ks.p12" -storetype PKCS12 -storepass "$password" >"$tmp/log" 2>&1 \
    || { cat "$tmp/log" >&2; exit 1; }
"$KEYTOOL" -exportcert -rfc -alias "$ALIAS" -keystore "$tmp/ks.p12" -storepass "$password" \
    -file "$tmp/cert.crt" >"$tmp/log" 2>&1 || { cat "$tmp/log" >&2; exit 1; }

install -m 640 -o root -g "$IC_USER" "$tmp/ks.p12" "$KEYSTORE"
install -m 644 -o root -g root "$tmp/cert.crt" "$PUBLIC_CERT"

echo "Certificate written to $KEYSTORE, valid $VALIDITY_DAYS days, for: ${san//,/, }"
echo "Public certificate for workstations: $PUBLIC_CERT"
# The caller (install.sh) reads the password from this line to put it in application.yaml.
echo "KEYSTORE_PASSWORD=$password"
