"""배포 ACL이 실제 인증 Lua 명령을 지원하고 관리 명령은 열지 않는지 확인한다."""
import ast
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

class RedisAclTest(unittest.TestCase):
    def test_auth_lua_commands_are_allowed(self):
        tree = ast.parse((ROOT / 'infra/oracle/provision-secrets.py').read_text())
        command_assignment = next(n for n in tree.body if isinstance(n, ast.Assign)
            and any(isinstance(t, ast.Name) and t.id == 'commands' for t in n.targets))
        commands = ast.literal_eval(command_assignment.value.func.value).split()
        allowed = {c.partition('|')[0] for c in commands}
        required = set()
        files = list((ROOT / 'src/main/resources/redis').glob('*.lua'))
        files += list((ROOT / 'src/main/java/com/pebble/api/auth/application').glob('*.java'))
        for path in files:
            required.update(c.lower() for c in re.findall(r"redis\.call\(['\"]([A-Za-z]+)['\"]", path.read_text()))
        self.assertGreaterEqual(len(required), 10)
        self.assertFalse(required - allowed, f'Missing Redis ACL commands: {required - allowed}')
        self.assertFalse({'acl', 'config', 'flushall', 'flushdb', 'shutdown'} & allowed)
        self.assertTrue(all(not c.startswith('@') for c in commands))

if __name__ == '__main__':
    unittest.main()
