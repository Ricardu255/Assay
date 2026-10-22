#!/usr/bin/env python3
"""Generate deterministic JSONL cases for the Assay benchmark."""

import argparse
import json
from pathlib import Path

TOOL_CHAIN = ["Router/route", "Tool/search", "Tool/answer"]


def case(index: int, mode: str) -> dict:
    if mode == "echo":
        return {
            "id": f"E{index:05d}",
            "scenario": "bench",
            "input": {"message": f"hello {index}"},
            "expected": {"route": "ECHO", "keyword": "hello"},
        }
    return {
        "id": f"A{index:05d}",
        "scenario": "bench",
        "input": {"message": f"refund order {index}"},
        "expected": {"route": "SUPPORT", "keyword": "refund", "tool_chain": TOOL_CHAIN},
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--output", default=".assay/bench/cases.jsonl")
    parser.add_argument("--mode", choices=("echo", "agent"), default="echo")
    args = parser.parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as handle:
        for index in range(1, args.count + 1):
            handle.write(json.dumps(case(index, args.mode), ensure_ascii=False) + "\n")
    print(f"wrote {args.count} {args.mode} cases to {output}")


if __name__ == "__main__":
    main()
