#!/usr/bin/env python3
"""The Lizard comparison's pairing and summary, on made-up methods (Lizard itself is not needed).

    python3 study/lizard_compare_selftest.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lizard_compare as lc  # noqa: E402


def s(path, end, length, sig="f()"):
    return {"path": path, "end_line": end, "length": length, "signature": sig, "type": "T", "line": end - length}


def z(path, end, nloc):
    return {"file": path, "end": end, "nloc": nloc, "name": "T::f", "start": end - nloc}


class CompareTest(unittest.TestCase):

    def test_pairs_by_file_and_last_line(self):
        c = lc.compare([s("A.java", 10, 5), s("A.java", 40, 30), s("B.java", 10, 27)],
                       [z("A.java", 10, 5), z("A.java", 40, 31), z("C.java", 3, 2)])
        self.assertEqual(2, len(c["pairs"]))
        self.assertEqual(["B.java"], [m["path"] for m in c["only_sinewslim"]])
        self.assertEqual(["C.java"], [f["file"] for f in c["only_lizard"]])

    def test_summary(self):
        c = lc.compare([s("A.java", 10, 5), s("A.java", 40, 25), s("B.java", 10, 27)],
                       [z("A.java", 10, 5), z("A.java", 40, 26)])
        r = lc.summary(c["pairs"], c["only_lizard"], c["only_sinewslim"])
        self.assertEqual((2, 1, 0.5), (r["paired"], r["equal"], r["equal_share"]))
        self.assertEqual({"both": 0, "sinewslim_only": 0, "lizard_only": 1}, r["long"])  # 25 against 26
        self.assertEqual(1, r["long_only_sinewslim_unpaired"])  # B's 27 lines Lizard never reported
        self.assertEqual({"mean": -0.5, "median": -0.5, "min": -1, "max": 0}, r["difference"])


if __name__ == "__main__":
    unittest.main()
