
# Threat model

## Protected assets

- pool credentials and wallet identifiers entered by the user;
- account session material;
- local device enrollment state;
- APK signing keys held outside the repository;
- the Ed25519 key used to sign update manifests;
- integrity of the mining engine and contribution scheduler.

## Trust boundaries

1. Android UI and foreground service to the JNI mining engine.
2. The application to user-selected mining pools over TCP or TLS.
3. Optional application features to public CryptoSteer HTTPS and WebSocket endpoints.
4. The direct update client to signed release metadata and downloaded APK files.

## Main risks

- modified pool responses or an untrusted pool;
- credential disclosure through logs or unsafe storage;
- a malicious replacement APK;
- forged update metadata;
- unauthorized remote-rig enrollment or commands;
- resource exhaustion, excessive heat, or unsafe operating settings;
- tampering with contribution scheduling;
- compromise of a rooted device.

## Mitigations

- mining is user initiated and remains visible through a foreground notification;
- TLS pool connections perform certificate and host verification;
- pool passwords are excluded from normal logs;
- update metadata is verified with Ed25519 and APK files are checked by SHA-256;
- account and command requests require authenticated sessions and trusted-device approval;
- thermal and power safeguards can pause or reduce work;
- contribution timing is measured from active mining time and covered by tests;
- signing keys and service credentials are excluded from source control.

A rooted or otherwise compromised device can bypass application-level controls. Users should keep Android updated and install builds only from a trusted distribution channel.
