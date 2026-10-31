#!/usr/bin/env python3
"""The study's scan driver (PLAN.md, Empirical study; ~/ui-long-degradation-test's
docs/SINEWSLIM_ANALYSIS_PLAN.md, section 4).

For one chain branch of the experiment, `evolve/<run>/<condition>/chain<N>`, it runs SinewSlim at
every finished checkpoint on the stack's backend Java only, and writes one JSON per checkpoint. A
checkpoint is finished when the harness has committed its `cpNN reset` boundary; what is measured
is its `cpNN agent` commit, the code as the agent left it (the analysis plan, section 3):

    OUT/<run>/<condition>/chain<N>/cpNN.sinewslim.json   SinewSlim's scan, as it wrote it
    OUT/<run>/<condition>/chain<N>/cpNN.officefloorslim.json   OfficeFloorSlim's scan, with
                                                         the categories (given --officefloorslim)
    OUT/<run>/<condition>/chain<N>/cpNN.wiring.json      W1-W3: the OfficeFloor configuration
    OUT/<run>/<condition>/chain<N>/manifest.json         what ran: tools, config, commits

The backend is the root `stack.yaml` declares at that commit (`layers.backend`), falling back to
`src/main/java` as the harness does; only that root is extracted, so test sources and the
front-end are never read. The wiring is the non-blank lines and files under
`src/main/resources/officefloor/`, and whether the checkpoint's commits changed them.

The analysis plan forbids reading any branch of the current runs before its amendments are
committed. So the driver reads a branch only when its run is named with --allow-run.

    study/scan_driver.py --repo ~/officehq-angular-spring \\
        --branch origin/evolve/202610112340/just-solve/chain1 --allow-run 202610112340 \\
        --sinewslim ~/SinewSlim/cli/build/libs/sinew-slim.jar --config docs/sinewslim-analysis.yml \\
        --out results/sinewslim
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from datetime import datetime, timezone

BRANCH = re.compile(r"^(?:origin/)?evolve/([^/]+)/([^/]+)/chain(\d+)$")
RESET = re.compile(r"^cp(\d+) reset ")
AGENT = re.compile(r"^cp(\d+) agent ")
WIRING_ROOT = "src/main/resources/officefloor/"
DEFAULT_BACKEND = "src/main/java"


def git(repo: str, *args: str, binary: bool = False):
    out = subprocess.run(["git", "-C", repo, *args], capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode("utf-8")


def checkpoints(repo: str, branch: str) -> list[tuple[int, str]]:
    """The (checkpoint, its `cpNN agent` commit) of every finished checkpoint on the branch (one
    with a `cpNN reset` commit), oldest first. A checkpoint is measured at its first agent commit,
    the code as the agent left it, before any refactor round."""
    agent, finished = {}, set()
    for line in git(repo, "log", "--reverse", "--format=%H %s", branch).splitlines():
        sha, _, subject = line.partition(" ")
        if m := AGENT.match(subject):
            agent.setdefault(int(m.group(1)), sha)
        elif m := RESET.match(subject):
            finished.add(int(m.group(1)))
    return [(k, agent[k]) for k in sorted(finished) if k in agent]


def backend_root(repo: str, sha: str) -> tuple[str, str]:
    """The backend's source root at the commit, and where it came from."""
    try:
        text = git(repo, "show", f"{sha}:stack.yaml")
    except subprocess.CalledProcessError:
        return DEFAULT_BACKEND, "default (no stack.yaml)"
    import yaml  # the harness's own dependency

    root = ((yaml.safe_load(text) or {}).get("layers") or {}).get("backend", {}).get("root")
    return (root.rstrip("/"), "stack.yaml") if root else (DEFAULT_BACKEND, "default (stack.yaml names no backend)")


def extract(repo: str, sha: str, root: str, into: str) -> int:
    """Write the tree under `root` at the commit into `into`; the number of files."""
    try:
        data = git(repo, "archive", "--format=tar", sha, "--", root, binary=True)
    except subprocess.CalledProcessError:
        return 0  # the root does not exist at this commit
    with tarfile.open(fileobj=io.BytesIO(data)) as t:
        members = [m for m in t.getmembers() if m.isfile()]
        t.extractall(into, members=members, filter="data")
        return len(members)


def wiring(repo: str, sha: str, previous: str | None) -> dict:
    """W1-W3 at the commit: the OfficeFloor configuration's non-blank lines and files, and
    whether it changed since the previous checkpoint's agent commit."""
    files = [f for f in git(repo, "ls-tree", "-r", "--name-only", sha, "--", WIRING_ROOT).splitlines() if f]
    lines = 0
    for f in files:
        text = git(repo, "show", f"{sha}:{f}", binary=True).decode("utf-8", "replace")
        lines += sum(1 for line in text.splitlines() if line.strip())
    touched = None
    if previous is not None:
        touched = bool(git(repo, "diff", "--name-only", previous, sha, "--", WIRING_ROOT).strip())
    return {"root": WIRING_ROOT, "w1_lines": lines, "w2_files": len(files), "w3_touched": touched}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo", required=True, help="the stack's base repository, with the chain branch")
    ap.add_argument("--branch", required=True, help="evolve/<run>/<condition>/chain<N> (origin/ allowed)")
    ap.add_argument("--allow-run", action="append", default=[], required=True,
                    help="a run this driver may read; repeat for more. A branch of any other run is refused")
    ap.add_argument("--sinewslim", required=True, help="SinewSlim's CLI jar (sinew-slim.jar)")
    ap.add_argument("--config", required=True, help="the pinned .sinew-slim.yml")
    ap.add_argument("--out", required=True, help="the results directory")
    ap.add_argument("--officefloorslim", help="OfficeFloorSlim's CLI jar: its scan too, for the categories "
                                              "(the go/no-go bar reads them at cp250)")
    ap.add_argument("--checkpoints", type=int, nargs="+",
                    help="only these checkpoints (default: every one on the branch)")
    ap.add_argument("--java", default="java")
    a = ap.parse_args(argv)

    m = BRANCH.match(a.branch)
    if not m:
        ap.error(f"{a.branch} is not evolve/<run>/<condition>/chain<N>")
    run, condition, chain = m.group(1), m.group(2), int(m.group(3))
    if run not in a.allow_run:
        print(f"refused: run {run} is not named with --allow-run (the analysis plan forbids reading a "
              f"current run before its amendments)", file=sys.stderr)
        return 2

    repo = os.path.abspath(os.path.expanduser(a.repo))
    jar = os.path.abspath(os.path.expanduser(a.sinewslim))
    ofs = os.path.abspath(os.path.expanduser(a.officefloorslim)) if a.officefloorslim else None
    config = os.path.abspath(os.path.expanduser(a.config))
    out = os.path.join(a.out, run, condition, f"chain{chain}")
    os.makedirs(out, exist_ok=True)

    version = subprocess.run([a.java, "-jar", jar, "--version"], capture_output=True, text=True).stdout.strip()
    cps = checkpoints(repo, a.branch)
    manifest = {
        "schema": "officefloorslim-study-scan/1",
        "repo": repo,
        "remote": git(repo, "remote", "get-url", "origin").strip() if git(repo, "remote").strip() else None,
        "branch": a.branch,
        "run": run, "condition": condition, "chain": chain,
        "sinewslim": {"jar_sha256": sha256(jar), "version": version},
        "officefloorslim": None if ofs is None else {
            "jar_sha256": sha256(ofs),
            "version": subprocess.run([a.java, "-jar", ofs, "--version"], capture_output=True,
                                      text=True).stdout.strip()},
        "config": {"path": a.config, "sha256": sha256(config)},
        "started": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "checkpoints": [],
    }

    previous = None
    for k, sha in cps:
        if a.checkpoints and k not in a.checkpoints:
            previous = sha  # W3 is against the checkpoint before, scanned or not
            continue
        root, provenance = backend_root(repo, sha)
        with tempfile.TemporaryDirectory(prefix=f"cp{k:02d}-") as tmp:
            n = extract(repo, sha, root, tmp)
            result = os.path.join(out, f"cp{k:02d}.sinewslim.json")
            done = subprocess.run([a.java, "-jar", jar, "scan", "--repo", tmp, "--config", config,
                                   "--format", "json", "--out", result], capture_output=True, text=True)
            if done.returncode != 0:
                print(f"cp{k:02d} {sha[:10]}: SinewSlim failed ({done.returncode}): {done.stderr.strip()}",
                      file=sys.stderr)
                return 1
            if ofs is not None:
                # the same backend tree; outside git, so no growth (the driver's series is the growth)
                done = subprocess.run([a.java, "-jar", ofs, "scan", "--repo", tmp, "--include", "**/*.java",
                                       "--format", "json",
                                       "--out", os.path.join(out, f"cp{k:02d}.officefloorslim.json")],
                                      capture_output=True, text=True)
                if done.returncode != 0:
                    print(f"cp{k:02d} {sha[:10]}: OfficeFloorSlim failed ({done.returncode}): "
                          f"{done.stderr.strip()}", file=sys.stderr)
                    return 1
        w = wiring(repo, sha, previous)
        with open(os.path.join(out, f"cp{k:02d}.wiring.json"), "w") as f:
            json.dump({"checkpoint": k, "commit": sha, **w}, f, indent=2)
        manifest["checkpoints"].append({"checkpoint": k, "commit": sha, "backend_root": root,
                                        "backend_root_from": provenance, "backend_files": n})
        print(f"cp{k:02d} {sha[:10]}: {n} backend files", file=sys.stderr)
        previous = sha

    manifest["finished"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"{len(manifest['checkpoints'])} of {len(cps)} checkpoints of {a.branch} -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
