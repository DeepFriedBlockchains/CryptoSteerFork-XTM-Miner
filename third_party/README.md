
# Third-party source and build layout

The project uses pinned source revisions for RandomX, libuv, hwloc, and OpenSSL.

- `third_party/src/<dependency>` contains the verified source tree.
- `third_party/build/` is a disposable local build directory.
- `third_party/install/android-arm64/` contains generated static libraries and headers consumed by the Android native target.

Release tags intended for F-Droid include the complete source trees and commit identity markers. Generated build and install directories are ignored.
