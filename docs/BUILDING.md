
# Building on Linux

## Host requirements

- x86-64 Linux
- JDK 17
- 25 GiB or more of free disk space
- 4 GiB RAM minimum; 8 GiB recommended
- Network access for the Android SDK, Gradle, Maven dependencies, and pinned native source repositories

The application targets `arm64-v8a` and requires:

- Android platform 37
- Android Build Tools 37.0.0
- Android NDK 29.0.14206865
- CMake 3.22.1
- Gradle 9.5.0
- Kotlin 2.4.10

## Host packages

Ubuntu or Debian:

```bash
sudo apt update
sudo apt install -y \
  openjdk-17-jdk git curl ca-certificates unzip zip jq \
  build-essential autoconf automake libtool pkg-config perl python3 \
  ninja-build cmake clang lld
```

## Android SDK

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/setup-linux-builder.sh
```

The setup is installed under the current user's home directory. No system-wide Android SDK path is required.

## F-Droid-compatible build

```bash
./scripts/build-linux.sh fdroid
```

Expected output:

```text
app/build/outputs/apk/fdroid/app-fdroid.apk
```

This APK is unsigned. F-Droid signs repository builds with its own signing identity.

## Direct release build

Unsigned:

```bash
./scripts/build-linux.sh release
```

Signed:

```bash
cp signing.properties.example /secure/location/signing.properties
chmod 600 /secure/location/signing.properties
export CRYPTOSTEER_SIGNING_PROPERTIES="/secure/location/signing.properties"
export CRYPTOSTEER_RELEASE_PUBLIC_KEY_BASE64="BASE64_ENCODED_ED25519_PUBLIC_KEY"
./scripts/build-linux.sh release
```

The signing file contains:

```properties
storeFile=/absolute/path/to/release-keystore.jks
storePassword=replace-with-keystore-password
keyAlias=replace-with-key-alias
keyPassword=replace-with-key-password
```

Keep the keystore and properties file outside the repository.

## Native dependencies

The project pins RandomX, libuv, hwloc, and OpenSSL by full commit in `DEPENDENCY_LOCK.json`.

Release tags contain the complete pinned source trees. A normal development checkout may obtain missing trees with:

```bash
./scripts/vendor-dependencies.sh
```

Native libraries are compiled for Android ARM64 and installed locally under:

```text
third_party/install/android-arm64
```

The directory is generated and must not be committed.

## Verification

Run all checks available on the host:

```bash
./scripts/run-all-checks.sh
```

Verify a completed APK:

```bash
./scripts/verify-release-apk.sh /absolute/path/to/app.apk
```

The verification scripts check the manifest, package metadata, signing state, native libraries, and 16 KB ELF alignment.
