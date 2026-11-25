import os

# Must be set before any ONNX/OpenMP/PyTorch library is imported.
# OMP_WAIT_POLICY=passive: OpenMP threads sleep instead of spin when idle.
# RERANKER_THREADS (set by Java, default 1): unlike the indexing services, the reranker
# serves one Sherlock question at a time, so extra worker processes sit idle while a
# single request pins one core. Threads inside the process are what make it faster.
THREADS = max(1, int(os.environ.get("RERANKER_THREADS", "1")))
os.environ.setdefault("OMP_NUM_THREADS", str(THREADS))
os.environ.setdefault("OMP_WAIT_POLICY", "passive")
os.environ.setdefault("MKL_NUM_THREADS", str(THREADS))
os.environ.setdefault("HF_HUB_OFFLINE", "1")

import subprocess
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from concurrent.futures import ThreadPoolExecutor
import asyncio
from sentence_transformers import CrossEncoder
import torch
torch.set_num_threads(THREADS)
import model_pin

MODEL_NAME = os.environ.get("RERANKER_MODEL", model_pin.MODEL_NAME)
# Load from local model/ directory if present (air-gapped deployment), otherwise from HF Hub.
_LOCAL_MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model")
_MODEL_PATH = _LOCAL_MODEL if os.path.isdir(_LOCAL_MODEL) else MODEL_NAME
# The pin only applies to the Hub id it was taken from; a local path has no revision.
_REVISION = model_pin.MODEL_REVISION if _MODEL_PATH == model_pin.MODEL_NAME else None
# Java sends reranker.candidates passages per question and refuses a reply that does not score
# every one of them. A cap below that number does not trim the request, it silently turns
# reranking off for the whole question — so the cap follows the configured candidate count
# (RERANKER_MAX_PASSAGES), with a ceiling that still protects the machine from a stray request.
MAX_PASSAGES = min(1000, max(1, int(os.environ.get("RERANKER_MAX_PASSAGES", "50"))))
# Passages per forward pass. 8 suits a CPU, where threads do the work and a larger batch only
# grows the memory spike; a GPU is under-fed at 8 and gets several times the throughput at 32+.
# Unset: chosen below, once the device is known.
_BATCH_SIZE_ENV = os.environ.get("RERANKER_BATCH_SIZE", "").strip()
# Half precision on a GPU: about twice the throughput for half the memory. Scores move in the
# third decimal, and Sherlock only compares scores within one question — it has no fixed
# threshold — so the ranking is what matters. RERANKER_FP16=0 keeps full precision.
FP16_WANTED = os.environ.get("RERANKER_FP16", "1") != "0"


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

app = FastAPI()
model = CrossEncoder(_MODEL_PATH, max_length=512, revision=_REVISION)
ON_GPU = model.model.device.type == "cuda"
BATCH_SIZE = max(1, int(_BATCH_SIZE_ENV)) if _BATCH_SIZE_ENV else (32 if ON_GPU else 8)
FP16 = FP16_WANTED and ON_GPU
if FP16:
    model.model.half()
print(f"Reranker on {model.model.device}{' (fp16)' if FP16 else ''}, batch {BATCH_SIZE}, "
      f"at most {MAX_PASSAGES} passages", flush=True)

# Single-worker executor: cross-encoders are not thread-safe during inference
_rerank_executor = ThreadPoolExecutor(max_workers=1)


def _run_rerank_sync(query: str, passages: list[str]) -> list[float]:
    pairs = [[query, p] for p in passages]
    scores = model.predict(pairs, batch_size=BATCH_SIZE)
    return [float(s) for s in scores]


def _gpu_info() -> dict:
    if not ON_GPU:
        return {}
    device = model.model.device
    free, total = torch.cuda.mem_get_info(device)
    return {"gpu": torch.cuda.get_device_name(device),
            "vram_free_mb": free // 2**20, "vram_total_mb": total // 2**20}


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL_NAME, "version": SERVICE_VERSION,
            "max_passages": MAX_PASSAGES, "batch_size": BATCH_SIZE, "threads": THREADS,
            "device": str(model.model.device), "fp16": FP16,
            **_gpu_info()}


@app.post("/rerank")
async def rerank(request: Request):
    body = await request.json()
    query = body.get("query", "")
    passages = body.get("passages", [])

    if not query or not passages:
        return JSONResponse({"scores": []})

    if len(passages) > MAX_PASSAGES:
        # Caller-visible: scoring fewer than asked makes the caller discard the whole reply.
        return JSONResponse(
            {"detail": f"{len(passages)} passages requested, cap is {MAX_PASSAGES} "
                       f"(raise RERANKER_MAX_PASSAGES to match reranker.candidates)"},
            status_code=413)

    loop = asyncio.get_event_loop()
    scores = await loop.run_in_executor(_rerank_executor, _run_rerank_sync, query, passages)
    return JSONResponse({"scores": scores})
