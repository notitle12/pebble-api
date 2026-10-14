"""SSH 표준 입력으로 승인된 외부 비밀을 받고 서버에서 운영 비밀을 생성한다."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

os.umask(0o077)
root = Path('/opt/pebble/runtime')
private = root / 'private'
if os.geteuid() != 0 or private.exists():
    raise SystemExit('Root required and existing secrets must never be overwritten')
external = json.load(sys.stdin)
required = ['NAVER_CLIENT_ID', 'NAVER_CLIENT_SECRET', 'R2_ENDPOINT', 'R2_BUCKET_NAME',
            'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'MEDIA_CDN_SIGNING_KEY_BASE64']
if set(external) != set(required) or any(not external[k] for k in required):
    raise SystemExit('Required external secret configuration is incomplete')
if external['R2_ENDPOINT'] != 'https://ae87d49be319ec8a092f90acc9554383.r2.cloudflarestorage.com':
    raise SystemExit('Unexpected R2 account endpoint')
if external['R2_BUCKET_NAME'] != 'pebble-media':
    raise SystemExit('Unexpected R2 bucket')

def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def directory(path, uid=0, mode=0o700):
    path.mkdir(parents=True, exist_ok=True)
    os.chown(path, uid, uid)
    os.chmod(path, mode)

def write(path, value, uid=0, mode=0o400):
    path.write_text(value)
    os.chown(path, uid, uid)
    os.chmod(path, mode)

directory(private)
directory(private / 'api', 10001, 0o500)
directory(private / 'postgres', 999, 0o500)
directory(private / 'redis', 999, 0o500)
directory(private / 'audit', 10001)
directory(private / 'ca')
directory(private / 'tls/public', mode=0o755)
ca = private / 'ca'
run('openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3650',
    '-subj', '/CN=Pebble internal CA', '-keyout', str(ca / 'key.pem'), '-out', str(ca / 'ca.crt'))
write(private / 'tls/public/ca.crt', (ca / 'ca.crt').read_text(), mode=0o444)
for service in ['postgres', 'redis']:
    dest = private / 'tls' / service
    directory(dest, 999, 0o500)
    csr = ca / (service + '.csr')
    ext = ca / (service + '.ext')
    write(ext, f'subjectAltName=DNS:{service}\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n')
    run('openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=' + service,
        '-keyout', str(dest / 'server.key'), '-out', str(csr))
    run('openssl', 'x509', '-req', '-in', str(csr), '-CA', str(ca / 'ca.crt'),
        '-CAkey', str(ca / 'key.pem'), '-CAcreateserial', '-days', '90', '-sha256',
        '-extfile', str(ext), '-out', str(dest / 'server.crt'))
    for filename in ['server.key', 'server.crt']:
        os.chown(dest / filename, 999, 999)
        os.chmod(dest / filename, 0o400)
    write(dest / 'ca.crt', (ca / 'ca.crt').read_text(), 999)

db_password = secrets.token_hex(32)
redis_password = secrets.token_hex(32)
write(private / 'postgres/admin-password', secrets.token_hex(32), 999)
write(private / 'postgres/app-password', db_password, 999)
run('openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072',
    '-out', str(ca / 'jwt.pem'))
run('openssl', 'pkcs8', '-topk8', '-nocrypt', '-in', str(ca / 'jwt.pem'),
    '-outform', 'DER', '-out', str(ca / 'jwt-private.der'))
run('openssl', 'pkey', '-in', str(ca / 'jwt.pem'), '-pubout', '-outform', 'DER',
    '-out', str(ca / 'jwt-public.der'))
api_secrets = external | {
    'DB_PASSWORD': db_password, 'REDIS_PASSWORD': redis_password,
    'JWT_PRIVATE_KEY_BASE64': base64.b64encode((ca / 'jwt-private.der').read_bytes()).decode(),
    'JWT_PUBLIC_KEY_BASE64': base64.b64encode((ca / 'jwt-public.der').read_bytes()).decode(),
    'REFRESH_TOKEN_PEPPER_BASE64': base64.b64encode(secrets.token_bytes(32)).decode(),
}
for name, value in api_secrets.items():
    write(private / 'api' / name, value, 10001)
for filename in ['jwt.pem', 'jwt-private.der', 'jwt-public.der']:
    (ca / filename).unlink()
commands = ('get getdel set exists type del hgetall hget hset sadd smembers pexpire pexpireat '
            'pttl incr zremrangebyscore zadd zrevrange zrange time eval evalsha '
            'script|load ping hello select client|setinfo client|setname quit').split()
acl = 'user default off\nuser pebble on #' + hashlib.sha256(redis_password.encode()).hexdigest()
acl += ' ~pebble:* ' + ' '.join('+' + cmd for cmd in commands) + '\n'
write(private / 'redis/users.acl', acl, 999)
write(private / 'redis/redis.conf', '''port 0
tls-port 6379
tls-cert-file /run/tls/server.crt
tls-key-file /run/tls/server.key
tls-ca-cert-file /run/tls/ca.crt
tls-auth-clients no
aclfile /run/config/users.acl
appendonly yes
appendfsync everysec
maxmemory 512mb
maxmemory-policy noeviction
protected-mode yes
''', 999)
settings = {
    'SPRING_PROFILES_ACTIVE': 'prod', 'SERVER_PORT': '8080',
    'DB_URL': 'jdbc:postgresql://postgres:5432/pebble?sslmode=verify-full&sslrootcert=/run/tls/ca.crt',
    'DB_USERNAME': 'pebble', 'REDIS_HOST': 'redis', 'REDIS_PORT': '6379',
    'REDIS_USERNAME': 'pebble', 'REDIS_SSL_ENABLED': 'true',
    'CORS_ALLOWED_ORIGINS': 'https://www.pebble-log.com',
    'NAVER_REDIRECT_URI': 'https://www.pebble-log.com/auth/naver/callback',
    'JWT_ISSUER': 'https://api.pebble-log.com', 'JWT_AUDIENCE': 'pebble-api',
    'JWT_KEY_ID': 'prod-20261004', 'REFRESH_TOKEN_PEPPER_VERSION': 'v1',
    'R2_ENABLED': 'true', 'R2_REGION': 'auto', 'MEDIA_CDN_ENABLED': 'true',
    'MEDIA_CDN_BASE_URL': 'https://images.pebble-log.com', 'ADMIN_BOOTSTRAP_ENABLED': 'false',
}
write(root / 'api.env', ''.join(k + '=' + v + '\n' for k, v in settings.items()))
print('Production secrets generated; external credentials installed; no values printed')
