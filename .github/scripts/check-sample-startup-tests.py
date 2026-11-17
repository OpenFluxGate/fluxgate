#!/usr/bin/env python3
"""Require the Java 11 sample's real startup tests in CI, including zero skips."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

SUITE = "org.fluxgate.sample.standalone.java11.StandaloneSampleStartupTest"
EXPECTED = {
    "startsWithTheShippedConfiguration",
    "multiFilterEndpointIsLimitedByMultiFilterRules",
    "compositeEndpointIsLimitedByCompositeKeyRules",
}
DEFAULT_REPORT = (
    Path(__file__).resolve().parents[2]
    / "fluxgate-samples/fluxgate-sample-standalone-java11/target/surefire-reports"
    / ("TEST-" + SUITE + ".xml")
)


def check_report(path):
    root = ET.parse(path).getroot()
    if root.tag != "testsuite" or root.get("name") != SUITE:
        raise ValueError("Unexpected sample startup test suite")
    cases = root.findall("testcase")
    names = [case.get("name") for case in cases]
    if not EXPECTED.issubset(names) or len(names) != len(set(names)):
        raise ValueError("Required sample startup tests are missing or duplicated")
    if int(root.attrib["tests"]) != len(cases):
        raise ValueError("Sample startup test count does not match testcase entries")
    for outcome in ("failures", "errors", "skipped"):
        if int(root.attrib[outcome]) != 0:
            raise ValueError("Sample startup tests must have zero " + outcome)
    for case in cases:
        if case.get("classname") != SUITE:
            raise ValueError("Unexpected sample startup testcase class")
        if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise ValueError("A sample startup testcase did not execute successfully")
    return len(cases)


if __name__ == "__main__":
    try:
        count = check_report(Path(sys.argv[1]) if len(sys.argv) == 2 else DEFAULT_REPORT)
    except (OSError, ET.ParseError, KeyError, ValueError) as error:
        print("FAIL: sample startup execution evidence: " + str(error), file=sys.stderr)
        sys.exit(1)
    print(f"PASS: {count} sample startup tests executed; failures/errors/skips=0")
