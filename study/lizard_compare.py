#!/usr/bin/env python3
"""SinewSlim's code lines against Lizard's NLOC, per method, on the checkpoints the scan driver
scanned (PLAN.md, Empirical study; the analysis plan's section 4, *Lines*: "the pilot reports the
agreement between the two"), so the harness's series, which is Lizard's, can be read beside the
new one, which is SinewSlim's.

Lizard runs as the harness runs it (harness/metrics.py, `functions`: `lizard.analyze_file` on each
backend file) and must be the version the harness pins (requirements.txt: lizard==1.23.0; 1.24
changed its Java reading). Run it with a Python that has that version:

    .venv-lizard/bin/python study/lizard_compare.py --repo ~/officehq-angular-spring \\
        --branch origin/evolve/202610112340/just-solve/chain1 --allow-run 202610112340 \\
        --scans results/sinewslim --out results/lizard-agreement.json

A method is paired across the two by its file and its last line (the closing brace, which both
see alike); a method only one of them reports is counted, not paired, and the long ones among them
are listed. Lizard 1.23.0 does not read Java records: a record's own methods are not reported, the
methods after one are put under its name, and it can lose the rest of the file; so most unpaired
methods are SinewSlim's, and the harness's series does not have them.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scan_driver  # noqa: E402

LIZARD_PIN = "1.23.0"
CAP = 26


def lizard_functions(root: str) -> list[dict]:
    """Every function Lizard finds under root, as harness/metrics.py's `functions` reads it."""
    import lizard

    out = []
    for d, _, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            path = os.path.join(d, f)
            try:
                analysis = lizard.analyze_file(path)
            except Exception:
                continue
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            for fn in analysis.function_list:
                out.append({"file": rel, "name": fn.name, "nloc": int(fn.nloc),
                            "start": int(fn.start_line), "end": int(fn.end_line)})
    return out


def compare(sinewslim: list[dict], lizard_fns: list[dict]) -> dict:
    """The pairs, and what each tool has that the other does not."""
    by_end = {(m["path"], m["end_line"]): m for m in sinewslim}
    pairs, only_lizard = [], []
    seen = set()
    for fn in lizard_fns:
        m = by_end.get((fn["file"], fn["end"]))
        if m is None or (fn["file"], fn["end"]) in seen:
            only_lizard.append(fn)
            continue
        seen.add((fn["file"], fn["end"]))
        pairs.append((m, fn))
    only_sinewslim = [m for m in sinewslim if (m["path"], m["end_line"]) not in seen]
    return {"pairs": pairs, "only_lizard": only_lizard, "only_sinewslim": only_sinewslim}


def summary(pairs: list[tuple[dict, dict]], only_lizard: list[dict], only_sinewslim: list[dict]) -> dict:
    diffs = [m["length"] - fn["nloc"] for m, fn in pairs]
    long_s = [m["length"] >= CAP for m, _ in pairs]
    long_l = [fn["nloc"] >= CAP for _, fn in pairs]
    both = sum(1 for a, b in zip(long_s, long_l) if a and b)
    out = {
        "paired": len(pairs), "only_lizard": len(only_lizard), "only_sinewslim": len(only_sinewslim),
        # what the harness's series does not see at all: long methods Lizard never reports
        "long_only_sinewslim_unpaired": sum(1 for m in only_sinewslim if m["length"] >= CAP),
        "long_only_lizard_unpaired": sum(1 for fn in only_lizard if fn["nloc"] >= CAP),
        "equal": sum(1 for d in diffs if d == 0),
        "equal_share": round(sum(1 for d in diffs if d == 0) / len(diffs), 4) if diffs else None,
        "difference": None if not diffs else {
            "mean": round(statistics.fmean(diffs), 3), "median": statistics.median(diffs),
            "min": min(diffs), "max": max(diffs)},
        "long": {"both": both, "sinewslim_only": sum(1 for a, b in zip(long_s, long_l) if a and not b),
                 "lizard_only": sum(1 for a, b in zip(long_s, long_l) if b and not a)},
    }
    if len(pairs) > 2:
        xs, ys = [m["length"] for m, _ in pairs], [fn["nloc"] for _, fn in pairs]
        out["pearson_r"] = round(statistics.correlation(xs, ys), 4) if len(set(xs)) > 1 and len(set(ys)) > 1 else None
    return out


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--branch", required=True)
    ap.add_argument("--allow-run", action="append", default=[], required=True)
    ap.add_argument("--scans", required=True, help="the scan driver's --out directory")
    ap.add_argument("--out", required=True, help="the agreement, as JSON")
    ap.add_argument("--checkpoints", type=int, nargs="+", help="only these (default: every one scanned)")
    ap.add_argument("--examples", type=int, default=15, help="the largest disagreements to list")
    a = ap.parse_args(argv)

    import lizard

    if lizard.version != LIZARD_PIN:
        print(f"refused: lizard {lizard.version}, but the harness pins {LIZARD_PIN} (its series was "
              f"measured with that)", file=sys.stderr)
        return 2
    m = scan_driver.BRANCH.match(a.branch)
    if not m:
        ap.error(f"{a.branch} is not evolve/<run>/<condition>/chain<N>")
    run, condition, chain = m.group(1), m.group(2), int(m.group(3))
    if run not in a.allow_run:
        print(f"refused: run {run} is not named with --allow-run", file=sys.stderr)
        return 2
    repo = os.path.abspath(os.path.expanduser(a.repo))
    scans = os.path.join(a.scans, run, condition, f"chain{chain}")

    per_checkpoint, all_pairs, only_l, only_s = [], [], [], []
    for k, sha in scan_driver.checkpoints(repo, a.branch):
        path = os.path.join(scans, f"cp{k:02d}.sinewslim.json")
        if (a.checkpoints and k not in a.checkpoints) or not os.path.exists(path):
            continue
        with open(path) as f:
            sinewslim = json.load(f)["methods"]
        root, _ = scan_driver.backend_root(repo, sha)
        with tempfile.TemporaryDirectory() as tmp:
            scan_driver.extract(repo, sha, root, tmp)
            fns = lizard_functions(tmp)
        c = compare(sinewslim, fns)
        all_pairs += [(dict(p[0], checkpoint=k), p[1]) for p in c["pairs"]]
        only_l += c["only_lizard"]
        only_s += [dict(m, checkpoint=k) for m in c["only_sinewslim"]]
        per_checkpoint.append({"checkpoint": k, "commit": sha,
                               **summary(c["pairs"], c["only_lizard"], c["only_sinewslim"])})

    last = per_checkpoint[-1]["checkpoint"] if per_checkpoint else None
    last_pairs = [p for p in all_pairs if p[0]["checkpoint"] == last]
    worst = sorted(last_pairs, key=lambda p: -abs(p[0]["length"] - p[1]["nloc"]))[:a.examples]
    report = {
        "schema": "officefloorslim-lizard-agreement/1",
        "lizard": lizard.version, "branch": a.branch, "cap": CAP,
        "pooled": summary(all_pairs, only_l, only_s),
        "last_checkpoint": per_checkpoint[-1] if per_checkpoint else None,
        "long_unpaired_at_last": [
            {"file": m["path"], "method": m["type"] + "." + m["signature"], "line": m["line"],
             "sinewslim": m["length"]}
            for m in only_s if m["checkpoint"] == last and m["length"] >= CAP],
        "largest_differences_at_last": [
            {"file": m["path"], "method": m["signature"], "end_line": m["end_line"],
             "sinewslim": m["length"], "lizard": fn["nloc"]} for m, fn in worst],
        "per_checkpoint": per_checkpoint,
    }
    with open(a.out, "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps({"pooled": report["pooled"], "last_checkpoint": report["last_checkpoint"]}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
