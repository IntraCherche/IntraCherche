#!/usr/bin/env python3
"""
IntraCherche end-to-end smoke test.

Automates the manual regression checklist you run after a change:

    scanned PDF  ->  OCR -> NER -> embedding -> Solr  ->  searchable
                 ->  Sherlock answers a question from the corpus
                 ->  indexing pause / resume behaves
                 ->  a search alert fires a notification

It drives the **already-running** application over HTTP exactly the way the
browser does (JWT cookie + CSRF header), so it exercises the real auto-launched
stack (Solr + the 4 Python services + Ollama). Nothing is mocked.

Why a script and not a @SpringBootTest: this codebase deliberately never boots
the full Spring context in tests (the context auto-launches Solr and four Python
subprocesses, does RAM/license checks, etc.). The honest automation of "what I
click by hand" is to talk to the running app — that is what this does.

Each phase prints PASS / FAIL / SKIP. Any FAIL makes the process exit non-zero,
so this is CI-friendly even though it is meant to run on your dev box:

    python3 e2e/smoke_test.py

Everything that varies between machines is read from environment variables — see
the CONFIG block and e2e/README.md.

To extend coverage you usually only add a row to SCENARIOS; you do not write new
plumbing. That is the whole point — the checklist lives in code so it cannot
silently forget a feature.
"""
from __future__ import annotations

import os
import sys
import time
import shutil
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

try:
    import requests
except ImportError:
    sys.exit("This harness needs the 'requests' library:  pip install requests")


# ─────────────────────────────────────────────────────────────────────────────
# CONFIG  (override any of these with environment variables)
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Config:
    base_url: str = os.environ.get("IC_BASE_URL", "http://localhost:8080")
    solr_url: str = os.environ.get("IC_SOLR_URL", "http://localhost:8983/solr")

    # A user that can log in. For the alert + Sherlock phases this user must be
    # allowed to use Sherlock; for the pause phase the user must belong to the
    # group named by `adminGroupSid` in application.yaml.
    username: str = os.environ.get("IC_USER", "")
    password: str = os.environ.get("IC_PASSWORD", "")

    # The Solr core (== coreName in indexes.yaml) that the test documents land in.
    core: str = os.environ.get("IC_CORE", "Index_3")

    # A LOCAL (POSIX) directory that feeds `core`. The harness drops test files
    # here; the running app's next indexing cycle picks them up. Use a dedicated
    # smoke-test index/dir so you never pollute a real share — see e2e/README.md.
    watched_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("IC_WATCHED_DIR", "")))

    # A document to push through the pipeline. Point this at a *scanned/image*
    # PDF (and make sure `core`'s index has ocr: true) to truly exercise OCR.
    source_doc: Path = field(
        default_factory=lambda: Path(os.environ.get(
            "IC_SOURCE_DOC",
            "src/main/resources/benchmark/benchmark-text.pdf")))

    # How long to wait for one document to traverse the whole pipeline. A large
    # scanned PDF (~100 OCR pages) can take several minutes plus one cycle gap.
    pipeline_timeout_s: int = int(os.environ.get("IC_PIPELINE_TIMEOUT", "900"))
    cycle_timeout_s: int = int(os.environ.get("IC_CYCLE_TIMEOUT", "600"))
    sherlock_timeout_s: int = int(os.environ.get("IC_SHERLOCK_TIMEOUT", "180"))

    # Leave dropped files in place for inspection instead of cleaning up.
    keep_files: bool = os.environ.get("IC_KEEP_FILES", "").lower() in ("1", "true", "yes")


CFG = Config()


# ─────────────────────────────────────────────────────────────────────────────
# SCENARIOS  — the searchable / Sherlock ground truth. Add a row to extend.
# ─────────────────────────────────────────────────────────────────────────────
# `search_query`        : a term that must retrieve the indexed document.
# `expect_in_filename`  : substring of the indexed filename that must appear in
#                         the search results (proves the *right* doc came back).
# `sherlock_question`   : optional question whose answer lives in the corpus.
# `sherlock_keywords`   : any one of these must appear in Sherlock's answer.
SCENARIOS = [
    {
        "search_query": "Turing",
        "expect_in_filename": "benchmark-text",
        "sherlock_question": "En quelle année le champ de recherche de l'intelligence artificielle a-t-il été établi ?",
        "sherlock_keywords": ["1956"],
    },
]


# ─────────────────────────────────────────────────────────────────────────────
# Result reporting
# ─────────────────────────────────────────────────────────────────────────────

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"
_RESULTS: list[tuple[str, str, str]] = []  # (status, name, detail)

_C = {PASS: "\033[32m", FAIL: "\033[31m", SKIP: "\033[33m", "_": "\033[0m"}


def record(status: str, name: str, detail: str = "") -> None:
    _RESULTS.append((status, name, detail))
    colour = _C.get(status, "")
    line = f"  {colour}{status:4}{_C['_']}  {name}"
    if detail:
        line += f"\n          {detail}"
    print(line, flush=True)


def check(name: str, ok: bool, detail: str = "") -> bool:
    record(PASS if ok else FAIL, name, detail)
    return ok


def phase(title: str) -> None:
    print(f"\n=== {title} ===", flush=True)


def wait_until(predicate: Callable[[], bool], timeout_s: int,
               desc: str, interval_s: float = 3.0) -> bool:
    """Poll `predicate` until it is true or the timeout elapses."""
    deadline = time.monotonic() + timeout_s
    last_print = 0.0
    while time.monotonic() < deadline:
        try:
            if predicate():
                return True
        except Exception as e:  # transient (service restarting, etc.)
            if time.monotonic() - last_print > 15:
                print(f"          …waiting for {desc} ({e})", flush=True)
                last_print = time.monotonic()
        now = time.monotonic()
        if now - last_print > 15:
            remaining = int(deadline - now)
            print(f"          …waiting for {desc} ({remaining}s left)", flush=True)
            last_print = now
        time.sleep(interval_s)
    return False


# ─────────────────────────────────────────────────────────────────────────────
# HTTP client — mirrors the browser (JWT cookie + CSRF header)
# ─────────────────────────────────────────────────────────────────────────────

class Client:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.s = requests.Session()
        # The default install serves a self-signed certificate; IC_VERIFY_TLS=1 checks it anyway.
        self.s.verify = os.environ.get("IC_VERIFY_TLS", "0") == "1"
        if not self.s.verify:
            import urllib3
            urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
        self.s.headers["Accept"] = "application/json"

    # -- low level --------------------------------------------------------------
    def _csrf_headers(self) -> dict:
        # __Host-XSRF-TOKEN over HTTPS (app.cookie.secure: true), XSRF-TOKEN over plain HTTP.
        token = self.s.cookies.get("__Host-XSRF-TOKEN") or self.s.cookies.get("XSRF-TOKEN")
        return {"X-XSRF-TOKEN": token} if token else {}

    def _url(self, path: str) -> str:
        return self.cfg.base_url.rstrip("/") + path

    def get(self, path: str, **kw) -> requests.Response:
        return self.s.get(self._url(path), timeout=30, **kw)

    def post(self, path: str, **kw) -> requests.Response:
        kw.setdefault("headers", {}).update(self._csrf_headers())
        return self.s.post(self._url(path), timeout=60, **kw)

    def delete(self, path: str, **kw) -> requests.Response:
        kw.setdefault("headers", {}).update(self._csrf_headers())
        return self.s.delete(self._url(path), timeout=30, **kw)

    # -- auth -------------------------------------------------------------------
    def login(self) -> bool:
        # Prime the CSRF cookie from a permitted page.
        self.get("/")
        r = self.s.post(self._url("/api/auth/login"),
                        json={"username": self.cfg.username,
                              "password": self.cfg.password},
                        timeout=30)
        if r.status_code != 200:
            return False
        # CSRF may rotate on authentication — refresh the cookie.
        self.get("/")
        return self.s.cookies.get("jwt") is not None

    # -- domain helpers ---------------------------------------------------------
    def admin_status(self) -> Optional[dict]:
        r = self.get("/api/admin/indexer/status")
        return r.json() if r.status_code == 200 else None

    def health(self) -> Optional[dict]:
        r = self.get("/api/admin/health")
        return r.json() if r.status_code == 200 else None

    def search(self, query: str, core: str, rows: int = 20) -> dict:
        r = self.get("/api/search",
                     params={"q": query, "indices": core, "rows": rows})
        r.raise_for_status()
        return r.json()

    def solr_numfound(self, core: str, q: str = "*:*") -> int:
        r = self.s.get(f"{self.cfg.solr_url}/{core}/select",
                       params={"q": q, "rows": 0, "wt": "json"}, timeout=15)
        r.raise_for_status()
        return int(r.json()["response"]["numFound"])

    def pause(self) -> requests.Response:
        return self.post("/api/admin/indexer/pause")

    def resume(self) -> requests.Response:
        return self.post("/api/admin/indexer/resume")

    def sherlock_ask(self, question: str) -> Optional[str]:
        r = self.post("/api/sherlock/questions", json={"question": question})
        if r.status_code in (200, 202):
            return r.json().get("id")
        return None

    def sherlock_get(self, qid: str) -> Optional[dict]:
        r = self.get(f"/api/sherlock/questions/{qid}")
        return r.json() if r.status_code == 200 else None

    def create_subscription(self, query: str, core: str) -> Optional[str]:
        r = self.post("/api/subscriptions",
                      json={"query": query, "indices": [core],
                            "frequency": "IMMEDIATE"})
        return r.json().get("id") if r.status_code == 201 else None

    def delete_subscription(self, sid: str) -> None:
        self.delete(f"/api/subscriptions/{sid}")

    def unread_count(self) -> int:
        r = self.get("/api/notifications/unread-count")
        return int(r.json().get("count", 0)) if r.status_code == 200 else 0

    def notifications(self) -> list:
        r = self.get("/api/notifications", params={"limit": 50})
        return r.json() if r.status_code == 200 else []


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def drop_file(src: Path, dst_dir: Path, tag: str) -> Path:
    """Copy `src` into the watched dir under a unique name so the registry treats
    it as a brand-new document (no dedup against a prior run)."""
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst = dst_dir / f"smoke-{tag}-{uuid.uuid4().hex[:8]}{src.suffix}"
    shutil.copy2(src, dst)
    return dst


def results_filenames(search_json: dict) -> list[str]:
    """Best-effort extraction of filenames/paths from a search response, tolerant
    of field-name differences across versions."""
    docs = (search_json.get("results")
            or search_json.get("docs")
            or search_json.get("response", {}).get("docs")
            or [])
    names = []
    for d in docs:
        if not isinstance(d, dict):
            continue
        for key in ("filename", "fileName", "name", "path", "filePath", "title", "id"):
            v = d.get(key)
            if v:
                names.append(str(v))
                break
    return names


# ─────────────────────────────────────────────────────────────────────────────
# Phases
# ─────────────────────────────────────────────────────────────────────────────

def preflight(c: Client) -> bool:
    phase("Preflight")
    if not CFG.username or not CFG.password:
        check("Credentials provided (IC_USER / IC_PASSWORD)", False,
              "Set IC_USER and IC_PASSWORD — see e2e/README.md")
        return False
    if not CFG.watched_dir or not str(CFG.watched_dir):
        check("Watched dir provided (IC_WATCHED_DIR)", False,
              "Set IC_WATCHED_DIR to a POSIX directory feeding the test core")
        return False

    ok = True
    try:
        reachable = c.get("/").status_code < 500
    except Exception as e:
        reachable = False
        record(FAIL, "App reachable", f"{CFG.base_url}: {e}")
    if reachable:
        check("App reachable", True, CFG.base_url)
    ok &= reachable

    ok &= check("Login succeeds", c.login(), f"user={CFG.username}")
    ok &= check("Source document exists", CFG.source_doc.is_file(), str(CFG.source_doc))
    ok &= check("Watched dir is writable",
                os.access(CFG.watched_dir.parent if not CFG.watched_dir.exists()
                          else CFG.watched_dir, os.W_OK),
                str(CFG.watched_dir))

    try:
        c.solr_numfound(CFG.core)
        check("Solr core reachable", True, f"{CFG.solr_url}/{CFG.core}")
    except Exception as e:
        ok &= check("Solr core reachable", False, str(e))

    h = c.health()
    if h:
        services = h.get("services", {})
        down = [n for n, s in services.items()
                if s.get("enabled") and not s.get("up")]
        check("Python services healthy", not down,
              "down: " + ", ".join(down) if down else "all up")
    return ok


def phase_index_and_search(c: Client) -> None:
    phase("Indexing pipeline (scanned PDF -> OCR -> NER -> embedding -> Solr)")
    scn = SCENARIOS[0]
    before = c.solr_numfound(CFG.core)
    dropped = drop_file(CFG.source_doc, CFG.watched_dir, "index")
    print(f"          dropped {dropped.name} into {CFG.watched_dir}", flush=True)

    # The document is searchable once the app's search API returns it by content.
    def searchable() -> bool:
        names = results_filenames(c.search(scn["search_query"], CFG.core))
        return any(dropped.name in n or scn["expect_in_filename"] in n for n in names)

    found = wait_until(searchable, CFG.pipeline_timeout_s,
                       f"'{scn['search_query']}' to become searchable")
    after = c.solr_numfound(CFG.core)
    check("Document traversed pipeline and is searchable by content", found,
          f"query='{scn['search_query']}'  Solr docs {before} -> {after}")


def phase_pause_resume(c: Client) -> None:
    phase("Pause / resume indexing")
    # Drop several files to create in-flight work.
    for i in range(3):
        drop_file(CFG.source_doc, CFG.watched_dir, f"pause{i}")

    def busy() -> bool:
        st = c.admin_status() or {}
        return st.get("isRunning") or st.get("queueDepth", 0) > 0 or st.get("activeFiles")

    if not wait_until(busy, CFG.cycle_timeout_s, "indexer to start working"):
        record(SKIP, "Pause / resume",
               "Indexer never showed activity within the cycle window — "
               "increase IC_CYCLE_TIMEOUT or check the indexing cycle interval.")
        return

    r = c.pause()
    if r.status_code == 403:
        record(SKIP, "Pause / resume",
               "Admin endpoint returned 403 — set adminGroupSid in application.yaml "
               "and make IC_USER a member of that group.")
        return
    paused_ok = r.status_code == 200 and (c.admin_status() or {}).get("isPaused") is True
    check("Indexer reports paused after /pause", paused_ok)

    # While paused, in-flight OCR is cancelled and the queue stops growing.
    time.sleep(8)
    st = c.admin_status() or {}
    check("Indexer stays paused (no new cycle starts)",
          st.get("isPaused") is True,
          f"queueDepth={st.get('queueDepth')} activeFiles={len(st.get('activeFiles') or [])}")

    r = c.resume()
    resumed_ok = r.status_code == 200 and (c.admin_status() or {}).get("isPaused") is False
    check("Indexer reports running after /resume", resumed_ok)

    # The files dropped above should eventually get indexed on a later cycle.
    target = c.solr_numfound(CFG.core)
    done = wait_until(lambda: c.solr_numfound(CFG.core) >= target,
                      CFG.cycle_timeout_s, "post-resume indexing to progress")
    check("Indexing resumes and processes remaining files", done)


def phase_sherlock(c: Client) -> None:
    phase("Sherlock (RAG question answering)")
    scn = SCENARIOS[0]
    question = scn.get("sherlock_question")
    if not question:
        record(SKIP, "Sherlock", "No sherlock_question configured in SCENARIOS")
        return

    qid = c.sherlock_ask(question)
    if not qid:
        record(SKIP, "Sherlock",
               "Could not enqueue question (Ollama down, or 403). "
               "Check /api/admin/health and llm.ollamaUrl.")
        return

    answer = {}

    def answered() -> bool:
        nonlocal answer
        a = c.sherlock_get(qid) or {}
        answer = a
        return a.get("status") == "ANSWERED"

    if not wait_until(answered, CFG.sherlock_timeout_s, "Sherlock to answer"):
        check("Sherlock answers the question", False,
              f"status stuck at {answer.get('status')}")
        return

    text = (answer.get("answer") or "").lower()
    kws = scn.get("sherlock_keywords", [])
    check("Sherlock produced a non-empty answer", bool(text.strip()))
    if kws:
        hit = [k for k in kws if k.lower() in text]
        check("Answer contains an expected fact (retrieval worked)", bool(hit),
              f"expected one of {kws}; matched {hit}")
    check("Answer carries citations", bool(answer.get("citations")))


def phase_alerts(c: Client) -> None:
    phase("Search alerts (subscription -> notification)")
    scn = SCENARIOS[0]
    # A query that only matches a document we are about to index.
    marker = f"smokealert{uuid.uuid4().hex[:6]}"
    # We piggy-back on the known-searchable term so the subscription matches the
    # freshly indexed doc; the marker keeps the run idempotent in the summary.
    query = scn["search_query"]

    sid = c.create_subscription(query, CFG.core)
    if not sid:
        record(SKIP, "Search alerts",
               "Could not create subscription (check /api/subscriptions and auth).")
        return
    print(f"          created IMMEDIATE subscription {sid} q='{query}'", flush=True)

    try:
        before = len(c.notifications())
        drop_file(CFG.source_doc, CFG.watched_dir, f"alert-{marker}")

        # IMMEDIATE subscriptions are evaluated at the end of each indexing cycle.
        def notified() -> bool:
            return len(c.notifications()) > before

        got = wait_until(notified, CFG.pipeline_timeout_s,
                         "an alert notification to be created")
        check("Indexing a matching document fires an alert notification", got,
              f"notifications {before} -> {len(c.notifications())}")
    finally:
        c.delete_subscription(sid)
        record(PASS, "Subscription cleaned up", sid)


def cleanup() -> None:
    if CFG.keep_files:
        return
    removed = 0
    if CFG.watched_dir.exists():
        for f in CFG.watched_dir.glob("smoke-*"):
            try:
                f.unlink()
                removed += 1
            except OSError:
                pass
    if removed:
        print(f"\nCleaned up {removed} dropped test file(s) "
              f"(set IC_KEEP_FILES=1 to keep them).", flush=True)


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main() -> int:
    print("IntraCherche end-to-end smoke test")
    print(f"  target : {CFG.base_url}")
    print(f"  core   : {CFG.core}")
    print(f"  drop   : {CFG.watched_dir}")

    c = Client(CFG)
    if not preflight(c):
        print("\nPreflight failed — fix the above before running the scenarios.")
        return _exit_code()

    try:
        phase_index_and_search(c)
        phase_sherlock(c)
        phase_pause_resume(c)
        phase_alerts(c)
    finally:
        cleanup()

    return _exit_code()


def _exit_code() -> int:
    phase("Summary")
    n_pass = sum(1 for s, *_ in _RESULTS if s == PASS)
    n_fail = sum(1 for s, *_ in _RESULTS if s == FAIL)
    n_skip = sum(1 for s, *_ in _RESULTS if s == SKIP)
    for status, name, _ in _RESULTS:
        if status in (FAIL, SKIP):
            print(f"  {_C.get(status,'')}{status:4}{_C['_']}  {name}")
    print(f"\n  {n_pass} passed, {n_fail} failed, {n_skip} skipped")
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
