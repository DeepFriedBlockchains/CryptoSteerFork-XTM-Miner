#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER = ROOT / "build-logic" / "patch_randomx_aarch64_branch.py"

SOURCE = r'''#if defined(__APPLE__)
#define DECL(x) _##x
#else
#define DECL(x) x
#endif
    .arch armv8-a
    .text
    .global DECL(randomx_program_aarch64)
    .global DECL(randomx_program_aarch64_main_loop)
DECL(randomx_program_aarch64):
    mov x3, 3
DECL(randomx_program_aarch64_main_loop):
    subs x3, x3, 1
    bne DECL(randomx_program_aarch64_main_loop)
    ret
'''


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=True)


def main() -> int:
    clang = subprocess.run(["bash", "-lc", "command -v clang"], text=True, capture_output=True).stdout.strip()
    readelf = subprocess.run(["bash", "-lc", "command -v llvm-readelf || command -v readelf"], text=True, capture_output=True).stdout.strip()
    llvm_ar = subprocess.run(["bash", "-lc", "command -v llvm-ar"], text=True, capture_output=True).stdout.strip()
    ld_lld = subprocess.run(["bash", "-lc", "command -v ld.lld"], text=True, capture_output=True).stdout.strip()
    if not clang or not readelf:
        print("RandomX AArch64 patch regression test skipped: clang/readelf unavailable.")
        return 0

    with tempfile.TemporaryDirectory(prefix="cryptosteer-randomx-patch-") as temp:
        root = Path(temp) / "third_party" / "src" / "RandomX"
        source = root / "src" / "jit_compiler_a64_static.S"
        source.parent.mkdir(parents=True)
        source.write_text(SOURCE)
        run(sys.executable, str(PATCHER), str(root))
        patched = source.read_text()
        assert ".Lcryptosteer_randomx_program_aarch64_main_loop:" in patched
        assert "bne\t.Lcryptosteer_randomx_program_aarch64_main_loop" in patched
        assert "bne DECL(randomx_program_aarch64_main_loop)" not in patched

        obj = Path(temp) / "patched.o"
        run(clang, "--target=aarch64-linux-gnu", "-x", "assembler-with-cpp", "-c", str(source), "-o", str(obj))
        relocations = run(readelf, "-rW", str(obj)).stdout
        assert "R_AARCH64_CONDBR19" not in relocations

        if llvm_ar and ld_lld:
            archive = Path(temp) / "librandomx-fixture.a"
            run(llvm_ar, "rcs", str(archive), str(obj))
            entry_source = Path(temp) / "entry.S"
            entry_source.write_text("""
.text
.global cryptosteer_jni_fixture
.type cryptosteer_jni_fixture,%function
cryptosteer_jni_fixture:
  ret
.size cryptosteer_jni_fixture, .-cryptosteer_jni_fixture
""")
            entry_obj = Path(temp) / "entry.o"
            run(clang, "--target=aarch64-linux-gnu", "-c", str(entry_source), "-o", str(entry_obj))
            shared = Path(temp) / "libfixture.so"
            run(ld_lld, "-m", "aarch64linux", "-shared", "--exclude-libs", "ALL", "--whole-archive", str(archive), "--no-whole-archive", str(entry_obj), "-o", str(shared))
            symbols = run(readelf, "-Ws", str(shared)).stdout
            global_lines = [line for line in symbols.splitlines() if "randomx_program_aarch64_main_loop" in line and " GLOBAL " in line]
            assert not global_lines
            assert any("cryptosteer_jni_fixture" in line and " GLOBAL " in line for line in symbols.splitlines())

    print("RandomX AArch64 branch patch regression test: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
