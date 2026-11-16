"""Read-only observer for the disposable performance project; no application credentials persisted."""
import datetime as dt
import json
import math
from pathlib import Path
import re
import threading
import time

ROUTES = {'/api/cart', '/api/cart/items', '/api/cart/items/{itemId}',
          '/api/favorites', '/api/favorites/{productId}', '/api/account/profile', '/api/addresses',
          '/api/products', '/api/products/{id}', '/api/reviews/products/{product}',
          '/api/reviews/products/{product}/summary', '/api/auth/login', '/api/auth/me', '/api/auth/csrf'}
PREFIXES = ('hikaricp_', 'jdbc_connections_', 'http_server_requests_', 'jvm_gc_pause_',
            'jvm_gc_memory_', 'jvm_memory_', 'jvm_threads_', 'process_cpu_', 'system_cpu_')
LABELS = {'pool', 'method', 'uri', 'status', 'outcome', 'exception', 'error', 'action',
          'cause', 'area', 'id', 'le', 'quantile'}
NUMERIC = r'[-+]?(?:\d+(?:\.\d*)?(?:[eE][-+]?\d+)?|Inf|NaN)'


def prometheus_points(text):
    """Allowlisted metric names, labels and route templates; never export arbitrary labels."""
    points = []
    for line in text.splitlines():
        match = re.fullmatch(r'([a-zA-Z_:][\w:]*)({[^\n]*})?\s+(' + NUMERIC + r')(?:\s+\d+)?', line)
        if not match or not match[1].startswith(PREFIXES):
            continue
        labels = {}
        bad = False
        raw_labels = (match[2] or '{}')[1:-1]
        parts = re.findall(r'(\w+)="((?:[^"\\]|\\.)*)"', raw_labels)
        residue = re.sub(r'\w+="(?:[^"\\]|\\.)*"', '', raw_labels).replace(',', '').strip()
        if residue:
            continue
        for key, value in parts:
            try:
                value = json.loads('"' + value + '"')
            except ValueError:
                bad = True
                break
            if key not in LABELS or not re.fullmatch(r'[A-Za-z0-9 _./{}:+-]{0,120}', value):
                bad = True
                break
            if key == 'uri' and value not in ROUTES:
                bad = True
                break
            labels[key] = value
        if bad:
            continue
        number = float(match[3])
        points.append({'name': match[1], 'labels': labels,
                       'value': number if math.isfinite(number) else None})
    return points


TABLES = ('user_accounts', 'user_account_roles', 'account_restrictions', 'SPRING_SESSION_ATTRIBUTES',
          'SPRING_SESSION', 'cart_items', 'carts', 'buyer_favorites', 'products', 'product_images',
          'product_variants', 'account_profiles', 'account_profile_settings', 'addresses')
# No statement text, bindings, SQL samples, processlist or connection identities are selected.
MYSQL_SQL = """
SELECT JSON_OBJECT('kind','status','values',JSON_OBJECTAGG(VARIABLE_NAME,VARIABLE_VALUE))
FROM performance_schema.global_status WHERE VARIABLE_NAME IN
('Threads_running','Threads_connected','Innodb_row_lock_waits','Innodb_row_lock_time',
 'Slow_queries','Created_tmp_disk_tables','Connections','Aborted_clients');
SELECT JSON_OBJECT('kind','digests','digest',DIGEST,'family',CASE
""" + '\n'.join("WHEN DIGEST_TEXT LIKE '%" + table + "%' THEN '" + table + "'" for table in TABLES) + """
ELSE 'other' END,'calls',COUNT_STAR,'total_ps',SUM_TIMER_WAIT,'max_ps',MAX_TIMER_WAIT,
'lock_ps',SUM_LOCK_TIME,'rows_examined',SUM_ROWS_EXAMINED,'errors',SUM_ERRORS)
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME='performance_fixture' AND DIGEST IS NOT NULL
AND DIGEST_TEXT NOT LIKE '%performance_schema%' AND DIGEST_TEXT NOT LIKE '%JSON_OBJECT%'
ORDER BY SUM_TIMER_WAIT DESC LIMIT 20;
SELECT JSON_OBJECT('kind','transactions','event',EVENT_NAME,'calls',COUNT_STAR,
 'total_ps',SUM_TIMER_WAIT,'max_ps',MAX_TIMER_WAIT)
FROM performance_schema.events_transactions_summary_global_by_event_name WHERE COUNT_STAR>0;
SELECT JSON_OBJECT('kind','consumers','name',NAME,'enabled',ENABLED)
FROM performance_schema.setup_consumers WHERE NAME LIKE 'events_statements%' OR NAME LIKE 'events_transactions%';
SELECT JSON_OBJECT('kind','fixture_counts','addresses',(SELECT COUNT(*) FROM addresses),
 'cart_items',(SELECT COUNT(*) FROM cart_items),'favorites',(SELECT COUNT(*) FROM buyer_favorites));
"""


def nginx_template(original):
    """Derive from current template; only access-log format changes, never proxy/security directives."""
    if original.count('server {') != 1:
        raise ValueError('Expected one performance frontend server')
    preamble = '''map $uri $perf_route {
    default other;
    /api/cart cart_read;
    /api/cart/items cart_items;
    ~^/api/cart/items/[0-9]+$ cart_item;
    /api/favorites favorites_read;
    ~^/api/favorites/[0-9]+$ favorite;
    /api/account/profile profile;
    /api/addresses addresses;
    /api/products catalog;
    ~^/api/products/[0-9]+$ product;
    ~^/api/reviews/products/[0-9]+/summary$ rating;
    ~^/api/reviews/products/[0-9]+$ reviews;
}
log_format performance_timing escape=json '{"perf_timing":true,"epoch":"$msec","route":"$perf_route","method":"$request_method","status":"$status","request_seconds":"$request_time","upstream_seconds":"$upstream_response_time","connect_seconds":"$upstream_connect_time","header_seconds":"$upstream_header_time"}';
'''
    return preamble + original.replace('server {', 'server {\n    access_log /dev/stdout performance_timing;', 1)


def nginx_timing(line):
    """Preserve only our static routes and numeric timing fields through log sanitization."""
    start = line.find('{"perf_timing":true,')
    if start < 0:
        return None
    try:
        obj = json.loads(line[start:])
    except ValueError:
        return None
    routes = {'other', 'cart_read', 'cart_items', 'cart_item', 'favorites_read', 'favorite',
              'profile', 'addresses', 'catalog', 'product', 'rating', 'reviews'}
    keys = {'perf_timing', 'epoch', 'route', 'method', 'status', 'request_seconds',
            'upstream_seconds', 'connect_seconds', 'header_seconds'}
    if set(obj) != keys or obj['route'] not in routes or obj['perf_timing'] is not True:
        return None
    if obj['method'] not in {'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'}:
        return None
    if not re.fullmatch(r'\d{3}', obj['status']):
        return None
    for key in keys - {'perf_timing', 'route', 'method', 'status'}:
        if not isinstance(obj[key], str) or not re.fullmatch(r'[0-9., :\-]{1,120}', obj[key]):
            return None
    return json.dumps(obj, separators=(',', ':'))


class Observer:
    """Separate sampling thread: never replaces/weakens the existing global CPU guard."""
    def __init__(self, compose, project, directory, label, command, interval=10):
        if not re.fullmatch(r'rnf-perf-[0-9a-f]{12}', project):
            raise ValueError('Disposable project required')
        self.compose, self.project, self.command = compose, project, command
        self.path = Path(directory) / (label + '-diagnostics.jsonl')
        self.interval = interval
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)
        self.failure = None

    def read(self, args, **kwargs):
        if self.stopped.is_set():
            raise InterruptedError()
        result = self.command(args, timeout=4, check=False, **kwargs)
        if result.returncode:
            raise RuntimeError('Diagnostic command unavailable')
        return result.stdout

    def sample(self):
        result = {'utc_started': dt.datetime.now(dt.timezone.utc).isoformat(),
                  'semantics': 'Counters/histograms are cumulative; compare consecutive samples. '
                               'MySQL max_ps is since server start, not an interval percentile.'}
        started = time.monotonic()
        for category, operation in [('micrometer', self.micrometer), ('mysql', self.mysql), ('cgroups', self.cgroups)]:
            try:
                result[category] = operation()
            except Exception as error:
                # Exception messages can include raw command output; never save them.
                result[category] = {'unavailable': type(error).__name__}
        result['collection_seconds'] = round(time.monotonic() - started, 3)
        result['utc_finished'] = dt.datetime.now(dt.timezone.utc).isoformat()
        return result

    def micrometer(self):
        raw = self.read(self.compose + ['exec', '-T', 'backend', 'sh', '-c',
            'curl --fail --silent --max-time 2 --max-filesize 2097152 '
            '-H "Authorization: Bearer $METRICS_TOKEN" http://127.0.0.1:8080/actuator/prometheus'])
        points = prometheus_points(raw)
        return {'points': points, 'coverage': {name: any(p['name'].startswith(prefix) for p in points)
                for name, prefix in [('hikari', 'hikaricp_'), ('http', 'http_server_requests_'),
                                     ('gc', 'jvm_gc_pause_'), ('jvm_cpu', 'process_cpu_')]}}

    def mysql(self):
        raw = self.read(self.compose + ['exec', '-T', 'mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -u root "$MYSQL_DATABASE"'],
            input=MYSQL_SQL)
        return [json.loads(line) for line in raw.splitlines() if line.strip()]

    def cgroups(self):
        raw = self.read(['docker', 'container', 'ls', '--filter', 'label=com.docker.compose.project=' + self.project,
                         '--format', '{{.ID}} {{.Label "com.docker.compose.service"}}'])
        output = {}
        allowed = {'usage_usec', 'user_usec', 'system_usec', 'nr_periods', 'nr_throttled', 'throttled_usec'}
        for line in raw.splitlines():
            identifier, service = line.split()
            if service not in {'backend', 'mysql', 'frontend', 'k6'} or not re.fullmatch(r'[0-9a-f]{12,64}', identifier):
                continue
            stat = self.read(['docker', 'exec', identifier, 'cat', '/sys/fs/cgroup/cpu.stat'])
            output[service] = {key: int(value) for key, value in (row.split() for row in stat.splitlines())
                               if key in allowed}
        return output

    def loop(self):
        try:
            with self.path.open('w') as stream:
                while not self.stopped.is_set():
                    stream.write(json.dumps(self.sample(), ensure_ascii=False, allow_nan=False) + '\n')
                    stream.flush()
                    self.stopped.wait(self.interval)
        except Exception as error:
            # Diagnostics are best effort; no approval decision depends on their availability.
            self.failure = type(error).__name__
            return

    def start(self):
        try:
            self.thread.start()
        except Exception as error:
            self.failure = type(error).__name__

    def stop(self):
        self.stopped.set()
        if self.thread.ident is not None:
            self.thread.join(timeout=10)
        return {'stopped': not self.thread.is_alive(), 'failure': self.failure}
