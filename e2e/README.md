# End-to-end smoke test

`smoke_test.py` automates the manual regression checklist you run after a change.
Instead of clicking through the UI and trying not to forget a feature, you run
**one command** and read a PASS/FAIL/SKIP report.

It covers, in order:

1. **Indexing pipeline** — drops a (scanned) PDF into a watched folder and waits
   until it is searchable by content. This proves OCR → NER → embedding → Solr
   all worked end to end.
2. **Sherlock** — asks a question whose answer is in the corpus and checks the
   answer contains the expected fact and has citations.
3. **Pause / resume** — creates in-flight indexing work, pauses, verifies the
   indexer reports paused and stops, resumes, verifies it finishes.
4. **Search alerts** — creates an `IMMEDIATE` subscription, indexes a matching
   document, and verifies a notification is fired (immediate alerts are
   evaluated at the end of each indexing cycle).

**Single sign-on** has its own harness, `sso/harness.py`, which drives the login through a throwaway Keycloak
and checks which directory account each user ends up in. See [`sso/README.md`](sso/README.md).

## Why a script, not a JUnit test

This codebase never boots the full Spring context in tests on purpose — the
context auto-launches Solr and the four Python services, does RAM/license checks,
and installs the security filter chain. The faithful automation of "what I do by
hand" is to drive the **running** application over HTTP (JWT cookie + CSRF
header), exactly like the browser. That is what this harness does, so it
exercises the real stack with nothing mocked.

## Prerequisites

- The application is **running** (`mvn spring-boot:run` or the packaged jar). It
  auto-launches Solr and the Python services.
- **Ollama** is up with the configured model pulled (only the Sherlock phase
  needs it — otherwise that phase is skipped, not failed).
- A **login** that works against your auth backend.
- A dedicated **local (POSIX) index** so the test never touches a real share.
  Add this once to your `indexes.yaml`:

  ```yaml
  - coreName: Smoke_Test
    visibleToGroups: ALL
    status: ACTIVE
    ocr: true                 # so scanned PDFs exercise the OCR path
    indexedDirs:
      - mountType: POSIX
        linuxPath: /abs/path/to/smoke-inbox
  ```

- For the **pause/resume** phase, `adminGroupSid` must be set in
  `application.yaml` and the test user must be a member of that group. If not,
  that phase is reported as SKIP (it returns HTTP 403), not FAIL.

## Run it

```bash
pip install requests   # once

IC_USER=alice \
IC_PASSWORD=secret \
IC_CORE=Smoke_Test \
IC_WATCHED_DIR=/abs/path/to/smoke-inbox \
IC_SOURCE_DOC="src/main/resources/benchmark/benchmark-text.pdf" \
python3 e2e/smoke_test.py
```

Exit code is non-zero if any check FAILs, so it slots into a pre-release script.

### All environment variables

| Variable             | Default                       | Meaning                                            |
|----------------------|-------------------------------|----------------------------------------------------|
| `IC_BASE_URL`        | `http://localhost:8080`       | App base URL (a default install redirects to `https://…:8443`) |
| `IC_VERIFY_TLS`      | `0`                           | `1` to verify the HTTPS certificate (off: self-signed by default) |
| `IC_SOLR_URL`        | `http://localhost:8983/solr`  | Solr base URL (used for doc-count progress)        |
| `IC_USER`            | —                             | Login username (**required**)                      |
| `IC_PASSWORD`        | —                             | Login password (**required**)                      |
| `IC_CORE`            | `Index_3`                     | Solr core / `coreName` the test docs land in       |
| `IC_WATCHED_DIR`     | —                             | POSIX dir feeding that core (**required**)         |
| `IC_SOURCE_DOC`      | the benchmark PDF             | Document pushed through the pipeline               |
| `IC_PIPELINE_TIMEOUT`| `900`                         | Seconds to wait for a doc to become searchable     |
| `IC_CYCLE_TIMEOUT`   | `600`                         | Seconds to wait for an indexing cycle              |
| `IC_SHERLOCK_TIMEOUT`| `180`                         | Seconds to wait for a Sherlock answer              |
| `IC_KEEP_FILES`      | unset                         | Set to `1` to keep dropped `smoke-*` test files    |

## Extending coverage

To assert a new searchable fact or a new Sherlock answer, add a row to the
`SCENARIOS` table at the top of `smoke_test.py` — no new plumbing. That keeps the
regression checklist in code, so it cannot silently forget a case.

## Notes / limitations

- Indexing is triggered by the app's own cycle, not an on-demand endpoint, so the
  harness drops a file and waits up to one cycle interval + processing time. Set
  a short `indexing-config.cycle-interval-minutes` in a test profile to speed
  this up.
- The pause assertion verifies the observable contract (status flips to paused,
  no new cycle starts, work resumes afterward) rather than exact in-flight
  counts, which are inherently racy.
- Sherlock answers are LLM-generated and non-deterministic, so the check asserts
  the presence of an expected keyword + citations, never exact prose.

---

# Scale corpus — `generate_wikipedia_corpus.py`

The demo corpus is a few dozen documents. To see how retrieval and Sherlock
behave with a real amount of noise, this builds a corpus of text-only PDFs from
a French Wikipedia dump:

```bash
uv run e2e/generate_wikipedia_corpus.py --count 20000 --out /path/to/wikipedia-20k
```

Measured on the dev laptop (i5-8350U, 8 threads): **20 000 articles → 47 492
pages, 113 MB, in 3 min**. Add `--seed-dir DIR` to
drop your own ground-truth documents into the same tree, so that known answers
compete against the distractors.

## Sizing

Pages, not articles, are the unit of cost: `PdfPageExtractor` makes one Solr doc
and one 768-dim vector per page. With ~2.4 pages per article:

| articles | pages | source | Solr index | indexing time |
|---------:|------:|-------:|-----------:|--------------:|
| 20 000   | 47 k  | 113 MB | ~1.1 GB    | ~1.3 h        |
| 100 000  | 240 k | 570 MB | ~5.4 GB    | ~6.6 h        |
| 1 000 000| 2.4 M | 5.7 GB | ~54 GB     | ~2.8 days     |

Indexing time assumes **10 sections/s**, measured against the live embedding
service (ONNX int8 e5-base; `ResourceValidator` gives it 2 workers on 8 cores).
It is the pipeline's bottleneck — NER measures 19.5 docs/s and runs in parallel.
Solr size assumes **22.7 KB/section**, measured across the three demo cores.

A million articles is a CPU and RAM problem long before it is a disk problem:
2.4 M vectors are ~7 GB that kNN wants in page cache, and the test VM has 16 GB
shared with Solr, the JVM and Ollama. Pointing `embedding.pythonUrl` at a
GPU-hosted copy of the service is what makes that size practical.

## Notes

- Downloads are cached in `dist/cache/`; the pinned shards are HEAD-checked at
  startup so a re-shard of the dataset fails fast instead of half-way through.
- The corpus is nested `NN/MM/` at 200 files per directory: the scanner walks the
  whole tree every cycle over SMB, and one flat 20 000-file directory is the
  slowest possible shape for that.
- Re-running the same command renders nothing; deleting files and re-running
  regenerates exactly those. `_manifest.csv` (not an indexed extension) records
  path, pages and bytes for every file.
- The HF dataset strips wikitext bold markers and takes the apostrophe with them
  in some lead sentences ("LArmée royale hongroise"). Cosmetic, and irrelevant to
  a retrieval scale test.
