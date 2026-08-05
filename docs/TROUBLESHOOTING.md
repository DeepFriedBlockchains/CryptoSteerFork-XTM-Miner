
# Troubleshooting

## Android SDK not found

Set the SDK location before building:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
```

Then run:

```bash
./scripts/setup-linux-builder.sh
```

## Wrong Java version

The build requires JDK 17:

```bash
java -version
javac -version
```

Select JDK 17 with your distribution's Java alternatives tool when another version is active.

## Missing native source

Run:

```bash
./scripts/vendor-dependencies.sh
```

The script checks out the exact commits listed in `DEPENDENCY_LOCK.json`.

## Native dependency build fails

Confirm that the pinned NDK and CMake packages exist:

```bash
test -x "$ANDROID_SDK_ROOT/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
test -x "$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
test -x "$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja"
```

Remove generated native output and build again:

```bash
rm -rf third_party/build third_party/install
./scripts/build-linux.sh fdroid
```

## Release APK is unsigned

The `release` build is unsigned unless `CRYPTOSTEER_SIGNING_PROPERTIES` points to a readable properties file containing a valid keystore path, passwords, and alias.

The `fdroid` build is intentionally unsigned.

## Installed build will not update over another build

Android requires matching application IDs and signing certificates for in-place updates. Direct and F-Droid builds use different signing certificates, so switching channels requires uninstalling the existing app first.
