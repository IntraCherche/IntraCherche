"""Mean-pooled, L2-normalised sentence embeddings straight from the quantised ONNX file.

This replaces SentenceTransformer(backend="onnx"): that backend needs `optimum`, which
caps `transformers` below 4.58 and so blocks every security fix released since. Only
`tokenizers` and `onnxruntime` are needed here, and the vectors are the same — same ONNX
graph, same tokenizer, same pooling — so an existing index stays valid.
"""
import os

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

MAX_SEQ_LENGTH = 512  # multilingual-e5-base: 514 positions minus the two reserved ones
BATCH_SIZE = 32


class OnnxEmbedder:
    def __init__(self, model_dir: str, onnx_file: str):
        self.tokenizer = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=MAX_SEQ_LENGTH)
        self.tokenizer.enable_padding(pad_id=1, pad_token="<pad>")  # XLM-R pad; to the longest of each batch
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1  # parallelism comes from Java-managed worker processes
        self.session = ort.InferenceSession(
            os.path.join(model_dir, onnx_file), opts, providers=["CPUExecutionProvider"])
        self._inputs = {i.name for i in self.session.get_inputs()}
        self.dimension = self.session.get_outputs()[0].shape[-1]

    def encode(self, texts: list[str]) -> np.ndarray:
        # Longest last, like sentence-transformers, so padding waste stays small.
        order = sorted(range(len(texts)), key=lambda i: len(texts[i]))
        out = np.empty((len(texts), self.dimension), dtype=np.float32)
        for start in range(0, len(order), BATCH_SIZE):
            idx = order[start:start + BATCH_SIZE]
            out[idx] = self._encode_batch([texts[i] for i in idx])
        return out

    def _encode_batch(self, texts: list[str]) -> np.ndarray:
        enc = self.tokenizer.encode_batch(texts)
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
        feed = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self._inputs:
            feed["token_type_ids"] = np.zeros_like(ids)
        hidden = self.session.run(None, {k: v for k, v in feed.items() if k in self._inputs})[0]
        m = mask[..., None].astype(np.float32)
        pooled = (hidden * m).sum(axis=1) / np.clip(m.sum(axis=1), 1e-9, None)
        return pooled / np.clip(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-12, None)
