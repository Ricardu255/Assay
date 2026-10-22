#!/usr/bin/env python3
"""Run the reproducible Assay benchmark against the shaded jar."""

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app" / "target" / "assay.jar"
BENCH = ROOT / "target" / "bench"
MVN = shutil.which("mvn") or shutil.which("mvn.cmd") or "mvn"
ADAPTERS = {
    "echo": "io.assay.examples.EchoAdapter",
    "agent": "io.assay.examples.BenchmarkAgentAdapter",
}


def run(command: list[str], log: Path) -> float:
    log.parent.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    with log.open("w", encoding="utf-8") as handle:
        subprocess.run(command, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT, check=True)
    return time.perf_counter() - started


def h2_jar() -> Path:
    candidates = sorted(
        (Path.home() / ".m2" / "repository" / "com" / "h2database" / "h2").glob("*/h2-*.jar")
    )
    if not candidates:
        raise SystemExit("H2 jar not found; run 'mvn -q -DskipTests -pl :assay -am package' first")
    return candidates[-1]


def newest_result(output: Path) -> dict:
    files = sorted(output.glob("**/results.json"), key=lambda path: path.stat().st_mtime)
    if not files:
        raise SystemExit(f"no results.json under {output}")
    return json.loads(files[-1].read_text(encoding="utf-8"))


def clean(path: Path) -> None:
    if path.exists():
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
    for suffix in (".mv.db", ".trace.db"):
        sibling = path.with_name(path.name + suffix)
        if sibling.exists():
            sibling.unlink()


def run_one(scenario: str, store: str, count: int, workers: int) -> dict:
    cases = BENCH / f"{scenario}-{count}.jsonl"
    if not cases.exists():
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "generate_cases.py"), "--count", str(count),
             "--mode", scenario, "--output", str(cases)],
            cwd=ROOT, check=True,
        )
    label = f"{scenario}-{store}-{count}"
    output = BENCH / "runs" / label
    clean(output)
    store_path = BENCH / "store" / label
    clean(store_path)
    if store == "jdbc":
        reference = f"jdbc:h2:file:{store_path.as_posix()};DB_CLOSE_DELAY=-1"
    else:
        reference = str(store_path)
    command = [
        "java", "-jar", str(APP), "run",
        "--adapter", ADAPTERS[scenario],
        "--suite", "bench",
        "--cases", str(cases),
        "--store", reference,
        "--output", str(output),
        "--workers", str(workers),
    ]
    if store == "jdbc":
        command = [
            "java", "-cp", os.pathsep.join([str(APP), str(h2_jar())]), "io.assay.cli.AssayCli",
            *command[3:],
        ]
    log = BENCH / "logs" / f"{label}.log"
    elapsed = run(command, log)
    payload = newest_result(output)
    results = payload.get("results") or []
    durations = []
    for result in results:
        trace = result.get("trace") or {}
        durations.append(sum(float(event.get("duration_ms") or 0) for event in trace.get("events") or []))
    return {
        "scenario": scenario,
        "adapter": ADAPTERS[scenario],
        "store": store,
        "count": int(payload.get("case_count") or 0),
        "workers": workers,
        "elapsed_ms": round(elapsed * 1000, 1),
        "cases_per_second": round(len(results) / elapsed, 1) if elapsed else None,
        "hard_failures": payload.get("hard_failures"),
        "soft_warnings": payload.get("soft_warnings"),
        "trace_events_per_case": round(statistics.mean([events for events in (
            len((result.get("trace") or {}).get("events") or []) for result in results
        )]), 2) if results else 0,
        "trace_duration_ms_p50": round(statistics.median(durations), 2) if durations else None,
        "run_id": payload.get("run_id"),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10000)
    parser.add_argument("--workers", type=int, default=20)
    parser.add_argument("--scenarios", nargs="+", choices=tuple(ADAPTERS), default=list(ADAPTERS))
    parser.add_argument("--stores", nargs="+", choices=("file", "jdbc"), default=["file", "jdbc"])
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not args.skip_build:
        subprocess.run(
            [MVN, "-q", "-DskipTests", "-pl", ":assay", "-am", "clean", "package"],
            cwd=ROOT, check=True,
        )
    if not APP.exists():
        raise SystemExit(f"missing {APP}; run with a successful build first")
    rows = [run_one(scenario, store, args.count, args.workers)
            for scenario in args.scenarios for store in args.stores]
    BENCH.mkdir(parents=True, exist_ok=True)
    (BENCH / "results.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    print("| Scenario | Store | Cases | Wall ms | Cases/s | Hard failures | Events/case |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for row in rows:
        print(f"| {row['scenario']} | {row['store']} | {row['count']} | {row['elapsed_ms']} | "
              f"{row['cases_per_second']} | {row['hard_failures']} | {row['trace_events_per_case']} |")
    print(f"\nresults: {BENCH / 'results.json'}")


if __name__ == "__main__":
    main()
