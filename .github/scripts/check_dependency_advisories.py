"""Enforce high/critical dependency advisories across the complete PR comparison."""

import argparse
import json
import os
import re
import sys
import time
from datetime import date, datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from xml.etree import ElementTree

HTTP_INVOKER = "GHSA-4wrc-f8pq-fpqp"
BOOT2_MANIFEST = "fluxgate-spring-boot2-starter/pom.xml"
BOOT3_MANIFEST = "fluxgate-spring-boot3-starter/pom.xml"

# Existing HTTP Invoker exception. Its exact runtime identity governs separately.
RUNTIME_EXCEPTION = {
    "manifest": BOOT2_MANIFEST,
    "ecosystem": "maven",
    "name": "org.springframework:spring-web",
    "version": "5.3.39",
    "package_url": "pkg:maven/org.springframework/spring-web@5.3.39",
    "scope": "runtime",
}

# Release maintainer review is required before this exclusive UTC expiry.
TEST_EXCEPTION_OWNER = "release maintainer"
TEST_EXCEPTION_EXPIRY = date(2026, 12, 19)
TEST_EXCEPTIONS = {
    (BOOT2_MANIFEST, "5.3.39", "GHSA-3chg-m5w7-qfv5", "high"),
    (BOOT2_MANIFEST, "5.3.39", "GHSA-cx7f-g6mp-7hqm", "high"),
    (BOOT2_MANIFEST, "5.3.39", "GHSA-g5vr-rgqm-vf78", "high"),
    (BOOT2_MANIFEST, "5.3.39", "GHSA-pc63-qcmh-9cmg", "critical"),
    (BOOT2_MANIFEST, "5.3.39", "GHSA-x23c-287f-qqv5", "high"),
    (BOOT3_MANIFEST, "6.2.19", "GHSA-j9f9-w8pj-32f8", "critical"),
    (BOOT3_MANIFEST, "6.2.19", "GHSA-pc63-qcmh-9cmg", "critical"),
}
BLOCKING_SEVERITIES = {"high", "critical"}
SCOPES = ("runtime", "development", "unknown")
PAGE_SIZE = 5


def check(changes, today):
    if not isinstance(changes, list):
        raise ValueError("expected an array of dependency changes")
    accepted = []
    blocked = []
    for change in changes:
        if not isinstance(change, dict) or change.get("change_type") not in ("added", "removed"):
            raise ValueError("malformed dependency change")
        if change["change_type"] == "removed":
            continue

        identity = {key: change.get(key) for key in RUNTIME_EXCEPTION}
        # GitHub can omit scope. Treat it as unknown for enforcement.
        if "scope" not in change:
            identity["scope"] = "unknown"
        if (
            not all(isinstance(value, str) for value in identity.values())
            or identity["scope"] not in SCOPES
            or not isinstance(change.get("vulnerabilities"), list)
        ):
            raise ValueError("malformed added dependency")

        manifest = identity["manifest"]
        version = identity["version"]
        scope = identity["scope"]
        for finding in change["vulnerabilities"]:
            if not isinstance(finding, dict):
                raise ValueError("malformed advisory")
            advisory = finding.get("advisory_ghsa_id")
            severity = finding.get("severity")
            if (
                not isinstance(advisory, str)
                or not re.fullmatch(r"GHSA-(?:[0-9a-z]{4}-){2}[0-9a-z]{4}", advisory)
                or severity not in ("low", "moderate", "high", "critical")
            ):
                raise ValueError("malformed advisory")

            description = f"{advisory} ({severity}) {json.dumps(identity, sort_keys=True)}"
            if advisory == HTTP_INVOKER:
                if identity == RUNTIME_EXCEPTION:
                    accepted.append((advisory, f"{description} (existing HTTP Invoker exception)"))
                else:
                    blocked.append(f"{description} (outside HTTP Invoker exception)")
                continue
            if severity not in BLOCKING_SEVERITIES:
                continue

            test_exception = (
                scope == "development"
                and identity["ecosystem"] == "maven"
                and identity["name"] == "org.springframework:spring-webmvc"
                and identity["package_url"] == f"pkg:maven/org.springframework/spring-webmvc@{version}"
                and (manifest, version, advisory, severity) in TEST_EXCEPTIONS
            )
            if test_exception and today < TEST_EXCEPTION_EXPIRY:
                accepted.append(
                    (advisory, f"{description} (expires {TEST_EXCEPTION_EXPIRY} UTC, owner: {TEST_EXCEPTION_OWNER})")
                )
            elif test_exception:
                blocked.append(f"{description} (exception expired {TEST_EXCEPTION_EXPIRY} UTC)")
            else:
                blocked.append(f"{description} (outside approved advisory exceptions)")

    if blocked:
        raise ValueError("unaccepted advisories:\n  " + "\n  ".join(blocked))
    return accepted


class SnapshotNotReady(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def page_links(endpoint, link_header):
    links = {}
    for entry in link_header.split(",") if link_header else []:
        match = re.fullmatch(r'\s*<([^>]+)>;\s*rel="(first|prev|next|last)"\s*', entry)
        if not match:
            raise ValueError("malformed dependency comparison pagination")
        url, relation = match.groups()
        link = urlsplit(url)
        endpoint_parts = urlsplit(endpoint)
        query = parse_qs(link.query, strict_parsing=True)
        page_numbers = query.get("page", [])
        if (
            (link.scheme, link.netloc, link.path)
            != (endpoint_parts.scheme, endpoint_parts.netloc, endpoint_parts.path)
            or link.fragment
            or set(query) != {"page", "per_page"}
            or query["per_page"] != [str(PAGE_SIZE)]
            or len(page_numbers) != 1
            or not re.fullmatch(r"[1-9][0-9]*", page_numbers[0])
            or relation in links
        ):
            raise ValueError("unexpected dependency comparison page link")
        links[relation] = int(page_numbers[0])
    return links


def comparison(endpoint, token, opener):
    page = 1
    expected_last = None
    changes = []
    while True:
        request = Request(
            f"{endpoint}?per_page={PAGE_SIZE}&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with opener.open(request, timeout=30) as response:
            if response.status != 200:
                raise ValueError(f"unexpected dependency comparison status {response.status}")
            if response.headers.get("X-GitHub-Dependency-Graph-Snapshot-Warnings", "").strip():
                raise SnapshotNotReady("GitHub reported dependency graph snapshot warnings")
            rows = json.load(response)
            if not isinstance(rows, list):
                raise ValueError(f"expected an array on dependency comparison page {page}")
            changes.extend(rows)
            links = page_links(endpoint, ", ".join(response.headers.get_all("Link", [])))

        if "last" in links:
            if expected_last is not None and links["last"] != expected_last:
                raise ValueError("dependency comparison page count changed")
            expected_last = links["last"]
        next_page = links.get("next")
        if next_page is None:
            if expected_last not in (None, page):
                raise ValueError("dependency comparison ended before the final page")
            return changes, page
        if next_page != page + 1 or (expected_last is not None and next_page > expected_last):
            raise ValueError("non-sequential dependency comparison pagination")
        page = next_page


def fetch_comparison(endpoint, token):
    opener = build_opener(NoRedirect())
    # Start again at the first page if a snapshot is still being indexed.
    for attempt in range(3):
        try:
            return comparison(endpoint, token, opener)
        except (SnapshotNotReady, HTTPError, URLError, TimeoutError) as error:
            if isinstance(error, HTTPError) and error.code not in (404, 429, 500, 502, 503, 504):
                raise
            if attempt == 2:
                raise
            print("Dependency comparison temporarily unavailable. Retrying the full comparison.")
            time.sleep(5 * (attempt + 1))


def check_release(effective_pom, today):
    # Inspect the current effective model even when both targets already exist on main.
    waived_targets = {
        (manifest.removesuffix("/pom.xml"), version) for manifest, version, _, _ in TEST_EXCEPTIONS
    }
    expected_projects = {artifact for artifact, _ in waived_targets}
    root = ElementTree.parse(effective_pom).getroot()
    projects = root.findall("{*}project")
    project_ids = [project.findtext("{*}artifactId") for project in projects]
    if (
        root.tag != "projects"
        or len(project_ids) != len(expected_projects)
        or set(project_ids) != expected_projects
    ):
        raise ValueError("expected effective Maven models for both FluxGate starters")

    expired = []
    for project, artifact in zip(projects, project_ids):
        if project.findtext("{*}groupId") != "io.github.openfluxgate":
            raise ValueError(f"unexpected Maven project {artifact}")
        mvc = [
            dep for dep in project.findall("{*}dependencies/{*}dependency")
            if dep.findtext("{*}groupId") == "org.springframework"
            and dep.findtext("{*}artifactId") == "spring-webmvc"
        ]
        if not mvc:
            continue
        if len(mvc) != 1:
            raise ValueError(f"ambiguous effective spring-webmvc dependency in {artifact}")

        version = mvc[0].findtext("{*}version", "")
        scope = mvc[0].findtext("{*}scope", "compile")
        if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]*", version) or version in ("LATEST", "RELEASE"):
            raise ValueError(f"unresolved effective spring-webmvc version in {artifact}")
        if (artifact, version) in waived_targets:
            if scope != "test":
                raise ValueError(f"{artifact} spring-webmvc@{version} outside approved test scope")
            if today >= TEST_EXCEPTION_EXPIRY:
                expired.append(f"{artifact} spring-webmvc@{version}")
    if expired:
        raise ValueError(f"test advisory exceptions expired {TEST_EXCEPTION_EXPIRY} UTC: {', '.join(expired)}")


def review_pull_request():
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        event = json.load(event_file)
    base = event["pull_request"]["base"]["sha"]
    head = event["pull_request"]["head"]["sha"]
    repository = event["repository"]["id"]
    if (
        not isinstance(base, str) or not re.fullmatch(r"[a-f0-9]{40}", base)
        or not isinstance(head, str) or not re.fullmatch(r"[a-f0-9]{40}", head)
        or type(repository) is not int or repository <= 0
    ):
        raise ValueError("expected an immutable PR comparison and base repository ID")

    endpoint = f"{os.environ['GITHUB_API_URL']}/repositories/{repository}/dependency-graph/compare/{base}...{head}"
    changes, pages = fetch_comparison(endpoint, os.environ["GITHUB_TOKEN"])
    print(f"Retrieved {len(changes)} dependency changes across {pages} pages for {base}...{head}.")
    accepted = check(changes, datetime.now(timezone.utc).date())
    for _, finding in accepted:
        print(f"Accepted {finding}")
    approved_ghsas = ",".join(sorted({advisory for advisory, _ in accepted}))
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output_file:
        output_file.write(f"approved_ghsas={approved_ghsas or 'none'}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-effective-pom", metavar="FILE")
    args = parser.parse_args()
    if args.release_effective_pom:
        today = datetime.now(timezone.utc).date()
        check_release(args.release_effective_pom, today)
        print(f"Release advisory expiry check passed on {today} UTC.")
    else:
        review_pull_request()


if __name__ == "__main__":
    try:
        main()
    except (KeyError, TypeError, ValueError, OSError, ElementTree.ParseError, SnapshotNotReady) as error:
        sys.exit(f"Dependency advisory scope check failed: {error}")
