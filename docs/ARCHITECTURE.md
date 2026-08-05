
# Architecture

## Android application

The Android layer is written in Kotlin and Java. It manages user settings, pool profiles, foreground-service state, notifications, statistics, device information, thermal safeguards, account actions, and remote-rig controls.

Sensitive local values are stored through Android platform security APIs where applicable. Pool passwords are not written to logs.

## Native mining layer

The native layer is written in C and C++ and is accessed through JNI. It contains:

- the RandomX execution context;
- worker scheduling and CPU affinity;
- Stratum request and response handling;
- TCP and TLS transport;
- share submission and accounting;
- benchmark and calibration support; and
- the active-mining contribution scheduler.

## Native dependencies

RandomX, libuv, hwloc, and OpenSSL are pinned by full commit. The Android build links static native libraries into the application shared library.

The dependency build targets ARM64, uses the NDK Clang toolchain, and applies 16 KB maximum and common page-size linker settings.

## Optional network services

Mining connections go directly to the pool selected by the user.

Account, remote-rig, installation telemetry, and direct-update functions use the public CryptoSteer service endpoints. The F-Droid build disables the private updater and anonymous installation/mining telemetry at compile time.

Release manifests are verified with an embedded Ed25519 public key. The corresponding private signing key is not part of this repository.
