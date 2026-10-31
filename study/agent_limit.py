#!/usr/bin/env python3
"""EXPLORATORY, labelled as such: it decides nothing (the analysis plan's section 8, *The
agent's limit*).

At each checkpoint: does the agent's own spec pass at the first gate? Set against the longest
method, and the largest slim cost, among the methods the agent's diff touched. The question is
whether the agent's failures go with length or with the sinew. The answer is descriptive: rates by
band, and a logistic fit with both predictors, not a test.

Read from the chain branch and the harness's capture on it:

- **The first gate** is the capture's results when the gate ran on the checkpoint's `cpNN agent`
  commit (`commit_sha`), the agent's first attempt. When it ran on anything else (a gated run's
  refactor), the outcome is unknown, not guessed.
- **The agent's own spec** is every test whose id starts with `<checkpoint_id>.spec.ts::`; it passes
  when they all pass.
- **The agent's diff** is `preagent_sha..commit_sha` on the backend root. A method is touched when
  its lines at `commit_sha` (name to closing brace) meet a changed hunk.
- **Beyond the plan, labelled so:** the same reading with the whole gate as the outcome (did every
  test pass, the earlier checkpoints' specs too), because an agent that runs the end-to-end tests
  itself before it stops always makes its own spec pass, and its failures are regressions instead.
- **The slim cost** is SinewSlim's (`slim_cost`), 0 under the cap. A not-slimmable method has none:
  touching one is flagged, and is its own band in the rates, rather than given a number. The fit
  takes the largest cost among the touched methods that have one.

    study/agent_limit.py --repo ~/officehq-angular-spring \\
        --branch origin/evolve/202610112340/just-solve/chain1 --allow-run 202610112340 \\
        --sinewslim ~/SinewSlim/cli/build/libs/sinew-slim.jar --config docs/sinewslim-analysis.yml \\
        --out results/agent-limit
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scan_driver  # noqa: E402

AGENT = re.compile(r"^cp(\d+) agent ")
HUNK = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
LENGTH_BANDS = [(0, 25, "under 26"), (26, 50, "26-50"), (51, 100, "51-100"), (101, math.inf, "over 100")]
COST_BANDS = [(0, 0, "0"), (1, 5, "1-5"), (6, 10, "6-10"), (11, math.inf, "over 10")]


def agent_commits(repo: str, branch: str) -> dict[int, str]:
    """checkpoint -> its first `cpNN agent` commit."""
    out = {}
    for line in scan_driver.git(repo, "log", "--reverse", "--format=%H %s", branch).splitlines():
        sha, _, subject = line.partition(" ")
        m = AGENT.match(subject)
        if m and int(m.group(1)) not in out:
            out[int(m.group(1))] = sha
    return out


def changed(repo: str, old: str, new: str, root: str) -> dict[str, list[tuple[int, int]]]:
    """path -> the new side's changed line ranges (a deletion is the line it follows)."""
    out: dict[str, list[tuple[int, int]]] = {}
    path = None
    for line in scan_driver.git(repo, "diff", "-U0", old, new, "--", root).splitlines():
        if line.startswith("+++ "):
            path = None if line[4:] == "/dev/null" else line[6:]
        elif path and (m := HUNK.match(line)):
            start, count = int(m.group(1)), int(m.group(2)) if m.group(2) is not None else 1
            out.setdefault(path, []).append((start, start + count - 1) if count else (start, start))
    return out


def touched(methods: list[dict], ranges: dict[str, list[tuple[int, int]]]) -> list[dict]:
    return [m for m in methods
            if any(a <= m["end_line"] and m["line"] <= b for a, b in ranges.get(m["path"], ()))]


def whole_gate(capture: dict) -> bool:
    """Whether every test of the gate passed, the earlier checkpoints' specs too."""
    return all(v is True for v in ((capture.get("tests") or {}).get("results") or {}).values())


def own_spec(capture: dict) -> bool | None:
    """Whether every test of the checkpoint's own spec passed; None when it has none recorded."""
    prefix = f"{capture['checkpoint_id']}.spec.ts::"
    results = (capture.get("tests") or {}).get("results") or {}
    mine = [v for k, v in results.items() if k.startswith(prefix)]
    return None if not mine else all(v is True for v in mine)


def band(x: float, bands) -> str:
    return next(name for lo, hi, name in bands if lo <= x <= hi)


def rates(rows: list[dict], key, outcome: str = "passed") -> list[dict]:
    groups: dict[str, list[bool]] = {}
    for r in rows:
        groups.setdefault(key(r), []).append(r[outcome])
    order = [name for _, _, name in LENGTH_BANDS + COST_BANDS] + ["not slimmable"]
    return [{"band": b, "checkpoints": len(v), "failed": v.count(False),
             "fail_rate": round(v.count(False) / len(v), 4)}
            for b, v in sorted(groups.items(), key=lambda bv: order.index(bv[0]) if bv[0] in order else len(order))]


def logistic(xs: list[list[float]], ys: list[int], iterations: int = 50) -> dict:
    """Fail ~ intercept + predictors, by iteratively reweighted least squares; descriptive only.
    The predictors are standardised, so the coefficients compare; None when the fit does not settle
    (too few failures, or separation)."""
    n, k = len(xs), len(xs[0])
    means = [sum(x[j] for x in xs) / n for j in range(k)]
    sds = [math.sqrt(sum((x[j] - means[j]) ** 2 for x in xs) / n) or 1.0 for j in range(k)]
    X = [[1.0] + [(x[j] - means[j]) / sds[j] for j in range(k)] for x in xs]
    beta = [0.0] * (k + 1)
    for _ in range(iterations):
        p = [1 / (1 + math.exp(-max(-30.0, min(30.0, sum(b * v for b, v in zip(beta, row)))))) for row in X]
        w = [pi * (1 - pi) for pi in p]
        # solve (X'WX) d = X'(y - p)
        A = [[sum(w[i] * X[i][a] * X[i][b] for i in range(n)) for b in range(k + 1)] for a in range(k + 1)]
        g = [sum(X[i][a] * (ys[i] - p[i]) for i in range(n)) for a in range(k + 1)]
        d = solve(A, g)
        if d is None:
            return {"settled": False}
        beta = [b + di for b, di in zip(beta, d)]
        if max(abs(di) for di in d) < 1e-8:
            break
        if max(abs(b) for b in beta) > 25:
            return {"settled": False, "reason": "separation"}
    return {"settled": True, "intercept": round(beta[0], 4),
            "per_sd": [round(b, 4) for b in beta[1:]], "means": means, "sds": sds}


def solve(A: list[list[float]], b: list[float]) -> list[float] | None:
    n = len(b)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        piv = max(range(c, n), key=lambda r: abs(M[r][c]))
        if abs(M[piv][c]) < 1e-12:
            return None
        M[c], M[piv] = M[piv], M[c]
        for r in range(n):
            if r != c:
                f = M[r][c] / M[c][c]
                M[r] = [x - f * y for x, y in zip(M[r], M[c])]
    return [M[i][n] / M[i][i] for i in range(n)]


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--branch", required=True)
    ap.add_argument("--allow-run", action="append", default=[], required=True)
    ap.add_argument("--sinewslim", required=True)
    ap.add_argument("--config", required=True)
    ap.add_argument("--out", required=True, help="a directory: the scans at the agent commits, and the reading")
    ap.add_argument("--java", default="java")
    a = ap.parse_args(argv)

    m = scan_driver.BRANCH.match(a.branch)
    if not m:
        ap.error(f"{a.branch} is not evolve/<run>/<condition>/chain<N>")
    run, condition, chain = m.group(1), m.group(2), int(m.group(3))
    if run not in a.allow_run:
        print(f"refused: run {run} is not named with --allow-run", file=sys.stderr)
        return 2
    repo = os.path.abspath(os.path.expanduser(a.repo))
    out = os.path.join(a.out, run, condition, f"chain{chain}")
    os.makedirs(out, exist_ok=True)
    jar, config = os.path.expanduser(a.sinewslim), os.path.expanduser(a.config)

    import normalise  # the captures, read as the harness reads them

    caps = normalise.captures(repo, a.branch)
    agents = agent_commits(repo, a.branch)
    rows, unknown = [], []
    for k in sorted(caps):
        cap = caps[k]
        sha = agents.get(k)
        if sha is None or cap.get("commit_sha") != sha:
            unknown.append({"checkpoint": k, "reason": "the gate did not run on the agent commit"})
            continue
        passed = own_spec(cap)
        if passed is None or (cap.get("tests") or {}).get("gate_invalid"):
            unknown.append({"checkpoint": k, "reason": "no own spec in the results, or the gate was invalid"})
            continue
        root, _ = scan_driver.backend_root(repo, sha)
        scan_path = os.path.join(out, f"cp{k:02d}.agent.sinewslim.json")
        if not os.path.exists(scan_path):
            with tempfile.TemporaryDirectory() as tmp:
                scan_driver.extract(repo, sha, root, tmp)
                subprocess.run([a.java, "-jar", jar, "scan", "--repo", tmp, "--config", config, "--format", "json",
                                "--out", scan_path], check=True, capture_output=True)
        with open(scan_path) as f:
            methods = json.load(f)["methods"]
        hit = touched(methods, changed(repo, cap["preagent_sha"], sha, root))
        costs = [mm.get("slim_cost", 0) for mm in hit if mm["status"] != "not_slimmable"]
        rows.append({"checkpoint": k, "passed": passed, "gate_passed": whole_gate(cap), "touched": len(hit),
                     "longest": max((mm["length"] for mm in hit), default=0),
                     "largest_cost": max(costs, default=0),
                     "touched_not_slimmable": any(mm["status"] == "not_slimmable" for mm in hit)})
        print(f"cp{k:02d}: {'pass' if passed else 'FAIL'}, {len(hit)} methods touched", file=sys.stderr)

    with_backend = [r for r in rows if r["touched"]]

    def reading(outcome: str) -> dict:
        fails = sum(not r[outcome] for r in with_backend)
        fit = None
        if 0 < fails < len(with_backend):
            fit = logistic([[r["longest"], r["largest_cost"]] for r in with_backend],
                           [0 if r[outcome] else 1 for r in with_backend])
            if fit.get("settled"):
                fit["predictors"] = ["longest touched method (lines)", "largest slim cost touched"]
        return {"failed": sum(not r[outcome] for r in rows), "failed_with_backend_touched": fails,
                "by_length": rates(with_backend, lambda r: band(r["longest"], LENGTH_BANDS), outcome),
                "by_cost": rates(with_backend, lambda r: "not slimmable" if r["touched_not_slimmable"]
                                 else band(r["largest_cost"], COST_BANDS), outcome),
                "logistic_fail": fit}

    report = {
        "schema": "officefloorslim-agent-limit/1",
        "exploratory": "decides nothing (analysis plan, section 8)",
        "branch": a.branch,
        "checkpoints": len(rows),
        "no_backend_touched": len(rows) - len(with_backend),
        "unknown": unknown,
        "own_spec": reading("passed"),
        "whole_gate": {"beyond_the_plan": "the whole gate as the outcome: the agent's regressions",
                       **reading("gate_passed")},
        "rows": rows,
    }
    with open(os.path.join(out, "agent-limit.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps({k: v for k, v in report.items() if k not in ("rows", "unknown")}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
