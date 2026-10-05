"""서버 내부에서 실행하는 운영 접근 제한 검증. 비밀·OAuth URL은 출력하지 않는다."""
import json
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

def request(path, method='GET', origin=None):
    headers = {} if origin is None else {'Origin': origin}
    req = urllib.request.Request('http://127.0.0.1:8080/api/v1' + path,
                                 data=b'' if method == 'POST' else None,
                                 headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()

for path in ['/categories', '/tags', '/posts']:
    status, _, body = request(path)
    assert status == 200 and json.loads(body)['data'] is not None
    print('Public API verified:', path, status)
assert request('/members/me')[0] == 401
print('Guest private member API: 401')
status, headers, _ = request('/auth/naver/authorization', 'POST', 'https://www.pebble-log.com')
assert status == 200
cookie = headers['Set-Cookie']
assert all(value in cookie for value in ['Secure', 'HttpOnly', 'SameSite=Lax'])
assert headers.get('Access-Control-Allow-Origin') == 'https://www.pebble-log.com'
print('Production OAuth state Redis write, origin and secure cookie verified')
assert request('/auth/naver/authorization', 'POST', 'https://untrusted.example')[0] == 403
print('Untrusted Origin: 403')

def db(sslmode, query):
    command = ['docker', 'exec', 'pebble-prod-postgres-1', 'sh', '-c',
               'PGPASSWORD=$(cat /run/secrets/app-password) exec psql ' +
               '"host=postgres user=pebble dbname=pebble sslmode=' + sslmode +
               ' sslrootcert=/run/tls/ca.crt" -At -c "$1"', 'verify', query]
    return subprocess.run(command, capture_output=True, text=True)

result = db('verify-full', 'select rolsuper or rolcreatedb or rolcreaterole or rolreplication from pg_roles where rolname=current_user')
assert result.returncode == 0 and result.stdout.strip() == 'f'
assert db('disable', 'select 1').returncode != 0
assert db('verify-full', 'select ssl from pg_stat_ssl where pid=pg_backend_pid()').stdout.strip() == 't'
print('PostgreSQL verified TLS, non-admin application role and plaintext rejection')

password = (Path('/opt/pebble/runtime/private/api/REDIS_PASSWORD')).read_text()
def redis(arguments, authenticated=True):
    shell = 'read -r password; export REDISCLI_AUTH="$password"; ' if authenticated else ''
    shell += 'exec redis-cli --tls --cacert /run/tls/ca.crt -h redis '
    if authenticated:
        shell += '--user pebble '
    shell += '"$@"'
    result = subprocess.run(['docker', 'exec', '-i', 'pebble-prod-redis-1',
                             'sh', '-c', shell, 'verify', *arguments],
                            input=password + '\n' if authenticated else '',
                            text=True, capture_output=True)
    return result.stdout.strip() + result.stderr.strip()
assert redis(['PING']) == 'PONG'
assert 'NOAUTH' in redis(['PING'], False)
assert 'NOPERM' in redis(['CONFIG', 'GET', 'dir'])
assert 'NOPERM' in redis(['SET', 'outside-pebble-prefix', 'forbidden'])
script = "redis.call('SET',KEYS[1],'check','PX',10000);local v=redis.call('GET',KEYS[1]);redis.call('DEL',KEYS[1]);return v"
assert redis(['EVAL', script, '1', 'pebble:deployment:acl-check']) == 'check'
print('Redis TLS, unauthenticated/config/foreign-key denial and scoped Lua verified')

for name in ['pebble-prod-postgres-1', 'pebble-prod-redis-1']:
    metadata = json.loads(subprocess.check_output(['docker', 'inspect', name]))[0]
    assert not any(metadata['NetworkSettings']['Ports'].values())
api = json.loads(subprocess.check_output(['docker', 'inspect', 'pebble-prod-api-1']))[0]
assert api['HostConfig']['ReadonlyRootfs'] and api['Config']['User'] == '10001:10001'
assert api['NetworkSettings']['Ports']['8080/tcp'][0]['HostIp'] == '127.0.0.1'
print('DB/Redis host ports absent; API loopback-only, non-root, read-only verified')
