import os

# Must be set before any ONNX/OpenMP library is imported.
# OMP_WAIT_POLICY=passive: OpenMP threads sleep instead of spin when idle.
# OMP_NUM_THREADS=1: single OpenMP thread; session options alone don't prevent
# the global thread pool from being initialised with the host CPU count.
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("OMP_WAIT_POLICY", "passive")

import subprocess
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import PlainTextResponse, JSONResponse
from fastapi.exceptions import RequestValidationError

from rapidocr import RapidOCR

import asyncio
import io
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, UnidentifiedImageError
import numpy as np

DEDICATED_CORES_FOR_OCR = 1

# "cuda" on a GPU node (the GPU node setup sets it), "cpu" everywhere else.
OCR_DEVICE = os.environ.get("OCR_DEVICE", "cpu").strip().lower()
if OCR_DEVICE == "cuda":
    # The CUDA and cuDNN libraries come as pip packages (onnxruntime-gpu[cuda,cudnn]), which
    # onnxruntime does not find on its own: load them before the first session is created.
    import onnxruntime
    onnxruntime.preload_dlls()

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

# OCR engine. The models are bundled under models/ and passed by path: given no
# path, RapidOCR downloads its defaults on first use, which fails on an
# air-gapped server. Recognition uses the Latin-script model: the Chinese one
# (RapidOCR's default) drops the spaces between French words, strips accents
# and reads l' as I'. The detection and orientation models are script-agnostic.
engine = RapidOCR(params={
    "Global.log_level": "error",  # "warning" logs every blank page
    "EngineConfig.onnxruntime.intra_op_num_threads": DEDICATED_CORES_FOR_OCR,
    "EngineConfig.onnxruntime.inter_op_num_threads": DEDICATED_CORES_FOR_OCR,
    "EngineConfig.onnxruntime.use_cuda": OCR_DEVICE == "cuda",
    "Det.model_path": "models/multi_PP-OCRv6_det_small.onnx",
    "Cls.model_path": "models/ch_ppocr_mobile_v2.0_cls_infer.onnx",
    "Rec.model_path": "models/latin_PP-OCRv5_rec_mobile.onnx",
})


def _device() -> str:
    """Where the models actually run: "cuda" only when all three got the CUDA provider.

    onnxruntime falls back to the processor with no more than a warning when CUDA cannot be
    loaded (wrong CUDA major version for the driver, missing cuDNN), so asking for it is not
    enough: /health reports what each session holds, and the GPU node setup refuses "cpu".
    """
    sessions = (engine.text_det, engine.text_cls, engine.text_rec)
    providers = [s.session.session.get_providers()[0] for s in sessions]
    return "cuda" if all(p == "CUDAExecutionProvider" for p in providers) else "cpu"


DEVICE = _device()

# Single-thread executor: serialises OCR calls so the ONNX engine is never
# used from two threads at once and the asyncio event loop is never blocked.
_ocr_executor = ThreadPoolExecutor(max_workers=1)


def _decode_image(contents: bytes) -> np.ndarray:
    """Raises ValueError (-> 400) when `contents` isn't a decodable image, not an engine error."""
    try:
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except (UnidentifiedImageError, OSError) as e:
        raise ValueError(f"not a decodable image: {e}") from e
    image.thumbnail((2500, 2500))
    return np.array(image)


def _run_ocr_sync(contents: bytes) -> str:
    img_np = _decode_image(contents)
    result = engine(img_np)

    return "\n".join(result.txts or ())


@app.get("/health")
async def health():
    return {"status": "ok", "version": SERVICE_VERSION, "device": DEVICE}


@app.post("/ocr", response_class=PlainTextResponse)
async def perform_ocr(request: Request):
    contents = await request.body()

    if not contents:
        return ""

    loop = asyncio.get_event_loop()
    try:
        full_text = await loop.run_in_executor(_ocr_executor, _run_ocr_sync, contents)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return full_text


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()}
    )