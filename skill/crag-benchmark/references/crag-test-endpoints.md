# CRAG-Demo Benchmark Endpoints

Real benchmark runs must use Docker Compose. The app endpoint is expected to be reached from inside the `app` container as `http://localhost:8080`.

## Retrieval Baseline Path

`benchmark/retrieval_benchmark_runner.py` uses:

1. `GET /api/v1/test/smoke`
2. `POST /api/v1/test/chunk`
3. `GET /api/v1/test/chunk/{chunkId}/indexes`
4. `GET /api/v1/test/rrf?query={query}&topN=10`
5. `GET /api/v1/test/retrieval?query={query}&topN=10`

## Docker Command Pattern

Use this style for benchmark HTTP calls:

```bash
docker compose exec -T app sh -lc 'wget -qO- http://localhost:8080/api/v1/test/smoke'
```

Do not use `./gradlew bootRun`, `java -jar`, `python sidecar/main.py`, or `uvicorn` for non-unit benchmark validation.

## Output Locations

Generated or runtime-only output belongs under:

```text
build/benchmark/
```

Tracked benchmark scripts and indexes belong under:

```text
benchmark/
skill/crag-benchmark/
```
