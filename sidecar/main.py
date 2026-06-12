"""
CRAG-Demo Sidecar Model Service.

Provides /embed (gte Chinese embedding, 768-dim) and /rerank (bge-reranker-v2-m3)
endpoints for the Java Spring Boot application.

Run: uvicorn main:app --host 0.0.0.0 --port 8001

@since 2026-06-10
"""
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer, CrossEncoder

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("crag-sidecar")

# ---------------------------------------------------------------------------
# Global model references — loaded eagerly in lifespan
# ---------------------------------------------------------------------------
embedding_model: SentenceTransformer | None = None
rerank_model: CrossEncoder | None = None

# Model status for /health endpoint — updated during lifespan startup
model_status: dict[str, str] = {
    "embedding": "loading",
    "rerank": "loading",
}

# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------

class EmbedRequest(BaseModel):
    """Request body for /embed."""
    text: str = Field(..., min_length=1, max_length=8192,
                      description="Input text to vectorize")


class EmbedResponse(BaseModel):
    """Response body for /embed."""
    embedding: list[float]
    dimension: int


class RerankRequest(BaseModel):
    """Request body for /rerank."""
    query: str = Field(..., min_length=1, max_length=1024,
                       description="User query for relevance scoring")
    documents: list[str] = Field(..., min_length=1, max_length=50,
                                 description="Candidate documents to rerank")


class RerankResultItem(BaseModel):
    """Single rerank result with original index and score."""
    index: int
    score: float


class RerankResponse(BaseModel):
    """Response body for /rerank."""
    results: list[RerankResultItem]


# ---------------------------------------------------------------------------
# Lifespan — eager model loading at startup
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Eager-load both models on startup so the first request doesn't time out.

    Models are loaded from local paths prepared by model-init.
    """
    global embedding_model, rerank_model

    embedding_model_path = os.environ.get(
        "EMBEDDING_MODEL_PATH",
        "/models/modelscope/iic__nlp_gte_sentence-embedding_chinese-base",
    )
    rerank_model_path = os.environ.get(
        "RERANK_MODEL_PATH",
        "/models/modelscope/BAAI__bge-reranker-v2-m3",
    )

    # ---- Load embedding model ----
    try:
        logger.info("Loading embedding model from: %s ...", embedding_model_path)
        embedding_model = SentenceTransformer(
            embedding_model_path
        )
        model_status["embedding"] = "loaded"
        logger.info("Embedding model loaded (dim=%d)", embedding_model.get_sentence_embedding_dimension())
    except Exception:
        logger.exception("Failed to load embedding model")
        model_status["embedding"] = "failed"

    # ---- Load rerank model ----
    try:
        logger.info("Loading rerank model from: %s ...", rerank_model_path)
        rerank_model = CrossEncoder(rerank_model_path)
        model_status["rerank"] = "loaded"
        logger.info("Rerank model loaded")
    except Exception:
        logger.exception("Failed to load rerank model")
        model_status["rerank"] = "failed"

    yield  # application runs here

    # Cleanup (no-op for sentence-transformers; models live until process exit)
    logger.info("Sidecar shutting down")


app = FastAPI(
    title="CRAG Sidecar Model Service",
    version="0.1.0",
    lifespan=lifespan,
)

# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    """
    Health check — reports model load status.

    Returns 200 when all models are loaded, 503 if any model failed.
    Docker Compose healthcheck uses this to gate app startup.
    """
    all_loaded = all(v == "loaded" for v in model_status.values())
    any_failed = any(v == "failed" for v in model_status.values())

    if any_failed:
        http_status = 503
        status = "error"
    elif all_loaded:
        http_status = 200
        status = "ok"
    else:
        http_status = 200
        status = "starting"

    return JSONResponse(
        content={"status": status, "models": dict(model_status)},
        status_code=http_status,
    )


@app.post("/embed", response_model=EmbedResponse)
async def embed(req: EmbedRequest):
    """
    Convert text to a dense vector using gte Chinese embedding.

    Returns a 768-dimensional normalized embedding suitable for
    cosine similarity with pgvector.
    """
    if model_status["embedding"] != "loaded":
        raise HTTPException(
            status_code=503,
            detail={
                "error": "EmbeddingModelNotLoaded",
                "message": f"Embedding model status: {model_status['embedding']}",
            },
        )

    try:
        # normalize_embeddings=True → unit vectors for cosine similarity
        vector = embedding_model.encode(
            req.text,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        return EmbedResponse(
            embedding=vector.tolist(),
            dimension=int(len(vector)),
        )
    except Exception as e:
        logger.exception("Embedding inference failed")
        raise HTTPException(
            status_code=500,
            detail={"error": "EmbeddingInferenceError", "message": str(e)},
        )


@app.post("/rerank", response_model=RerankResponse)
async def rerank(req: RerankRequest):
    """
    Rerank candidate documents by semantic relevance to the query.

    Uses bge-reranker-v2-m3 (CrossEncoder). Results sorted by score descending.
    """
    if model_status["rerank"] != "loaded":
        raise HTTPException(
            status_code=503,
            detail={
                "error": "RerankModelNotLoaded",
                "message": f"Rerank model status: {model_status['rerank']}",
            },
        )

    try:
        # Build (query, document) pairs for CrossEncoder
        pairs = [[req.query, doc] for doc in req.documents]
        scores = rerank_model.predict(pairs, show_progress_bar=False)

        # Sort by score descending, keep original index
        results = sorted(
            (RerankResultItem(index=i, score=float(s))
             for i, s in enumerate(scores)),
            key=lambda x: x.score,
            reverse=True,
        )
        return RerankResponse(results=results)
    except Exception as e:
        logger.exception("Rerank inference failed")
        raise HTTPException(
            status_code=500,
            detail={"error": "RerankInferenceError", "message": str(e)},
        )
