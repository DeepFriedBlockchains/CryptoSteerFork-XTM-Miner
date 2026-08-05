#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
ROOT=$(repo_root)
REPORT_DIR="$ROOT/build/reports"
mkdir -p "$REPORT_DIR"
exec > >(tee "$REPORT_DIR/run-all-checks.log") 2>&1

log "Checking shell syntax"
while IFS= read -r -d '' script; do bash -n "$script"; done < <(find "$ROOT/scripts" -type f -name '*.sh' -print0)
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x -S error "$ROOT"/scripts/*.sh
else
  log "ShellCheck unavailable; bash -n completed. Install ShellCheck for the additional shell analysis."
fi

log "Verifying immutable dependency and donation lock"
python3 "$ROOT/build-logic/verify_lock.py"

log "Checking JSON and XML resources"
python3 - <<PY
import json, pathlib, xml.etree.ElementTree as ET
root=pathlib.Path(${ROOT@Q})
for path in [root/'DEPENDENCY_LOCK.json',root/'SBOM.json']:
    json.loads(path.read_text())
for path in (root/'app/src/main/res').rglob('*.xml'):
    ET.parse(path)
ET.parse(root/'app/src/main/AndroidManifest.xml')
print('JSON/XML parse checks: PASS')
PY

log "Running Python syntax, RandomX relocation regression, and mock-pool integration"
python3 -m py_compile \
  "$ROOT/tests/integration/mock_stratum_server.py" \
  "$ROOT/tests/integration/test_mock_stratum.py" \
  "$ROOT/tests/native/test_randomx_aarch64_branch_patch.py" \
  "$ROOT/tests/integration/test_source_archive_policy.py" \
  "$ROOT/build-logic/patch_randomx_aarch64_branch.py" \
  "$ROOT/build-logic/create_source_archive.py"
python3 "$ROOT/tests/native/test_randomx_aarch64_branch_patch.py"
python3 "$ROOT/tests/integration/test_mock_stratum.py"
python3 "$ROOT/tests/integration/test_source_archive_policy.py"

log "Running host-native tests"
"$ROOT/scripts/run-native-tests.sh"

log "Running translation-unit and Kotlin domain syntax checks"
"$ROOT/scripts/run-source-syntax-checks.sh"
python3 "$ROOT/build-logic/source_quality_check.py"

log "Checking source completeness rules"
if grep -RInE 'TODO|FIXME|PLACEHOLDER|stub native|fake pool|not implemented' \
  --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.git --exclude='run-all-checks.sh' "$ROOT/app" "$ROOT/native"; then
  die "Unfinished-source marker found."
fi
for required in src/jni_bridge.cpp src/miner_engine.cpp include/cryptosteer/miner_engine.hpp; do
  [[ -s "$ROOT/native/$required" ]] || die "Required native source is missing: native/$required"
done
if find "$ROOT" \
  -path "$ROOT/build" -prune -o \
  -path "$ROOT/.gradle" -prune -o \
  -path "$ROOT/third_party/src" -prune -o \
  -path "$ROOT/third_party/build" -prune -o \
  -path "$ROOT/third_party/install" -prune -o \
  -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' -o -name '*.pem' -o -name '*.key' -o -name 'signing.properties' -o -name 'local.properties' \) -print -quit | grep -q .; then
  die "Sensitive signing material or machine-local configuration exists inside the source tree."
fi
if find "$ROOT" \
  -path "$ROOT/build" -prune -o \
  -path "$ROOT/.gradle" -prune -o \
  -path "$ROOT/third_party/src" -prune -o \
  -path "$ROOT/third_party/build" -prune -o \
  -path "$ROOT/third_party/install" -prune -o \
  -type l -print -quit | grep -q .; then
  die "Symbolic links are not permitted in the deterministic source handoff."
fi
jq -e '.donation.basisPoints == 200 and .donation.reducedBasisPoints == 100 and .donation.reductionThresholdActiveMiningNanos == 2592000000000000 and .donation.poolHost == "gulf.moneroocean.stream" and .donation.poolPort == 10128 and .donation.tlsRequired == false and .donation.remoteOverride == false' "$ROOT/DEPENDENCY_LOCK.json" >/dev/null
grep -Fq -- 'ANDROID_NATIVE_API="35"' "$ROOT/scripts/common.sh" || die "Pinned native dependency API 35 is missing."
grep -Fq -- 'TARGET_TRIPLE="aarch64-linux-android${ANDROID_NATIVE_API}"' "$ROOT/scripts/build-native-dependencies.sh" || die "NDK compiler target must use the pinned native dependency API."
grep -Fq -- '--target="$TARGET_TRIPLE"' "$ROOT/scripts/build-native-dependencies.sh" || die "NDK compiler wrapper must pass the Android target triple."
grep -Fq -- '-DANDROID_PLATFORM="android-${ANDROID_NATIVE_API}"' "$ROOT/scripts/build-native-dependencies.sh" || die "CMake dependency builds must use the pinned native API."
for openssl_option in no-shared no-module no-dso no-legacy no-apps no-tests; do
  grep -Fq -- "$openssl_option" "$ROOT/scripts/build-native-dependencies.sh" || die "OpenSSL minimization option is missing: $openssl_option"
done
grep -Fq -- 'STUBS/openssl/ssl.h' "$ROOT/scripts/run-source-syntax-checks.sh" || \
  die "Host syntax checker is missing its OpenSSL API stubs."
grep -Fq -- "Path('third_party/src')" "$ROOT/build-logic/source_quality_check.py" || \
  die "Source-quality audit must exclude dynamically vendored upstream source trees."
grep -Fq -- "Path('third_party/build')" "$ROOT/build-logic/source_quality_check.py" || \
  die "Source-quality audit must exclude generated third-party build trees."
grep -Fq -- "Path('third_party/install')" "$ROOT/build-logic/source_quality_check.py" || \
  die "Source-quality audit must exclude generated third-party install trees."
if grep -Fq -- 'variantBuilder.enableUnitTest' "$ROOT/app/build.gradle.kts"; then
  die "Removed AGP 9 VariantBuilder.enableUnitTest API is still referenced."
fi
grep -Fq -- ':app:testDebugUnitTest' "$ROOT/scripts/build-linux.sh" || \
  die "Release builder does not invoke the exact module-scoped debug unit-test task."
grep -Fq -- ':app:assembleDebugAndroidTest' "$ROOT/scripts/build-linux.sh" || \
  die "Release builder does not compile the Compose instrumentation test APK."
if grep -Fq -- ':app:testReleaseUnitTest' "$ROOT/scripts/build-linux.sh"; then
  die "Release builder still invokes the AGP 9 task that is absent by default."
fi
if grep -Fq -- '-D__ANDROID_API__=' "$ROOT/scripts/build-native-dependencies.sh"; then
  die "OpenSSL build redundantly overrides the API macro already supplied by the NDK target triple."
fi
grep -Fq -- 'patch_randomx_aarch64_branch.py' "$ROOT/scripts/vendor-dependencies.sh" ||   die "RandomX AArch64 branch-locality patch is not applied during deterministic vendoring."
grep -Fq -- 'R_AARCH64_CONDBR19.*randomx_program_aarch64_main_loop' "$ROOT/scripts/build-native-dependencies.sh" ||   die "RandomX archive relocation verification is missing."
grep -Fq -- '-DCRYPTOSTEER_ENABLE_LTO=OFF' "$ROOT/app/build.gradle.kts" ||   die "Android JNI ThinLTO must remain disabled for reliable RandomX AArch64 shared linking."
grep -Fq -- '--exclude-libs,ALL' "$ROOT/native/CMakeLists.txt" ||   die "Static dependency symbols are not hidden from the JNI shared-library export surface."
grep -Fq -- 'target_link_options(cryptosteer_miner PRIVATE -fno-lto)' "$ROOT/native/CMakeLists.txt" ||   die "JNI shared-library link does not explicitly override ThinLTO."
if grep -Fq -- 'Icons.Default.List' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt"; then
  die "Deprecated non-mirrored List icon remains in the Compose UI."
fi

if grep -RInF -- 'import androidx.compose.foundation.layout.weight' \
  "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui" >/dev/null; then
  die "Compose scope-only Modifier.weight must not be statically imported with the 2026 Compose foundation API."
fi
grep -Fq -- 'import androidx.compose.foundation.gestures.detectTapGestures' \
  "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt" || \
  die "The chart tap detector is missing its public Compose gestures import."
if grep -Fq -- 'androidx.compose.foundation.gestures.detectTapGestures {' \
  "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt"; then
  die "The chart tap detector must be invoked through the imported PointerInputScope extension."
fi
grep -Fq -- 'import androidx.navigation.NavGraph.Companion.findStartDestination' \
  "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" || \
  die "Navigation start-destination lookup is missing the NavGraph companion extension import."
if grep -Fq -- 'import androidx.navigation.findStartDestination' \
  "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt"; then
  die "Obsolete top-level findStartDestination import remains."
fi
if grep -RInF -- 'import androidx.compose.ui.test.assertExists' \
  "$ROOT/app/src/androidTest" >/dev/null; then
  die "Compose assertExists is a SemanticsNodeInteraction member and must not be imported as a top-level extension."
fi
grep -Fq -- '.assertExists()' \
  "$ROOT/app/src/androidTest/java/com/thecryptosteer/xmrminer/ui/CryptoSteerUiSmokeTest.kt" || \
  die "Compose smoke tests no longer assert that their target nodes exist."
grep -Fq -- 'AttachCurrentThread(&env_, nullptr)' "$ROOT/native/src/jni_bridge.cpp" ||   die "Android JNI thread attachment does not use the NDK JNIEnv** signature."
grep -Fq -- 'val chartLineColor = CryptoSteerThemeTokens.colors.healthy'   "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt" ||   die "Monitoring chart line color is not captured before entering Canvas DrawScope."
if grep -Fq -- 'drawPath(path, color = MaterialTheme.colorScheme'   "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt"; then
  die "Composable MaterialTheme access remains inside Canvas DrawScope."
fi

grep -Fq -- 'return ready == 0 ? -1 : (ready < 0 ? 0 : 1);' "$ROOT/native/include/cryptosteer/transport.hpp" ||   die "Socket timeout and peer-close outcomes are not separated; this would cause reconnect loops."
grep -Fq -- 'constexpr auto kStatsInterval = std::chrono::milliseconds(200);' "$ROOT/native/src/miner_engine.cpp" ||   die "Native live statistics are not configured for five updates per second."
grep -Fq -- 'NavigationBar(containerColor = CryptoSteerThemeTokens.colors.navigation, tonalElevation = 0.dp)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" ||   die "Flat monitoring-console bottom navigation is missing."
grep -Fq -- 'DEVICE("device", "Device")' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" ||   die "Primary Device tab route is missing."
grep -Fq -- 'DeviceScreen(' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" ||   die "Device tab is not connected to navigation."
grep -Fq -- 'Cpus_allowed_list:' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/device/DeviceInfoProvider.kt" ||   die "Device tab does not inspect the Android process CPU mask."
grep -Fq -- 'getThermalHeadroom(0)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/device/DeviceInfoProvider.kt" ||   die "Device tab thermal-headroom collection is missing."
if grep -Fq -- 'FEATURE_SUSTAINED_PERFORMANCE_MODE' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/device/DeviceInfoProvider.kt"; then
  die "Device collector still references the nonexistent PackageManager sustained-performance feature constant."
fi
grep -Fq -- 'powerManager.isSustainedPerformanceModeSupported()' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/device/DeviceInfoProvider.kt" ||   die "Device collector is not using the supported PowerManager sustained-performance API."
grep -Fq -- 'recommendedCpus' "$ROOT/native/src/benchmark.cpp" ||   die "Native CPU recommendation diagnostics are missing."
grep -Fq -- 'NavigationRail(' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" ||   die "Adaptive navigation rail is missing."
grep -Fq -- 'AppPersistentNavigation' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" ||   die "Expanded persistent navigation is missing."
if grep -RInE -- 'MotionReactiveProvider|Sensor.TYPE_ACCELEROMETER|chromePanel|radialGradient|linearGradient'   "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer" >/dev/null; then
  die "Motion-reactive, shimmer, or gradient UI code remains after the monitoring-console redesign."
fi
grep -Fq -- 'fun MonitoringBackdrop' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/MonitoringStyle.kt" ||   die "Flat monitoring backdrop is missing."
grep -Fq -- 'fun Modifier.monitoringPanel' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/MonitoringStyle.kt" ||   die "Reusable crisp monitoring panel modifier is missing."
grep -Fq -- 'val BrandOrange = Color(0xFFFF9830)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Theme.kt" ||   die "Monitoring-console orange accent is missing."
grep -Fq -- 'val HealthyGreen = Color(0xFF73BF69)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Theme.kt" ||   die "Monitoring-console health green is missing."
grep -Fq -- 'fun SemiCircleGauge' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt" ||   die "Dashboard semicircle gauge component is missing."
grep -Fq -- 'DashboardGaugeRow(snapshot)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/DashboardScreen.kt" ||   die "Dashboard monitoring gauges are not connected."
if grep -RInE -- '0xFF(A98BFF|C1B3F6|6D52BC|7060BA)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui" >/dev/null; then
  die "Purple synthetic-looking palette values remain in the monitoring-console UI."
fi
grep -Fq -- 'random_donation_worker()' "$ROOT/native/src/miner_engine.cpp" ||   die "Per-donation random worker naming is missing."
grep -Fq -- 'buildAutoTuneConfig' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/data/MiningRepository.kt" ||   die "Live pool auto-tune configuration path is missing."
grep -Fq -- 'NativeMiner.cancelAutoTune()' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/MainViewModel.kt" ||   die "Live auto-tune cancellation path is missing."


grep -Fq -- 'versionName = "2.2.1"' "$ROOT/app/build.gradle.kts" ||   die "Release version is not 2.2.1."
grep -Fq -- 'PROJECT_VERSION="2.2.1"' "$ROOT/scripts/common.sh" ||   die "Release scripts are not aligned to version 2.2.1."

grep -Fq -- 'create_source_archive.py' "$ROOT/scripts/create-source-zip.sh" || \
  die "Deterministic source archiver is not routed through the reviewed archive-policy module."
grep -Fq -- 'test_source_archive_policy.py' "$ROOT/scripts/run-all-checks.sh" || \
  die "Source archive policy regression tests are not part of the release checks."
if grep -Fq -- 'third_party/src/' "$ROOT/.gitignore"; then
  die "Complete pinned native source must remain trackable in public release tags."
fi
grep -Fq -- '.cryptosteer-upstream-commit' "$ROOT/scripts/verify-dependencies.sh" || \
  die "Dependency verification cannot validate VCS-free vendored source identities."

# 2.2.1 distribution and live-chart invariants.
grep -Fq -- 'LIVE("Live", 60_000L)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/UiLogic.kt" || \
  die "The live hashrate range is missing."
grep -Fq -- 'fun chartWindow(' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/UiLogic.kt" || \
  die "The time-windowed hashrate chart logic is missing."
grep -Fq -- 'val oldest = older.first()' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/UiLogic.kt" || \
  die "Hashrate history compaction no longer preserves the session-start sample."
grep -Fq -- 'repeatedHistoryCompactionKeepsSessionBoundaryAndNewestSamples' "$ROOT/app/src/test/java/com/thecryptosteer/xmrminer/ui/UiLogicTest.kt" || \
  die "Repeated hashrate-history compaction regression coverage is missing."
grep -Fq -- 'BuildConfig.INSTALLATION_TELEMETRY_ENABLED' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/CryptoSteerApplication.kt" || \
  die "Distribution-gated installation telemetry is not wired into application startup."
grep -Fq -- '.put("accountLinked", accountLinked)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/telemetry/InstallationTelemetryManager.kt" || \
  die "Installation account-link state is missing from telemetry."
grep -Fq -- 'CURRENT_DISCLOSURE_VERSION = 2' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/data/AppPreferences.kt" || \
  die "Existing users are not required to review the new telemetry disclosure."
if grep -Fq -- 'DefinitionRow("Donation endpoint"' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Screens.kt" || \
   grep -Fq -- 'DefinitionRow("Donation transport"' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Screens.kt"; then
  die "A user-facing donation endpoint address remains in the UI."
fi
grep -Fq -- 'THE CRYPTO STEER LLC • Advanced Android XMR Miner' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Screens.kt" || \
  die "The final company branding is missing."
grep -Fq -- 'project(cryptosteer_miner VERSION 2.2.1 LANGUAGES C CXX)' "$ROOT/native/CMakeLists.txt" ||   die "Native CMake project version is not aligned to 2.2.1."
grep -Fq -- 'CRYPTOSTEER_VERSION="2.2.1"' "$ROOT/native/CMakeLists.txt" ||   die "Native runtime version is not aligned to 2.2.1."
grep -Fq -- 'checkNow(showBlockingIndicator = true)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/update/UpdateManager.kt" || die "Blocking startup update verification is missing."
grep -Fq -- 'KEY_LAST_VERIFIED_MANIFEST' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/update/UpdateManager.kt" || die "Verified mandatory manifest caching is missing."
grep -Fq -- 'delay(60_000L)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/MainActivity.kt" || die "Foreground update polling is missing."
grep -Fq -- 'updateState.error.isNotBlank()' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt" || die "Update verification failures are hidden from the user."
grep -Fq -- 'create("fdroid")' "$ROOT/app/build.gradle.kts" || die "F-Droid build type is missing."
grep -Fq -- 'SELF_UPDATE_ENABLED", "false"' "$ROOT/app/build.gradle.kts" || die "F-Droid self-update disable flag is missing."
grep -Fq -- 'INSTALLATION_TELEMETRY_ENABLED", "false"' "$ROOT/app/build.gradle.kts" || die "F-Droid anonymous telemetry disable flag is missing."
grep -Fq -- 'tools:node="remove"' "$ROOT/app/src/fdroid/AndroidManifest.xml" || die "F-Droid package-installer permission removal is missing."
[[ -s "$ROOT/branding/cryptosteer-xmr-miner-logo.png" ]] || die "Final brand artwork is missing."
[[ -s "$ROOT/branding/source/cryptosteer-xmr-miner-logo-original.png" ]] || die "Exact supplied master logo is missing from the source archive."
(cd "$ROOT/branding" && sha256sum -c ORIGINAL_LOGO_SHA256.txt) || die "Exact supplied master logo checksum failed."
[[ -s "$ROOT/app/src/main/res/drawable/ic_stat_cryptosteer.xml" ]] || die "Dedicated notification icon resource is missing."
grep -Fq -- '.setSmallIcon(R.drawable.ic_stat_cryptosteer)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/service/MiningForegroundService.java" ||   die "Foreground service does not use the dedicated notification icon."
if grep -RInF -- 'R.drawable.ic_launcher_foreground' "$ROOT/app/src/main" >/dev/null; then
  die "Removed launcher foreground resource is still referenced as a drawable."
fi
[[ -s "$ROOT/fastlane/metadata/android/en-US/images/icon.png" ]] || die "F-Droid icon is missing."
grep -Fq -- 'Apache License' "$ROOT/LICENSE" || die "Apache-2.0 license text is missing."
(( $(wc -c < "$ROOT/LICENSE") > 10000 )) || die "LICENSE must contain the complete Apache-2.0 text."
[[ -s "$ROOT/LICENSES/CC-BY-SA-4.0.txt" ]] || die "CC-BY-SA-4.0 artwork license text is missing."
grep -Fq -- 'android:name=".update.UpdateInstallReceiver"' "$ROOT/app/src/fdroid/AndroidManifest.xml" || die "F-Droid update receiver removal is missing."
grep -Fq -- 'tools:node="remove"' "$ROOT/app/src/fdroid/AndroidManifest.xml" || die "F-Droid manifest removals are missing."
if grep -RInF --exclude='run-all-checks.sh' -- 'keytool -genkeypair' "$ROOT/scripts" >/dev/null; then
  die "Public build scripts must not create a release signing identity."
fi
grep -Fq -- 'CRYPTOSTEER_SIGNING_PROPERTIES' "$ROOT/app/build.gradle.kts" || \
  die "Optional local signing configuration is missing."
grep -Fq -- 'signing.properties.example' "$ROOT/README.md" || \
  die "Public signing instructions are missing."
grep -Fq -- 'buildConfigField("boolean", "DONATION_POOL_TLS", "false")' "$ROOT/app/build.gradle.kts" ||   die "Donation endpoint must remain non-TLS TCP as disclosed."
grep -Fq -- 'private fun toneColor' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt" ||   die "Semantic status-color mapping is missing."
grep -Fq -- 'fun LiveHashrateChart' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt" ||   die "Interactive bounded hashrate chart is missing."
grep -Fq -- 'fun DiagnosticsScreen' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Screens.kt" ||   die "Structured diagnostics screen is missing."
grep -Fq -- 'fun LogsScreen' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Screens.kt" ||   die "Compact filterable logs screen is missing."
if grep -Fq -- 'Icons.AutoMirrored.Outlined.HelpOutline' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt"; then
  die "Nonexistent AutoMirrored HelpOutline icon reference remains."
fi

if grep -RInF -- 'StatusTone.ERROR' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui" >/dev/null; then
  die "Undefined StatusTone.ERROR remains in the Compose UI; use the existing CRITICAL tone for errors."
fi
grep -Fq -- 'icon = Icons.Outlined.DevicesOther' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/ControlScreen.kt" || \
  die "Control empty-state icon is not passed through the named icon parameter."
grep -Fq -- 'private fun formatHashrate(hashrate: Double): String {' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/ControlScreen.kt" || \
  die "Control rig hashrate formatter is missing."
grep -Fq -- 'hashrate.takeIf { it.isFinite() && it > 0.0 } ?: 0.0' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/ui/ControlScreen.kt" || \
  die "Control rig hashrate formatter does not sanitize non-finite or negative telemetry."

if grep -Fq -- 'all(Char::isHexDigit)' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/data/PoolConnectionTester.kt"; then
  die "Pool job validation still uses the prohibited member-extension callable reference."
fi
grep -Fq -- 'blob.all { isAsciiHexDigit(it) }' "$ROOT/app/src/main/java/com/thecryptosteer/xmrminer/data/PoolConnectionTester.kt" ||   die "Pool job hex validation regression guard is missing."
if grep -Fq -- 'ordered_cpus.begin() + std::min<std::size_t>' "$ROOT/native/src/benchmark.cpp"; then
  die "Auto-tune CPU selection still relies on a signed iterator offset conversion."
fi
grep -Fq -- 'worker = "Pixel10-Max"' "$ROOT/app/src/test/java/com/thecryptosteer/xmrminer/domain/PoolValidatorTest.kt" ||   die "The valid pool-profile unit test does not provide the now-required worker name."
if grep -Fq -- 'android:extractNativeLibs=' "$ROOT/app/src/main/AndroidManifest.xml"; then
  die "android:extractNativeLibs must be controlled by AGP packaging, not the source manifest."
fi

gradle_ready=false
if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" && -d "$ROOT/third_party/install/android-arm64" ]]; then gradle_ready=true; fi
if [[ "$gradle_ready" == true ]]; then
  log "Running Android lint, unit tests, formatting, and release compile"
  "$ROOT/gradlew" --no-daemon --no-configuration-cache --stacktrace \
    :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintRelease :app:assembleRelease
else
  log "Android SDK/NDK or native prefix is unavailable; Android compilation is deferred to scripts/build-linux.sh."
fi
log "All checks available in this environment passed."
