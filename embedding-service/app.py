import os

# Must be set before any ONNX/OpenMP library is imported.
# OMP_WAIT_POLICY=passive: OpenMP threads sleep instead of spin when idle.
# OMP_NUM_THREADS=1: each worker process uses exactly one core; parallelism
# comes from multiple worker processes (managed by Java), not internal threading.
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("OMP_WAIT_POLICY", "passive")
os.environ.setdefault("HF_HUB_OFFLINE", "1")

import subprocess
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from concurrent.futures import ThreadPoolExecutor
import asyncio
from onnx_embedder import OnnxEmbedder
import model_pin

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", model_pin.MODEL_NAME)
# The pin only applies to the Hub id it was taken from; a local path has no revision.
_REVISION = model_pin.MODEL_REVISION if MODEL_NAME == model_pin.MODEL_NAME else None
ONNX_FILE  = os.environ.get("EMBEDDING_ONNX_FILE", "onnx/model_qint8_avx512_vnni.onnx")
MAX_TEXT_CHARS = 8192


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


def _model_dir() -> str:
    """The model directory: EMBEDDING_MODEL when it is a path, else the pinned Hub snapshot."""
    if os.path.isdir(MODEL_NAME):
        return MODEL_NAME
    from huggingface_hub import snapshot_download
    return snapshot_download(MODEL_NAME, revision=_REVISION)

app = FastAPI()
model = OnnxEmbedder(_model_dir(), ONNX_FILE)

# Single-worker executor: prevents threading conflicts in the inference backend
_embed_executor = ThreadPoolExecutor(max_workers=1)


def _run_embed_sync(text: str) -> list[float]:
    return model.encode([text])[0].tolist()


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "onnx_file": ONNX_FILE,
        "dims": model.dimension,
        "version": SERVICE_VERSION,
    }


@app.post("/embed")
async def embed(request: Request):
    body = await request.json()
    text = body.get("text", "")
    is_query = body.get("is_query", False)

    if not text or not text.strip():
        return JSONResponse({"vector": []})

    # multilingual-e5 requires these prefixes for retrieval quality
    prefix = "query: " if is_query else "passage: "
    prefixed = prefix + text[:MAX_TEXT_CHARS]

    loop = asyncio.get_event_loop()
    vector = await loop.run_in_executor(_embed_executor, _run_embed_sync, prefixed)
    return JSONResponse({"vector": vector})


@app.post("/embed-batch")
async def embed_batch(request: Request):
    body = await request.json()
    texts = body.get("texts", [])
    is_query = body.get("is_query", False)
    prefix = "query: " if is_query else "passage: "

    # Track non-blank entries by original index to preserve position mapping
    to_encode = [(i, prefix + t[:MAX_TEXT_CHARS]) for i, t in enumerate(texts) if t and t.strip()]

    if not to_encode:
        return JSONResponse({"vectors": [None] * len(texts)})

    indices, prefixed_texts = zip(*to_encode)
    loop = asyncio.get_event_loop()
    encoded = await loop.run_in_executor(
        _embed_executor,
        lambda: model.encode(list(prefixed_texts)).tolist(),
    )

    result = [None] * len(texts)
    for idx, vec in zip(indices, encoded):
        result[idx] = vec
    return JSONResponse({"vectors": result})
