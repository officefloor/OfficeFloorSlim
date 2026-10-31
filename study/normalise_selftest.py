#!/usr/bin/env python3
"""The normalisation on made-up scans and captures: pooled sum over sum, and the checkpoints
without a passing count left out of the ratios but listed.

    python3 study/normalise_selftest.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import normalise  # noqa: E402


def scan(methods, under_cap, not_slimmable, with_record):
    return {"summary": {"methods": methods, "under_cap": under_cap, "not_slimmable": not_slimmable,
                        "slimmable_with_record": with_record}}


def capture(passed, failed, **flags):
    results = {f"p{i}": True for i in range(passed)} | {f"f{i}": False for i in range(failed)}
    return {"tests": {"results": results, **flags}}


class NormaliseTest(unittest.TestCase):

    def test_pooled_and_exclusions(self):
        r = normalise.series(
            {1: scan(10, 8, 1, 1), 2: scan(20, 14, 2, 0), 3: scan(30, 20, 5, 0), 4: scan(40, 30, 1, 0)},
            {1: capture(4, 1), 2: capture(6, 0), 3: capture(9, 0, gate_invalid=True)})
        self.assertEqual({"m1": 2, "m2": 2, "methods": 10, "passing": 4, "m1n": 0.5, "m2n": 0.5, "checkpoint": 1},
                         r["rows"][0])
        self.assertEqual([3, 4], [e["checkpoint"] for e in r["excluded"]])
        self.assertEqual(["gate invalid or app broken", "no capture"], [e["reason"] for e in r["excluded"]])
        p = r["pooled"]
        self.assertEqual((2, 10, 4, 8), (p["checkpoints"], p["passing"], p["m1"], p["m2"]))
        self.assertEqual((0.4, 0.8), (p["m1n"], p["m2n"]))  # sum over sum, not a mean of ratios

    def test_only_full_gates_count(self):
        r = normalise.series({10: scan(10, 8, 1, 0), 11: scan(10, 8, 1, 0)},
                             {10: capture(5, 0, scope="full"), 11: capture(1, 0, scope="checkpoint")})
        self.assertEqual(5, r["pooled"]["passing"])
        self.assertEqual([{"checkpoint": 11, "reason": "own spec only, not the full gate"}], r["excluded"])

    def test_no_passing_tests_is_no_ratio(self):
        r = normalise.series({1: scan(5, 5, 0, 0)}, {1: capture(0, 3)})
        self.assertIsNone(r["rows"][0]["m1n"])
        self.assertIsNone(r["pooled"]["m1n"])
        self.assertEqual([{"checkpoint": 1, "reason": "no test passing"}], r["excluded"])


if __name__ == "__main__":
    unittest.main()
