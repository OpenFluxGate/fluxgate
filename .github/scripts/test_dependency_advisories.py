import io
import json
import os
import unittest
from datetime import date
from email.message import Message
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from xml.etree import ElementTree

from check_dependency_advisories import (
    BOOT2_MANIFEST,
    BOOT3_MANIFEST,
    HTTP_INVOKER,
    RUNTIME_EXCEPTION,
    TEST_EXCEPTION_EXPIRY,
    SnapshotNotReady,
    check,
    check_release,
    comparison,
    fetch_comparison,
    review_pull_request,
)

TODAY = date(2026, 12, 18)
SHAS = f"{'a' * 40}...{'b' * 40}"
ENDPOINT = f"https://api.github.com/repositories/7317/dependency-graph/compare/{SHAS}"
BOOT2_IDS = {
    "GHSA-3chg-m5w7-qfv5": "high",
    "GHSA-cx7f-g6mp-7hqm": "high",
    "GHSA-g5vr-rgqm-vf78": "high",
    "GHSA-pc63-qcmh-9cmg": "critical",
    "GHSA-x23c-287f-qqv5": "high",
}
BOOT3_IDS = {"GHSA-j9f9-w8pj-32f8": "critical", "GHSA-pc63-qcmh-9cmg": "critical"}


def findings(identities):
    return [
        {"advisory_ghsa_id": advisory, "severity": severity}
        for advisory, severity in identities.items()
    ]


def mvc(manifest=BOOT2_MANIFEST, version="5.3.39", advisories=BOOT2_IDS):
    return {
        "change_type": "added",
        "manifest": manifest,
        "ecosystem": "maven",
        "name": "org.springframework:spring-webmvc",
        "version": version,
        "package_url": f"pkg:maven/org.springframework/spring-webmvc@{version}",
        "scope": "development",
        "vulnerabilities": findings(advisories),
    }


def page_url(page):
    return f"{ENDPOINT}?per_page=5&page={page}"


class AdvisoryPolicyTest(unittest.TestCase):
    def setUp(self):
        self.invoker = dict(
            RUNTIME_EXCEPTION, change_type="added", vulnerabilities=findings({HTTP_INVOKER: "critical"})
        )
        self.boot2 = mvc()
        self.boot3 = mvc(BOOT3_MANIFEST, "6.2.19", BOOT3_IDS)

    def test_exact_seven_test_rows_and_existing_runtime_exception(self):
        self.assertEqual(8, len(check([self.invoker, self.boot2, self.boot3], TODAY)))
        self.assertEqual([], check([dict(self.boot2, change_type="removed")], TODAY))
        self.assertEqual([], check([], TODAY))

    def test_each_dimension_must_match_even_after_an_accepted_occurrence(self):
        for change in (self.invoker, self.boot2, self.boot3):
            for field in ("manifest", "ecosystem", "name", "version", "package_url", "scope"):
                value = "runtime" if field == "scope" and change["scope"] == "development" else "unknown"
                with self.subTest(field=field, original=change["manifest"]), self.assertRaisesRegex(
                    ValueError, "outside"
                ):
                    check([change, dict(change, **{field: value})], TODAY)

    def test_same_advisory_only_covers_approved_boot_manifests_and_versions(self):
        shared = {"GHSA-pc63-qcmh-9cmg": "critical"}
        self.assertEqual(2, len(check([mvc(advisories=shared), mvc(BOOT3_MANIFEST, "6.2.19", shared)], TODAY)))
        for row in (mvc(BOOT3_MANIFEST, advisories=shared), mvc("other/pom.xml", advisories=shared)):
            with self.subTest(row=row), self.assertRaisesRegex(ValueError, "outside"):
                check([self.boot2, self.boot3, row], TODAY)
        with self.assertRaisesRegex(ValueError, "outside"):
            check([mvc(BOOT3_MANIFEST, "6.2.19")], TODAY)

    def test_expiry_is_exclusive_and_separate_from_http_invoker(self):
        with self.assertRaisesRegex(ValueError, "expired"):
            check([self.boot2], TEST_EXCEPTION_EXPIRY)
        with self.assertRaisesRegex(ValueError, "expired"):
            check([self.boot3], date(2026, 12, 20))
        self.assertEqual(1, len(check([self.invoker], TEST_EXCEPTION_EXPIRY)))

    def test_new_high_or_critical_is_blocked_in_every_scope(self):
        for severity in ("high", "critical"):
            for scope in ("runtime", "unknown", "development"):
                row = dict(self.boot2, scope=scope, vulnerabilities=findings({"GHSA-0000-0000-0000": severity}))
                with self.subTest(scope=scope, severity=severity), self.assertRaisesRegex(ValueError, "outside"):
                    check([self.boot2, row], TODAY)
                if scope == "unknown":
                    del row["scope"]
                    with self.assertRaisesRegex(ValueError, "outside"):
                        check([row], TODAY)

    def test_severity_escalation_is_blocked_and_lower_unrelated_severities_pass(self):
        with self.assertRaisesRegex(ValueError, "outside"):
            check([mvc(advisories={"GHSA-3chg-m5w7-qfv5": "critical"})], TODAY)
        self.assertEqual(
            [],
            check([mvc(advisories={"GHSA-0000-0000-0000": "moderate", "GHSA-1111-1111-1111": "low"})], TODAY),
        )

    def test_malformed_comparison_fails_closed(self):
        for changes in (
            None, {}, [None], [{}], [dict(self.boot2, scope=None)],
            [dict(self.boot2, change_type="other")], [dict(self.boot2, vulnerabilities=None)],
            [dict(self.boot2, vulnerabilities=[None])], [dict(self.boot2, vulnerabilities=[{}])],
            [mvc(advisories={"bad-id": "critical"})], [mvc(advisories={"GHSA-0000-0000-0000": "unknown"})],
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                check(changes, TODAY)


def pom_project(artifact, version=None, scope="test"):
    dependency = ""
    if version is not None:
        dependency = (
            "<dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId>"
            f"<version>{version}</version><scope>{scope}</scope></dependency>"
        )
    return (
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.openfluxgate</groupId>'
        f"<artifactId>{artifact}</artifactId><dependencies>{dependency}</dependencies></project>"
    )


BOOT2_ARTIFACT = "fluxgate-spring-boot2-starter"
BOOT3_ARTIFACT = "fluxgate-spring-boot3-starter"


class ReleaseExpiryTest(unittest.TestCase):
    @staticmethod
    def verify(today, *projects):
        return check_release(io.StringIO("<projects>" + "".join(projects) + "</projects>"), today)

    def test_both_exact_test_versions_pass_only_before_the_deadline(self):
        boot2 = pom_project(BOOT2_ARTIFACT, "5.3.39")
        boot3 = pom_project(BOOT3_ARTIFACT, "6.2.19")
        self.verify(TODAY, boot2, boot3)
        with self.assertRaisesRegex(ValueError, r"expired.*5[.]3[.]39.*6[.]2[.]19"):
            self.verify(TEST_EXCEPTION_EXPIRY, boot2, boot3)

    def test_either_exact_version_alone_blocks_after_expiry(self):
        for projects, finding in (
            ((pom_project(BOOT2_ARTIFACT, "5.3.39"), pom_project(BOOT3_ARTIFACT)), BOOT2_ARTIFACT),
            ((pom_project(BOOT2_ARTIFACT, "5.3.40"), pom_project(BOOT3_ARTIFACT, "6.2.19")), BOOT3_ARTIFACT),
        ):
            with self.subTest(finding=finding), self.assertRaisesRegex(ValueError, f"expired.*{finding}"):
                self.verify(TEST_EXCEPTION_EXPIRY, *projects)

    def test_upgraded_or_removed_mvc_pass_after_expiry(self):
        self.verify(TEST_EXCEPTION_EXPIRY, pom_project(BOOT2_ARTIFACT, "5.3.40"), pom_project(BOOT3_ARTIFACT))
        self.verify(TEST_EXCEPTION_EXPIRY, pom_project(BOOT2_ARTIFACT), pom_project(BOOT3_ARTIFACT, "6.2.20"))

    def test_unreadable_incomplete_or_unresolved_models_are_rejected(self):
        with self.assertRaises(ElementTree.ParseError):
            check_release(io.StringIO("<broken>"), TODAY)
        with self.assertRaisesRegex(ValueError, "both FluxGate"):
            self.verify(TODAY, pom_project(BOOT2_ARTIFACT))
        with self.assertRaisesRegex(ValueError, "both FluxGate"):
            self.verify(TODAY, pom_project(BOOT2_ARTIFACT), pom_project(BOOT2_ARTIFACT))
        with self.assertRaisesRegex(ValueError, "unresolved"):
            self.verify(TODAY, pom_project(BOOT2_ARTIFACT, "${spring.version}"), pom_project(BOOT3_ARTIFACT))

    def test_exact_version_in_non_test_scope_is_unaccepted_even_before_expiry(self):
        with self.assertRaisesRegex(ValueError, "outside approved test scope"):
            self.verify(TODAY, pom_project(BOOT2_ARTIFACT, "5.3.39", "runtime"), pom_project(BOOT3_ARTIFACT))


class MarketplaceHandoffTest(unittest.TestCase):
    def test_only_successful_full_checks_emit_a_deduplicated_sorted_list_or_explicit_empty_result(self):
        with TemporaryDirectory() as temp:
            event = Path(temp, "event.json")
            output = Path(temp, "output")
            base, head = SHAS.split("...")
            event.write_text(json.dumps({
                "repository": {"id": 7317},
                "pull_request": {"base": {"sha": base}, "head": {"sha": head}},
            }))
            environment = {
                "GITHUB_EVENT_PATH": str(event), "GITHUB_OUTPUT": str(output),
                "GITHUB_API_URL": "https://api.github.com", "GITHUB_TOKEN": "test-token",
            }
            with (
                patch.dict(os.environ, environment),
                patch("check_dependency_advisories.fetch_comparison") as fetch,
                patch("check_dependency_advisories.datetime") as clock,
            ):
                clock.now.return_value.date.return_value = TODAY
                fetch.return_value = ([mvc(), mvc(BOOT3_MANIFEST, "6.2.19", BOOT3_IDS)], 2)
                review_pull_request()
                expected_ids = ",".join(sorted(BOOT2_IDS.keys() | BOOT3_IDS.keys()))
                self.assertEqual(f"approved_ghsas={expected_ids}\n", output.read_text())
                fetch.assert_called_once_with(ENDPOINT, "test-token")

                fetch.return_value = ([], 1)
                review_pull_request()
                expected_output = f"approved_ghsas={expected_ids}\napproved_ghsas=none\n"
                self.assertEqual(expected_output, output.read_text())

                fetch.return_value = ([mvc(), dict(mvc(), scope="runtime")], 1)
                with self.assertRaisesRegex(ValueError, "unaccepted advisories"):
                    review_pull_request()
                self.assertEqual(expected_output, output.read_text())


class Page(io.BytesIO):
    status = 200

    def __init__(self, data, link=None, snapshot_warning=None):
        super().__init__(json.dumps(data).encode())
        self.headers = Message()
        if link:
            self.headers["Link"] = link
        if snapshot_warning:
            self.headers["X-GitHub-Dependency-Graph-Snapshot-Warnings"] = snapshot_warning


class ComparisonTest(unittest.TestCase):
    def test_retrieves_and_checks_a_new_finding_on_the_last_page(self):
        next_links = f'<{page_url(2)}>; rel="next", <{page_url(2)}>; rel="last"'
        late = mvc(advisories={"GHSA-0000-0000-0000": "high"})
        opener = Mock()
        opener.open.side_effect = [Page([mvc()], next_links), Page([late])]
        rows, pages = comparison(ENDPOINT, "test-token", opener)
        self.assertEqual(2, pages)
        self.assertEqual([page_url(1), page_url(2)], [call.args[0].full_url for call in opener.open.call_args_list])
        with self.assertRaisesRegex(ValueError, "GHSA-0000"):
            check(rows, TODAY)

    def test_rejects_missing_page_malformed_payload_and_wrong_comparison_links(self):
        for page in (
            Page({}, None),
            Page([], f'<{page_url(4)}>; rel="next"'),
            Page([], f'<{page_url(2)}>; rel="last"'),
            Page([], f'<{page_url(2).replace("7317", "7318")}>; rel="next"'),
            Page([], f'<{page_url(2).replace("https://", "http://")}>; rel="next"'),
            Page([], f'<{page_url(2).replace(SHAS, SHAS[::-1])}>; rel="next"'),
        ):
            opener = Mock()
            opener.open.return_value = page
            with self.subTest(page=page), self.assertRaises(ValueError):
                comparison(ENDPOINT, "test-token", opener)
            opener.open.assert_called_once()

    @patch("check_dependency_advisories.time.sleep")
    @patch("check_dependency_advisories.build_opener")
    def test_persistent_second_page_failure_fails_closed_after_bounded_retries(self, build_opener, sleep):
        first_page_link = f'<{page_url(2)}>; rel="next", <{page_url(2)}>; rel="last"'
        failure = HTTPError(page_url(2), 503, "unavailable", {}, None)
        build_opener.return_value.open.side_effect = [
            item for _ in range(3) for item in (Page([mvc()], first_page_link), failure)
        ]
        with self.assertRaises(HTTPError):
            fetch_comparison(ENDPOINT, "test-token")
        self.assertEqual(6, build_opener.return_value.open.call_count)
        self.assertEqual(2, sleep.call_count)

    @patch("check_dependency_advisories.time.sleep")
    @patch("check_dependency_advisories.build_opener")
    def test_snapshot_warnings_retry_the_complete_diff_and_never_accept_partial_rows(self, build_opener, sleep):
        next_link = f'<{page_url(2)}>; rel="next"'
        build_opener.return_value.open.side_effect = [
            Page([mvc()], next_link),
            Page([], snapshot_warning="pending"),
            Page([], snapshot_warning="pending"),
            Page([], snapshot_warning="pending"),
        ]
        with self.assertRaises(SnapshotNotReady):
            fetch_comparison(ENDPOINT, "test-token")
        urls = [call.args[0].full_url for call in build_opener.return_value.open.call_args_list]
        self.assertEqual([page_url(1), page_url(2), page_url(1), page_url(1)], urls)
        self.assertEqual(2, sleep.call_count)

    @patch("check_dependency_advisories.time.sleep")
    @patch("check_dependency_advisories.build_opener")
    def test_retries_successfully_after_snapshot_warning(self, build_opener, sleep):
        build_opener.return_value.open.side_effect = [Page([], snapshot_warning="pending"), Page([mvc()])]
        self.assertEqual(([mvc()], 1), fetch_comparison(ENDPOINT, "test-token"))
        sleep.assert_called_once_with(5)


if __name__ == "__main__":
    unittest.main()
