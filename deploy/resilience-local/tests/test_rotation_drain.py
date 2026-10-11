"""Offline fixture drain budget checks; no cluster or external YAML package."""
import ast
from pathlib import Path
import re
import unittest

HERE = Path(__file__).resolve().parents[1]


class RotationDrainTest(unittest.TestCase):
    def test_external_configuration_retains_packaged_graceful_shutdown(self):
        tree = ast.parse((HERE / 'setup.py').read_text())
        configurations = [node.value for node in ast.walk(tree) if isinstance(node, ast.Assign)
                          and any(isinstance(target, ast.Name) and target.id == 'configuration'
                                  for target in node.targets)]
        self.assertEqual(len(configurations), 1)
        config = ast.literal_eval(configurations[0])
        self.assertEqual(config['server'].get('shutdown'), 'graceful')
        self.assertEqual(config['spring']['lifecycle']['timeout-per-shutdown-phase'], '20s')
        self.assertEqual(config['server']['ssl']['client-auth'], 'need')
        self.assertIn('file:/credentials/api/application-credentials.yml', config['spring']['config']['import'])

    def test_authz_native_prestop_and_shutdown_fit_inside_pod_grace(self):
        documents = (HERE / 'stack.yaml').read_text().split('---')
        authz = [document for document in documents if 'kind: Deployment' in document
                 and 'metadata: {name: fluxgate-authz, namespace: fluxgate-resilience}' in document]
        self.assertEqual(len(authz), 1)
        deployment = authz[0]
        grace = re.search(r'^      terminationGracePeriodSeconds: (\d+)$', deployment, re.M)
        sleep = re.search(r'^          lifecycle:\n            preStop:\n              sleep:\n                seconds: (\d+)$', deployment, re.M)
        self.assertIsNotNone(grace)
        self.assertIsNotNone(sleep)
        self.assertGreaterEqual(int(sleep[1]), 10)
        self.assertGreaterEqual(int(grace[1]) - int(sleep[1]) - 20, 15)
        self.assertIn('replicas: 2', deployment)
        self.assertIn('maxSurge: 0', deployment)
        self.assertIn('podAntiAffinity:', deployment)


if __name__ == '__main__':
    unittest.main()
