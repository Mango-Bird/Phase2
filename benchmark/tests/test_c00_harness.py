import csv
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = REPO_ROOT / 'benchmark' / 'scripts'


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


summarize_run = load_script('summarize_run')
aggregate_runs = load_script('aggregate_runs')
source_provenance = load_script('source_provenance')


class SummarizeRunTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.run_dir = self.root / 'run'
        self.run_dir.mkdir()
        profiles = self.root / 'digitalwallet-stress-test' / 'config' / 'profiles'
        profiles.mkdir(parents=True)
        for name, accounts, receiving in (
                ('smoke', 5, 10), ('load', 200, 50), ('hot-wallet', 200, 1)):
            (profiles / f'{name}.json').write_text(json.dumps({
                'name': name, 'accounts': accounts, 'receivingWallets': receiving,
            }), encoding='utf-8')
        self.write_valid_summary()
        self.write_reconciliation(15)
        self.write_raw()

    def tearDown(self):
        self.temp.cleanup()

    def write_valid_summary(self, omit_metric=None):
        metrics = {
            'http_reqs': {'count': 100, 'rate': 2.0},
            'http_req_duration': {
                'avg': 10.0, 'med': 8.0, 'p(95)': 20.0, 'p(99)': 30.0, 'max': 40.0,
            },
            'http_req_failed': {'value': 0.2},
            'request_outcome': {'count': 2},
            'unexpected_failure': {'passes': 2, 'fails': 0},
            'checks': {'fails': 2},
        }
        metrics.pop(omit_metric, None)
        (self.run_dir / 'k6_summary.json').write_text(json.dumps({
            'metrics': metrics,
            'setup_data': {'startTime': 1000, 'testEndTime': 181000},
        }), encoding='utf-8')

    def write_reconciliation(self, count, mismatch=False, negative=False):
        with (self.run_dir / 'reconciliation.tsv').open(
                'w', newline='', encoding='utf-8') as file:
            writer = csv.DictWriter(file, fieldnames=(
                'wallet_id', 'wallet_number', 'actual_balance',
                'expected_balance', 'delta'), delimiter='\t')
            writer.writeheader()
            for index in range(count):
                actual = -1 if negative and index == 0 else 100
                expected = 99 if mismatch and index == 0 else actual
                writer.writerow({
                    'wallet_id': index + 1,
                    'wallet_number': f'W{index + 1}',
                    'actual_balance': actual,
                    'expected_balance': expected,
                    'delta': actual - expected,
                })

    def write_raw(self, invariant=False):
        fieldnames = ('metric_name', 'metric_value', 'extra_tags')
        with (self.run_dir / 'k6_raw.csv').open(
                'w', newline='', encoding='utf-8') as file:
            writer = csv.DictWriter(file, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerow({
                'metric_name': 'request_outcome', 'metric_value': 1,
                'extra_tags': (
                    'label=failure&request=transfer&expected=201&actual=409'),
            })
            writer.writerow({
                'metric_name': 'request_outcome', 'metric_value': 1,
                'extra_tags': (
                    'label=failure&request=transfer&expected=201&actual=500'),
            })
            if invariant:
                writer.writerow({
                    'metric_name': 'invariant_violation', 'metric_value': 1,
                    'extra_tags': 'kind=negative-balance',
                })

    def invoke(self):
        return summarize_run.main([
            '--run-dir', str(self.run_dir), '--k6-exit', '0',
            '--profile', 'smoke', '--repo-root', str(self.root),
        ])

    def report(self):
        return json.loads((self.run_dir / 'run-summary.json').read_text(encoding='utf-8'))

    def test_valid_evidence_passes_with_409_and_500_observations(self):
        self.assertEqual(0, self.invoke())
        report = self.report()
        self.assertTrue(report['gate']['mandatoryPassed'])
        self.assertEqual({'409', '500'}, {
            item['actual'] for item in report['failuresByRequestAndActual']})

    def test_header_only_reconciliation_writes_report_and_fails(self):
        self.write_reconciliation(0)
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['completeness']['reconciliationHasData'])
        self.assertIn('reconciliation.tsv contains no wallet data',
                      report['completenessErrors'])

    def test_invalid_reconciliation_row_writes_report_and_fails(self):
        self.write_reconciliation(15)
        with (self.run_dir / 'reconciliation.tsv').open(
                'a', newline='', encoding='utf-8') as file:
            file.write('invalid\tW-invalid\tnot-a-number\t100\t0\n')
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertIn(
            'invalid reconciliation row at line 17', report['completenessErrors'])

    def test_header_only_raw_writes_report_and_fails(self):
        with (self.run_dir / 'k6_raw.csv').open(
                'w', newline='', encoding='utf-8') as file:
            csv.DictWriter(file, fieldnames=(
                'metric_name', 'metric_value', 'extra_tags')).writeheader()
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['completeness']['rawMetricsPresent'])
        self.assertIn('k6_raw.csv contains no data rows', report['completenessErrors'])

    def test_raw_without_request_outcome_writes_report_and_fails(self):
        with (self.run_dir / 'k6_raw.csv').open(
                'w', newline='', encoding='utf-8') as file:
            writer = csv.DictWriter(file, fieldnames=(
                'metric_name', 'metric_value', 'extra_tags'))
            writer.writeheader()
            writer.writerow({
                'metric_name': 'http_reqs', 'metric_value': 1, 'extra_tags': '',
            })
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['completeness']['rawMetricsPresent'])
        self.assertIn(
            'k6_raw.csv contains no valid request_outcome samples',
            report['completenessErrors'])

    def test_raw_summary_counter_mismatch_writes_report_and_fails(self):
        summary = json.loads(
            (self.run_dir / 'k6_summary.json').read_text(encoding='utf-8'))
        summary['metrics']['request_outcome']['count'] = 3
        (self.run_dir / 'k6_summary.json').write_text(
            json.dumps(summary), encoding='utf-8')
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['rawMetrics']['summaryCountersConsistent'])
        self.assertTrue(any(
            error.startswith('raw/summary counter mismatch for request_outcome count')
            for error in report['completenessErrors']))

    def test_truncated_raw_writes_report_and_fails(self):
        (self.run_dir / 'k6_raw.csv').write_text(
            'metric_name,metric_value,extra_tags\n'
            'request_outcome,1,'
            'label=failure&request=transfer&expected=201&actual=409',
            encoding='utf-8')
        self.write_valid_summary()
        summary = json.loads(
            (self.run_dir / 'k6_summary.json').read_text(encoding='utf-8'))
        summary['metrics']['request_outcome']['count'] = 1
        summary['metrics']['unexpected_failure'] = {'passes': 1, 'fails': 0}
        summary['metrics']['checks']['fails'] = 1
        (self.run_dir / 'k6_summary.json').write_text(
            json.dumps(summary), encoding='utf-8')
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['completeness']['rawMetricsPresent'])
        self.assertIn(
            'k6_raw.csv does not end with a complete CSV record',
            report['completenessErrors'])

    def test_missing_wallet_coverage_writes_report_and_fails(self):
        self.write_reconciliation(14)
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['walletReconciliation']['coverageComplete'])

    def test_missing_required_metric_writes_report_and_fails(self):
        self.write_valid_summary(omit_metric='http_req_duration')
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['completeness']['requiredMetricsPresent'])

    def test_financial_failures_write_report_and_return_nonzero(self):
        for case in ('mismatch', 'negative', 'client-violation'):
            with self.subTest(case=case):
                self.write_reconciliation(
                    15, mismatch=case == 'mismatch', negative=case == 'negative')
                self.write_raw(invariant=case == 'client-violation')
                self.assertNotEqual(0, self.invoke())
                self.assertFalse(self.report()['gate']['mandatoryPassed'])


class AggregateRunTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.output = self.root / 'aggregate.json'
        for name in aggregate_runs.LOAD_RUNS:
            self.write_summary(name, 250)
        self.write_summary(aggregate_runs.HOT_WALLET_RUN, 201)

    def tearDown(self):
        self.temp.cleanup()

    def write_summary(self, name, wallet_count, mismatch=0, negative=0, violations=0):
        directory = self.root / name
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'run-summary.json').write_text(json.dumps({
            'k6ExitCode': 0,
            'plannedDurationSec': 1920,
            'requests': 1000,
            'achievedRps': 90.0,
            'latencyMs': {'p50': 10.0, 'p95': 200.0, 'p99': 4000.0},
            'unexpectedFailures': 123,
            'httpFailedRate': 0.12,
            'invariantViolations': violations,
            'walletReconciliation': {
                'walletCount': wallet_count,
                'mismatchCount': mismatch,
                'negativeBalanceCount': negative,
            },
            'rawMetrics': {'sha256': 'a' * 64},
            'appLogCounts': {'deadlocks': 50},
        }), encoding='utf-8')

    def invoke(self):
        return aggregate_runs.main([
            '--evidence-dir', str(self.root), '--output', str(self.output),
        ])

    def report(self):
        return json.loads(self.output.read_text(encoding='utf-8'))

    def test_valid_complete_set_passes_despite_performance_errors(self):
        self.assertEqual(0, self.invoke())
        report = self.report()
        self.assertTrue(report['gate']['mandatoryPassed'])
        self.assertGreater(report['hotWallet']['unexpectedFailures'], 0)
        self.assertGreater(report['hotWallet']['httpFailedRate'], 0)

    def test_missing_hot_wallet_writes_report_and_fails(self):
        (self.root / aggregate_runs.HOT_WALLET_RUN / 'run-summary.json').unlink()
        self.assertNotEqual(0, self.invoke())
        report = self.report()
        self.assertFalse(report['gate']['requiredRunSummariesPresent'])
        self.assertIsNone(report['hotWallet'])

    def test_financial_failures_write_report_and_return_nonzero(self):
        cases = {
            'mismatch': {'mismatch': 1},
            'negative': {'negative': 1},
            'client-violation': {'violations': 1},
        }
        for case, values in cases.items():
            with self.subTest(case=case):
                self.write_summary(aggregate_runs.HOT_WALLET_RUN, 201, **values)
                self.assertNotEqual(0, self.invoke())
                self.assertFalse(self.report()['gate']['mandatoryPassed'])

    def test_real_failed_summarizer_report_propagates_to_aggregate(self):
        profiles = self.root / 'digitalwallet-stress-test' / 'config' / 'profiles'
        profiles.mkdir(parents=True)
        (profiles / 'load.json').write_text(json.dumps({
            'name': 'load', 'accounts': 200, 'receivingWallets': 50,
        }), encoding='utf-8')

        run_dir = self.root / aggregate_runs.LOAD_RUNS[0]
        (run_dir / 'k6_summary.json').write_text(json.dumps({
            'metrics': {
                'http_reqs': {'count': 2, 'rate': 1.0},
                'http_req_duration': {
                    'avg': 10.0, 'med': 8.0, 'p(95)': 20.0,
                    'p(99)': 30.0, 'max': 40.0,
                },
                'http_req_failed': {'value': 0.0},
                'request_outcome': {'count': 2},
                'unexpected_failure': {'passes': 0, 'fails': 2},
                'checks': {'fails': 0},
            },
            'setup_data': {'startTime': 1000, 'testEndTime': 1921000},
        }), encoding='utf-8')
        with (run_dir / 'reconciliation.tsv').open(
                'w', newline='', encoding='utf-8') as file:
            writer = csv.DictWriter(file, fieldnames=(
                'wallet_id', 'wallet_number', 'actual_balance',
                'expected_balance', 'delta'), delimiter='\t')
            writer.writeheader()
            for index in range(250):
                writer.writerow({
                    'wallet_id': index + 1, 'wallet_number': f'W{index + 1}',
                    'actual_balance': 100, 'expected_balance': 100, 'delta': 0,
                })
        (run_dir / 'k6_raw.csv').write_text(
            'metric_name,extra_tags\n'
            'request_outcome,label=success&request=transfer&expected=201&actual=201\n',
            encoding='utf-8')

        summarize_exit = summarize_run.main([
            '--run-dir', str(run_dir), '--k6-exit', '0', '--profile', 'load',
            '--repo-root', str(self.root),
        ])
        failed_run = json.loads(
            (run_dir / 'run-summary.json').read_text(encoding='utf-8'))
        self.assertNotEqual(0, summarize_exit)
        self.assertFalse(failed_run['gate']['mandatoryPassed'])
        self.assertTrue(failed_run['completenessErrors'])

        self.assertNotEqual(0, self.invoke())
        aggregate = self.report()
        self.assertFalse(aggregate['gate']['allPerRunMandatoryGatesPassed'])
        self.assertFalse(aggregate['gate']['mandatoryPassed'])
        self.assertTrue(any(
            error.startswith('load-run-01:')
            for error in aggregate['completenessErrors']))


class SourceProvenanceTests(unittest.TestCase):
    def test_fingerprint_ignores_files_excluded_from_docker_context(self):
        with tempfile.TemporaryDirectory() as directory:
            context = Path(directory) / 'digitalwallet-system'
            (context / 'gradle').mkdir(parents=True)
            (context / 'src').mkdir()
            for name in ('Dockerfile', 'gradlew', 'build.gradle', 'settings.gradle'):
                (context / name).write_text(name, encoding='utf-8')
            (context / '.dockerignore').write_text('.DS_Store\n', encoding='utf-8')
            (context / 'gradle' / 'wrapper.properties').write_text('wrapper', encoding='utf-8')
            (context / 'src' / 'Main.java').write_text('class Main {}', encoding='utf-8')
            before = source_provenance.fingerprint(directory)
            (context / 'src' / '.DS_Store').write_text('ignored', encoding='utf-8')
            self.assertEqual(before, source_provenance.fingerprint(directory))

    def test_stale_running_image_is_rejected(self):
        errors = source_provenance.provenance_errors(
            'revision', 'fingerprint', 'sha256:new', 'sha256:old',
            'revision', 'fingerprint')
        self.assertTrue(any('stale running image' in error for error in errors))

    def test_source_fingerprint_mismatch_is_rejected(self):
        errors = source_provenance.provenance_errors(
            'revision', 'new-fingerprint', 'sha256:image', 'sha256:image',
            'revision', 'old-fingerprint')
        self.assertTrue(any('source fingerprint mismatch' in error for error in errors))

    def test_matching_image_and_source_are_accepted(self):
        self.assertEqual([], source_provenance.provenance_errors(
            'revision', 'fingerprint', 'sha256:image', 'sha256:image',
            'revision', 'fingerprint'))


if __name__ == '__main__':
    unittest.main()
