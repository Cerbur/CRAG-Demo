#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


REQUIRED_SCORE_FIELDS = ["sparseScore", "denseScore", "rrfScore", "rerankScore"]


def load_report(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def summaries_from_report(report):
    if "summaries" in report:
        return report["summaries"]
    if "cases" in report:
        return report["cases"]
    raise ValueError("report must contain summaries or cases")


def field_present(item, field):
    scores = item.get("expected_scores") or item.get("scores") or {}
    return scores.get(field) is not None


def summarize(report, top_k):
    items = summaries_from_report(report)
    if not items:
        raise ValueError("report has no items")
    total_score = 0
    scored_count = 0
    top1_hits = 0
    top_k_hits = 0
    missing_scores = []
    for item in items:
        rank = item.get("rank")
        if rank == 1:
            top1_hits += 1
        if isinstance(rank, int) and rank <= top_k:
            top_k_hits += 1
        if item.get("score") is not None:
            total_score += item["score"]
            scored_count += 1
        missing = [field for field in REQUIRED_SCORE_FIELDS if not field_present(item, field)]
        if missing:
            missing_scores.append({"case_id": item.get("case_id") or item.get("id"), "missing": missing})
    average_score = round(total_score / scored_count, 2) if scored_count else None
    return {
        "case_count": len(items),
        "top1_hits": top1_hits,
        "top_k": top_k,
        "top_k_hits": top_k_hits,
        "average_score": average_score,
        "missing_score_fields": missing_scores,
    }


def self_test():
    report = {
        "summaries": [
            {
                "case_id": "case-a",
                "rank": 1,
                "score": 100,
                "expected_scores": {
                    "sparseScore": 0.5,
                    "denseScore": 0.8,
                    "rrfScore": 0.03,
                    "rerankScore": 0.9,
                },
            },
            {
                "case_id": "case-b",
                "rank": 4,
                "score": 72,
                "expected_scores": {
                    "sparseScore": 0.2,
                    "denseScore": None,
                    "rrfScore": 0.02,
                    "rerankScore": 0.6,
                },
            },
        ]
    }
    summary = summarize(report, top_k=5)
    assert summary["case_count"] == 2
    assert summary["top1_hits"] == 1
    assert summary["top_k_hits"] == 2
    assert summary["average_score"] == 86
    assert summary["missing_score_fields"] == [{"case_id": "case-b", "missing": ["denseScore"]}]
    print(json.dumps({"ok": True, "summary": summary}, ensure_ascii=False))


def parse_args():
    parser = argparse.ArgumentParser(description="Summarize CRAG benchmark reports.")
    parser.add_argument("--input")
    parser.add_argument("--output")
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.self_test:
        self_test()
        return
    if not args.input:
        raise SystemExit("--input is required unless --self-test is used")
    summary = summarize(load_report(args.input), args.top_k)
    text = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
