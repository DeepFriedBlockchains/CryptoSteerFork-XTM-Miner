#!/usr/bin/env python3
"""Deterministic Android/Kotlin/Java source-quality and security invariant checks.

This complements Android Lint and compiler warnings without relying on a Gradle
plugin that is not compatible with AGP 9 built-in Kotlin.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def balanced_source(path: Path, text: str) -> None:
    pairs = {')': '(', ']': '[', '}': '{'}
    stack: list[tuple[str, int]] = []
    i = 0
    line = 1
    state = 'code'
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ''
        if c == '\n':
            line += 1
        if state == 'code':
            if c == '/' and n == '/':
                state = 'line_comment'; i += 2; continue
            if c == '/' and n == '*':
                state = 'block_comment'; i += 2; continue
            if c == '"':
                if text[i:i + 3] == '"""':
                    state = 'triple_string'; i += 3; continue
                state = 'string'; i += 1; continue
            if c == "'":
                state = 'char'; i += 1; continue
            if c in '([{':
                stack.append((c, line))
            elif c in ')]}':
                if not stack or stack[-1][0] != pairs[c]:
                    fail(f'{path.relative_to(ROOT)}:{line}: unmatched {c}')
                    return
                stack.pop()
        elif state == 'line_comment':
            if c == '\n': state = 'code'
        elif state == 'block_comment':
            if c == '*' and n == '/': state = 'code'; i += 2; continue
        elif state == 'string':
            if c == '\\': i += 2; continue
            if c == '"': state = 'code'
        elif state == 'triple_string':
            if text[i:i + 3] == '"""': state = 'code'; i += 3; continue
        elif state == 'char':
            if c == '\\': i += 2; continue
            if c == "'": state = 'code'
        i += 1
    if state in {'string', 'triple_string', 'char', 'block_comment'}:
        fail(f'{path.relative_to(ROOT)}: unterminated {state.replace("_", " ")}')
    if stack:
        opening, opening_line = stack[-1]
        fail(f'{path.relative_to(ROOT)}:{opening_line}: unmatched {opening}')


GENERATED_PART_NAMES = {
    '.git', '.gradle', '.idea', 'build', '.cxx', '.host-test-build', '__pycache__',
}
# These directories are created only after the immutable upstream sources are
# cloned and cross-compiled. Upstream test fixtures and Autotools dependency
# metadata legitimately contain empty files, so first-party repository rules
# must never be applied to these generated/vendor-owned trees.
VENDORED_OR_GENERATED_PREFIXES = (
    Path('third_party/src'),
    Path('third_party/build'),
    Path('third_party/install'),
)


def is_first_party_repository_file(path: Path) -> bool:
    relative = path.relative_to(ROOT)
    if any(part in GENERATED_PART_NAMES for part in relative.parts):
        return False
    return not any(relative == prefix or prefix in relative.parents for prefix in VENDORED_OR_GENERATED_PREFIXES)


all_repository_files = [
    path for path in ROOT.rglob('*')
    if path.is_file() and is_first_party_repository_file(path)
]
for path in all_repository_files:
    if path.stat().st_size == 0:
        fail(f'{path.relative_to(ROOT)} is empty')

source_files = sorted((ROOT / 'app/src').rglob('*.kt')) + sorted((ROOT / 'app/src').rglob('*.java'))
if not source_files:
    fail('No Android source files found.')
for path in source_files:
    text = path.read_text(encoding='utf-8')
    if not text.strip(): fail(f'{path.relative_to(ROOT)} is empty')
    if '\t' in text: fail(f'{path.relative_to(ROOT)} contains tab indentation')
    for number, line in enumerate(text.splitlines(), 1):
        if line.rstrip() != line: fail(f'{path.relative_to(ROOT)}:{number}: trailing whitespace')
    if re.search(r'\b(?:TODO|FIXME|PLACEHOLDER)\b', text, re.IGNORECASE):
        fail(f'{path.relative_to(ROOT)} contains unfinished-source marker')
    if re.search(r'import\s+[^\n]+\.\*', text):
        fail(f'{path.relative_to(ROOT)} contains wildcard import')
    if 'import androidx.compose.ui.test.assertExists' in text:
        fail(f'{path.relative_to(ROOT)} imports assertExists even though it is a SemanticsNodeInteraction member')
    balanced_source(path, text)

    # Extended Material icon properties are generated extension properties. A
    # reference can parse correctly yet fail the Android compile when its exact
    # extension import is missing. Keep this deterministic check close to the
    # source scan so regressions are caught before a remote Gradle build.
    regular_icon_names = set(re.findall(r'Icons\.Outlined\.([A-Za-z0-9_]+)', text))
    mirrored_icon_names = set(re.findall(r'Icons\.AutoMirrored\.Outlined\.([A-Za-z0-9_]+)', text))
    regular_imports = set(re.findall(r'import\s+androidx\.compose\.material\.icons\.outlined\.([A-Za-z0-9_]+)', text))
    mirrored_imports = set(re.findall(r'import\s+androidx\.compose\.material\.icons\.automirrored\.outlined\.([A-Za-z0-9_]+)', text))
    for icon_name in sorted(regular_icon_names - regular_imports):
        fail(f'{path.relative_to(ROOT)} references Icons.Outlined.{icon_name} without its exact import')
    for icon_name in sorted(mirrored_icon_names - mirrored_imports):
        fail(f'{path.relative_to(ROOT)} references Icons.AutoMirrored.Outlined.{icon_name} without its exact import')


gradle_properties = (ROOT / 'gradle.properties').read_text()
if 'android.builtInKotlin=false' in gradle_properties:
    fail('AGP 9 built-in Kotlin is disabled, which would skip Android Kotlin compilation without the legacy plugin.')
if 'org.jetbrains.kotlin.android' in (ROOT / 'build.gradle.kts').read_text() or 'org.jetbrains.kotlin.android' in (ROOT / 'app/build.gradle.kts').read_text():
    fail('Legacy kotlin-android plugin is incompatible with the AGP 9 built-in Kotlin configuration.')
if 'org.jetbrains.kotlin.plugin.compose' not in (ROOT / 'app/build.gradle.kts').read_text():
    fail('Compose compiler plugin is missing from the Android application module.')

manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
permissions = {node.attrib.get(android + 'name', '') for node in manifest.findall('uses-permission')}
allowed = {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.POST_NOTIFICATIONS', 'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_SPECIAL_USE', 'android.permission.WAKE_LOCK',
    'android.permission.USE_BIOMETRIC', 'android.permission.REQUEST_INSTALL_PACKAGES',
}
extra = permissions - allowed
if extra: fail(f'Unexpected Android permissions: {sorted(extra)}')
for component in manifest.findall('.//receiver') + manifest.findall('.//service'):
    if component.attrib.get(android + 'exported') == 'true':
        fail(f'Non-activity component is exported: {component.attrib.get(android + "name")}')
if any('BOOT_COMPLETED' in (node.attrib.get(android + 'name') or '') for node in manifest.iter()):
    fail('A boot receiver/action is present despite explicit user-start-only behavior.')

service = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/service/MiningForegroundService.java').read_text()
for literal in (
    'registerDefaultNetworkCallback', 'ACTION_SCREEN_ON', 'getCurrentThermalStatus',
    'getResumeThermalStatus', 'constraintsBlocked()', 'THERMAL_STATUS_EMERGENCY',
    'WAKE_LOCK_TIMEOUT_MILLIS', 'terminalState', 'terminateSession(MiningState.ERROR',
    'Resume deferred because a selected device constraint is still active',
):
    if literal not in service: fail(f'Foreground-service safety invariant missing: {literal}')

pool_tester = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/data/PoolConnectionTester.kt').read_text()
for literal in ('JSONArray().put("rx/0")', 'createSocket(raw, verifyHost', 'endpointIdentificationAlgorithm', 'isIpLiteral(verifyHost)'):
    if literal not in pool_tester: fail(f'Pool TLS/algorithm invariant missing: {literal}')

models = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/domain/Models.kt').read_text()
if 'restartAfterReboot' in models or 'notificationUpdatesSeconds' in models:
    fail('Dead Android settings remain in the public settings model.')
state_bus = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/data/MiningStateBus.kt').read_text()
if 'val hashes: Long = 0' not in models or 'o.optLong("hashes"' not in state_bus:
    fail('Exact native hash counter is not propagated into Android state.')

components = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/ui/Components.kt').read_text()
for literal in (
    'val chartLineColor = CryptoSteerThemeTokens.colors.healthy',
    'val chartAxisColor = MaterialTheme.colorScheme.outlineVariant',
    'drawPath(linePath, color = chartLineColor',
    'drawLine(chartAxisColor',
):
    if literal not in components:
        fail(f'Compose DrawScope color-capture invariant missing: {literal}')
if 'drawPath(path, color = MaterialTheme.colorScheme' in components or 'drawLine(MaterialTheme.colorScheme' in components:
    fail('Composable MaterialTheme access remains inside the non-composable Canvas DrawScope.')

jni_source = (ROOT / 'native/src/jni_bridge.cpp').read_text()
for literal in (
    '#if defined(__ANDROID__)',
    'AttachCurrentThread(&env_, nullptr)',
    'AttachCurrentThread(reinterpret_cast<void**>(&env_), nullptr)',
    'attach_status != JNI_OK || env_ == nullptr',
):
    if literal not in jni_source:
        fail(f'Portable JNI thread-attachment invariant missing: {literal}')

affinity_source = (ROOT / 'native/src/platform/affinity.c').read_text()
for literal in ('sched_getaffinity', 'cryptosteer_allowed_cpu_indices'):
    if literal not in affinity_source: fail(f'Android cpuset-awareness invariant missing: {literal}')

transport_source = (ROOT / 'native/src/transport.cpp').read_text()
for literal in ('X509_VERIFY_PARAM_set1_ip_asc', 'SSL_set1_host', 'SSL_set_tlsext_host_name'):
    if literal not in transport_source: fail(f'TLS peer-name verification invariant missing: {literal}')

hex_source = (ROOT / 'native/src/hex.cpp').read_text()
for literal in ('numeric_limits<std::uint32_t>::max()', 'hash.data() + 24U', 'pool target cannot be zero'):
    if literal not in hex_source: fail(f'Monero Stratum target-expansion invariant missing: {literal}')

native = (ROOT / 'native/src/miner_engine.cpp').read_text()
for literal in ('donation_retry_after', 'donation_stop_at', 'Donation pool connection failed; returning to user mining without increasing the fee:', 'runtime_.hashes.store(0', 'ScopedPasswordWipe', 'cryptosteer_secure_zero', 'wipe_string(config_.password)'):
    if literal not in native: fail(f'Native lifecycle/donation invariant missing: {literal}')

benchmark_source = (ROOT / 'native/src/benchmark.cpp').read_text()
for literal in (
    'class WorkerJoinGuard',
    'WorkerJoinGuard join_guard',
    'worker_failed.store(true',
    'throw std::runtime_error(worker_error',
):
    if literal not in benchmark_source:
        fail(f'Native benchmark worker-safety invariant missing: {literal}')

view_model = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/ui/MainViewModel.kt').read_text()
for literal in (
    'if (autoTuneJob?.isActive == true)',
    'if (benchmarkJob?.isActive == true)',
    'catch (exception: CancellationException)',
    'override fun onCleared()',
    'NativeMiner.cancelBenchmark()',
    'NativeMiner.cancelAutoTune()',
):
    if literal not in view_model:
        fail(f'ViewModel lifecycle/concurrency invariant missing: {literal}')

ui_logic = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/ui/UiLogic.kt').read_text()
for literal in ('fun decimatePoints(', 'fun formatDuration(', 'fun humanMiningSummary('):
    if literal not in ui_logic:
        fail(f'Pure UI-logic invariant missing: {literal}')

device_provider = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/device/DeviceInfoProvider.kt').read_text()
for literal in (
    'Cpus_allowed_list:', 'getThermalHeadroom(0)', 'ActivityManager.MemoryInfo()',
    'Build.SOC_MODEL', 'allowedCpuIds', 'recommendedCpuIds', 'deviceInfoReport(',
):
    if literal not in device_provider:
        fail(f'Device-capability collector invariant missing: {literal}')
for forbidden in ('Build.getSerial', 'TelephonyManager', 'AdvertisingId', 'ANDROID_ID', 'WifiInfo', 'BluetoothAdapter'):
    if forbidden in device_provider:
        fail(f'Device tab reads a prohibited identifying surface: {forbidden}')

device_screen = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/ui/DeviceScreen.kt').read_text()
for literal in (
    'Live, non-identifying hardware', 'CPU topology', 'Memory and RandomX',
    'Native mining capabilities', 'Copy sanitized device report', 'specifications alone do not prove sustained hashrate',
):
    if literal not in device_screen:
        fail(f'Device-screen production invariant missing: {literal}')

app_navigation = (ROOT / 'app/src/main/java/com/thecryptosteer/xmrminer/ui/CryptoSteerApp.kt').read_text()
for literal in ('DEVICE("device", "Device")', 'Icons.Outlined.DeveloperBoard', 'DeviceScreen('):
    if literal not in app_navigation:
        fail(f'Device-tab navigation invariant missing: {literal}')


randomx_source = (ROOT / 'native/src/randomx_context.cpp').read_text()
for literal in ('randomx_create_vm(context->flags_', 'without_flag(without_flag(context->flags_, RANDOMX_FLAG_JIT)', 'portable modes'):
    if literal not in randomx_source: fail(f'RandomX JIT fallback invariant missing: {literal}')

stratum_source = (ROOT / 'native/src/stratum_client.cpp').read_text()
for literal in ('kMaximumPendingSubmissions', 'pool share-response queue is full', 'pending_submissions_.erase(id)'):
    if literal not in stratum_source: fail(f'Stratum queue-bound invariant missing: {literal}')

if errors:
    print('Android source quality checks: FAIL', file=sys.stderr)
    for error in errors: print(f' - {error}', file=sys.stderr)
    raise SystemExit(1)
print(f'Android source quality checks: PASS ({len(source_files)} Kotlin/Java files)')
