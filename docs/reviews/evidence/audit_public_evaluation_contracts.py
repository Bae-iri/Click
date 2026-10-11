"""Audit downloaded public metadata/scorer semantics, not model performance.

No network, model loading, audio, screenshots, or user records are processed.
Requires PyYAML. Source URLs and byte hashes are included in the output.
"""
from __future__ import annotations

import argparse
import ast
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[3]


def source(path, url):
    return {"url": url, "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def evaluate(args):
    metadata = yaml.safe_load(args.fleurs.read_text().split("---", 2)[1])
    korean = next(item for item in metadata["dataset_info"] if item["config_name"] == "ko_kr")
    splits = json.loads(args.splits.read_text())
    groups = {key: set(values) for key, values in splits.items()}
    subsets = {key: set(values) for key, values in json.loads(args.subsets.read_text()).items()}
    loader_path = ROOT / "AI-Agent/src/ai_agent/graph_proposal/datasets/android_control.py"
    spec = importlib.util.spec_from_file_location("click_ac_loader_audit", loader_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    loaded = module.load_official_splits(args.splits)
    assert loaded == groups
    for name, ids in groups.items():
        assert len(ids) == len(splits[name])
        for identifier in ids:
            assert module.split_for_episode(identifier, loaded) == name

    # Extract only the published point-in-box predicate; do not execute its
    # model-loading code or imports. Reject any other syntax before evaluation.
    tree = ast.parse(args.scorer.read_text())
    predicate = next(node.test for node in ast.walk(tree)
                     if isinstance(node, ast.If) and isinstance(node.test, ast.BoolOp)
                     and "click_point" in ast.unparse(node.test) and "bbox" in ast.unparse(node.test))
    allowed = (ast.BoolOp, ast.And, ast.Compare, ast.LtE, ast.Name, ast.Load,
               ast.Subscript, ast.Constant)
    assert all(isinstance(node, allowed) for node in ast.walk(predicate))
    code = compile(ast.Expression(predicate), "public-point-in-box", "eval")
    target = [0.4, 0.4, 0.6, 0.6]
    witnesses = []
    for label, predicted in (("tight_target_box", target), ("entire_screen_box", [0, 0, 1, 1])):
        point = [(predicted[0] + predicted[2]) / 2, (predicted[1] + predicted[3]) / 2]
        accepted = eval(code, {"__builtins__": {}}, {"bbox": target, "click_point": point})
        area = (predicted[2] - predicted[0]) * (predicted[3] - predicted[1])
        witnesses.append({"synthetic_witness": label, "point_accepted": accepted,
                          "overlay_iou": round(0.04 / area, 6)})
    return {
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "scope": "public metadata, official split compatibility, published scoring predicate",
        "model_inference_performed": False, "audio_or_screenshots_reviewed": False,
        "sources": [
            source(args.fleurs, "https://huggingface.co/datasets/google/fleurs/raw/main/README.md"),
            source(args.splits, "https://storage.googleapis.com/gresearch/android_control/splits.json"),
            source(args.subsets, "https://storage.googleapis.com/gresearch/android_control/test_subsplits.json"),
            source(args.scorer, "https://raw.githubusercontent.com/njucckevin/SeeClick/main/pretrain/screenspot_test.py"),
        ],
        "fleurs_ko_kr": {
            "metadata_declared_examples": {item["name"]: item["num_examples"] for item in korean["splits"]},
            "audio_schema": next(item["dtype"] for item in korean["features"] if item["name"] == "audio"),
            "features": [item["name"] for item in korean["features"]],
            "speaker_id_exposed": any(item["name"] == "speaker_id" for item in korean["features"]),
            "license_from_card": metadata.get("license"),
            "counts_are_metadata_not_audio_archive_enumeration": True,
        },
        "android_control": {
            "official_split_counts": {name: len(ids) for name, ids in groups.items()},
            "total_unique_episodes": len(set.union(*groups.values())),
            "splits_disjoint": sum(map(len, groups.values())) == len(set.union(*groups.values())),
            "current_loader_official_split_compatibility": "PASS",
            "current_loader_sha256": hashlib.sha256(loader_path.read_bytes()).hexdigest(),
            "test_subset_counts": {name: len(ids) for name, ids in subsets.items()},
            "test_subset_union": len(set.union(*subsets.values())),
            "all_subsets_within_test": all(ids <= groups["test"] for ids in subsets.values()),
            "test_subsets_overlap": sum(map(len, subsets.values())) > len(set.union(*subsets.values())),
        },
        "screenspot": {"published_predicate": ast.unparse(predicate), "synthetic_witnesses": witnesses,
                       "interpretation": "Point accuracy cannot establish overlay rectangle quality or task completion."},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("fleurs", "splits", "subsets", "scorer", "output"):
        parser.add_argument("--" + option, type=Path, required=True)
    args = parser.parse_args()
    report = evaluate(args)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("fleurs_ko_kr", "android_control", "screenspot")}, ensure_ascii=False, indent=2))
