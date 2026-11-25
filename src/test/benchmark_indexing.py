#!/usr/bin/env python3
"""
End-to-end indexing throughput benchmark for IntraCherche.

Measures real pipeline speed by forcing a re-index of registered files and
watching the running app via its admin API.

How it works:
  1. Resets indexed_at = NULL for the chosen files in the SQLite registry.
  2. Logs into the running app to get a JWT.
  3. Waits for any in-progress cycle to finish, then waits for the next
     cycle to complete (the one that picks up our reset).
  4. Reads textPagesTotal before/after (lifetime counter — delta = this run).
  5. Reports wall time, files, pages, and throughput.

Usage:
  python3 src/test/benchmark_indexing.py --username USER --password PASS [options]

  --username, --password   AD/LDAP credentials to log into the app.
  --path-filter STR        SQL LIKE filter on the path column (default: %, all files).
                           Example: --path-filter '%demo-files-fr%'
  --list                   Show matching files without resetting anything, then exit.
  --db PATH                Path to registry.db (auto-detected if omitted).
  --app-url URL            App base URL (default: http://localhost:8080).
"""

import argparse
import sqlite3
import sys
import time
import datetime
import requests
from pathlib import Path

# ---------------------------------------------------------------------------
# Config helpers
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).resolve().parents[2]

DB_CANDIDATES = [
    PROJECT_ROOT / "db" / "registry.db",
    Path.home() / ".IntraCherche" / "registry.db",
]


def find_db() -> Path:
    """Try to locate registry.db from known locations or the yaml config."""
    # Try reading application-local.yaml for dbPath
    local_yaml = PROJECT_ROOT / "src/main/resources/config/application-local.yaml"
    if local_yaml.exists():
        for line in local_yaml.read_text().splitlines():
            stripped = line.strip()
            if stripped.startswith("dbPath:"):
                raw = stripped.split(":", 1)[1].strip().strip('"').strip("'")
                p = Path(raw)
                if p.exists():
                    return p

    for candidate in DB_CANDIDATES:
        if candidate.exists():
            return candidate

    return None


# ---------------------------------------------------------------------------
# Registry helpers
# ---------------------------------------------------------------------------

def list_files(conn: sqlite3.Connection, path_filter: str) -> list[tuple]:
    """Return (id, path, indexed_at) for files matching the LIKE filter."""
    cur = conn.execute(
        "SELECT id, path, indexed_at FROM file_registry WHERE path LIKE ?",
        (path_filter,),
    )
    return cur.fetchall()


def reset_indexed_at(conn: sqlite3.Connection, ids: list[str]) -> int:
    """Set indexed_at = NULL for the given file ids. Returns row count."""
    if not ids:
        return 0
    placeholders = ",".join("?" * len(ids))
    cur = conn.execute(
        f"UPDATE file_registry SET indexed_at = NULL WHERE id IN ({placeholders})",
        ids,
    )
    conn.commit()
    return cur.rowcount


# ---------------------------------------------------------------------------
# Admin API helpers
# ---------------------------------------------------------------------------

def login(app_url: str, username: str, password: str) -> str:
    """Return a JWT token, or raise on failure."""
    r = requests.post(
        f"{app_url}/api/auth/login",
        json={"username": username, "password": password},
        timeout=10,
    )
    r.raise_for_status()
    data = r.json()
    token = data.get("token") or data.get("jwt") or data.get("accessToken")
    if not token:
        raise ValueError(f"Unexpected login response: {data}")
    return token


def get_status(app_url: str, token: str) -> dict:
    """Fetch /api/admin/indexer/status."""
    r = requests.get(
        f"{app_url}/api/admin/indexer/status",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def wait_for_idle(app_url: str, token: str, timeout_s: int = 600) -> dict:
    """Block until isRunning is False. Returns the last status snapshot."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        st = get_status(app_url, token)
        if not st.get("isRunning", False):
            return st
        print("  (cycle en cours, attente…)", flush=True)
        time.sleep(5)
    raise TimeoutError("L'indexeur ne s'est pas arrêté dans le délai imparti.")


def wait_for_cycle(app_url: str, token: str, after_ts: str | None, timeout_s: int = 300) -> dict:
    """
    Wait until a cycle completes whose lastCycleCompletedAt is strictly after
    after_ts. Returns the status snapshot when that condition is met.
    after_ts is the ISO string of the lastCycleCompletedAt we saw before the reset.
    """
    deadline = time.monotonic() + timeout_s
    saw_running = False

    while time.monotonic() < deadline:
        st = get_status(app_url, token)
        is_running = st.get("isRunning", False)
        completed = st.get("lastCycleCompletedAt")

        if is_running:
            if not saw_running:
                started = st.get("cycleStartedAt", "?")
                files_total = st.get("cycleTotal", "?")
                print(f"  Cycle démarré à {started} ({files_total} fichier(s) à traiter).", flush=True)
            saw_running = True

        if saw_running and not is_running:
            # Cycle just finished — check it's a new one
            if completed and completed != after_ts:
                return st

        time.sleep(3)

    raise TimeoutError(
        "Aucun cycle ne s'est terminé dans le délai. "
        "Vérifiez que l'indexeur n'est pas en pause et que les fichiers sont accessibles."
    )


# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------

def fmt_duration(seconds: float) -> str:
    m, s = divmod(int(seconds), 60)
    return f"{m}m {s:02d}s" if m else f"{s}s"


def parse_iso(ts: str | None) -> datetime.datetime | None:
    if not ts:
        return None
    try:
        return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark de débit d'indexation IntraCherche (pipeline complet).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--username", required=True, help="Identifiant AD/LDAP")
    parser.add_argument("--password", required=True, help="Mot de passe AD/LDAP")
    parser.add_argument(
        "--path-filter", default="%",
        help="Filtre SQL LIKE sur la colonne path (défaut : %%, tous les fichiers). "
             "Exemple : --path-filter '%%demo-files-fr%%'",
    )
    parser.add_argument("--list", action="store_true", help="Afficher les fichiers concernés sans rien modifier.")
    parser.add_argument("--db", help="Chemin vers registry.db (auto-détecté si absent).")
    parser.add_argument("--app-url", default="http://localhost:8080")
    args = parser.parse_args()

    # --- Locate DB ---
    db_path = Path(args.db) if args.db else find_db()
    if not db_path or not db_path.exists():
        print(f"Erreur : registry.db introuvable. Utilisez --db pour le spécifier.")
        sys.exit(1)
    print(f"Base de données : {db_path}")

    conn = sqlite3.connect(str(db_path))

    # --- Find matching files ---
    rows = list_files(conn, args.path_filter)
    if not rows:
        print(f"Aucun fichier trouvé avec le filtre '{args.path_filter}'.")
        conn.close()
        sys.exit(1)

    indexed_count = sum(1 for _, _, ia in rows if ia is not None)
    print(f"\nFichiers correspondant au filtre : {len(rows)}")
    print(f"  Déjà indexés (indexed_at défini) : {indexed_count}")
    print(f"  Jamais indexés                   : {len(rows) - indexed_count}")
    print()
    for _, path, indexed_at in rows:
        status = f"indexé le {datetime.datetime.fromtimestamp(indexed_at/1000):%Y-%m-%d %H:%M}" if indexed_at else "jamais indexé"
        print(f"  {Path(path).name:<60}  {status}")

    if args.list:
        conn.close()
        return

    if not rows:
        print("Rien à faire.")
        conn.close()
        return

    # --- Confirm ---
    print()
    answer = input(f"Réinitialiser indexed_at pour {len(rows)} fichier(s) et démarrer le benchmark ? [o/N] ").strip().lower()
    if answer not in ("o", "oui", "y", "yes"):
        print("Annulé.")
        conn.close()
        return

    # --- Connect to app ---
    print(f"\nConnexion à {args.app_url}…")
    try:
        token = login(args.app_url, args.username, args.password)
        print("  Authentification réussie.")
    except Exception as e:
        print(f"  Échec de la connexion : {e}")
        conn.close()
        sys.exit(1)

    # --- Wait for any in-progress cycle to finish ---
    print("\nAttente de la fin du cycle en cours (si applicable)…")
    pre_status = wait_for_idle(args.app_url, token)
    pages_before = pre_status.get("textPagesTotal", 0)
    prev_completed = pre_status.get("lastCycleCompletedAt")
    print(f"  Indexeur inactif. textPagesTotal = {pages_before}")

    # --- Reset indexed_at ---
    file_ids = [row[0] for row in rows]
    reset_count = reset_indexed_at(conn, file_ids)
    conn.close()
    t_reset = time.monotonic()
    t_reset_wall = datetime.datetime.now()
    print(f"\n{reset_count} fichier(s) réinitialisé(s) à {t_reset_wall:%H:%M:%S}.")
    print("En attente du prochain cycle d'indexation…\n")

    # --- Wait for the next cycle to complete ---
    try:
        post_status = wait_for_cycle(args.app_url, token, prev_completed, timeout_s=600)
    except TimeoutError as e:
        print(f"\nErreur : {e}")
        sys.exit(1)

    t_done = time.monotonic()
    wall_s = t_done - t_reset

    # --- Collect metrics ---
    pages_after = post_status.get("textPagesTotal", 0)
    pages_indexed = pages_after - pages_before
    files_processed = post_status.get("cycleProcessed", reset_count)
    files_total_cycle = post_status.get("cycleTotal", reset_count)

    # Use app-reported timestamps for the cycle duration when available
    cycle_started  = parse_iso(post_status.get("cycleStartedAt"))
    cycle_finished = parse_iso(post_status.get("lastCycleCompletedAt"))
    if cycle_started and cycle_finished:
        cycle_s = (cycle_finished - cycle_started).total_seconds()
    else:
        cycle_s = wall_s

    # Index summaries
    summaries = post_status.get("indexSummaries", {})

    # --- Report ---
    W = 60
    print()
    print("=" * W)
    print("  RÉSULTATS DU BENCHMARK")
    print("=" * W)
    print(f"  Fichiers réinitialisés    : {reset_count}")
    print(f"  Fichiers traités (cycle)  : {files_processed} / {files_total_cycle}")
    print(f"  Pages de texte indexées   : {pages_indexed}  (textPagesTotal {pages_before} → {pages_after})")
    print()
    print(f"  Durée du cycle (app)      : {fmt_duration(cycle_s)}  ({cycle_s:.1f}s)")
    print(f"  Durée totale (horloge)    : {fmt_duration(wall_s)}  ({wall_s:.1f}s)")
    print()
    if pages_indexed > 0:
        print(f"  Débit pages               : {pages_indexed / cycle_s:.2f} pages/s")
    if reset_count > 0 and cycle_s > 0:
        print(f"  Débit fichiers            : {reset_count / cycle_s:.2f} fichiers/s")
    print()
    if summaries:
        print("  Résultats par index :")
        for idx_name, s in summaries.items():
            print(f"    {idx_name:<30}  ✓ {s.get('succeeded',0)}  ✗ {s.get('failed',0)}  ø {s.get('skipped',0)}")
    print("=" * W)


if __name__ == "__main__":
    main()
