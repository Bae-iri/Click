"""Prepare AndroidControl reference episodes without mixing split groups.

TensorFlow is needed only to read the source TFRecords. The resulting JSONL
contains instruction text and observed action type for offline graph analysis.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
from pathlib import Path


def split_for_episode(episode_id: int, official: dict[str, set[int]] | None = None) -> str:
    if official is not None:
        matches = [name for name, ids in official.items() if episode_id in ids]
        if len(matches) != 1:
            raise ValueError(f"episode {episode_id} must occur in exactly one split")
        return matches[0]
    bucket = int.from_bytes(hashlib.sha256(str(episode_id).encode()).digest()[:8], "big") % 100
    return "train" if bucket < 80 else "validation" if bucket < 90 else "test"


def load_official_splits(path: Path) -> dict[str, set[int]]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if set(raw) != {"train", "validation", "test"}:
        raise ValueError("split JSON must contain train, validation, and test episode IDs")
    result = {key: set(map(int, values)) for key, values in raw.items()}
    if sum(map(len, result.values())) != len(set.union(*result.values())):
        raise ValueError("episode IDs overlap between splits")
    return result


def prepare(files: list[str], output: Path, official: dict[str, set[int]] | None = None) -> dict[str, int]:
    try:
        import tensorflow as tf
    except ImportError as exc:
        raise RuntimeError("Install tensorflow to read AndroidControl TFRecords") from exc

    output.mkdir(parents=True, exist_ok=True)
    counts = {name: 0 for name in ("train", "validation", "test")}
    seen: set[int] = set()
    handles = {name: (output / f"{name}.jsonl").open("w", encoding="utf-8") for name in counts}
    try:
        for raw in tf.data.TFRecordDataset(files, compression_type="GZIP"):
            features = tf.train.Example.FromString(raw.numpy()).features.feature
            episode_id = int(features["episode_id"].int64_list.value[0])
            if episode_id in seen:
                raise ValueError(f"duplicate episode {episode_id}")
            seen.add(episode_id)
            split = split_for_episode(episode_id, official)
            goal = features["goal"].bytes_list.value[0].decode("utf-8", errors="replace")
            instructions = features["step_instructions"].bytes_list.value
            actions = features["actions"].bytes_list.value
            if len(instructions) != len(actions):
                raise ValueError(f"episode {episode_id}: action/instruction count mismatch")
            for step, (instruction, action_bytes) in enumerate(zip(instructions, actions)):
                action = json.loads(action_bytes)
                action_type = action.get("action_type")
                if not isinstance(action_type, str):
                    raise ValueError(f"episode {episode_id}, step {step}: missing action_type")
                sample = {
                    "episode_id": episode_id,
                    "step_id": step,
                    "goal": goal,
                    "step_instruction": instruction.decode("utf-8", errors="replace"),
                    "action_type": action_type,
                }
                handles[split].write(json.dumps(sample, ensure_ascii=False) + "\n")
                counts[split] += 1
    finally:
        for handle in handles.values():
            handle.close()
    (output / "manifest.json").write_text(json.dumps({
        "source": "Google Research AndroidControl TFRecord",
        "split_method": "official episode IDs" if official else "SHA256 episode ID 80/10/10",
        "episodes": len(seen), "samples": counts,
        "features": ["goal", "step_instruction"], "target": "action_type",
    }, indent=2), encoding="utf-8")
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tfrecords", nargs="+", help="local AndroidControl TFRecord GZIP files")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--official-splits", type=Path, help="JSON of train/validation/test episode IDs")
    args = parser.parse_args()
    files = sorted({path for pattern in args.tfrecords for path in glob.glob(pattern)})
    if not files:
        parser.error("no TFRecord files matched")
    print(prepare(files, args.output,
                  load_official_splits(args.official_splits) if args.official_splits else None))


if __name__ == "__main__":
    main()
