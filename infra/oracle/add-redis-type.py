"""사용자 승인 후 기존 비밀 ACL을 출력하지 않고 TYPE 권한만 추가한다."""
import os
from pathlib import Path
import stat
import tempfile

path = Path('/opt/pebble/runtime/private/redis/users.acl')
info = path.lstat()
if not stat.S_ISREG(info.st_mode):
    raise SystemExit('Expected a regular ACL file')
original = path.read_bytes()
lines = original.splitlines(keepends=True)
indexes = [i for i, line in enumerate(lines) if line.startswith(b'user pebble ')]
if len(indexes) != 1:
    raise SystemExit('Expected exactly one pebble ACL entry')
i = indexes[0]
if b'+type' in lines[i].split():
    print('TYPE permission already present')
    raise SystemExit(0)
line = lines[i]
ending = b'\r\n' if line.endswith(b'\r\n') else b'\n' if line.endswith(b'\n') else b''
lines[i] = line.rstrip(b'\r\n') + b' +type' + ending
fd, temp = tempfile.mkstemp(prefix='.acl-type-', dir=path.parent)
try:
    os.fchmod(fd, stat.S_IMODE(info.st_mode))
    os.fchown(fd, info.st_uid, info.st_gid)
    with os.fdopen(fd, 'wb') as output:
        output.write(b''.join(lines))
        output.flush()
        os.fsync(output.fileno())
    os.replace(temp, path)
finally:
    if os.path.exists(temp):
        os.unlink(temp)
print('Added TYPE only; credentials and other permissions preserved')
