#!/usr/bin/env bash
# Evidence collection, NOT a vulnerability exemption. Run with a build JDK >=17.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${BOOT2_JAVA_HOME:?Set BOOT2_JAVA_HOME to the Java 11 runtime to verify}"
command -v python3 >/dev/null || { echo 'python3 is required for XML verification' >&2; exit 1; }
runtime_major=$("$BOOT2_JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
  | awk '$1 == "java.specification.version" && $2 == "=" {print $3}')
if [[ "$runtime_major" != "11" ]]; then
  echo "BOOT2_JAVA_HOME must select Java 11; found: $runtime_major" >&2
  exit 1
fi
report=fluxgate-spring-boot2-starter/target/surefire-reports/TEST-org.fluxgate.spring.autoconfigure.HttpInvokerExposureAuditTest.xml
# A skipped Maven execution must never reuse an earlier successful report.
rm -f "$report"
mkdir -p target/security-evidence
./mvnw -B -pl fluxgate-spring-boot2-starter -am \
  -Dtest=HttpInvokerExposureAuditTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Djvm="$BOOT2_JAVA_HOME/bin/java" test
python3 - "$report" <<'PYXML'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
properties = {p.get('name'): p.get('value') for p in root.findall('./properties/property')}
required = {
    'defaultStarterContextDoesNotRegisterOrMapAnHttpInvoker',
    'auditDetectsAnApplicationSuppliedInvokerAndItsMappedEndpoint',
    'auditRunsAgainstBoot2Spring5AndDoesNotHideTheFlaggedClass',
}
cases = root.findall('testcase')
assert properties.get('java.specification.version') == '11', 'Test JVM was not Java 11'
assert int(root.get('tests', '0')) == len(cases) >= 3, 'Missing executed tests'
assert required.issubset({c.get('name') for c in cases}), 'Missing required audit cases'
for status in ('failures', 'errors', 'skipped'):
    assert int(root.get(status, '-1')) == 0, 'Nonzero or missing ' + status
assert not any(c.find(tag) is not None for c in cases for tag in ('failure', 'error', 'skipped'))
print('Verified fresh Java 11 Surefire report: all required audit tests ran without skips.')
PYXML
./mvnw -B -pl fluxgate-spring-boot2-starter -am dependency:tree \
  -Dincludes=org.springframework:spring-web,org.springframework:spring-webmvc -Dverbose \
  | tee target/security-evidence/boot2-web-dependencies.log
grep -Eq 'org.springframework:spring-web:jar:5\.3\.[0-9]+:compile' \
  target/security-evidence/boot2-web-dependencies.log
printf '%s\n' 'Evidence collected. GHSA-4wrc-f8pq-fpqp remains flagged; no security gate exemption is granted.'
