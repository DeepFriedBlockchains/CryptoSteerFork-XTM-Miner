
# F-Droid build

The `fdroid` build type keeps the mining engine and the same application ID as the direct release. It changes the distribution-specific behavior at compile time:

- disables the private APK updater;
- disables anonymous installation and mining telemetry;
- removes `REQUEST_INSTALL_PACKAGES`;
- removes the update-install receiver; and
- leaves optional account and remote-rig functions user initiated.

Build it on x86-64 Linux:

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./scripts/setup-linux-builder.sh
./scripts/build-linux.sh fdroid
```

The output is unsigned:

```text
app/build/outputs/apk/fdroid/app-fdroid.apk
```

F-Droid builds the application from source and applies its own signing key. A direct-distribution installation cannot be updated in place by the F-Droid build, and the F-Droid installation cannot be updated in place by the direct build.

The proposed repository metadata is stored at:

```text
fdroid-submission/metadata/com.thecryptosteer.xmrminer.yml
```

The release source includes the exact pinned RandomX, libuv, hwloc, and OpenSSL trees. Public OpenSSL test certificates and test keys inside `third_party/src/OpenSSL` are upstream fixtures from the verified source commit; they are not application signing material or service credentials.
