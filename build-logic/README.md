# Build Logic

`verify_lock.py` is an executable release gate. It cross-checks SDK, NDK, Gradle, Kotlin, native commit pins, donation endpoint/transport constants, user-pool TLS enforcement, and required repository files before any release is packaged.
