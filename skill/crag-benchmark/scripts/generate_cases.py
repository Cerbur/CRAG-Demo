#!/usr/bin/env python3
import argparse
import json
import random
import re
import string
import time
from pathlib import Path


TOPICS = [
    {
        "slug": "dense-vector-search",
        "title": "Dense Vector Search pgvector Cosine",
        "keywords": ["pgvector", "dense", "cosine", "embedding", "vector retrieval"],
        "semantic": "semantic vector lookup over embedded child chunks",
        "zh": "向量检索需要覆盖 embedding 生成、pgvector 排序和 dense score 可观测性。",
    },
    {
        "slug": "sparse-fts-cjk",
        "title": "Sparse FTS CJK Tokenization",
        "keywords": ["PostgreSQL FTS", "sparse", "CJK", "plainto_tsquery", "chunk_fts"],
        "semantic": "keyword retrieval with full text search and Chinese preprocessing",
        "zh": "稀疏检索需要验证中文预处理、tsvector 写入和 ts_rank 排序。",
    },
    {
        "slug": "rrf-fusion",
        "title": "RRF Fusion Child Granularity",
        "keywords": ["RRF", "child chunk", "fusion", "sparseScore", "denseScore"],
        "semantic": "hybrid search fusion without collapsing sibling child chunks",
        "zh": "融合排序必须保留 child 粒度，并同时暴露 sparse、dense 和 rrf 分数。",
    },
    {
        "slug": "rerank-adjacency",
        "title": "Rerank Candidate Adjacency Expansion",
        "keywords": ["rerank", "adjacent child", "candidate expansion", "CrossEncoder"],
        "semantic": "reranking expanded candidates around top fused child chunks",
        "zh": "重排候选集需要覆盖相邻 child，但不能伪造上游召回分数。",
    },
    {
        "slug": "query-context",
        "title": "Query Context Sources",
        "keywords": ["query layer", "context", "sources", "LLM", "retrieval chunks"],
        "semantic": "answer generation context assembled from traceable retrieval sources",
        "zh": "问答层需要保留 sources，并控制 prompt context 长度。",
    },
    {
        "slug": "docker-verification",
        "title": "Docker Compose Full Stack Verification",
        "keywords": ["Docker Compose", "smoke test", "sidecar", "db", "retrieval"],
        "semantic": "full stack verification through containers and model sidecar",
        "zh": "端到端验证必须通过 Docker Compose 覆盖数据库、应用和模型 sidecar。",
    },
]

NOISE_ANGLES = [
    "operations checklist",
    "architecture overview",
    "debugging notes",
    "future roadmap",
    "incident review",
    "data model commentary",
]


def slug_token(rng, size=8):
    alphabet = string.ascii_lowercase + string.digits
    return "".join(rng.choice(alphabet) for _ in range(size))


def normalize_words(words):
    return " ".join(re.sub(r"[^A-Za-z0-9_]+", " ", word).strip() for word in words)


def build_target_content(case_id, topic, sentinel, language):
    keyword_text = ", ".join(topic["keywords"])
    english = (
        f"[{case_id}] {topic['title']} is the target benchmark document. "
        f"It focuses on {topic['semantic']} in CRAG-Demo. "
        f"Important exact terms include {keyword_text}. "
        f"The unique retrieval sentinel is {sentinel}. "
        f"A correct benchmark run should rank this target above similar RAG documents "
        f"when the query asks for {normalize_words(topic['keywords'])}. "
        f"Scores should remain observable across sparseScore, denseScore, rrfScore, and rerankScore."
    )
    if language == "en":
        return english
    if language == "zh":
        return f"{english} {topic['zh']}"
    return f"{english} Mixed-language evidence: {topic['zh']}"


def build_noise_content(case_id, topic, angle, noise_index, language):
    shared = ", ".join(topic["keywords"][: max(2, len(topic["keywords"]) - 1)])
    english = (
        f"[{case_id}-noise-{noise_index}] This distractor is a {angle} for CRAG-Demo. "
        f"It intentionally shares broad vocabulary such as {shared}, retrieval, benchmark, and RAG quality. "
        f"It does not contain the target sentinel and should not outrank the target for exact benchmark queries. "
        f"The document is useful as a similar but non-answer candidate."
    )
    if language == "en":
        return english
    if language == "zh":
        return f"{english} 这是相似干扰文档，用于测试排序是否能识别真正目标。"
    return f"{english} 混合语言噪声用于验证 sparse、dense、RRF 和 rerank 的抗干扰能力。"


def build_case(rng, index, topic, noise_per_case, mode):
    case_id = f"generated-{index:02d}"
    language = rng.choice(["en", "zh", "mixed"])
    sentinel = f"crag-sentinel-{topic['slug']}-{slug_token(rng)}"
    exact_query = f"{normalize_words(topic['keywords'])} {sentinel}"
    semantic_query = f"{topic['semantic']} {sentinel}"
    weak_query = f"{topic['title']} benchmark quality"
    mixed_query = f"{topic['keywords'][0]} {topic['zh']} {sentinel}"
    target_title = f"{case_id} {topic['title']}"
    noise_docs = []
    for noise_index in range(1, noise_per_case + 1):
        angle = rng.choice(NOISE_ANGLES)
        noise_docs.append(
            {
                "id": f"{case_id}-noise-{noise_index}",
                "title": f"{case_id} noise {noise_index} {angle}",
                "content": build_noise_content(case_id, topic, angle, noise_index, language),
            }
        )
    return {
        "id": case_id,
        "mode": mode,
        "topic": topic["slug"],
        "language": language,
        "title": topic["title"],
        "keywords": topic["keywords"],
        "sentinel": sentinel,
        "query_variants": {
            "exact": exact_query,
            "semantic": semantic_query,
            "mixed": mixed_query,
            "weak": weak_query,
        },
        "target_document": {
            "id": f"{case_id}-target",
            "title": target_title,
            "content": build_target_content(case_id, topic, sentinel, language),
        },
        "noise_documents": noise_docs,
        "expected": {
            "target_document_id": f"{case_id}-target",
            "preferred_query_variant": "exact",
            "top_k": 10,
        },
    }


def generate_cases(seed, case_count, noise_per_case, mode):
    rng = random.Random(seed)
    topics = TOPICS[:]
    rng.shuffle(topics)
    cases = []
    for index in range(1, case_count + 1):
        topic = topics[(index - 1) % len(topics)]
        if index > len(topics):
            topic = dict(topic)
            topic["slug"] = f"{topic['slug']}-{index}"
        cases.append(build_case(rng, index, topic, noise_per_case, mode))
    return {
        "metadata": {
            "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
            "seed": seed,
            "mode": mode,
            "case_count": case_count,
            "noise_per_case": noise_per_case,
            "schema": "crag-benchmark.generated-cases.v1",
        },
        "cases": cases,
    }


def validate_generated(payload):
    sentinels = set()
    for case in payload["cases"]:
        sentinel = case["sentinel"]
        if sentinel in sentinels:
            raise AssertionError(f"duplicate sentinel: {sentinel}")
        sentinels.add(sentinel)
        target_content = case["target_document"]["content"]
        if sentinel not in target_content:
            raise AssertionError(f"target missing sentinel: {case['id']}")
        for noise_doc in case["noise_documents"]:
            if sentinel in noise_doc["content"]:
                raise AssertionError(f"noise contains sentinel: {case['id']}")
        if sentinel not in case["query_variants"]["exact"]:
            raise AssertionError(f"exact query missing sentinel: {case['id']}")


def parse_args():
    parser = argparse.ArgumentParser(description="Generate seeded CRAG benchmark cases.")
    parser.add_argument("--seed", type=int, default=20260618)
    parser.add_argument("--case-count", type=int, default=6)
    parser.add_argument("--noise-per-case", type=int, default=3)
    parser.add_argument("--mode", choices=["randomized", "mixed"], default="randomized")
    parser.add_argument("--output", default="build/benchmark/generated_cases.json")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    payload = generate_cases(args.seed, args.case_count, args.noise_per_case, args.mode)
    validate_generated(payload)
    if args.self_test:
        again = generate_cases(args.seed, args.case_count, args.noise_per_case, args.mode)
        if payload != again:
            raise AssertionError("generation is not deterministic for the same seed")
        print(json.dumps({"ok": True, "case_count": len(payload["cases"]), "seed": args.seed}))
        return
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(str(output))


if __name__ == "__main__":
    main()
