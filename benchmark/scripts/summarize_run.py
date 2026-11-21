import argparse
import csv
import hashlib
import json
import os
from datetime import datetime, timezone
from collections import Counter, defaultdict
from urllib.parse import parse_qsl


def metric(metrics, name, key, default=None):
    return metrics.get(name, {}).get(key, default)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-dir', required=True)
    parser.add_argument('--k6-exit', type=int, required=True)
    args = parser.parse_args()

    run_dir = os.path.abspath(args.run_dir)
    summary_path = os.path.join(run_dir, 'k6_summary.json')
    with open(summary_path, encoding='utf-8') as f:
        k6 = json.load(f)
    metrics = k6.get('metrics', {})

    reconciliation_path = os.path.join(run_dir, 'reconciliation.tsv')
    wallets = []
    with open(reconciliation_path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f, delimiter='\t'):
            wallets.append({
                'walletId': int(row['wallet_id']),
                'actual': int(row['actual_balance']),
                'expected': int(row['expected_balance']),
                'delta': int(row['delta']),
            })

    raw_path = os.path.join(run_dir, 'k6_raw.csv')
    sha256 = hashlib.sha256()
    with open(raw_path, 'rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            sha256.update(block)

    failures = Counter()
    failure_details = Counter()
    violations = 0
    with open(raw_path, newline='', encoding='utf-8') as f:
        for row in csv.DictReader(f):
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
                    detail_key = '500 | could not open JPA EntityManager (connection pool unavailable)'
                else:
                    try:
                        body = json.loads(detail)
                        detail_key = ' | '.join(str(body.get(k, '')) for k in
                                                ('status', 'error', 'message', 'path')).strip(' |')
                    except (json.JSONDecodeError, TypeError, AttributeError):
                        pass
                failure_details[(request, actual, detail_key)] += 1

    resource_values = defaultdict(list)
    sampler_path = os.path.join(run_dir, 'sampler.csv')
    if os.path.exists(sampler_path):
        with open(sampler_path, newline='', encoding='utf-8') as f:
            for row in csv.DictReader(f):
                try:
                    labels = json.loads(row.get('labels') or '{}')
                    key = (row['category'], row['metric'], labels.get('container', 'host'))
                    resource_values[key].append(float(row['value']))
                except (ValueError, json.JSONDecodeError, KeyError):
                    continue

    resource_summary = {}
    for (category, name, subject), values in resource_values.items():
        if not values:
            continue
        resource_summary[f'{category}.{name}.{subject}'] = {
            'min': min(values),
            'avg': sum(values) / len(values),
            'max': max(values),
            'samples': len(values),
        }
        if category == 'mysql' and name in (
                'Queries', 'Innodb_row_lock_waits', 'Innodb_row_lock_time'):
            resource_summary[f'{category}.{name}.{subject}']['delta'] = values[-1] - values[0]

    app_log_path = os.path.join(run_dir, 'app.log')
    app_log_counts = {'deadlocks': 0, 'errors': 0, 'warnings': 0}
    if os.path.exists(app_log_path):
        with open(app_log_path, encoding='utf-8', errors='replace') as f:
            for line in f:
                app_log_counts['deadlocks'] += 'Deadlock found' in line
                app_log_counts['errors'] += ' ERROR ' in line
                app_log_counts['warnings'] += ' WARN ' in line

    start_ms = k6.get('setup_data', {}).get('startTime')
    end_ms = k6.get('setup_data', {}).get('testEndTime')
    result = {
        'generatedAt': datetime.now(timezone.utc).isoformat(),
        'k6ExitCode': args.k6_exit,
        'plannedDurationSec': ((end_ms - start_ms) / 1000) if start_ms and end_ms else None,
        'executor': 'ramping-vus',
        'droppedIterations': metric(metrics, 'dropped_iterations', 'count'),
        'droppedIterationsNote': (
            None if 'dropped_iterations' in metrics
            else 'N/A: ramping-vus is a closed-model executor and does not emit dropped_iterations.'
        ),
        'requests': metric(metrics, 'http_reqs', 'count', 0),
        'achievedRps': metric(metrics, 'http_reqs', 'rate', 0),
        'latencyMs': {
            'avg': metric(metrics, 'http_req_duration', 'avg'),
            'p50': metric(metrics, 'http_req_duration', 'med'),
            'p95': metric(metrics, 'http_req_duration', 'p(95)'),
            'p99': metric(metrics, 'http_req_duration', 'p(99)'),
            'max': metric(metrics, 'http_req_duration', 'max'),
        },
        'httpFailedRate': metric(metrics, 'http_req_failed', 'value', 0),
        'unexpectedFailures': metric(metrics, 'unexpected_failure', 'passes', 0),
        'checksFailed': metric(metrics, 'checks', 'fails', 0),
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
            'walletCount': len(wallets),
            'mismatchCount': sum(1 for w in wallets if w['delta'] != 0),
            'actualTotal': sum(w['actual'] for w in wallets),
            'expectedTotal': sum(w['expected'] for w in wallets),
            'negativeBalanceCount': sum(1 for w in wallets if w['actual'] < 0),
        },
        'rawMetrics': {
            'file': 'k6_raw.csv',
            'bytes': os.path.getsize(raw_path),
            'sha256': sha256.hexdigest(),
            'retention': 'Local evidence; upload as PR/CI artifact instead of committing the oversized CSV.',
        },
        'resources': resource_summary,
        'appLogCounts': app_log_counts,
    }
    with open(os.path.join(run_dir, 'run-summary.json'), 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write('\n')


if __name__ == '__main__':
    main()
