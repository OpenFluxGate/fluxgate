#!/usr/bin/env bash
# Evidence collection, NOT a vulnerability exemption. Run with a build JDK >=17.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${BOOT2_JAVA_HOME:?Set BOOT2_JAVA_HOME to the Java 11 runtime to verify}"
mkdir -p target/security-evidence
./mvnw -B -pl fluxgate-spring-boot2-starter -am \
  -Dtest=HttpInvokerExposureAuditTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Djvm="$BOOT2_JAVA_HOME/bin/java" test
./mvnw -B -pl fluxgate-spring-boot2-starter -am dependency:tree \
  -Dincludes=org.springframework:spring-web,org.springframework:spring-webmvc -Dverbose \
  | tee target/security-evidence/boot2-web-dependencies.log
grep -Eq 'org.springframework:spring-web:jar:5\.3\.[0-9]+:compile' \
  target/security-evidence/boot2-web-dependencies.log
printf '%s\n' 'Evidence collected. GHSA-4wrc-f8pq-fpqp remains flagged; no security gate exemption is granted.'
