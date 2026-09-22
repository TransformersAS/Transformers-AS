"""Pruebas del medidor; no son evidencia de performance/disponibilidad del sistema."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('quality_run', Path(__file__).resolve().parents[1] / 'run.py')
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)


def task(identity, node, state='running'):
    return {'id': identity, 'node': node, 'state': state}


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.baseline = [task('old1', 'pc1'), task('old2', 'pc2')]

    def test_two_replicas_on_one_node_are_not_distributed(self):
        self.assertFalse(quality.distributed([task('a', 'pc1'), task('b', 'pc1')]))
        self.assertTrue(quality.distributed(self.baseline))

    def test_healthy_without_failure_is_not_recovery(self):
        measurement = quality.Recovery(self.baseline)
        for elapsed in range(5):
            measurement.observe(self.baseline, True, elapsed)
        self.assertFalse(measurement.result()['replacement_observed'])
        self.assertIsNone(measurement.result()['observed_recovery_seconds'])

    def test_new_task_requires_distribution_and_three_up_samples(self):
        measurement = quality.Recovery(self.baseline)
        measurement.observe([task('old1', 'pc1')], True, 10)
        measurement.observe([task('old1', 'pc1'), task('new2', 'pc1')], True, 12)
        recovered = [task('old1', 'pc1'), task('new2', 'pc2')]
        measurement.observe(recovered, False, 14)
        for elapsed in (16, 18, 20):
            measurement.observe(recovered, True, elapsed)
        self.assertEqual(measurement.result()['observed_recovery_seconds'], 10)
        self.assertIsNone(measurement.invalid)

    def test_losing_both_original_tasks_invalidates_single_failure_test(self):
        measurement = quality.Recovery(self.baseline)
        measurement.observe([], False, 10)
        self.assertIsNotNone(measurement.invalid)

    def test_losing_survivor_later_is_also_invalid(self):
        measurement = quality.Recovery(self.baseline)
        measurement.observe([task('old1', 'pc1')], True, 10)
        measurement.observe([task('new1', 'pc1'), task('new2', 'pc2')], True, 12)
        self.assertIsNotNone(measurement.invalid)

    def test_rates_are_not_renamed_to_success_counts(self):
        data = {'metrics': {'http_req_failed': {'values': {'rate': 0.1, 'passes': 1, 'fails': 9}},
                            'catalog_requests': {'count': 10, 'rate': 5}}}
        extracted = quality.metrics(data)
        self.assertEqual(extracted['http_req_failed']['passes'], 1)
        self.assertEqual(extracted['catalog_requests']['count'], 10)


if __name__ == '__main__':
    unittest.main()
