# Dependency Lock

## Application identity

- Application ID: `com.thecryptosteer.xmrminer`
- Version name: `2.2.1`
- Version code: `20201`
- Release scripts, native runtime macro, SBOM, and archive names are validated against this identity by `build-logic/verify_lock.py`.

All production dependencies are exact versions or immutable Git commits. `scripts/vendor-dependencies.sh` checks out the commits recorded in `DEPENDENCY_LOCK.json`, verifies `HEAD`, records source state, and refuses dirty or mismatched trees.

## Android build

- Android API 37; minimum API 36
- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- Kotlin 2.4.10
- JDK 17
- Android NDK 29.0.14206865
- CMake 3.22.1
- ARM64 only; 16 KB-compatible ELF alignment

## Native source

| Component | Version | Immutable commit | License |
|---|---:|---|---|
| RandomX | 2.0.1 | `aaafe71322df6602c21a5c72937ac284724ae561` | BSD-3-Clause |
| libuv | 1.52.1 | `1cfa32ff59c076ffb6ed735bbc8c18361558661f` | MIT |
| hwloc | 2.14.0 | `b5660dff631a171a96a4b3abf5a170c9cf62d6ef` | BSD-3-Clause |
| OpenSSL | 3.5.7 | `8cf17aaeb4599f8af87fefd810b5b5fee90fe69e` | Apache-2.0 |

The source ZIP intentionally does not embed third-party Git trees. It contains a deterministic, fail-closed vendoring process so a clean build never follows a moving branch.

## Control-plane dependencies

- OkHttp 5.1.0 for HTTPS and WebSocket transport
- AndroidX Biometric 1.4.0-alpha04 for opt-in biometric unlock
- AndroidX Fragment 1.8.9 for the FragmentActivity biometric host

## Donation policy

The immutable wallet and donation endpoint remain source-locked. The permitted cumulative active-time tiers are exactly 200 basis points initially and 100 basis points after 2,592,000 seconds of authenticated account user-mining time. The backend may only select one of those two compiled tiers; it cannot supply a wallet, pool, or arbitrary percentage.
