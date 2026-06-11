# ============================================================
# CRAG-Demo Sidecar Model Service
# Python 3.12 + FastAPI + sentence-transformers (CPU-only)
# Models are downloaded from ModelScope by docker-compose model-init or scripts/ensure-sidecar-models.sh.
# ============================================================

FROM python:3.12-slim

# --- Non-root user ---
RUN groupadd -r sidecar && useradd -r -g sidecar -m sidecar

WORKDIR /app

# --- Install Python dependencies (layer caching) ---
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# --- Shared ModelScope model directory mounted by docker-compose ---
ENV MODELSCOPE_CACHE=/models/modelscope

# --- Copy application code ---
COPY main.py .
COPY download_models.py .

# --- Runtime ---
USER sidecar
EXPOSE 8001

# Healthcheck: poll /health via Python urllib (no curl needed)
HEALTHCHECK --interval=10s --timeout=5s --retries=30 --start-period=120s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8001/health')" || exit 1

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
