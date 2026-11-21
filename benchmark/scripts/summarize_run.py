import argparse
import csv
import hashlib
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from urllib.parse import parse_qsl


REQUIRED_RECONCILIATION_COLUMNS = {
    'wallet_id', 'wallet_number', 'actual_balance', 'expected_balance', 'delta'
}
REQUIRED_RAW_COLUMNS = {'metric_name', 'metric_value', 'extra_tags'}
REQUIRED_METRICS = {
    'http_reqs': {'count', 'rate'},
    'http_req_duration': {'avg', 'med', 'p(95)', 'p(99)', 'max'},
    'http_req_failed': {'value'},
    'unexpected_failure': {'passes'},
    'checks': {'fails'},
}


def metric(metrics, name, key):
    return metrics.get(name, {}).get(key)


def expected_wallet_count(profile, repo_root):
    path = os.path.join(
        repo_root, 'digitalwallet-stress-test', 'config', 'profiles', f'{profile}.json')
    with open(path, encoding='utf-8') as file:
        config = json.load(file)
    if config.get('name') != profile:
        raise ValueError(f'profile config name mismatch in {path}')
    accounts = config.get('accounts')
    receiving = config.get('receivingWallets')
    if not isinstance(accounts, int) or accounts < 1:
        raise ValueError(f'invalid accounts in {path}')
    if not isinstance(receiving, int) or receiving < 1:
        raise ValueError(f'invalid receivingWallets in {path}')
    return accounts + receiving


def validate_required_metrics(metrics):
    errors = []
    for name, required_keys in REQUIRED_METRICS.items():
        value = metrics.get(name)
        if not isinstance(value, dict):
            errors.append(f'missing required k6 metric: {name}')
            continue
        missing = sorted(key for key in required_keys if value.get(key) is None)
        if missing:
            errors.append(f'metric {name} missing values: {", ".join(missing)}')
    return errors


def read_reconciliation(path, errors):
    wallets = []
    if not os.path.isfile(path):
        errors.append('missing reconciliation.tsv')
        return wallets
    try:
        with open(path, newline='', encoding='utf-8-sig') as file:
            reader = csv.DictReader(file, delimiter='\t')
            actual_columns = set(reader.fieldnames or [])
            missing = sorted(REQUIRED_RECONCILIATION_COLUMNS - actual_columns)
            if missing:
                errors.append(
                    f'reconciliation.tsv missing columns: {", ".join(missing)}')
                return wallets
            for line_number, row in enumerate(reader, start=2):
                try:
                    wallets.append({
                        'walletId': int(row['wallet_id']),
                        'actual': int(row['actual_balance']),
                        'expected': int(row['expected_balance']),
                        'delta': int(row['delta']),
                    })
                except (TypeError, ValueError):
                    errors.append(f'invalid reconciliation row at line {line_number}')
    except (OSError, csv.Error) as error:
        errors.append(f'cannot read reconciliation.tsv: {error}')
    if not wallets:
        errors.append('reconciliation.tsv contains no wallet data')
    return wallets


def read_raw_metrics(path, errors):
    failures = Counter()
    failure_details = Counter()
    violations = 0
    digest = hashlib.sha256()
    raw_bytes = None
    if not os.path.isfile(path):
        errors.append('missing k6_raw.csv')
        return failures, failure_details, violations, raw_bytes, None
    try:
        raw_bytes = os.path.getsize(path)
        with open(path, 'rb') as file:
            for block in iter(lambda: file.read(1024 * 1024), b''):
                digest.update(block)
        with open(path, newline='', encoding='utf-8-sig') as file:
            reader = csv.DictReader(file)
            actual_columns = set(reader.fieldnames or [])
            missing = sorted(REQUIRED_RAW_COLUMNS - actual_columns)
            if missing:
                errors.append(f'k6_raw.csv missing columns: {", ".join(missing)}')
                return failures, failure_details, violations, raw_bytes, digest.hexdigest()
            for row in reader:
                metric_name = row.get('metric_name')
                if metric_name not in ('request_outcome', 'invariant_violation'):
                    continue
                tags = dict(parse_qsl(row.get('extra_tags') or '', keep_blank_values=True))
                if metric_name == 'invariant_violation':
                    violations += 1
                elif tags.get('label') == 'failure':
                    request = tags.get('request', 'unknown')
                    actual = tags.get('actual', 'unknown')
                    failures[(request, actual)] += 1
                    detail = tags.get('detail', '')
                    detail_key = detail[:300]
                    if 'Deadlock found when trying to get lock' in detail:
                        detail_key = '500 | database deadlock while updating wallets'
                    elif 'Could not open JPA EntityManager for transaction' in detail:
                        detail_key = (
                            '500 | could not open JPA EntityManager '
                            '(connection pool unavailable)')
                    else:
                        try:
                            body = json.loads(detail)
                            detail_key = ' | '.join(
                                str(body.get(key, ''))
                                for key in ('status', 'error', 'message', 'path')
                            ).strip(' |')
                        except (json.JSONDecodeError, TypeError, AttributeError):
                            pass
                    failure_details[(request, actual, detail_key)] += 1
    except (OSError, csv.Error) as error:
        errors.append(f'cannot read k6_raw.csv: {error}')
    return failures, failure_details, violations, raw_bytes, digest.hexdigest()


def resource_summary(run_dir):
    values_by_key = defaultdict(list)
    path = os.path.join(run_dir, 'sampler.csv')
    if os.path.exists(path):
        with open(path, newline='', encoding='utf-8') as file:
            for row in csv.DictReader(file):
                try:
                    labels = json.loads(row.get('labels') or '{}')
                    key = (row['category'], row['metric'], labels.get('container', 'host'))
                    values_by_key[key].append(float(row['value']))
                except (ValueError, json.JSONDecodeError, KeyError):
                    continue
    summary = {}
    for (category, name, subject), values in values_by_key.items():
        if not values:
            continue
        item = {
            'min': min(values), 'avg': sum(values) / len(values),
            'max': max(values), 'samples': len(values),
        }
        if category == 'mysql' and name in (
                'Queries', 'Innodb_row_lock_waits', 'Innodb_row_lock_time'):
            item['delta'] = values[-1] - values[0]
        summary[f'{category}.{name}.{subject}'] = item
    return summary


def app_log_counts(run_dir):
    counts = {'deadlocks': 0, 'errors': 0, 'warnings': 0}
    path = os.path.join(run_dir, 'app.log')
    if os.path.exists(path):
        with open(path, encoding='utf-8', errors='replace') as file:
            for line in file:
                counts['deadlocks'] += 'Deadlock found' in line
                counts['errors'] += ' ERROR ' in line
                counts['warnings'] += ' WARN ' in line
    return counts


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--k6-exit', type=int, required=True)
    parser.add_argument('--profile', choices=('smoke', 'load', 'hot-wallet'), required=True)
    parser.add_argument('--repo-root')
    args = parser.parse_args(argv)

    run_dir = os.path.abspath(args.run_dir)
    repo_root = os.path.abspath(args.repo_root or os.path.join(
        os.path.dirname(__file__), '..', '..'))
    errors = []
    try:
        expected_wallets = expected_wallet_count(args.profile, repo_root)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        expected_wallets = None
        errors.append(f'cannot determine expected wallet coverage: {error}')

    summary_path = os.path.join(run_dir, 'k6_summary.json')
    try:
        with open(summary_path, encoding='utf-8') as file:
            k6 = json.load(file)
    except (OSError, json.JSONDecodeError) as error:
        k6 = {}
        errors.append(f'cannot read k6_summary.json: {error}')
    metrics = k6.get('metrics', {}) if isinstance(k6, dict) else {}
    errors.extend(validate_required_metrics(metrics))

    wallets = read_reconciliation(os.path.join(run_dir, 'reconciliation.tsv'), errors)
    failures, failure_details, violations, raw_bytes, raw_sha256 = read_raw_metrics(
        os.path.join(run_dir, 'k6_raw.csv'), errors)

    start_ms = k6.get('setup_data', {}).get('startTime') if isinstance(k6, dict) else None
    end_ms = k6.get('setup_data', {}).get('testEndTime') if isinstance(k6, dict) else None
    planned_duration = ((end_ms - start_ms) / 1000) if start_ms and end_ms else None
    if planned_duration is None:
        errors.append('k6 summary missing setup_data startTime/testEndTime')

    wallet_count = len(wallets)
    mismatch_count = sum(1 for wallet in wallets if wallet['delta'] != 0)
    negative_count = sum(1 for wallet in wallets if wallet['actual'] < 0)
    coverage_complete = expected_wallets is not None and wallet_count == expected_wallets
    if expected_wallets is not None and not coverage_complete:
        errors.append(
            f'wallet coverage mismatch for {args.profile}: '
            f'expected {expected_wallets}, found {wallet_count}')

    financial_gate = {
        'allWalletsReconciled': bool(wallets) and mismatch_count == 0,
        'noNegativeBalances': bool(wallets) and negative_count == 0,
        'noClientObservedInvariantViolations': violations == 0,
    }
    completeness_gate = {
        'requiredMetricsPresent': not any(
            error.startswith(('missing required k6 metric', 'metric ')) for error in errors),
        'reconciliationHasData': bool(wallets),
        'expectedWalletCoverage': coverage_complete,
        'rawMetricsPresent': raw_bytes is not None and raw_sha256 is not None,
        'evidenceComplete': not errors,
    }
    mandatory_gate_passed = (
        all(financial_gate.values()) and all(completeness_gate.values()))

    result = {
        'generatedAt': datetime.now(timezone.utc).isoformat(),
        'profile': args.profile,
        'k6ExitCode': args.k6_exit,
        'plannedDurationSec': planned_duration,
        'executor': 'ramping-vus',
        'droppedIterations': metric(metrics, 'dropped_iterations', 'count'),
        'droppedIterationsNote': (
            None if 'dropped_iterations' in metrics
            else 'N/A: ramping-vus is a closed-model executor and does not emit dropped_iterations.'
        ),
        'requests': metric(metrics, 'http_reqs', 'count'),
        'achievedRps': metric(metrics, 'http_reqs', 'rate'),
        'latencyMs': {
            'avg': metric(metrics, 'http_req_duration', 'avg'),
            'p50': metric(metrics, 'http_req_duration', 'med'),
            'p95': metric(metrics, 'http_req_duration', 'p(95)'),
            'p99': metric(metrics, 'http_req_duration', 'p(99)'),
            'max': metric(metrics, 'http_req_duration', 'max'),
        },
        'httpFailedRate': metric(metrics, 'http_req_failed', 'value'),
        'unexpectedFailures': metric(metrics, 'unexpected_failure', 'passes'),
        'checksFailed': metric(metrics, 'checks', 'fails'),
        'failuresByRequestAndActual': [
            {'request': request, 'actual': actual, 'count': count}
            for (request, actual), count in sorted(failures.items())
        ],
        'failureDetails': [
            {'request': request, 'actual': actual, 'detail': detail, 'count': count}
            for (request, actual, detail), count in sorted(
                failure_details.items(), key=lambda item: (-item[1], item[0]))
        ],
        'invariantViolations': violations,
        'walletReconciliation': {
            'walletCount': wallet_count,
            'expectedWalletCount': expected_wallets,
            'coverageComplete': coverage_complete,
            'mismatchCount': mismatch_count,
            'actualTotal': sum(wallet['actual'] for wallet in wallets),
            'expectedTotal': sum(wallet['expected'] for wallet in wallets),
            'negativeBalanceCount': negative_count,
        },
        'rawMetrics': {
            'file': 'k6_raw.csv',
            'bytes': raw_bytes,
            'sha256': raw_sha256,
            'retention': (
                'Local evidence; upload as PR/CI artifact instead of committing '
                'the oversized CSV.'),
        },
        'resources': resource_summary(run_dir),
        'appLogCounts': app_log_counts(run_dir),
        'gate': {
            'financial': financial_gate,
            'completeness': completeness_gate,
            'mandatoryPassed': mandatory_gate_passed,
            'performanceErrorsAreInformational': True,
        },
        'completenessErrors': errors,
    }
    os.makedirs(run_dir, exist_ok=True)
    with open(os.path.join(run_dir, 'run-summary.json'), 'w', encoding='utf-8') as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
        file.write('\n')
    return 0 if mandatory_gate_passed else 2


if __name__ == '__main__':
    sys.exit(main())
