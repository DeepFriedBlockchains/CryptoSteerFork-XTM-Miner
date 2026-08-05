#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import os
import stat
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "build-logic" / "create_source_archive.py"
spec = importlib.util.spec_from_file_location("create_source_archive", MODULE_PATH)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

COMMITS = {
    "RandomX": "a" * 40,
    "libuv": "b" * 40,
    "hwloc": "c" * 40,
    "OpenSSL": "d" * 40,
}


def make_project(base: Path) -> Path:
    root = base / "project"
    root.mkdir()
    (root / "safe.txt").write_text("safe\n", encoding="utf-8")
    lock = {"native": [{"name": name, "commit": commit} for name, commit in COMMITS.items()]}
    (root / "DEPENDENCY_LOCK.json").write_text(json.dumps(lock), encoding="utf-8")
    third_party = root / "third_party"
    source_root = third_party / "src"
    source_root.mkdir(parents=True)
    (third_party / "SOURCE_STATE.json").write_text(json.dumps({"commits": COMMITS}), encoding="utf-8")
    for name, commit in COMMITS.items():
        dependency = source_root / name
        dependency.mkdir()
        (dependency / ".cryptosteer-upstream-commit").write_text(commit + "\n", encoding="utf-8")
        (dependency / "source.c").write_text("int source(void) { return 0; }\n", encoding="utf-8")
    openssl = source_root / "OpenSSL"
    (openssl / "apps").mkdir()
    (openssl / "apps" / "ca-key.pem").write_text("PUBLIC UPSTREAM TEST FIXTURE\n", encoding="utf-8")
    (openssl / "test").mkdir()
    (openssl / "test" / "fixture.key").write_text("PUBLIC UPSTREAM TEST KEY\n", encoding="utf-8")
    if hasattr(os, "symlink"):
        os.symlink("../apps/ca-key.pem", openssl / "test" / "ca-key-link.pem")
    return root


def test_vendored_fixtures_are_preserved() -> None:
    with tempfile.TemporaryDirectory() as temp:
        root = make_project(Path(temp))
        output = Path(temp) / "source.zip"
        entries = module.collect_entries(root, output.resolve(), "Project", True)
        module.write_archive(output, entries)
        with zipfile.ZipFile(output) as archive:
            names = set(archive.namelist())
            assert "Project/third_party/src/OpenSSL/apps/ca-key.pem" in names
            assert "Project/third_party/src/OpenSSL/test/fixture.key" in names
            link_name = "Project/third_party/src/OpenSSL/test/ca-key-link.pem"
            if link_name in names:
                info = archive.getinfo(link_name)
                assert stat.S_ISLNK(info.external_attr >> 16)
                assert archive.read(link_name).decode() == "../apps/ca-key.pem"


def test_project_secret_is_rejected() -> None:
    with tempfile.TemporaryDirectory() as temp:
        root = make_project(Path(temp))
        (root / "release-private.pem").write_text("secret\n", encoding="utf-8")
        try:
            module.collect_entries(root, (Path(temp) / "source.zip").resolve(), "Project", True)
        except SystemExit as exc:
            assert "sensitive project file" in str(exc)
        else:
            raise AssertionError("Project-owned PEM file was not rejected")


def test_vendored_identity_mismatch_is_rejected() -> None:
    with tempfile.TemporaryDirectory() as temp:
        root = make_project(Path(temp))
        marker = root / "third_party" / "src" / "OpenSSL" / ".cryptosteer-upstream-commit"
        marker.write_text("0" * 40 + "\n", encoding="utf-8")
        try:
            module.collect_entries(root, (Path(temp) / "source.zip").resolve(), "Project", True)
        except SystemExit as exc:
            assert "identity check failed" in str(exc)
        else:
            raise AssertionError("Mismatched vendored source identity was not rejected")


if __name__ == "__main__":
    test_vendored_fixtures_are_preserved()
    test_project_secret_is_rejected()
    test_vendored_identity_mismatch_is_rejected()
    print("Source archive security policy tests: PASS")
