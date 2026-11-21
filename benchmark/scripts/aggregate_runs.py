import argparse
import json
import os
import statistics


def stats(values):
    mean = statistics.fmean(values)
    return {
        'min': min(values),
        'median': statistics.median(values),
        'max': max(values),
        'mean': mean,
        'coefficientOfVariation': statistics.pstdev(values) / mean if mean else 0,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence-dir', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()

    runs = []
    for name in ('load-run-01', 'load-run-02', 'load-run-03'):
        path = os.path.join(args.evidence_dir, name, 'run-summary.json')
        with open(path, encoding='utf-8') as f:
            item = json.load(f)
        item['name'] = name
        runs.append(item)

    aggregate = {
        'runs': [
            {
                'name': r['name'],
                'requests': r['requests'],
                'achievedRps': r['achievedRps'],
                'p50Ms': r['latencyMs']['p50'],
                'p95Ms': r['latencyMs']['p95'],
                'p99Ms': r['latencyMs']['p99'],
                'unexpectedFailures': r['unexpectedFailures'],
                'httpFailedRate': r['httpFailedRate'],
                'invariantViolations': r['invariantViolations'],
                'walletMismatchCount': r['walletReconciliation']['mismatchCount'],
                'negativeBalanceCount': r['walletReconciliation']['negativeBalanceCount'],
                'deadlocks': r.get('appLogCounts', {}).get('deadlocks', 0),
                'rawSha256': r['rawMetrics']['sha256'],
            }
            for r in runs
        ],
        'statistics': {
            'achievedRps': stats([r['achievedRps'] for r in runs]),
            'p50Ms': stats([r['latencyMs']['p50'] for r in runs]),
            'p95Ms': stats([r['latencyMs']['p95'] for r in runs]),
            'p99Ms': stats([r['latencyMs']['p99'] for r in runs]),
            'unexpectedFailures': stats([r['unexpectedFailures'] for r in runs]),
        },
        'gate': {
            'allK6ExitZero': all(r['k6ExitCode'] == 0 for r in runs),
            'allRunsPlannedDuration': all(r['plannedDurationSec'] == 1920 for r in runs),
            'allWalletsReconciled': all(r['walletReconciliation']['mismatchCount'] == 0 for r in runs),
            'noNegativeBalances': all(r['walletReconciliation']['negativeBalanceCount'] == 0 for r in runs),
            'noClientObservedInvariantViolations': all(r['invariantViolations'] == 0 for r in runs),
        },
    }
    hot_path = os.path.join(args.evidence_dir, 'hot-wallet-run-01', 'run-summary.json')
    if os.path.exists(hot_path):
        with open(hot_path, encoding='utf-8') as f:
            hot = json.load(f)
        aggregate['hotWallet'] = {
            'profile': '200 accounts / 100 target RPS / 1 receiving wallet / 1920 seconds',
            'requests': hot['requests'],
            'achievedRps': hot['achievedRps'],
            'p50Ms': hot['latencyMs']['p50'],
            'p95Ms': hot['latencyMs']['p95'],
            'p99Ms': hot['latencyMs']['p99'],
            'unexpectedFailures': hot['unexpectedFailures'],
            'httpFailedRate': hot['httpFailedRate'],
            'failuresByRequestAndActual': hot['failuresByRequestAndActual'],
            'invariantViolations': hot['invariantViolations'],
            'walletMismatchCount': hot['walletReconciliation']['mismatchCount'],
            'negativeBalanceCount': hot['walletReconciliation']['negativeBalanceCount'],
            'deadlocks': hot.get('appLogCounts', {}).get('deadlocks', 0),
            'rowLockWaitsDelta': hot.get('resources', {}).get(
                'mysql.Innodb_row_lock_waits.dw-mysql', {}).get('delta'),
            'rowLockTimeMsDelta': hot.get('resources', {}).get(
                'mysql.Innodb_row_lock_time.dw-mysql', {}).get('delta'),
            'rawSha256': hot['rawMetrics']['sha256'],
        }
        aggregate['gate']['hotWalletK6ExitZero'] = hot['k6ExitCode'] == 0
        aggregate['gate']['hotWalletPlannedDuration'] = hot['plannedDurationSec'] == 1920
        aggregate['gate']['hotWalletReconciled'] = hot['walletReconciliation']['mismatchCount'] == 0
        aggregate['gate']['hotWalletNoNegativeBalance'] = hot['walletReconciliation']['negativeBalanceCount'] == 0
    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(aggregate, f, ensure_ascii=False, indent=2)
        f.write('\n')


if __name__ == '__main__':
    main()
