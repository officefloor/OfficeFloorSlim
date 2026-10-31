#!/usr/bin/env python3
"""The agent-limit reading's parts on made-up data: which methods a diff touches, the agent's own
spec, the bands, and the logistic fit on data whose answer is known.

    python3 study/agent_limit_selftest.py
"""
import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import agent_limit as al  # noqa: E402


class AgentLimitTest(unittest.TestCase):

    def test_touched(self):
        ms = [{"path": "A.java", "line": 10, "end_line": 20}, {"path": "A.java", "line": 30, "end_line": 40},
              {"path": "B.java", "line": 1, "end_line": 5}]
        hit = al.touched(ms, {"A.java": [(18, 19), (41, 41)], "C.java": [(1, 9)]})
        self.assertEqual([10], [m["line"] for m in hit])  # 41 is past the second method's brace

    def test_own_spec(self):
        cap = {"checkpoint_id": "cp07_x", "tests": {"results": {
            "cp07_x.spec.ts::a": True, "cp07_x.spec.ts::b": False, "cp01_y.spec.ts::c": True}}}
        self.assertFalse(al.own_spec(cap))
        cap["tests"]["results"]["cp07_x.spec.ts::b"] = True
        self.assertTrue(al.own_spec(cap))
        self.assertIsNone(al.own_spec({"checkpoint_id": "cp08_z", "tests": {"results": {}}}))

    def test_bands_and_rates(self):
        self.assertEqual("26-50", al.band(26, al.LENGTH_BANDS))
        self.assertEqual("over 100", al.band(400, al.LENGTH_BANDS))
        rows = [{"passed": True, "x": "over 10"}, {"passed": False, "x": "1-5"}, {"passed": True, "x": "1-5"}]
        self.assertEqual([{"band": "1-5", "checkpoints": 2, "failed": 1, "fail_rate": 0.5},
                          {"band": "over 10", "checkpoints": 1, "failed": 0, "fail_rate": 0.0}],
                         al.rates(rows, lambda r: r["x"]))  # in the bands' order

    def test_logistic_recovers_a_known_effect(self):
        rng = random.Random(7)
        xs, ys = [], []
        for _ in range(3000):
            a, b = rng.gauss(0, 1), rng.gauss(0, 1)
            p = 1 / (1 + math.exp(-(-1.0 + 1.5 * a + 0.0 * b)))
            xs.append([a, b])
            ys.append(1 if rng.random() < p else 0)
        fit = al.logistic(xs, ys)
        self.assertTrue(fit["settled"])
        self.assertAlmostEqual(1.5, fit["per_sd"][0], delta=0.2)  # per sd of a ~ per unit here
        self.assertAlmostEqual(0.0, fit["per_sd"][1], delta=0.15)

    def test_separation_does_not_settle(self):
        # every failure has a above 4.5: perfectly separated, so no finite fit
        fit = al.logistic([[x, float((x * 7) % 3)] for x in range(10)], [0] * 5 + [1] * 5)
        self.assertEqual({"settled": False, "reason": "separation"}, fit)


if __name__ == "__main__":
    unittest.main()
