#!/usr/bin/env python3
import argparse
import json
import math
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


def wilson_interval(successes, total, confidence=0.95):
    if total == 0:
        return {"lower": None, "upper": None, "width_points": None}
    z = 1.959963984540054 if confidence == 0.95 else 1.959963984540054
    p = successes / total
    denominator = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denominator
    half_width = z * math.sqrt((p * (1 - p) + z * z / (4 * total)) / total) / denominator
    lower = max(0, center - half_width)
    upper = min(1, center + half_width)
    return {
        "lower": round(lower, 4),
        "upper": round(upper, 4),
        "width_points": round((upper - lower) * 100, 1),
    }


def regression_detection_label(case_count):
    if case_count < 100:
        return "no: sample is too small to detect a 5 point regression"
    if case_count < 200:
        return "weak: only large regressions are distinguishable"
    if case_count < 500:
        return "yes: enough for broad deployment decisions"
    if case_count < 1000:
        return "supported: enough to compare similar systems"
    return "precise: strong tracking for small quality movement"


def category_of(item):
    return item.get("category") or item.get("case_category") or "unknown"


def summarize(report, top_k):
    items = summaries_from_report(report)
    if not items:
        raise ValueError("report has no items")
    total_score = 0
    scored_count = 0
    top1_hits = 0
    top_k_hits = 0
    missing_scores = []
    category_counts = {}
    category_top1 = {}
    for item in items:
        category = category_of(item)
        category_counts[category] = category_counts.get(category, 0) + 1
        rank = item.get("rank")
        if rank == 1:
            top1_hits += 1
            category_top1[category] = category_top1.get(category, 0) + 1
        if isinstance(rank, int) and rank <= top_k:
            top_k_hits += 1
        if item.get("score") is not None:
            total_score += item["score"]
            scored_count += 1
        missing = [field for field in REQUIRED_SCORE_FIELDS if not field_present(item, field)]
        if missing:
            missing_scores.append({"case_id": item.get("case_id") or item.get("id"), "missing": missing})
    case_count = len(items)
    average_score = round(total_score / scored_count, 2) if scored_count else None
    category_summary = {}
    for category, count in category_counts.items():
        hits = category_top1.get(category, 0)
        category_summary[category] = {
            "case_count": count,
            "top1_hits": hits,
            "top1_rate": round(hits / count, 4),
            "top1_95_ci": wilson_interval(hits, count),
        }
    return {
        "case_count": case_count,
        "top1_hits": top1_hits,
        "top1_rate": round(top1_hits / case_count, 4),
        "top1_95_ci": wilson_interval(top1_hits, case_count),
        "top_k": top_k,
        "top_k_hits": top_k_hits,
        "top_k_rate": round(top_k_hits / case_count, 4),
        "top_k_95_ci": wilson_interval(top_k_hits, case_count),
        "average_score": average_score,
        "category_summary": category_summary,
        "regression_detection": regression_detection_label(case_count),
        "missing_score_fields": missing_scores,
    }


def self_test():
    report = {
        "summaries": [
            {
                "case_id": "case-a",
                "category": "golden",
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
                "category": "adversarial",
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
    assert summary["top1_95_ci"]["width_points"] > 0
    assert summary["regression_detection"].startswith("no:")
    assert summary["category_summary"]["golden"]["top1_hits"] == 1
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
