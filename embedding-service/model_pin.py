# The embedding model on Hugging Face, pinned to one commit so that every download — the
# distribution build, a dev machine, the Hub fallback in app.py — gets the same weights.
# To move to a newer commit: update the hash, re-run download_model.py on an empty model/,
# and reindex — vectors from two model versions do not mix.
MODEL_NAME = "intfloat/multilingual-e5-base"
MODEL_REVISION = "d128750597153bb5987e10b1c3493a34e5a4502a"
