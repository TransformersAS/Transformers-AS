"""Real HTTP evidence runner, scoped to the disposable H02 lab. No secrets in output."""
import http.cookiejar
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

EV = Path(__file__).resolve().parent
ROOT = EV.parent
D = ['docker', '--context', 'desktop-linux']
seed = (ROOT / 'seed.sql').read_text()
email = re.search(r"VALUES \('([^']+)'", seed).group(1)
password = re.search(r'Password: (\S+)', seed).group(1)
results = []


def sql(query):
    result = subprocess.run(D + ['exec', 'cu08-h02-20261107-mysql-1', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --user="$MYSQL_USER" --database=cu08_h02_validation --batch --skip-column-names --execute "$1"',
        'sh', query], capture_output=True, text=True, check=True)
    return result.stdout.strip()


def state():
    row = sql('SELECT COUNT(*),COALESCE(SUM(blocked_until IS NOT NULL),0) FROM login_attempts').split('\t')
    rows = sql('SELECT failure_1,failure_2,failure_3,failure_4,failure_5,blocked_until FROM login_attempts')
    values = [[None if x == 'NULL' else int(x) for x in line.split('\t')]
              for line in rows.splitlines()]
    # Compare key in SQL, without exposing email or binary key in evidence.
    match = sql("SELECT COUNT(*) FROM login_attempts WHERE attempt_key=UNHEX(SHA2(CONCAT('"
                + email + "',CHAR(0),'172.30.242.1'),256))")
    return {'records': int(row[0]), 'blocks': int(row[1]),
            'failures_and_deadline_ms': values, 'verified_origin_key_matches': int(match)}


def request(client, url, body=None, headers=None):
    try:
        response = client.open(urllib.request.Request(url, data=body, headers=headers or {}), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers.get('Retry-After'), response.read()


def client():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def login(label, expected, wrong=False, forged=None, direct=False, invalid_csrf=False):
    c = client()
    base = 'http://127.0.0.1:' + ('18320' if direct else '14320')
    code, _, body = request(c, base + '/api/auth/csrf')
    assert code == 200, 'CSRF acquisition failed'
    csrf = json.loads(body)
    headers = dict(forged or {})
    headers[csrf['headerName']] = 'invalid-fixture-token' if invalid_csrf else csrf['token']
    headers['Content-Type'] = 'application/x-www-form-urlencoded'
    data = urllib.parse.urlencode({'email': email,
        'password': password + '-incorrect' if wrong else password}).encode()
    code, retry, _ = request(c, base + '/api/auth/login', data, headers)
    record = {'scenario': label, 'expected_http': expected, 'observed_http': code,
              'retry_after_seconds': int(retry) if retry and retry.isdigit() else None,
              'result': 'APROBADO' if code == expected else 'FALLIDO'}
    results.append(record)
    assert code == expected, label + ': unexpected HTTP status'
    if expected == 429:
        assert retry and 0 < int(retry) <= 900, 'Invalid Retry-After'
    return c


def verify_before():
    c = login('legitimate_login_valid_csrf', 204)
    code, _, _ = request(c, 'http://127.0.0.1:14320/api/auth/me')
    results.append({'scenario': 'authenticated_session', 'expected_http': 200,
                    'observed_http': code, 'result': 'APROBADO' if code == 200 else 'FALLIDO'})
    assert code == 200, 'Session not authenticated'
    for i in range(5):
        login('failed_login_' + str(i + 1), 401, wrong=True)
    blocked = state()
    assert blocked['records'] == blocked['blocks'] == blocked['verified_origin_key_matches'] == 1
    values = blocked['failures_and_deadline_ms'][0]
    assert all(v is not None for v in values)
    assert values[4] - values[0] < 900000, 'Failures outside 15-minute window'
    assert values[5] - values[4] == 900000, 'Wait is not 15 minutes'
    login('sixth_attempt_correct_password', 429)
    login('repeated_blocked_request', 429)
    assert state() == blocked, 'Blocked request extended or changed counter'
    forged = {'X-Forwarded-For': '1.1.1.1, 8.8.8.8',
              'Forwarded': 'for=8.8.8.8;proto=https', 'X-Real-IP': '8.8.8.8'}
    login('forged_origin_headers', 429, forged=forged)
    assert state() == blocked, 'Forged headers changed origin/counter'
    login('direct_backend_with_forged_headers', 503, forged=forged, direct=True)
    assert state() == blocked, 'Direct access changed counter'
    login('invalid_csrf', 403, invalid_csrf=True, wrong=True)
    assert state() == blocked, 'Invalid CSRF changed counter'
    return blocked


mode = sys.argv[1]
assert mode in ('before-restart', 'after-restart')
target = EV / (mode + '.json')
assert not target.exists(), 'Refusing to overwrite evidence'
report = {'mode': mode, 'utc': datetime.now(timezone.utc).isoformat(), 'scenarios': results}
try:
    if mode == 'before-restart':
        report['counter'] = verify_before()
    else:
        previous = json.loads((EV / 'before-restart.json').read_text())['counter']
        assert state() == previous, 'Persisted counter changed across restart'
        login('block_persists_after_backend_restart', 429)
        assert state() == previous, 'Post-restart request changed deadline'
        report['counter'] = state()
    report['result'] = 'APROBADO'
except Exception as error:
    report['result'] = 'FALLIDO'
    # Assertion text is controlled here; never serialize HTTP bodies or credentials.
    report['error'] = str(error) if isinstance(error, AssertionError) else type(error).__name__
    raise
finally:
    target.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
