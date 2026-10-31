#!/usr/bin/env python3
"""The scan driver on a made-up chain: which commits it scans, what it extracts, W1-W3, and the
refusal of a run not allowed. A stub stands in for java, so no jar is needed.

    python3 study/scan_driver_selftest.py
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import scan_driver  # noqa: E402

# Records what it was asked to scan: the files under --repo, written to --out.
STUB = """#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
if "--version" in a:
    print("stub 1.0"); sys.exit(0)
repo, out = a[a.index("--repo") + 1], a[a.index("--out") + 1]
files = sorted(os.path.relpath(os.path.join(d, f), repo) for d, _, fs in os.walk(repo) for f in fs)
with open(out, "w") as f:
    json.dump({"args": a, "files": files}, f)
"""


class DriverTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        self.repo = os.path.join(t, "repo")
        self.out = os.path.join(t, "out")
        self.java = os.path.join(t, "java")
        with open(self.java, "w") as f:
            f.write(STUB)
        os.chmod(self.java, 0o755)
        self.jar = os.path.join(t, "stub.jar")
        self.config = os.path.join(t, "config.yml")
        for p in (self.jar, self.config):
            with open(p, "w") as f:
                f.write("x")
        os.makedirs(self.repo)
        self.git("init", "-q", "-b", "base")
        self.write("stack.yaml", "layers:\n  backend:\n    root: src/main/java\n")
        self.write("src/main/java/a/A.java", "class A {}\n")
        self.write("src/test/java/a/ATest.java", "class ATest {}\n")
        self.commit("base")
        self.git("checkout", "-q", "-b", "evolve/r1/cond/chain1")
        self.write("src/main/java/a/B.java", "class B {}\n")
        self.commit("cp01 agent c1")
        self.commit("cp01 reset c1", empty=True)
        self.write("src/main/resources/officefloor/rest/x.GET.yml", "service:\n\n  class: a.B\n")
        self.commit("cp02 agent c2")
        self.commit("cp02 refactor1 c2", empty=True)
        self.commit("cp02 reset c2", empty=True)
        self.commit("cp03 agent c3", empty=True)  # not finished: no reset commit

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        subprocess.run(["git", "-C", self.repo, "-c", "user.name=t", "-c", "user.email=t@t",
                        "-c", "commit.gpgsign=false", *args], check=True, capture_output=True)

    def write(self, path, text):
        p = os.path.join(self.repo, path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(text)

    def commit(self, message, empty=False):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message, *(["--allow-empty"] if empty else []))

    def run_driver(self, *extra, run="r1"):
        return scan_driver.main(["--repo", self.repo, "--branch", "evolve/r1/cond/chain1", "--allow-run", run,
                                 "--sinewslim", self.jar, "--config", self.config, "--out", self.out,
                                 "--java", self.java, *extra])

    def read(self, name):
        with open(os.path.join(self.out, "r1", "cond", "chain1", name)) as f:
            return json.load(f)

    def test_scans_each_finished_checkpoint_backend_only(self):
        self.assertEqual(0, self.run_driver())
        m = self.read("manifest.json")
        self.assertEqual([1, 2], [c["checkpoint"] for c in m["checkpoints"]])
        log = subprocess.run(["git", "-C", self.repo, "log", "--format=%H %s"], capture_output=True, text=True).stdout
        agents = {s.split(" ")[0]: h for h, _, s in (l.partition(" ") for l in log.splitlines()) if " agent " in s}
        self.assertEqual([agents["cp01"], agents["cp02"]], [c["commit"] for c in m["checkpoints"]])  # the agent's
        self.assertEqual(["src/main/java/a/A.java", "src/main/java/a/B.java"],
                         self.read("cp02.sinewslim.json")["files"])  # no tests, paths as in the repository
        self.assertIn(self.config, self.read("cp01.sinewslim.json")["args"])
        self.assertEqual("stack.yaml", m["checkpoints"][0]["backend_root_from"])
        self.assertEqual("stub 1.0", m["sinewslim"]["version"])

    def test_wiring(self):
        self.assertEqual(0, self.run_driver())
        w1, w2 = self.read("cp01.wiring.json"), self.read("cp02.wiring.json")
        self.assertEqual((0, 0, None), (w1["w1_lines"], w1["w2_files"], w1["w3_touched"]))
        self.assertEqual((2, 1, True), (w2["w1_lines"], w2["w2_files"], w2["w3_touched"]))

    def test_only_some_checkpoints(self):
        self.assertEqual(0, self.run_driver("--checkpoints", "2", "--officefloorslim", self.jar))
        self.assertEqual([2], [c["checkpoint"] for c in self.read("manifest.json")["checkpoints"]])
        self.assertTrue(self.read("cp02.wiring.json")["w3_touched"])  # against cp01, not scanned
        self.assertEqual(["src/main/java/a/A.java", "src/main/java/a/B.java"],
                         self.read("cp02.officefloorslim.json")["files"])

    def test_refuses_a_run_not_allowed(self):
        self.assertEqual(2, self.run_driver(run="another"))
        self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
