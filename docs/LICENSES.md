# Third-Party Licenses

The deterministic vendoring step retrieves each upstream license at its locked commit. The release packaging process must retain attribution for:

- RandomX 2.0.1 — BSD 3-Clause
- libuv 1.52.1 — MIT
- hwloc 2.14.0 — BSD 3-Clause
- OpenSSL 3.5.7 — Apache License 2.0
- AndroidX, Kotlin, Gradle, and Android build tooling — their respective upstream licenses

No XMRig source is incorporated. The Stratum layer and mining orchestration are first-party implementations around the official RandomX API.
