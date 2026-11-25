#!/usr/bin/env python
"""
One-time INT8 ONNX export. Run once from the embedding-service/ directory:
    uv run --no-project --with sentence-transformers --with "optimum[onnxruntime]" python quantize.py

Output: model_int8/ — loaded automatically by app.py on next start.
"""
import os
import pathlib
from sentence_transformers import SentenceTransformer
from sentence_transformers.backend import export_dynamic_quantized_onnx_model

MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "intfloat/multilingual-e5-base")
OUT_DIR = pathlib.Path(__file__).parent / "model_int8"

existing = list(OUT_DIR.glob("*.onnx")) if OUT_DIR.exists() else []
if existing:
    print(f"INT8 model already present at {OUT_DIR}/{existing[0].name} — nothing to do.")
else:
    print("Loading FP32 ONNX model…")
    model = SentenceTransformer(MODEL_NAME, backend="onnx")
    print("Quantizing to INT8 (avx2 dynamic)…")
    export_dynamic_quantized_onnx_model(model, "avx2", str(OUT_DIR))
    exported = list(OUT_DIR.glob("*.onnx"))
    print(f"Done — {OUT_DIR}/{exported[0].name}" if exported else "Warning: no .onnx found in output dir")
