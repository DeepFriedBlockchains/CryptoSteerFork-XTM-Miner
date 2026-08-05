#!/usr/bin/env python3
"""Verify that immutable release constants match every build surface."""
from __future__ import annotations
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / "DEPENDENCY_LOCK.json").read_text())
errors: list[str] = []

def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

def contains(path: str, literal: str) -> None:
    text = (ROOT / path).read_text()
    require(literal in text, f"{path} does not contain locked value: {literal}")

application = lock["application"]
android = lock["android"]
build = lock["build"]
donation = lock["donation"]
app = (ROOT / "app/build.gradle.kts").read_text()
root_build = (ROOT / "build.gradle.kts").read_text()
wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()

contains("app/build.gradle.kts", f'applicationId = "{application["applicationId"]}"')
contains("app/build.gradle.kts", f'versionCode = {application["versionCode"]}')
contains("app/build.gradle.kts", f'versionName = "{application["versionName"]}"')
contains("scripts/common.sh", f'PROJECT_VERSION="{application["versionName"]}"')
contains("native/CMakeLists.txt", f'project(cryptosteer_miner VERSION {application["versionName"]} LANGUAGES C CXX)')
contains("native/CMakeLists.txt", f'CRYPTOSTEER_VERSION="{application["versionName"]}"')
sbom = json.loads((ROOT / "SBOM.json").read_text())
sbom_component = sbom.get("metadata", {}).get("component", {})
require(sbom_component.get("version") == application["versionName"], "SBOM application version does not match dependency lock")
require(sbom_component.get("purl") == f'pkg:apk/{application["applicationId"]}@{application["versionName"]}', "SBOM application purl does not match dependency lock")

contains("app/build.gradle.kts", f'compileSdk = {android["compileSdk"]}')
contains("app/build.gradle.kts", f'minSdk = {android["minSdk"]}')
contains("app/build.gradle.kts", f'targetSdk = {android["targetSdk"]}')
contains("app/build.gradle.kts", f'ndkVersion = "{android["ndk"]}"')
contains("app/build.gradle.kts", f'buildToolsVersion = "{android["buildTools"]}"')
contains("app/build.gradle.kts", f'version = "{android["cmake"]}"')
contains("build.gradle.kts", f'id("com.android.application") version "{build["androidGradlePlugin"]}"')
contains("build.gradle.kts", f'id("org.jetbrains.kotlin.plugin.compose") version "{build["kotlin"]}"')
contains("gradle/wrapper/gradle-wrapper.properties", f'gradle-{build["gradle"]}-bin.zip')
contains("app/build.gradle.kts", f'implementation("com.squareup.okhttp3:okhttp:{build["okHttp"]}")')
contains("app/build.gradle.kts", f'implementation("androidx.biometric:biometric:{build["androidxBiometric"]}")')
contains("app/build.gradle.kts", f'implementation("androidx.fragment:fragment-ktx:{build["androidxFragment"]}")')
contains("app/build.gradle.kts", 'buildConfigField("String", "RELEASE_PUBLIC_KEY_BASE64"')

for field, gradle_name in [
    ("wallet", "DONATION_WALLET"),
    ("poolHost", "DONATION_POOL_HOST"),
    ("workerPrefix", "DONATION_WORKER_PREFIX"),
]:
    require(str(donation[field]) in app, f"app BuildConfig {gradle_name} does not match dependency lock")
require(f'buildConfigField("int", "DONATION_BASIS_POINTS", "{donation["basisPoints"]}")' in app, "initial donation basis points missing from app BuildConfig")
require(f'buildConfigField("int", "REDUCED_DONATION_BASIS_POINTS", "{donation["reducedBasisPoints"]}")' in app, "reduced donation basis points missing from app BuildConfig")
require(f'buildConfigField("long", "DONATION_REDUCTION_THRESHOLD_NANOS", "{donation["reductionThresholdActiveMiningNanos"]}L")' in app, "donation reduction threshold missing from app BuildConfig")
require(str(donation["poolPort"]) in app, "donation port missing from app BuildConfig")
require(f'buildConfigField("boolean", "DONATION_POOL_TLS", "{str(donation["tlsRequired"]).lower()}")' in app, "app BuildConfig donation transport does not match dependency lock")

native = (ROOT / "native/src/miner_engine.cpp").read_text()
for value in (donation["poolHost"], donation["workerPrefix"], donation["wallet"]):
    require(str(value) in native, f"native donation constant missing: {value}")
require(f'kDonationPort = {donation["poolPort"]}' in native, "native donation port mismatch")
require('donation_pool->bool_or("tls", true)' in native, "native donation transport validation is missing")
if donation["tlsRequired"]:
    require('!donation_pool->bool_or("tls", false)' in native, "native donation endpoint is not locked to TLS")
else:
    require('|| donation_pool->bool_or("tls", true)' in native, "native donation endpoint is not locked to TCP")
require(f'\\"donationTls\\":{str(donation["tlsRequired"]).lower()}' in native, "native status does not expose the locked donation transport")
require('\\"algo\\":[\\"rx/0\\"]' in (ROOT / "native/src/stratum_client.cpp").read_text(), "RandomX algorithm hint missing from Stratum login")

for source in lock["native"]:
    require(re.fullmatch(r"[0-9a-f]{40}", source["commit"]) is not None, f"{source['name']} commit is not immutable")
    require(not source["repository"].endswith("/master"), f"{source['name']} repository is a moving branch")

for file in ROOT.rglob("*.gradle.kts"):
    text = file.read_text()
    require("+\"" not in text and not re.search(r'version\s+["\'][^"\']*\+', text), f"dynamic dependency version found in {file.relative_to(ROOT)}")

required = [
    "README.md", "SECURITY.md", "PRIVACY.md", "THREAT_MODEL.md", "SBOM.json",
    "docs/BUILDING.md", "docs/FDROID.md",
    "native/src/miner_engine.cpp", "native/src/jni_bridge.cpp",
    "scripts/setup-linux-builder.sh", "scripts/build-linux.sh",
]
for relative in required:
    path = ROOT / relative
    require(path.is_file() and path.stat().st_size > 0, f"required non-empty file missing: {relative}")

if errors:
    print("Dependency/build lock verification: FAIL", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    raise SystemExit(1)
print("Dependency/build lock verification: PASS")
