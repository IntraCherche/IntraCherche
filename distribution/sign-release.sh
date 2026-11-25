#!/bin/bash
# sign-release.sh — sign the release archives of dist/ with minisign.
#
#   SIGNING_KEY=~/.minisign/intracherche.key ./distribution/sign-release.sh [VERSION]
#
# Signs, for the given version (default: the version in pom.xml), every archive that
# build-distrib.sh produced in dist/ (base, GPU if present) and the model archive, writing
# <archive>.minisig next to each. minisign asks for the key's passphrase once.
#
# The private key stays on the machine that signs: never in the repository, never copied to a
# customer. The matching public key is distribution/intracherche-signing.pub; a customer verifies with
#   minisign -Vm <archive> -p intracherche-signing.pub
set -euo pipefail

cd "$(dirname "$0")/.."
KEY="${SIGNING_KEY:-$HOME/.minisign/intracherche.key}"
VERSION="${1:-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)}"

command -v minisign >/dev/null || { echo "minisign is not installed (sudo apt install minisign)." >&2; exit 1; }
[[ -f "$KEY" ]] || { echo "Signing key not found: $KEY (set SIGNING_KEY)." >&2; exit 1; }

shopt -s nullglob
archives=(dist/intracherche-"$VERSION"-*.tar.gz dist/intracherche-ollama-*.tar.gz)
[[ ${#archives[@]} -gt 0 ]] || { echo "No archive for $VERSION in dist/." >&2; exit 1; }

# One minisign call for all the archives: the passphrase is asked once.
echo "Signing ${#archives[@]} archive(s):"
printf '  %s\n' "${archives[@]}"
minisign -S -s "$KEY" -t "IntraCherche $VERSION" -m "${archives[@]}"

echo
echo "Verifying with distribution/intracherche-signing.pub:"
for a in "${archives[@]}"; do
    minisign -Vm "$a" -p distribution/intracherche-signing.pub >/dev/null && echo "  ok  $a" || { echo "  FAILED  $a" >&2; exit 1; }
done
echo
echo "Deliver each archive with its .sha256 and .minisig."
