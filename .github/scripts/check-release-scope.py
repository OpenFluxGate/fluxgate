"""Fail closed if the approved 0.5.0 module boundary drifts. No Maven/deployment needed."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
expected = {
    'fluxgate', 'fluxgate-core', 'fluxgate-mongo-adapter', 'fluxgate-redis-ratelimiter',
    'fluxgate-spring-boot3-starter', 'fluxgate-control-support', 'fluxgate-envoy-extauth',
}
root = ET.parse(ROOT / 'pom.xml').getroot()
assert root.findtext('m:version', namespaces=NS) == '0.5.0'
excluded = {n.text for n in root.findall('.//m:excludeArtifacts/m:artifact', NS)}
artifacts = set()

def visit(directory):
    pom = ET.parse(directory / 'pom.xml').getroot()
    artifact = pom.findtext('m:artifactId', namespaces=NS)
    assert 'boot2' not in artifact and artifact != 'fluxgate-sample-standalone-java11'
    artifacts.add(artifact)
    for module in pom.findall('m:modules/m:module', NS):
        visit(directory / module.text)

visit(ROOT)
assert artifacts - excluded == expected, artifacts - excluded
assert not (ROOT / 'fluxgate-spring-boot2-starter').exists()
assert not (ROOT / 'fluxgate-samples/fluxgate-sample-standalone-java11').exists()
for path in ROOT.rglob('pom.xml'):
    if 'target' not in path.parts:
        assert 'fluxgate-spring-boot2-starter' not in path.read_text(), path
print('0.5.0 release scope verified: seven artifacts, no Boot 2 module or consumer POM.')
