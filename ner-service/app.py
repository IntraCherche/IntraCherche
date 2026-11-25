import os

# Must be set before any ONNX/OpenMP library is imported.
# OMP_WAIT_POLICY=passive: OpenMP threads sleep instead of spin when idle.
# OMP_NUM_THREADS=1: each worker process uses exactly one core; parallelism
# comes from multiple worker processes (managed by Java), not internal threading.
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("OMP_WAIT_POLICY", "passive")

import subprocess
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from concurrent.futures import ThreadPoolExecutor
import asyncio
import spacy

from gazetteer import (
    classify, normalize, is_plausible_place, city_department, _accent_fold,
    DEPARTMENTS, REGIONS, COUNTRIES,
)

# NER model used for geographic entity extraction.
# Limitation: statistical NER models underperform on two classes of text:
#   1. Reference/footnote pages where location names appear inside URLs or
#      fragmented across line breaks — the model sees no valid token context.
#   2. Documents entirely about a single city (e.g. "Attentats de Paris"):
#      high-frequency mentions of the topic city are often left untagged because
#      the model treats them as background rather than named entities.
# Consequence: prominent cities like Paris may be absent from geolocation fields
# even when the document is clearly about them.
# Mitigation options: switch to fr_core_news_lg (better recall), or add a
# curated whitelist of major unambiguous cities for gazetteer-based fallback.
# See test_ner_models.py for a reproducible comparison on a known problematic page.
NER_MODEL = os.environ.get("NER_MODEL", "fr_core_news_md")
NER_LABELS = {"GPE", "LOC"}
MAX_TEXT_CHARS = 100_000


def _git_version() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL,
            cwd=os.path.dirname(os.path.abspath(__file__)),
        ).decode().strip()
    except Exception:
        return "unknown"


SERVICE_VERSION = _git_version()


def _sorted_fr(names) -> list[str]:
    # Accent-folded key so "Île-de-France" sorts under I rather than after "Vendée".
    return sorted(names, key=_accent_fold)


# Every country, region and department name /ner can emit. The search UI offers
# these as its geo filter choices, so a filter can't silently miss on a typo.
GAZETTEER = {
    "countries": _sorted_fr(COUNTRIES),
    "regions": _sorted_fr(REGIONS),
    "departments": [{"name": d, "region": DEPARTMENTS[d]} for d in _sorted_fr(DEPARTMENTS)],
}

app = FastAPI()

nlp = spacy.load(NER_MODEL, disable=["parser", "senter", "morphologizer", "attribute_ruler", "lemmatizer"])

_ner_executor = ThreadPoolExecutor(max_workers=1)


def _infer_country(seen: set, countries: list) -> None:
    if "France" not in seen:
        seen.add("France")
        countries.append("France")


def _infer_region(dept: str, seen: set, regions: list, countries: list) -> None:
    region = DEPARTMENTS.get(dept)
    if region and region not in seen:
        seen.add(region)
        regions.append(region)
    _infer_country(seen, countries)


def _infer_department(city: str, seen: set, departments: list, regions: list, countries: list) -> None:
    dept = city_department(city)
    # Use a prefixed key so that "Paris" (city) in seen does not block "Paris" (dept 75),
    # which share the same string — the only such case in French administrative geography.
    dept_key = f"dept:{dept}" if dept else None
    if dept and dept_key not in seen:
        seen.add(dept_key)
        departments.append(dept)
    _infer_region(dept or city, seen, regions, countries)


def _run_ner_sync(text: str) -> dict:
    doc = nlp(text[:MAX_TEXT_CHARS])

    cities: list[str] = []
    departments: list[str] = []
    regions: list[str] = []
    countries: list[str] = []
    seen: set[str] = set()

    for ent in doc.ents:
        if ent.label_ not in NER_LABELS:
            continue
        name = normalize(ent.text)
        if not name or name in seen:
            continue
        if not is_plausible_place(name):
            continue
        seen.add(name)

        bucket = classify(name)
        if bucket is None:
            continue
        if bucket == "city":
            cities.append(name)
            _infer_department(name, seen, departments, regions, countries)
        elif bucket == "department":
            departments.append(name)
            _infer_region(name, seen, regions, countries)
        elif bucket == "region":
            regions.append(name)
            _infer_country(seen, countries)
        elif bucket == "country":
            countries.append(name)

    return {
        "cities": cities,
        "departments": departments,
        "regions": regions,
        "countries": countries,
    }


@app.get("/health")
async def health():
    return {"status": "ok", "model": NER_MODEL, "version": SERVICE_VERSION}


@app.get("/gazetteer")
async def get_gazetteer():
    return GAZETTEER


@app.post("/ner")
async def extract_locations(request: Request):
    body = await request.body()
    if not body:
        return JSONResponse({"cities": [], "departments": [], "regions": [], "countries": []})

    text = body.decode("utf-8", errors="replace")
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(_ner_executor, _run_ner_sync, text)
    return JSONResponse(result)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    return JSONResponse(status_code=422, content={"detail": exc.errors()})
