# The reranker model on Hugging Face, pinned to one commit so that every download — the
# distribution build, a dev machine, the Hub fallback in app.py — gets the same weights.
# To move to a newer commit: update the hash, re-run download_model.py on an empty model/,
# and re-run the Sherlock retrieval eval before shipping.
MODEL_NAME = "BAAI/bge-reranker-v2-m3"
MODEL_REVISION = "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e"
