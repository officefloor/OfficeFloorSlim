#!/usr/bin/env python3
"""The scan driver's series normalised by passing tests, as the harness normalises (the analysis
plan's section 5: M1n is M1 divided by the tests passing at k in the full gate, from the capture;
every ratio pooled, sum over sum; raw reported as well).

The passing tests come from the harness's own capture, committed on the chain branch as
`evolve-results/capture/cpNN.json` (harness/analyze.py, `_read_captures`): the tests whose result
is a pass. A checkpoint whose gate was invalid or whose app was broken has no passing count, and is
left out of the ratios (listed, not dropped silently).

    study/normalise.py --repo ~/officehq-angular-spring \\
        --branch origin/evolve/202610112340/just-solve/chain1 --allow-run 202610112340 \\
        --scans results/sinewslim --out results/normalised.json

M1 is SinewSlim's `not_slimmable` and `slimmable_with_record` together (decided 1 November 2026);
M2 the long methods, every status but `under_cap`.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scan_driver  # noqa: E402

CAPTURE = re.compile(r"^evolve-results/capture/cp0*(\d+)\.json$")


def captures(repo: str, branch: str) -> dict[int, dict]:
    """checkpoint -> capture, from the branch, as the harness reads them."""
    out = {}
    for path in scan_driver.git(repo, "ls-tree", "-r", "--name-only", branch, "evolve-results/capture/").splitlines():
        m = CAPTURE.match(path.strip())
        if m:
            try:
                out[int(m.group(1))] = json.loads(scan_driver.git(repo, "show", f"{branch}:{path.strip()}"))
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                pass
    return out


def passing(capture: dict) -> int | None:
    """The tests passing in the full gate; None when the gate was invalid or the app broken."""
    t = capture.get("tests") or {}
    if t.get("gate_invalid") or t.get("app_broken") or "results" not in t:
        return None
    return sum(1 for v in t["results"].values() if v is True)


def measures(scan: dict) -> dict:
    s = scan["summary"]
    return {"m1": s["not_slimmable"] + s["slimmable_with_record"], "m2": s["methods"] - s["under_cap"],
            "methods": s["methods"]}


def series(scans: dict[int, dict], caps: dict[int, dict]) -> dict:
    rows, excluded = [], []
    for k in sorted(scans):
        m = measures(scans[k])
        p = passing(caps[k]) if k in caps else None
        if p is None:
            excluded.append({"checkpoint": k, "reason": "no capture" if k not in caps else "gate invalid or app broken"})
        elif p == 0:
            excluded.append({"checkpoint": k, "reason": "no test passing"})
        rows.append({"checkpoint": k, **m, "passing": p,
                     "m1n": None if not p else round(m["m1"] / p, 6),
                     "m2n": None if not p else round(m["m2"] / p, 6)})
    graded = [r for r in rows if r["passing"]]
    sp = sum(r["passing"] for r in graded)
    pooled = {"checkpoints": len(graded), "passing": sp, "m1": sum(r["m1"] for r in graded),
              "m2": sum(r["m2"] for r in graded)}
    pooled["m1n"] = round(pooled["m1"] / sp, 6) if sp else None
    pooled["m2n"] = round(pooled["m2"] / sp, 6) if sp else None
    return {"rows": rows, "excluded": excluded, "pooled": pooled}


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--branch", required=True)
    ap.add_argument("--allow-run", action="append", default=[], required=True)
    ap.add_argument("--scans", required=True, help="the scan driver's --out directory")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)

    m = scan_driver.BRANCH.match(a.branch)
    if not m:
        ap.error(f"{a.branch} is not evolve/<run>/<condition>/chain<N>")
    run, condition, chain = m.group(1), m.group(2), int(m.group(3))
    if run not in a.allow_run:
        print(f"refused: run {run} is not named with --allow-run", file=sys.stderr)
        return 2
    repo = os.path.abspath(os.path.expanduser(a.repo))
    d = os.path.join(a.scans, run, condition, f"chain{chain}")
    scans = {}
    for f in os.listdir(d):
        mt = re.match(r"^cp0*(\d+)\.sinewslim\.json$", f)
        if mt:
            with open(os.path.join(d, f)) as fh:
                scans[int(mt.group(1))] = json.load(fh)
    result = {"schema": "officefloorslim-normalised/1", "branch": a.branch,
              **series(scans, captures(repo, a.branch))}
    last = next((r for r in reversed(result["rows"]) if r["passing"]), None)
    result["last_graded"] = last
    with open(a.out, "w") as f:
        json.dump(result, f, indent=2)
    print(json.dumps({"pooled": result["pooled"], "last_graded": last, "excluded": len(result["excluded"])},
                     indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
