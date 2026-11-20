"""Regression coverage for the CI sample startup evidence gate."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/check-sample-startup-tests.py"
SPEC = importlib.util.spec_from_file_location("sample_gate", SCRIPT)
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class SampleStartupEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "report.xml"
        self.root = ET.Element("testsuite", name=GATE.SUITE, tests="3", failures="0", errors="0", skipped="0")
        for name in sorted(GATE.EXPECTED):
            ET.SubElement(self.root, "testcase", name=name, classname=GATE.SUITE)

    def check(self):
        ET.ElementTree(self.root).write(self.path)
        return GATE.check_report(self.path)

    def test_accepts_three_executed_tests(self):
        self.assertEqual(3, self.check())

    def test_rejects_summary_skips_failures_or_errors(self):
        for outcome in ("skipped", "failures", "errors"):
            with self.subTest(outcome=outcome):
                self.root.set(outcome, "1")
                with self.assertRaises(ValueError):
                    self.check()
                self.root.set(outcome, "0")

    def test_rejects_hidden_testcase_outcomes(self):
        for outcome in ("skipped", "failure", "error"):
            with self.subTest(outcome=outcome):
                child = ET.SubElement(self.root[0], outcome)
                with self.assertRaises(ValueError):
                    self.check()
                self.root[0].remove(child)

    def test_rejects_missing_required_test(self):
        self.root.remove(self.root[0])
        self.root.set("tests", "2")
        with self.assertRaises(ValueError):
            self.check()

    def test_rejects_missing_report(self):
        with self.assertRaises(OSError):
            GATE.check_report(self.path)

    def test_rejects_count_mismatch(self):
        self.root.set("tests", "4")
        with self.assertRaises(ValueError):
            self.check()

    def test_rejects_wrong_suite_or_class(self):
        self.root.set("name", "OtherTest")
        with self.assertRaises(ValueError):
            self.check()
        self.root.set("name", GATE.SUITE)
        self.root[0].set("classname", "OtherTest")
        with self.assertRaises(ValueError):
            self.check()

    def test_rejects_missing_counter(self):
        del self.root.attrib["skipped"]
        with self.assertRaises(KeyError):
            self.check()

    def test_rejects_duplicate_names(self):
        ET.SubElement(self.root, "testcase", **self.root[0].attrib)
        self.root.set("tests", "4")
        with self.assertRaises(ValueError):
            self.check()


if __name__ == "__main__":
    unittest.main()
