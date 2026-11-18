"""Constrain the marketplace action's advisory-only exception to its approved dependency."""

import json
import sys

ADVISORY = "GHSA-4wrc-f8pq-fpqp"
ACCEPTED = {
    "manifest": "fluxgate-spring-boot2-starter/pom.xml",
    "ecosystem": "maven",
    "name": "org.springframework:spring-web",
    "version": "5.3.39",
    "package_url": "pkg:maven/org.springframework/spring-web@5.3.39",
    "scope": "runtime",
}


def check(changes):
    if not isinstance(changes, list):
        raise ValueError("expected an array of dependency changes")
    for change in changes:
        if change["change_type"] == "removed":
            continue
        if change["change_type"] != "added":
            raise ValueError("unknown dependency change type")
        for advisory in change["vulnerabilities"]:
            if advisory["advisory_ghsa_id"] != ADVISORY:
                continue  # The marketplace action enforces every other advisory.
            identity = {key: change.get(key) for key in ACCEPTED}
            if identity != ACCEPTED:
                raise ValueError(f"{ADVISORY} outside accepted scope: {json.dumps(identity)}")
            print(f"Accepted {ADVISORY} in {ACCEPTED['manifest']} at {ACCEPTED['package_url']}")


if __name__ == "__main__":
    try:
        check(json.load(sys.stdin))
    except (TypeError, KeyError, ValueError) as error:
        sys.exit(f"Dependency advisory scope check failed: {error}")
