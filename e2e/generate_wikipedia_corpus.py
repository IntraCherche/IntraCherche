#!/usr/bin/env -S uv run --quiet --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyarrow>=17", "reportlab>=4"]
# ///
"""
Build a large French Wikipedia corpus of text-only PDFs, to test IntraCherche
and Sherlock at a scale the demo corpus cannot reach.

    uv run e2e/generate_wikipedia_corpus.py --count 20000 --out /path/to/VM_Shared/corpus

Why PDFs and not .txt: a PDF is the only format the pipeline splits **per page**
(PdfPageExtractor -> one IndexedSection, one Solr doc and one 768-dim vector per
page). Pages are therefore the unit of cost, and generating PDFs exercises the
code path that a customer's share actually hits.

Why generated, not printed from a browser: the demo Wikipedia PDFs average
2.4 MB because they carry images, and printing 20 000 of them through
LibreOffice would take longer than indexing them. ReportLab writes a text-only
PDF in ~14 ms (~5 KB), so the whole corpus is built in minutes and weighs about
100 MB. Image-free also keeps PdfPageExtractor from mistaking a page for a
scanned one and queueing it for OCR, which would wreck the timings.

Layout is tuned to ~2000 chars/page (measured with pdftotext), the density of a
Wikipedia article printed from a browser. That also keeps a page under the 512
tokens multilingual-e5-base reads, so no section is embedded only in part.

Source: the `wikimedia/wikipedia` 20231101.fr dataset on HuggingFace, pinned
parquet shards cached under dist/cache/ and downloaded only as far as --count
needs. Articles are taken in file order, so two runs with the same arguments
give the same corpus.

The run is resumable and idempotent: a PDF that exists and has a manifest row is
left alone, so an interrupted build continues where it stopped and re-running
the same command renders nothing.
"""
from __future__ import annotations

import argparse
import csv
import os
import re
import shutil
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from xml.sax.saxutils import escape

# --- Pinned source ----------------------------------------------------------
# The four smallest shards of the 17, smallest first; each holds ~150k articles
# of which ~38% clear --min-chars, so one shard covers ~57k documents and the
# next is only downloaded when the previous runs out. Re-pin from:
#   curl -s https://huggingface.co/api/datasets/wikimedia/wikipedia/tree/main/20231101.fr
BASE_URL = "https://huggingface.co/datasets/wikimedia/wikipedia/resolve/main/20231101.fr/"
SHARDS: list[tuple[str, int]] = [
    ("train-00011-of-00017.parquet", 168_714_731),
    ("train-00015-of-00017.parquet", 185_553_661),
    ("train-00016-of-00017.parquet", 185_595_041),
    ("train-00008-of-00017.parquet", 204_905_052),
]

# --- Measured constants, used only for the closing projection ---------------
# Embedding throughput of the ONNX int8 e5-base service on the dev laptop
# (8 cores -> ResourceValidator allocates 2 embedding workers).
EMBED_SECTIONS_PER_SEC = 10.0
# Solr bytes per section, measured across the three live demo cores
# (927 docs / 21.6 MB), vector + stored content + BM25 + content_exact included.
SOLR_KB_PER_SECTION = 22.7
WINDOWS_RESERVED = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}


# --- Source ------------------------------------------------------------------

def check_source() -> None:
    """Fail before anything else if the pinned shards have moved."""
    name, expected = SHARDS[0]
    req = urllib.request.Request(BASE_URL + name, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            size = int(r.headers.get("x-linked-size") or r.headers.get("content-length") or 0)
    except urllib.error.URLError as e:
        sys.exit(f"[source] unreachable: {BASE_URL + name}\n         {e}\n"
                 f"         The dataset may have been re-sharded — re-pin SHARDS.")
    if size != expected:
        print(f"[source] warning: {size} bytes, expected {expected} — shard may have changed")


def fetch_shard(name: str, expected: int, cache_dir: Path) -> Path:
    """Download one shard into dist/cache/, or reuse a complete earlier download."""
    cache_dir.mkdir(parents=True, exist_ok=True)
    dest = cache_dir / name
    if dest.exists() and dest.stat().st_size == expected:
        print(f"[source] cached  {dest} ({expected / 1e6:.0f} MB)")
        return dest

    part = dest.with_suffix(dest.suffix + ".part")
    print(f"[source] downloading {name} ({expected / 1e6:.0f} MB)")
    tty, t0, done = sys.stdout.isatty(), time.time(), 0
    with urllib.request.urlopen(BASE_URL + name, timeout=60) as r, part.open("wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if tty:
                print(f"\r[source] {done / 1e6:6.0f} / {expected / 1e6:.0f} MB "
                      f"{100 * done / expected:5.1f}%", end="")
    if tty:
        print("\r" + " " * 40, end="")
    print(f"\r[source] downloaded {done / 1e6:.0f} MB in {time.time() - t0:.0f}s")
    part.rename(dest)
    return dest


# --- Selection ---------------------------------------------------------------

def slugify(title: str, used: set[str]) -> str:
    """A filename a Windows share will accept, keeping French accents."""
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', " ", title)
    # A leading dot ("...explosante-fixe" is a real article) would make a hidden
    # file that shell globs and ls skip, so the corpus would look short when you
    # count it against what got indexed.
    name = re.sub(r"\s+", " ", name).strip(". ")
    name = name[:120].rstrip(". ") or "article"
    if name.split(".")[0].upper() in WINDOWS_RESERVED:
        name = f"_{name}"
    candidate, n = name, 1
    while candidate.lower() in used:
        n += 1
        candidate = f"{name} ({n})"
    used.add(candidate.lower())
    return candidate


def trim(text: str, max_chars: int) -> str:
    """Cut over-long articles at a paragraph boundary rather than mid-sentence."""
    if len(text) <= max_chars:
        return text
    cut = text.rfind("\n\n", 0, max_chars)
    return text[: cut if cut > max_chars // 2 else max_chars]


def select(cache: Path, count: int, skip: int, min_chars: int, max_chars: int):
    """Yield (title, text) for usable articles, spanning shards as needed."""
    import pyarrow.parquet as pq

    seen = kept = 0
    for name, expected in SHARDS:
        shard = fetch_shard(name, expected, cache)
        print(f"[select] reading {name}")
        pf = pq.ParquetFile(shard)
        for batch in pf.iter_batches(batch_size=2048, columns=["title", "text"]):
            titles = batch.column("title").to_pylist()
            texts = batch.column("text").to_pylist()
            for title, text in zip(titles, texts):
                if not title or not text or len(text) < min_chars:
                    continue
                seen += 1
                if seen <= skip:
                    continue
                yield title, trim(text, max_chars)
                kept += 1
                if kept >= count:
                    return
    raise SystemExit(f"[select] all {len(SHARDS)} shards exhausted after {kept} articles "
                     f"(wanted {count}) — lower --min-chars or pin another shard in SHARDS")


# --- Rendering ---------------------------------------------------------------

_styles = None


def _init_styles(font: str) -> None:
    """Build the paragraph styles once per worker process."""
    global _styles
    from reportlab.lib.enums import TA_JUSTIFY
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import cm

    body_font, bold_font = "Helvetica", "Helvetica-Bold"
    if font == "dejavu":
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        root = Path("/usr/share/fonts/truetype/dejavu")
        pdfmetrics.registerFont(TTFont("DejaVu", root / "DejaVuSans.ttf"))
        pdfmetrics.registerFont(TTFont("DejaVu-Bold", root / "DejaVuSans-Bold.ttf"))
        body_font, bold_font = "DejaVu", "DejaVu-Bold"

    # 11pt/15pt on A4 with 2 cm margins lands at ~3000 chars/page, the density of
    # a Wikipedia article printed from a browser.
    _styles = {
        "title": ParagraphStyle("t", fontName=bold_font, fontSize=17, leading=21, spaceAfter=14),
        "body": ParagraphStyle("b", fontName=body_font, fontSize=11, leading=15,
                               spaceAfter=7, alignment=TA_JUSTIFY),
        "margin": 2 * cm,
        "ascii_only": font != "dejavu",
    }


def _latin1(text: str) -> str:
    """Drop what Helvetica's WinAnsi encoding cannot show, transliterating first.

    French keeps all its accents; a Greek etymology or a CJK name is stripped.
    That costs nothing for a French retrieval test and saves embedding a font
    subset in every one of the 20 000 files.
    """
    if text.isascii():
        return text
    out = []
    for ch in text:
        try:
            ch.encode("cp1252")
            out.append(ch)
        except UnicodeEncodeError:
            folded = unicodedata.normalize("NFKD", ch)
            out.append("".join(c for c in folded if c.isascii()))
    return "".join(out)


def render(job: tuple[str, str, str]) -> tuple[str, int, int]:
    """Write one PDF. Returns (path, bytes, pages). Runs in a worker process."""
    path_s, title, text = job
    from reportlab.lib.pagesizes import A4
    from reportlab.platypus import Paragraph, SimpleDocTemplate

    if _styles["ascii_only"]:
        title, text = _latin1(title), _latin1(text)

    path = Path(path_s)
    doc = SimpleDocTemplate(
        str(path), pagesize=A4, title=title, author="Wikipédia",
        subject="Corpus de test IntraCherche",
        leftMargin=_styles["margin"], rightMargin=_styles["margin"],
        topMargin=_styles["margin"], bottomMargin=_styles["margin"],
    )
    flow = [Paragraph(escape(title), _styles["title"])]
    for para in text.split("\n"):
        para = para.strip()
        if para:
            flow.append(Paragraph(escape(para), _styles["body"]))
    doc.build(flow)
    return path_s, path.stat().st_size, doc.page


def _worker_init(font: str) -> None:
    _init_styles(font)


# --- Layout ------------------------------------------------------------------

def target_path(out: Path, idx: int, slug: str, files_per_dir: int) -> Path:
    """Nest the corpus instead of dropping 20 000 files in one folder.

    The scanner walks the whole tree every cycle (cycleIntervalMinutes: 1) over
    SMB, and a single huge directory is the slowest possible shape for that.
    """
    sub = (idx // files_per_dir) % 100
    top = idx // (files_per_dir * 100)
    return out / f"{top:02d}" / f"{sub:02d}" / f"{slug}.pdf"


# --- Main --------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", type=Path, required=True, help="corpus root (created if absent)")
    p.add_argument("--count", type=int, default=20_000, help="articles to generate (default 20000)")
    p.add_argument("--skip", type=int, default=0, help="skip this many usable articles first")
    p.add_argument("--min-chars", type=int, default=1500,
                   help="drop stubs shorter than this (default 1500, about one page)")
    p.add_argument("--max-chars", type=int, default=60_000,
                   help="trim articles longer than this (default 60000, about 20 pages)")
    p.add_argument("--files-per-dir", type=int, default=200, help="files per leaf directory")
    p.add_argument("--font", choices=("helvetica", "dejavu"), default="helvetica",
                   help="helvetica: no embedded font, smallest files. dejavu: full Unicode")
    p.add_argument("--jobs", type=int, default=os.cpu_count(), help="rendering processes")
    p.add_argument("--seed-dir", type=Path,
                   help="copy this folder into the corpus (your own ground-truth documents) "
                        "so the ground-truth documents share a core with the distractors")
    p.add_argument("--cache", type=Path, default=Path("dist/cache"), help="download cache")
    args = p.parse_args()

    check_source()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.seed_dir:
        dest = args.out / "_seed"
        print(f"[seed]   copying {args.seed_dir} -> {dest}")
        shutil.copytree(args.seed_dir, dest, dirs_exist_ok=True)

    # A file counts as done only when the manifest also has its row, so a resumed
    # run can never report a corpus smaller than what is on disk. The price of a
    # lost manifest is a full re-render, which costs minutes.
    manifest = args.out / "_manifest.csv"   # .csv is not an indexed extension
    corpus: dict[str, tuple[int, int]] = {}
    if manifest.exists():
        with manifest.open(newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                corpus[row["path"]] = (int(row["pages"]), int(row["bytes"]))

    used: set[str] = set()
    jobs, skipped = [], 0
    for idx, (title, text) in enumerate(
            select(args.cache, args.count, args.skip, args.min_chars, args.max_chars)):
        path = target_path(args.out, idx, slugify(title, used), args.files_per_dir)
        rel = os.path.relpath(path, args.out)
        if rel in corpus and path.exists() and path.stat().st_size > 0:
            skipped += 1
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        jobs.append((str(path), title, text))

    if skipped:
        print(f"[render] {skipped} already present, resuming")
    print(f"[render] {len(jobs)} PDFs with {args.jobs} processes")

    run_bytes, run_pages = 0, 0
    t0 = time.time()
    with ProcessPoolExecutor(max_workers=args.jobs,
                             initializer=_worker_init, initargs=(args.font,)) as pool:
        for n, (path_s, size, pages) in enumerate(pool.map(render, jobs, chunksize=64), 1):
            run_bytes += size
            run_pages += pages
            corpus[os.path.relpath(path_s, args.out)] = (pages, size)
            if n % 500 == 0 or n == len(jobs):
                rate = n / (time.time() - t0)
                print(f"\r[render] {n}/{len(jobs)}  {rate:.0f} PDF/s  "
                      f"{run_bytes / 1e6:.0f} MB  {run_pages} pages", end="")
    print()

    # Forget rows whose file has gone, so the totals below describe what is
    # really on the share rather than what some earlier run left behind.
    corpus = {rel: v for rel, v in corpus.items() if (args.out / rel).exists()}

    with manifest.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["path", "pages", "bytes"])
        w.writerows((path, pages, size) for path, (pages, size) in sorted(corpus.items()))

    sections = sum(pages for pages, _ in corpus.values())
    total_bytes = sum(size for _, size in corpus.values())
    embed_h = sections / EMBED_SECTIONS_PER_SEC / 3600
    solr_gb = sections * SOLR_KB_PER_SECTION / 1e6
    print(f"""
[done]   rendered {len(jobs)} PDFs in {time.time() - t0:.0f}s
         corpus: {len(corpus)} PDFs, {total_bytes / 1e6:.0f} MB on disk
         manifest: {manifest}

         {sections} pages -> {sections} Solr docs, one 768-dim vector each
         projected Solr index : {solr_gb:.1f} GB   ({SOLR_KB_PER_SECTION} KB/section, measured)
         projected indexing   : {embed_h:.1f} h    ({EMBED_SECTIONS_PER_SEC:.0f} sections/s, measured on the dev laptop)
         vectors in RAM       : {sections * 3 / 1e6:.2f} GB  (kNN stays fast only while this fits in page cache)
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
