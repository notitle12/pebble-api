#!/usr/bin/env python3
"""Remove expired, verified Pebble PostgreSQL backup pairs."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import re
import stat


BACKUP_RE = re.compile(r"\Apebble-(\d{8}T\d{6}Z)\.dump\Z")


def parse_retention_days(value):
    if not re.fullmatch(r"[0-9]{1,3}", str(value)):
        raise ValueError("retention days must be an integer from 1 to 365")
    days = int(str(value), 10)
    if not 1 <= days <= 365:
        raise ValueError("retention days must be an integer from 1 to 365")
    return days


def _regular_file(directory_fd, name):
    try:
        info = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)
    except FileNotFoundError:
        return False
    return stat.S_ISREG(info.st_mode)


def _sha256(directory_fd, name):
    file_fd = os.open(name, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
                      | getattr(os, "O_NONBLOCK", 0), dir_fd=directory_fd)
    try:
        info = os.fstat(file_fd)
        if not stat.S_ISREG(info.st_mode):
            return None
        digest = hashlib.sha256()
        with os.fdopen(file_fd, "rb", closefd=False) as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    finally:
        os.close(file_fd)


def _verified_pair(directory_fd, dump_name, directory_path):
    manifest_name = dump_name + ".sha256"
    if not _regular_file(directory_fd, dump_name) or not _regular_file(directory_fd, manifest_name):
        return False

    try:
        manifest_fd = os.open(manifest_name, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
                              | getattr(os, "O_NONBLOCK", 0), dir_fd=directory_fd)
        if not stat.S_ISREG(os.fstat(manifest_fd).st_mode):
            os.close(manifest_fd)
            return False
        with os.fdopen(manifest_fd, "r", encoding="ascii") as manifest:
            content = manifest.read(256)
            if manifest.read(1):
                return False
    except (FileNotFoundError, OSError, UnicodeDecodeError):
        return False

    accepted_paths = (dump_name, os.path.join(directory_path, dump_name))
    path_pattern = "(?:" + "|".join(re.escape(path) for path in accepted_paths) + ")"
    match = re.fullmatch(r"([0-9a-f]{64})  " + path_pattern + r"\n", content)
    if not match:
        return False
    try:
        return _sha256(directory_fd, dump_name) == match.group(1)
    except (FileNotFoundError, OSError):
        return False


def prune(directory, retention_days, now=None):
    """Prune expired completed pairs, preserving the newest verified pair."""
    days = parse_retention_days(retention_days)
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        raise ValueError("now must include a timezone")
    cutoff = now.astimezone(timezone.utc) - timedelta(days=days)

    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    directory_path = os.path.abspath(os.fspath(directory))
    directory_fd = os.open(Path(directory), flags)
    try:
        valid = []
        for name in os.listdir(directory_fd):
            match = BACKUP_RE.fullmatch(name)
            if not match:
                continue
            try:
                created = datetime.strptime(match.group(1), "%Y%m%dT%H%M%SZ").replace(
                    tzinfo=timezone.utc)
            except ValueError:
                continue
            if _verified_pair(directory_fd, name, directory_path):
                valid.append((created, name))

        newest = max(valid, default=(None, None))[1]
        removed = []
        for created, name in valid:
            if name == newest or created >= cutoff:
                continue
            # Recheck the pair immediately before unlinking. unlinkat does not follow symlinks.
            if not _verified_pair(directory_fd, name, directory_path):
                continue
            os.unlink(name + ".sha256", dir_fd=directory_fd)
            os.unlink(name, dir_fd=directory_fd)
            removed.append(name)
        return removed
    finally:
        os.close(directory_fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--retention-days", required=True)
    args = parser.parse_args()
    try:
        removed = prune(args.directory, args.retention_days)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    for name in removed:
        print(f"Removed expired Pebble backup: {name}")


if __name__ == "__main__":
    main()
