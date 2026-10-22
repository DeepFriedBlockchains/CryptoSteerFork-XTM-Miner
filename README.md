# NFP XTM-MINER

**Experimental Android Tari (XTM) miner · Development preview**

**Maintained by Night Fantom Projects (NFProjects)** · Based on [CryptoSteer XMR Miner](https://github.com/Crypto-Steer-LLC/CryptoSteer-XMR-Miner) by The Crypto Steer LLC.

NFP XTM-MINER is an independent fork adapting the CryptoSteer Android mining application toward Tari (XTM) support. The application is in **pre-release testing**, not a verified public release.

> **Independent project:** NFProjects is not affiliated with, endorsed by, or officially associated with CryptoSteer LLC, the Tari Project, Monero, or XMRig. We thank the original CryptoSteer developers and the wider open-source mining community for their work.

## Development preview

Two early Android interface screenshots—**Mining Dashboard** and **More / Tools**—have been prepared for publication. They will be displayed here once the screenshot files are committed to `docs/screenshots/`.

The interface is a development preview; appearance and functionality may change.

## Project goals and features under evaluation

- Native C/C++ mining components with an Android Kotlin/Java application layer
- ARM64 Android support
- User-managed mining pools and connection settings
- Live hashrate, share counts, uptime and session information
- Adjustable performance modes and device diagnostics
- Tari-specific mining, wallet and pool compatibility (**verification in progress**)
- Removal of the upstream developer-fee mechanism (**source and runtime audit pending**)

Some features originate in the upstream application and may require further adaptation or testing. Listing a feature here does not mean it has passed release verification.

## Preliminary testing — October 22, 2026

Initial Android testing compared this development build with the original CryptoSteer XMR APK using similar configurations. The applications exhibited broadly comparable reported hashrates and operating behavior. This is an encouraging **preliminary observation**, not proof of successful Tari mining.

The following remain unverified: Tari pool accepted shares, complete source and build integrity, developer-fee removal, security, long-running stability and release readiness.

## Release verification checklist

- [x] Initial Android application testing
- [x] Preliminary comparison with upstream XMR miner
- [ ] Review the complete source and build configuration
- [ ] Confirm Tari algorithm, wallet handling and pool protocol
- [ ] Confirm accepted Tari shares on a pool
- [ ] Audit developer-fee logic and any alternate pool routing
- [ ] Inspect network communications, permissions and dependencies
- [ ] Perform extended stability, battery and thermal testing
- [ ] Verify release signing, APK integrity and checksums
- [ ] Publish a tested release with reproducible instructions

## Downloads

**No public APK release is available yet.** Development APKs are for controlled testing. A verified release and checksum will be published through official NFProjects channels after testing and review.

## Source and building

Repository: [NightFantomProjects/CryptoSteerFork-XTM-Miner](https://github.com/NightFantomProjects/CryptoSteerFork-XTM-Miner)

```bash
git clone https://github.com/NightFantomProjects/CryptoSteerFork-XTM-Miner.git
cd CryptoSteerFork-XTM-Miner
```

The upstream project uses a Linux/Android toolchain. Fork-specific dependencies, build scripts, signing and build variants must be checked before publishing authoritative build instructions. Do not assume the original project's release steps produce a verified Tari APK.

## Responsible mining

Mining can cause sustained CPU load, heat, higher power consumption and battery or hardware wear. Mine only on devices you own or are authorized to operate. Do not bypass thermal safeguards. Users should explicitly control when mining starts and stops.

## Credits and licensing

- **Fork maintainer:** Night Fantom Projects (NFProjects)
- **Original application:** [CryptoSteer XMR Miner](https://github.com/Crypto-Steer-LLC/CryptoSteer-XMR-Miner), The Crypto Steer LLC
- **Acknowledgments:** XMRig, Monero and Tari open-source contributors

The upstream repository identifies Apache License 2.0 for application source and CC BY-SA 4.0 for certain artwork; third-party components retain their respective licenses. Refer to the repository's `LICENSE`, `NOTICE`, `ARTWORK_LICENSE.md` and third-party notices for the actual applicable terms. Original copyright and license notices remain in force.

---

*Built on open-source foundations. Developed independently. Testing and transparency first.*
