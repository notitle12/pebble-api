"""Fixture-only tests for PostgreSQL backup retention; no database is used."""
from datetime import datetime, timezone
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("prune_backups", HERE / "prune-backups.py")
pruner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pruner)


def add_backup(directory, stamp, data=b"fixture dump"):
    name = f"pebble-{stamp}.dump"
    (directory / name).write_bytes(data)
    (directory / f"{name}.sha256").write_text(
        f"{hashlib.sha256(data).hexdigest()}  {name}\n", encoding="ascii")
    return name


class BackupRetentionTest(unittest.TestCase):
    def test_prunes_only_expired_verified_pairs_and_keeps_newest(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            expired = add_backup(directory, "20260101T000000Z")
            boundary = add_backup(directory, "20261008T120000Z")
            latest = add_backup(directory, "20261014T120000Z")
            damaged = add_backup(directory, "20260102T000000Z")
            (directory / f"{damaged}.sha256").write_text("incorrect\n", encoding="ascii")
            unrelated = directory / "other.dump"
            unrelated.write_bytes(b"untouched")
            (directory / "pebble-20250101T000000Z.dump.partial").write_bytes(b"partial")
            nested = directory / "nested"
            nested.mkdir()
            (nested / "data").write_bytes(b"untouched")
            symlink = directory / "pebble-20250102T000000Z.dump"
            symlink.symlink_to(unrelated)
            now = datetime(2026, 10, 15, 12, tzinfo=timezone.utc)

            removed = pruner.prune(directory, "7", now)

            self.assertEqual(removed, [expired])
            self.assertFalse((directory / expired).exists())
            self.assertFalse((directory / f"{expired}.sha256").exists())
            self.assertTrue((directory / boundary).exists())  # Exact cutoff is retained.
            self.assertTrue((directory / latest).exists())
            self.assertTrue((directory / damaged).exists())
            self.assertTrue((directory / f"{damaged}.sha256").exists())
            self.assertEqual(unrelated.read_bytes(), b"untouched")
            self.assertEqual((nested / "data").read_bytes(), b"untouched")
            self.assertTrue(symlink.is_symlink())

    def test_preserves_only_backup_when_it_is_expired(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            only = add_backup(directory, "20260101T000000Z")
            removed = pruner.prune(directory, 7, datetime(2026, 10, 15, tzinfo=timezone.utc))
            self.assertEqual(removed, [])
            self.assertTrue((directory / only).exists())

    def test_accepts_previous_absolute_path_checksum_sidecar(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            name = "pebble-20260101T000000Z.dump"
            data = b"legacy fixture dump"
            (directory / name).write_bytes(data)
            absolute_path = directory / name
            (directory / f"{name}.sha256").write_text(
                f"{hashlib.sha256(data).hexdigest()}  {absolute_path}\n", encoding="ascii")
            newest = add_backup(directory, "20261014T120000Z")
            removed = pruner.prune(directory, 7, datetime(2026, 10, 15, tzinfo=timezone.utc))
            self.assertEqual(removed, [name])
            self.assertTrue((directory / newest).exists())

    def test_invalid_retention_values_are_rejected(self):
        for value in ("", "0", "366", "-1", "1.5", "999999999999", "1;rm"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                pruner.parse_retention_days(value)

    def _run_backup_script(self, root, retention="7", fail_restore=False):
        staged = root / "staged"
        staged.mkdir()
        script = (HERE / "backup-postgres.sh").read_text(encoding="utf-8")
        script = script.replace("root=/opt/pebble/runtime", f"root={root / 'runtime'}")
        (staged / "backup-postgres.sh").write_text(script, encoding="utf-8")
        shutil.copy2(HERE / "prune-backups.py", staged / "prune-backups.py")
        docker = root / "docker"
        docker.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$BACKUP_TEST_LOG"
case " $* " in
  *" pg_dump "*) printf 'test backup data';;
  *" pg_restore "*) [[ "$BACKUP_TEST_FAIL_RESTORE" != 1 ]];;
  *) exit 90;;
esac
''', encoding="utf-8")
        docker.chmod(0o700)
        flock = root / "flock"
        flock.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        flock.chmod(0o700)
        log = root / "docker-calls"
        environment = dict(os.environ, PATH=f"{root}:{os.environ.get('PATH', os.defpath)}",
                           BACKUP_TEST_LOG=str(log),
                           BACKUP_TEST_FAIL_RESTORE="1" if fail_restore else "0",
                           PEBBLE_BACKUP_RETENTION_DAYS=retention)
        result = subprocess.run(["bash", str(staged / "backup-postgres.sh")],
                                env=environment, capture_output=True, text=True)
        return result, log.read_text(encoding="utf-8") if log.exists() else "", root / "runtime/backups"

    def test_verification_failure_does_not_prune_expired_backups(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            backups = root / "runtime/backups"
            backups.mkdir(parents=True)
            old = add_backup(backups, "20260101T000000Z")
            result, _, _ = self._run_backup_script(root, fail_restore=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue((backups / old).exists())
            self.assertTrue((backups / f"{old}.sha256").exists())

    def test_successful_backup_prunes_expired_pair_after_creating_verified_pair(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            backups = root / "runtime/backups"
            backups.mkdir(parents=True)
            old = add_backup(backups, "20260101T000000Z")
            result, _, _ = self._run_backup_script(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((backups / old).exists())
            created = list(backups.glob("pebble-*.dump"))
            self.assertEqual(len(created), 1)
            self.assertTrue(Path(f"{created[0]}.sha256").exists())
            check = subprocess.run(["sha256sum", "--check", str(created[0]) + ".sha256"],
                                  cwd=backups, capture_output=True, text=True)
            self.assertEqual(check.returncode, 0, check.stderr)

    def test_invalid_retention_is_rejected_before_docker_or_directory_creation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result, calls, backups = self._run_backup_script(root, retention="366")
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(calls, "")
            self.assertFalse(backups.exists())


if __name__ == "__main__":
    unittest.main()
