"""Sample CPU / RAM / disk / network / JVM / MySQL metrics at fixed interval.

Output CSV columns: timestamp, category, metric, value, unit, labels (JSON)

Usage:
  python sampler.py --interval 2 --out results/sampler.csv
  python sampler.py --interval 2 --duration 1900 --out results/sampler.csv
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone

try:
    import psutil
except ImportError:
    print("ERROR: pip install psutil", file=sys.stderr)
    sys.exit(1)


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec='milliseconds')


def write_row(w, ts, category, metric, value, unit, labels=None):
    w.writerow([ts, category, metric, value, unit, json.dumps(labels or {}, separators=(',', ':'))])


# ── System ──────────────────────────────────────────────────────────────────

def sample_system():
    yield ('cpu', 'percent', psutil.cpu_percent(interval=None), '%', None)
    mem = psutil.virtual_memory()
    yield ('ram', 'used_bytes',      mem.used,      'B', None)
    yield ('ram', 'available_bytes', mem.available, 'B', None)
    yield ('ram', 'percent',         mem.percent,   '%', None)
    try:
        io = psutil.disk_io_counters()
        if io:
            yield ('disk', 'read_bytes',  io.read_bytes,  'B', None)
            yield ('disk', 'write_bytes', io.write_bytes, 'B', None)
    except Exception:
        pass
    try:
        net = psutil.net_io_counters()
        if net:
            yield ('net', 'sent_bytes', net.bytes_sent, 'B', None)
            yield ('net', 'recv_bytes', net.bytes_recv, 'B', None)
    except Exception:
        pass


# ── JVM (Actuator) ──────────────────────────────────────────────────────────

def _get_json(url, timeout=2):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read())


def sample_jvm(base_url):
    try:
        names = {m['name'] for m in _get_json(f'{base_url}/metrics')['names']}
    except Exception:
        return

    watch = [
        'jvm.memory.used', 'jvm.memory.max',
        'jvm.gc.pause', 'jvm.threads.live', 'jvm.classes.loaded',
        'hikaricp.connections.active', 'hikaricp.connections.idle',
        'hikaricp.connections.pending',
        'system.cpu.usage', 'process.cpu.usage',
        'process.uptime',
    ]
    for name in watch:
        if name not in names:
            continue
        try:
            data = _get_json(f'{base_url}/metrics/{name}')
        except Exception:
            continue
        # Rate/gauge style: {measurements: [{statistic, value}], availableTags: [...]}
        if 'measurements' in data and isinstance(data['measurements'], list):
            for m in data['measurements']:
                yield ('jvm', name, m.get('value', 0), '', {'stat': m.get('statistic', '')})
        # Aggregated counter: sum over measurements
        elif isinstance(data.get('measurements', None), list):
            total = sum(float(m.get('value', 0) or 0) for m in data['measurements'])
            if total:
                yield ('jvm', name, total, '', None)


# ── MySQL ───────────────────────────────────────────────────────────────────

def mysql_query(sql, root='root', timeout=3):
    try:
        r = subprocess.run(['mysql', f'-u{root}', '-N', '-B', '-e', sql],
                           capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip() if r.returncode == 0 else None
    except Exception:
        return None


def sample_mysql():
    for name in ('Threads_connected', 'Threads_running', 'Queries',
                 'Innodb_row_lock_waits', 'Innodb_row_lock_time'):
        v = mysql_query(f"SHOW GLOBAL STATUS LIKE '{name}'")
        if not v:
            continue
        parts = v.split('\t')
        if len(parts) >= 2 and parts[1].isdigit():
            yield ('mysql', name, int(parts[1]), '', None)


def _parse_size(value):
    """Convert Docker's human-readable sizes (MiB, GB, kB...) to bytes."""
    value = value.strip()
    units = {
        'B': 1, 'kB': 1000, 'MB': 1000 ** 2, 'GB': 1000 ** 3,
        'KiB': 1024, 'MiB': 1024 ** 2, 'GiB': 1024 ** 3,
    }
    for unit in sorted(units, key=len, reverse=True):
        if value.endswith(unit):
            try:
                return float(value[:-len(unit)].strip()) * units[unit]
            except ValueError:
                return None
    return None


def sample_docker(containers):
    if not containers:
        return
    try:
        proc = subprocess.run(
            ['docker', 'stats', '--no-stream', '--format', '{{json .}}', *containers],
            capture_output=True, text=True, timeout=10,
        )
    except Exception:
        return
    if proc.returncode != 0:
        return
    for line in proc.stdout.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        name = item.get('Name', 'unknown')
        cpu = str(item.get('CPUPerc', '')).rstrip('%')
        try:
            yield ('docker', 'cpu_percent', float(cpu), '%', {'container': name})
        except ValueError:
            pass
        mem_used = str(item.get('MemUsage', '')).split('/')[0].strip()
        mem_bytes = _parse_size(mem_used)
        if mem_bytes is not None:
            yield ('docker', 'memory_used_bytes', mem_bytes, 'B', {'container': name})
        try:
            yield ('docker', 'pids', int(item.get('PIDs', 0)), '', {'container': name})
        except (TypeError, ValueError):
            pass


def sample_mysql_docker(container):
    if not container:
        return
    names = ('Threads_connected', 'Threads_running', 'Queries',
             'Innodb_row_lock_waits', 'Innodb_row_lock_time')
    for name in names:
        sql = f"SHOW GLOBAL STATUS LIKE '{name}'"
        try:
            proc = subprocess.run(
                ['docker', 'exec', container, 'sh', '-c',
                 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "$1"',
                 'sampler', sql],
                capture_output=True, text=True, timeout=10,
            )
        except Exception:
            continue
        if proc.returncode != 0:
            continue
        parts = proc.stdout.strip().split('\t')
        if len(parts) >= 2:
            try:
                yield ('mysql', name, int(parts[1]), '', {'container': container})
            except ValueError:
                pass


# ── main ────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--interval', type=float, default=2.0)
    ap.add_argument('--duration', type=float, default=None,
                    help='stop after N seconds; omit to run until killed')
    ap.add_argument('--out', required=True)
    ap.add_argument('--actuator-url', default='http://localhost:8080/actuator')
    ap.add_argument('--docker-containers', nargs='*', default=[])
    ap.add_argument('--mysql-container', default=None)
    args = ap.parse_args()

    os.makedirs(os.path.dirname(os.path.abspath(args.out)) or '.', exist_ok=True)

    with open(args.out, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['timestamp', 'category', 'metric', 'value', 'unit', 'labels'])
        f.flush()

        psutil.cpu_percent(interval=None)   # khởi tạo để lần đầu không trả 0
        start = time.time()

        while True:
            if args.duration is not None and (time.time() - start) >= args.duration:
                break

            ts = now_iso()
            sources = [sample_system, lambda: sample_jvm(args.actuator_url)]
            if args.mysql_container:
                sources.append(lambda: sample_mysql_docker(args.mysql_container))
            else:
                sources.append(sample_mysql)
            if args.docker_containers:
                sources.append(lambda: sample_docker(args.docker_containers))
            for src in sources:
                try:
                    for cat, met, val, unit, lab in src():
                        write_row(w, ts, cat, met, val, unit, lab)
                except Exception as e:
                    print(f'sampler: {src.__name__ if hasattr(src, "__name__") else "?"}: {e}',
                          file=sys.stderr)
            f.flush()
            time.sleep(args.interval)


if __name__ == '__main__':
    main()
