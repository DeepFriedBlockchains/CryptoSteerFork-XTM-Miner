# Privacy

CryptoSteer XMR Miner does not include advertising SDKs, third-party analytics, cross-app tracking, or automatic third-party crash reporting.

## Data stored on the Android device

- Pool profiles, tuning settings, local hashrate history, and redacted logs.
- Pool passwords encrypted with an Android Keystore AES-GCM key.
- When the user signs in, an account refresh token and short-lived access token encrypted with Android Keystore. If biometric login is enabled, the stored account session requires a strong biometric operation before it can be decrypted.
- A bounded list of completed remote-command identifiers and results, used only to prevent a command from being applied twice after a reconnect.

## Data sent to mining pools

The app connects directly to mining pool hosts chosen by the user and, during the disclosed donation slice, to the built-in donation pool. A pool can observe the connecting IP address, pool username or wallet address, worker name, submitted shares, and ordinary connection metadata. TLS is enabled by default for user profiles and certificate/hostname validation is enforced when TLS is selected.

## Data sent to the CryptoSteer control service

Update checks are sent to `https://miner-api.thecryptosteer.com`. When the user chooses to create or use an account, the app also sends:

- The wallet address used as the account identifier.
- A password verifier generated server-side with Argon2id; the plaintext password is not stored by the service.
- A random installation identity, device display name, platform, and app version.
- Live mining state, hashrate, cumulative user/donation mining counters, settings, pool profile configuration, bounded redacted logs, benchmark summaries, and tuning summaries needed to display and control the user’s own rigs.
- Trusted-device approval decisions and remote-control commands.

Pool passwords are not included in routine rig telemetry. A pool password is sent through the authenticated control service only when the account owner explicitly creates or changes that pool profile from another trusted rig. The service stores account/device state, release metadata, audit events, and command delivery state in its private PostgreSQL database.

## Account recovery and administration

Users who lose their trusted device or password are directed to `dev@thecryptosteer.com`. After identity verification outside the app, an administrator can either revoke all trusted devices and open a time-limited password-authenticated enrollment window, or issue a one-time 30-minute recovery code for a forgotten password. Only a hash of that recovery code is retained by the service; redeeming it revokes prior sessions, sets the new password verifier, and enrolls one replacement device. Administrative recovery actions and release publications are recorded in an audit log.

A local diagnostics export is created only after an explicit user action. Deleting the app clears its local data. Account deletion is handled through `dev@thecryptosteer.com` until a self-service deletion flow is released.
