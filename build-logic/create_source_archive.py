#!/usr/bin/env python3
"""Create deterministic CryptoSteer source archives with strict secret handling.

Project-owned files are screened for signing material and machine-local state.
Pinned upstream source under third_party/src is treated differently: it is
verified against DEPENDENCY_LOCK.json and SOURCE_STATE.json, then archived
byte-for-byte, including public test certificates and keys that are part of the
upstream source trees and may be required for reproducible builds or tests.
"""
from __future__ import annotations

import argparse
import json
import os
import stat
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

ARCHIVE_TIME = (2026, 8, 5, 0, 0, 0)
GENERATED_DIR_NAMES = {".git", ".gradle", ".idea", "build", ".cxx", ".host-test-build", "__pycache__"}
PROJECT_SECRET_SUFFIXES = {
    ".apk", ".aab", ".class", ".o", ".so", ".a", ".keystore", ".jks",
    ".p12", ".pfx", ".pem", ".key", ".pyc",
}
PROJECT_SECRET_NAMES = {"signing.properties", "local.properties"}
EXPECTED_VENDORED_NAMES = {"RandomX", "libuv", "hwloc", "OpenSSL"}


@dataclass(frozen=True)
class ArchiveEntry:
    source: Path
    archive_path: PurePosixPath
    symlink_target: str | None = None


def is_vendored(parts: tuple[str, ...]) -> bool:
    return len(parts) >= 3 and parts[0] == "third_party" and parts[1] == "src"


def is_generated_project_path(parts: tuple[str, ...]) -> bool:
    if not parts:
        return False
    if len(parts) >= 2 and parts[0] == "third_party" and parts[1] in {"build", "install"}:
        return True
    return any(part in GENERATED_DIR_NAMES for part in parts)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Unable to read required JSON file {path}: {exc}") from exc


def validate_vendored_sources(root: Path) -> None:
    lock = load_json(root / "DEPENDENCY_LOCK.json")
    state = load_json(root / "third_party" / "SOURCE_STATE.json")
    native = lock.get("native")
    if not isinstance(native, list):
        raise SystemExit("DEPENDENCY_LOCK.json has no native dependency list.")
    expected = {item.get("name"): item.get("commit") for item in native if isinstance(item, dict)}
    if set(expected) != EXPECTED_VENDORED_NAMES or any(not value for value in expected.values()):
        raise SystemExit("Pinned native dependency names or commits are incomplete.")
    state_commits = state.get("commits")
    if not isinstance(state_commits, dict) or state_commits != expected:
        raise SystemExit("third_party/SOURCE_STATE.json does not match DEPENDENCY_LOCK.json.")

    source_root = root / "third_party" / "src"
    if not source_root.is_dir():
        raise SystemExit("Vendored source directory is missing: third_party/src")
    actual_names = {path.name for path in source_root.iterdir() if path.is_dir()}
    if actual_names != EXPECTED_VENDORED_NAMES:
        raise SystemExit(
            "Vendored source roots differ from the locked dependency set: "
            f"expected {sorted(EXPECTED_VENDORED_NAMES)}, found {sorted(actual_names)}"
        )
    for name, commit in expected.items():
        marker = source_root / name / ".cryptosteer-upstream-commit"
        if not marker.is_file() or marker.read_text(encoding="utf-8").strip() != commit:
            raise SystemExit(f"Vendored source identity check failed for {name}.")


def validate_vendored_symlink(root: Path, path: Path, rel: Path) -> str:
    target = os.readlink(path)
    if os.path.isabs(target):
        raise SystemExit(f"Refusing absolute vendored-source symbolic link: {rel} -> {target}")
    source_name = rel.parts[2]
    source_root = (root / "third_party" / "src" / source_name).resolve()
    resolved = (path.parent / target).resolve(strict=False)
    try:
        resolved.relative_to(source_root)
    except ValueError as exc:
        raise SystemExit(f"Refusing escaping vendored-source symbolic link: {rel} -> {target}") from exc
    return target


def collect_entries(root: Path, output: Path, project_name: str, include_vendored: bool) -> list[ArchiveEntry]:
    if include_vendored:
        validate_vendored_sources(root)

    entries: list[ArchiveEntry] = []
    archive_root = PurePosixPath(project_name)
    for path in root.rglob("*"):
        rel = path.relative_to(root)
        parts = rel.parts
        vendored = is_vendored(parts)

        if vendored and not include_vendored:
            continue
        if not vendored and is_generated_project_path(parts):
            continue
        if len(parts) >= 2 and parts[0] == "third_party" and parts[1] in {"build", "install"}:
            continue
        if path.resolve(strict=False) == output:
            continue

        if path.is_symlink():
            if not vendored:
                raise SystemExit(f"Refusing project symbolic link: {rel}")
            target = validate_vendored_symlink(root, path, rel)
            entries.append(ArchiveEntry(path, archive_root / PurePosixPath(rel.as_posix()), target))
            continue

        if not path.is_file():
            continue
        if not vendored and (path.name in PROJECT_SECRET_NAMES or path.suffix.lower() in PROJECT_SECRET_SUFFIXES):
            raise SystemExit(f"Refusing to archive generated or sensitive project file: {rel}")
        entries.append(ArchiveEntry(path, archive_root / PurePosixPath(rel.as_posix())))

    return sorted(entries, key=lambda entry: entry.archive_path.as_posix())


def write_archive(output: Path, entries: list[ArchiveEntry]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for entry in entries:
            if entry.symlink_target is not None:
                info = zipfile.ZipInfo(entry.archive_path.as_posix(), date_time=ARCHIVE_TIME)
                info.create_system = 3
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                info.compress_type = zipfile.ZIP_STORED
                archive.writestr(info, entry.symlink_target.encode("utf-8"))
                continue

            mode = entry.source.stat().st_mode
            permissions = 0o755 if mode & stat.S_IXUSR else 0o644
            info = zipfile.ZipInfo(entry.archive_path.as_posix(), date_time=ARCHIVE_TIME)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | permissions) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, entry.source.read_bytes())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--project-name", required=True)
    parser.add_argument("--include-vendored", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    output = args.output.resolve()
    if not root.is_dir():
        raise SystemExit(f"Project root is unavailable: {root}")
    entries = collect_entries(root, output, args.project_name, args.include_vendored)
    if not entries:
        raise SystemExit("No source files were selected for the archive.")
    write_archive(output, entries)
    print(f"{output}\nArchived {len(entries)} deterministic entries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
