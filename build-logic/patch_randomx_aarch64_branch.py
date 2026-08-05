#!/usr/bin/env python3
"""Apply the Android shared-library branch-locality fix to pinned RandomX 2.0.1.

RandomX's hand-written AArch64 template branches back to the globally visible
`randomx_program_aarch64_main_loop` symbol. In a ThinLTO/shared-library link,
the linker can leave an R_AARCH64_CONDBR19 relocation against that global
symbol and place its resolved target beyond the instruction's +/-1 MiB range.

The loop target is physically adjacent in the same assembly template. Add a
local alias and branch to it, preserving the exact one-instruction hot loop
while allowing the assembler to resolve the branch with no ELF relocation.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

MARKER = ".Lcryptosteer_randomx_program_aarch64_main_loop"
GLOBAL_LABEL = "DECL(randomx_program_aarch64_main_loop):"
GLOBAL_BRANCH_RE = re.compile(
    r"^(?P<indent>\s*)bne\s+DECL\(randomx_program_aarch64_main_loop\)\s*$",
    re.MULTILINE,
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} <RandomX-source-directory>", file=sys.stderr)
        return 64

    root = Path(sys.argv[1]).resolve()
    source = root / "src" / "jit_compiler_a64_static.S"
    if not source.is_file():
        print(f"RandomX AArch64 template is missing: {source}", file=sys.stderr)
        return 65

    before_bytes = source.read_bytes()
    text = before_bytes.decode("utf-8")

    if MARKER in text:
        if f"bne\t{MARKER}" not in text and f"bne {MARKER}" not in text:
            print("RandomX local branch marker exists but the loop branch does not target it.", file=sys.stderr)
            return 65
        print("RandomX AArch64 branch-locality patch already applied.")
        return 0

    if text.count(GLOBAL_LABEL) != 1:
        print(
            "Pinned RandomX source changed unexpectedly: expected exactly one global main-loop label.",
            file=sys.stderr,
        )
        return 65

    matches = list(GLOBAL_BRANCH_RE.finditer(text))
    if len(matches) != 1:
        print(
            "Pinned RandomX source changed unexpectedly: expected exactly one global BNE loop branch.",
            file=sys.stderr,
        )
        return 65

    text = text.replace(GLOBAL_LABEL, f"{GLOBAL_LABEL}\n{MARKER}:", 1)
    text = GLOBAL_BRANCH_RE.sub(lambda m: f"{m.group('indent')}bne\t{MARKER}", text, count=1)

    if GLOBAL_BRANCH_RE.search(text):
        print("Global RandomX conditional branch remains after patching.", file=sys.stderr)
        return 65
    if text.count(f"{MARKER}:") != 1:
        print("RandomX local branch target was not inserted exactly once.", file=sys.stderr)
        return 65

    after_bytes = text.encode("utf-8")
    source.write_bytes(after_bytes)

    state = {
        "schema": 1,
        "file": "src/jit_compiler_a64_static.S",
        "purpose": "Resolve the ARM64 RandomX hot-loop conditional branch locally for Android shared-library links.",
        "beforeSha256": sha256(before_bytes),
        "afterSha256": sha256(after_bytes),
        "marker": MARKER,
    }
    state_path = root.parent.parent / "RANDOMX_AARCH64_BRANCH_PATCH.json"
    state_path.write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Applied RandomX AArch64 branch-locality patch: {state_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
