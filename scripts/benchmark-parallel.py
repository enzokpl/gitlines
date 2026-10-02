#!/usr/bin/env python3
"""Compare serial and persistent diff workers; sample process-tree RSS separately."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("repository", type=Path)
parser.add_argument("--binary", type=Path, default=Path("dist/gitlines"))
parser.add_argument("--runs", type=int, default=5)
parser.add_argument("--sample-memory", action="store_true")
parser.add_argument("--output", type=Path, default=Path("dist/parallel-benchmark.json"))
args = parser.parse_args()
if args.runs < 1:
    parser.error("runs must be positive")
binary = args.binary.resolve()
args.output.parent.mkdir(parents=True, exist_ok=True)
reference = None
results = {str(workers): [] for workers in (1, 2, 4)}


def tree_rss(root_pid):
    """Sum sampled RSS for the CLI and descendants; shared pages may count twice."""
    listing = subprocess.check_output(["ps", "-eo", "pid=,ppid=,rss="], text=True)
    rows = [tuple(map(int, line.split())) for line in listing.splitlines() if line.strip()]
    descendants = {root_pid}
    while True:
        expanded = descendants | {pid for pid, parent, _ in rows if parent in descendants}
        if expanded == descendants:
            break
        descendants = expanded
    return sum(rss for pid, _, rss in rows if pid in descendants) / 1024


for run in range(args.runs):
    # Rotate the first configuration to reduce fixed cache/order bias.
    order = (1, 2, 4)
    order = order[run % 3:] + order[:run % 3]
    for workers in order:
        report = args.output.parent / f"parallel-{workers}-{run}.json"
        started = time.monotonic()
        process = subprocess.Popen([str(binary), str(args.repository.resolve()), "--workers", str(workers),
                                    "--json", str(report)], stdout=subprocess.DEVNULL)
        peak = 0
        try:
            while args.sample_memory and process.poll() is None:
                peak = max(peak, tree_rss(process.pid))
                time.sleep(0.05)
            status = process.wait()
        except BaseException:
            process.terminate()
            process.wait()
            raise
        elapsed = time.monotonic() - started
        if status:
            raise RuntimeError(f"workers={workers} exited with status {status}")
        digest = hashlib.sha256(report.read_bytes()).hexdigest()
        if reference is None:
            reference = digest
        if digest != reference:
            raise RuntimeError(f"workers={workers} produced different JSON")
        measurement = {"seconds": elapsed, "json_sha256": digest}
        if args.sample_memory:
            measurement["sampled_tree_rss_mib"] = peak
        results[str(workers)].append(measurement)
        args.output.write_text(json.dumps(results, indent=2))

summary = {}
for workers, measurements in results.items():
    summary[workers] = {"median_seconds": statistics.median(row["seconds"] for row in measurements)}
    if args.sample_memory:
        summary[workers]["median_sampled_tree_rss_mib"] = statistics.median(
            row["sampled_tree_rss_mib"] for row in measurements)
results["summary"] = summary
results["memory_sampling"] = args.sample_memory
args.output.write_text(json.dumps(results, indent=2))
print(json.dumps(summary, indent=2))
