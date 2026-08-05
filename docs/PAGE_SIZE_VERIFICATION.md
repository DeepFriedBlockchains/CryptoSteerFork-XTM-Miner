# 4 KB and 16 KB Page-Size Verification

## Source design

- Android NDK `29.0.14206865` is pinned for every first- and third-party native target.
- Every native dependency is rebuilt for `arm64-v8a`; no precompiled `.so` is included.
- Runtime page size is read with `sysconf(_SC_PAGESIZE)` rather than assuming 4,096 bytes.
- First-party mappings and diagnostics accept both 4,096-byte and 16,384-byte base pages.
- The JNI shared object uses explicit reproducible link options:

```text
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

RandomX cache, dataset, JIT allocation, and executable-memory transitions use the pinned upstream Android build. JIT is probed at runtime and falls back to the portable interpreter when allocation or VM creation is unavailable.

## Release verification

`scripts/verify-16kb-alignment.sh` performs these mandatory checks on the exact signed APK:

1. `zipalign -c -P 16 4`;
2. extract every packaged ARM64 `.so`;
3. inspect every program header with NDK `llvm-readelf -lW`;
4. fail when any LOAD segment has alignment below 16,384 bytes;
5. report the ABI, library name, and LOAD-segment result.

## Current status

Source-level runtime-page-size and native syntax checks are included in the normal test suite. Run `scripts/verify-16kb-alignment.sh` against the completed APK to verify ZIP and ELF alignment before distribution.
