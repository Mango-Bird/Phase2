import argparse
import json
import os
import statistics
import sys


LOAD_RUNS = ('load-run-01', 'load-run-02', 'load-run-03')
HOT_WALLET_RUN = 'hot-wallet-run-01'
EXPECTED_WALLETS = {'load': 250, 'hot-wallet': 201}
REQUIRED_PATHS = (
    ('k6ExitCode',), ('plannedDurationSec',), ('requests',), ('achievedRps',),
    ('latencyMs', 'p50'), ('latencyMs', 'p95'), ('latencyMs', 'p99'),
    ('unexpectedFailures',), ('httpFailedRate',), ('invariantViolations',),
    ('walletReconciliation', 'walletCount'),
    ('walletReconciliation', 'mismatchCount'),
    ('walletReconciliation', 'negativeBalanceCount'),
    ('rawMetrics', 'sha256'),
)


def nested(item, path):
    value = item
    for key in path:
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def stats(values):
    if not values:
        return None
    mean = statistics.fmean(values)
    return {
        'min': min(values),
        'median': statistics.median(values),
        'max': max(values),
        'mean': mean,
        'coefficientOfVariation': statistics.pstdev(values) / mean if mean else 0,
    }


def read_summary(evidence_dir, name, profile, errors):
    path = os.path.join(evidence_dir, name, 'run-summary.json')
    if not os.path.isfile(path):
        errors.append(f'missing required run summary: {name}/run-summary.json')
        return None
    try:
        with open(path, encoding='utf-8') as file:
            item = json.load(file)
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f'cannot read {name}/run-summary.json: {error}')
        return None
    missing = ['.'.join(parts) for parts in REQUIRED_PATHS if nested(item, parts) is None]
    if missing:
        errors.append(f'{name} missing required fields: {", ".join(missing)}')
    expected = EXPECTED_WALLETS[profile]
    actual = nested(item, ('walletReconciliation', 'walletCount'))
    if actual != expected:
        errors.append(
            f'{name} wallet coverage mismatch: expected {expected}, found {actual}')
    item['name'] = name
    item['_profile'] = profile
    item['_coverage'] = actual == expected
    item['_complete'] = not missing and actual == expected
    return item


def run_view(run):
    return {
        'name': run['name'],
        'requests': nested(run, ('requests',)),
        'achievedRps': nested(run, ('achievedRps',)),
        'p50Ms': nested(run, ('latencyMs', 'p50')),
        'p95Ms': nested(run, ('latencyMs', 'p95')),
        'p99Ms': nested(run, ('latencyMs', 'p99')),
        'unexpectedFailures': nested(run, ('unexpectedFailures',)),
        'httpFailedRate': nested(run, ('httpFailedRate',)),
        'invariantViolations': nested(run, ('invariantViolations',)),
        'walletCount': nested(run, ('walletReconciliation', 'walletCount')),
        'walletMismatchCount': nested(run, ('walletReconciliation', 'mismatchCount')),
        'negativeBalanceCount': nested(run, ('walletReconciliation', 'negativeBalanceCount')),
        'deadlocks': nested(run, ('appLogCounts', 'deadlocks')) or 0,
        'rawSha256': nested(run, ('rawMetrics', 'sha256')),
    }


def all_equal(runs, path, expected):
    return bool(runs) and all(nested(run, path) == expected for run in runs)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence-dir', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args(argv)

    evidence_dir = os.path.abspath(args.evidence_dir)
    errors = []
    load_runs = [
        read_summary(evidence_dir, name, 'load', errors) for name in LOAD_RUNS
    ]
    load_runs = [run for run in load_runs if run is not None]
    hot = read_summary(evidence_dir, HOT_WALLET_RUN, 'hot-wallet', errors)
    all_runs = load_runs + ([hot] if hot is not None else [])

    required_present = len(load_runs) == len(LOAD_RUNS) and hot is not None
    required_fields = required_present and all(
        all(nested(run, parts) is not None for parts in REQUIRED_PATHS)
        for run in all_runs)
    expected_coverage = required_present and all(run['_coverage'] for run in all_runs)
    all_k6_exit_zero = required_present and all_equal(all_runs, ('k6ExitCode',), 0)
    all_duration = required_present and all_equal(all_runs, ('plannedDurationSec',), 1920)
    all_reconciled = required_present and all_equal(
        all_runs, ('walletReconciliation', 'mismatchCount'), 0)
    no_negative = required_present and all_equal(
        all_runs, ('walletReconciliation', 'negativeBalanceCount'), 0)
    no_client_violations = required_present and all_equal(
        all_runs, ('invariantViolations',), 0)

    mandatory_passed = all((
        required_present, required_fields, expected_coverage, not errors, all_k6_exit_zero,
        all_duration, all_reconciled, no_negative, no_client_violations,
    ))

    numeric = lambda path: [nested(run, path) for run in load_runs
                            if isinstance(nested(run, path), (int, float))]
    aggregate = {
        'runs': [run_view(run) for run in load_runs],
        'statistics': {
            'achievedRps': stats(numeric(('achievedRps',))),
            'p50Ms': stats(numeric(('latencyMs', 'p50'))),
            'p95Ms': stats(numeric(('latencyMs', 'p95'))),
            'p99Ms': stats(numeric(('latencyMs', 'p99'))),
            'unexpectedFailures': stats(numeric(('unexpectedFailures',))),
        },
        'hotWallet': run_view(hot) if hot is not None else None,
        'gate': {
            'requiredRunSummariesPresent': required_present,
            'requiredFieldsPresent': required_fields,
            'expectedWalletCoverage': expected_coverage,
            'allK6ExitZero': all_k6_exit_zero,
            'allRunsPlannedDuration': all_duration,
            'allWalletsReconciled': all_reconciled,
            'noNegativeBalances': no_negative,
            'noClientObservedInvariantViolations': no_client_violations,
            'mandatoryPassed': mandatory_passed,
            'performanceErrorsAreInformational': True,
        },
        'completenessErrors': errors,
    }
    output = os.path.abspath(args.output)
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, 'w', encoding='utf-8') as file:
        json.dump(aggregate, file, ensure_ascii=False, indent=2)
        file.write('\n')
    return 0 if mandatory_passed else 2


if __name__ == '__main__':
    sys.exit(main())
