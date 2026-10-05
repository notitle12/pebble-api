"""실제 DB/백업/비밀 없이 복원 검증 도구의 임시 DB 정리를 확인한다."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class RestoreVerificationTest(unittest.TestCase):
    def run_restore(self, expected="16", fail_restore=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "calls"
            docker = root / "docker"
            docker.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$RESTORE_TEST_LOG"
case " $* " in
  *" pg_restore "*) [[ "$RESTORE_TEST_FAIL" != 1 ]] ;;
  *" psql "*) echo 16 ;;
esac
''')
            docker.chmod(0o700)
            backup = root / "dummy.dump"
            backup.write_bytes(b"test-only")
            environment = dict(os.environ, PATH=f"{root}:{os.defpath}",
                               RESTORE_TEST_LOG=str(log),
                               RESTORE_TEST_FAIL="1" if fail_restore else "0")
            result = subprocess.run(["bash", str(Path(__file__).with_name("verify-restore.sh")),
                                     str(backup), expected], env=environment,
                                    capture_output=True, text=True)
            return result, log.read_text() if log.exists() else ""

    def test_current_release_restores_and_removes_only_generated_database(self):
        result, calls = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = calls.splitlines()
        created = commands[0].split()[-1]
        self.assertTrue(created.startswith("pebble_restore_verify_"))
        self.assertEqual(commands[-1].split()[-1], created)
        self.assertIn("dropdb", commands[-1])

    def test_restore_failure_still_removes_temporary_database(self):
        result, calls = self.run_restore(fail_restore=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dropdb", calls.splitlines()[-1])

    def test_wrong_release_is_rejected_and_temporary_database_removed(self):
        result, calls = self.run_restore(expected="15")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dropdb", calls.splitlines()[-1])

    def test_invalid_expected_count_rejected_before_database_creation(self):
        for value in ("0", "16; echo unsafe", "", "-1"):
            result, calls = self.run_restore(expected=value)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(calls, "")


if __name__ == "__main__":
    unittest.main()
