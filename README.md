FIRST, WE ARE NOT AFFILIATED WITH MONERO, TARI PROJECT, OR CRYPTOSTEER LLC. THIS IS AN EXPERIMENTIAL MOBILE MINER PROJECT USING OTHER GREAT WORKS TO CONTINUE EXPANDING ON OTHERS GREAT IDEAS. 
THANKS TO XMR, XTM, XMRIG, & THE ORIGINAL BUILDERS (CryptoSteer LLC) FOR ALL THE TIME, EFFORT AND WORK THEY PUT INTO THEIR ORIGINAL PROJECT SOURCECODE AND BUILD. THEIR SUCCESS & ACHIEVEMENTS WITH ITS ORIGINAL BUILD HELPED PAVE THE WAY TO SPEED UP OUR PROJECT WORK AND MADE THE PROJECT FORK MUCH EASIER TO EXPAND ON. THANKS AGAIN CRYPTOSTEER! 
# CryptoSteer XMR Miner

<p align="center">
  <img src="branding/cryptosteer-xmr-miner-logo.png" alt="CryptoSteer XMR Miner" width="520">
</p>

XTM Miner is an NFProject fork of CryptoSteer XMR Miner. A native Android RandomX miner published by **THE CRYPTO STEER LLC**. Mining starts only when the user requests it and remains visible through an Android foreground-service notification.

Use the application only on devices that you own or are authorized to operate. Sustained mining creates heat, increases power use, and can accelerate battery and hardware wear.

## Features

- Native RandomX mining engine written in C and C++
- Kotlin and Java Android application layer
- ARM64 build for Android 16 and newer
- User-managed pool profiles with TLS support
- Live hashrate, accepted and rejected shares, uptime, and session history
- Eco, Balanced, Performance, Max, and custom operating profiles
- Benchmarking, calibration, temperature monitoring, and thermal safeguards
- Optional account and remote-rig controls
- Visible developer contribution: 2% of active mining time, reduced to 1% after the documented threshold

## Build variants

| Variant | Purpose |
| --- | --- |
| `fdroid` | Unsigned F-Droid-compatible APK. The private APK updater and anonymous installation/mining telemetry are disabled at compile time. |
| `release` | Direct-distribution APK. It can be signed with a maintainer-owned keystore and configured with the public update-verification key. |
| `debug` | Development build with the `.debug` application ID suffix. |

The F-Droid and direct-distribution APKs are signed by different parties and cannot update one another in place.

## Build on Linux

The supported build host is x86-64 Linux with JDK 17. The Android output is ARM64 only.

### 1. Install host packages

Ubuntu or Debian:

```bash
sudo apt update
sudo apt install -y \
  openjdk-17-jdk git curl ca-certificates unzip zip jq \
  build-essential autoconf automake libtool pkg-config perl python3 \
  ninja-build cmake clang lld
```

### 2. Clone the repository

```bash
git clone https://github.com/Crypto-Steer-LLC/CryptoSteer-XMR-Miner.git
cd CryptoSteer-XMR-Miner
git checkout v2.2.1
```

### 3. Install the pinned Android toolchain

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/setup-linux-builder.sh
```

The setup script installs the Android 17 platform, Build Tools 37.0.0, NDK 29.0.14206865, and CMake 3.22.1 under `ANDROID_SDK_ROOT`.

### 4. Build the F-Droid-compatible APK

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/build-linux.sh fdroid
```

Output:

```text
app/build/outputs/apk/fdroid/app-fdroid.apk
```

The build script verifies the dependency lock, compiles the pinned native libraries, runs the host-native and JVM tests, runs Android lint, and assembles the APK.

## Building a direct release

A direct release may be built unsigned:

```bash
./scripts/build-linux.sh release
```

To sign it, copy `signing.properties.example` to a location outside the repository, fill in the four values, and export its absolute path:

```bash
export CRYPTOSTEER_SIGNING_PROPERTIES="/absolute/path/signing.properties"
export CRYPTOSTEER_RELEASE_PUBLIC_KEY_BASE64="BASE64_ENCODED_ED25519_PUBLIC_KEY"
./scripts/build-linux.sh release
```

Never commit a keystore, signing properties, passwords, tokens, private keys, or local SDK configuration.

## Source layout

- `app/` — Android application, services, storage, UI, and tests
- `native/` — RandomX engine, Stratum client, TLS transport, and JNI bridge
- `third_party/` — pinned native source and generated local build directories
- `scripts/` — Linux build, dependency, test, and verification scripts
- `build-logic/` — source checks, lock verification, and archive tooling
- `fastlane/` — store listing metadata and artwork

## License

The application source is licensed under Apache License 2.0. The launcher and store artwork are licensed under CC BY-SA 4.0. Third-party components keep their upstream licenses.

See `LICENSE`, `ARTWORK_LICENSE.md`, `NOTICE`, and `docs/LICENSES.md`.

© 2026 THE CRYPTO STEER LLC
