"""
Download Sidecar models from ModelScope into local project-mounted directories.

This script is used by both:
- scripts/ensure-sidecar-models.sh on the host
- the docker-compose model-init one-shot service
"""
from __future__ import annotations

import os
import time
from pathlib import Path

from modelscope import snapshot_download


MODELS = {
    "embedding": "iic/nlp_gte_sentence-embedding_chinese-base",
    "rerank": "BAAI/bge-reranker-v2-m3",
}


def safe_model_dir(model_id: str) -> str:
    return model_id.replace("/", "__")


def model_dir(models_home: Path, model_id: str) -> Path:
    return models_home / safe_model_dir(model_id)


def has_model_files(path: Path) -> bool:
    if not path.is_dir():
        return False
    if any(path.rglob("*.incomplete")):
        return False
    weight_names = {"pytorch_model.bin", "model.safetensors", "model.bin", "vectors.txt", "vectors.kv"}
    return any(file.name in weight_names or file.suffix in {".bin", ".safetensors"} for file in path.rglob("*") if file.is_file())


def main() -> None:
    models_home = Path(os.environ.get("MODELSCOPE_CACHE", "/models/modelscope")).expanduser().resolve()
    models_home.mkdir(parents=True, exist_ok=True)

    print(f"MODELSCOPE_CACHE={models_home}")

    for role, model_id in MODELS.items():
        target_dir = model_dir(models_home, model_id)
        if has_model_files(target_dir):
            print(f"[ok] {role}: {model_id} already exists at {target_dir}")
            continue

        print(f"[download] {role}: {model_id} -> {target_dir}")
        for attempt in range(1, 6):
            try:
                snapshot_download(
                    model_id=model_id,
                    local_dir=str(target_dir),
                )
                break
            except Exception:
                if attempt == 5:
                    raise
                print(f"[retry] {role}: {model_id} ({attempt}/5)")
                time.sleep(10)
        print(f"[ok] {role}: {model_id} downloaded")

    print("All sidecar models are ready.")


if __name__ == "__main__":
    main()
