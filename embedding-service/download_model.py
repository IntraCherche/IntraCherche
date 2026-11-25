#!/usr/bin/env python
"""
Download the INT8 ONNX embedding model for offline / air-gapped deployment.

Run ONCE on a machine that has internet access:
    uv run python download_model.py

Then transfer the resulting model/ directory to the production server
(alongside the rest of embedding-service/).  app.py loads from model/
automatically when it exists, and falls back to Hugging Face Hub otherwise.
"""
import pathlib
from huggingface_hub import snapshot_download
from model_pin import MODEL_NAME, MODEL_REVISION

ONNX_FILE  = "onnx/model_qint8_avx512_vnni.onnx"
OUT_DIR    = pathlib.Path(__file__).parent / "model"

if OUT_DIR.exists() and any(OUT_DIR.rglob("*.onnx")):
    print(f"Model already present in {OUT_DIR} — nothing to do.")
else:
    print(f"Downloading {MODEL_NAME} @ {MODEL_REVISION[:12]} ({ONNX_FILE}) …")
    snapshot_download(MODEL_NAME, revision=MODEL_REVISION, local_dir=str(OUT_DIR),
                      allow_patterns=["*.json", ONNX_FILE])
    saved = list(OUT_DIR.rglob("*.onnx"))
    print(f"Saved to {OUT_DIR}  ({saved[0].name if saved else 'no .onnx found — check output'})")
