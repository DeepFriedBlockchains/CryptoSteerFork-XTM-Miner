import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingFilePath = providers.environmentVariable("CRYPTOSTEER_SIGNING_PROPERTIES").orNull
val signingProps = Properties().apply {
    if (!signingFilePath.isNullOrBlank()) file(signingFilePath).inputStream().use(::load)
}
val controlApiBaseUrl = providers.environmentVariable("CRYPTOSTEER_API_BASE_URL").orElse("https://miner-api.thecryptosteer.com").get().trimEnd('/')
val releasePublicKeyBase64 = providers.environmentVariable("CRYPTOSTEER_RELEASE_PUBLIC_KEY_BASE64").orElse("").get().trim()

android {
    namespace = "com.thecryptosteer.xmrminer"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.thecryptosteer.xmrminer"
        minSdk = 36
        targetSdk = 37
        versionCode = 20201
        versionName = "2.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk.abiFilters += "arm64-v8a"
        buildConfigField("String", "DONATION_WALLET", "\"44VNzHZAE1HK3NQZRPeDkMQ8pjDzivtq2eAE4iZLuLaSLZiu3wmCswrCUEm7NnWGMUB4k6cK7p7S7EXfJU7FbVTUMczHdSE\"")
        buildConfigField("int", "DONATION_BASIS_POINTS", "200")
        buildConfigField("String", "DONATION_POOL_HOST", "\"gulf.moneroocean.stream\"")
        buildConfigField("int", "DONATION_POOL_PORT", "10128")
        buildConfigField("boolean", "DONATION_POOL_TLS", "false")
        buildConfigField("String", "DONATION_WORKER_PREFIX", "\"CryptoSteer\"")
        buildConfigField("int", "REDUCED_DONATION_BASIS_POINTS", "100")
        buildConfigField("long", "DONATION_REDUCTION_THRESHOLD_NANOS", "2592000000000000L")
        buildConfigField("String", "CONTROL_API_BASE_URL", "\"$controlApiBaseUrl\"")
        buildConfigField("String", "RELEASE_PUBLIC_KEY_BASE64", "\"$releasePublicKeyBase64\"")
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        buildConfigField("boolean", "INSTALLATION_TELEMETRY_ENABLED", "true")
        buildConfigField("String", "DISTRIBUTION_LABEL", "\"Direct signed release\"")
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCRYPTOSTEER_THIRD_PARTY_PREFIX=${rootProject.projectDir}/third_party/install/android-arm64",
                    "-DCRYPTOSTEER_ENABLE_LTO=OFF",
                    "-DCRYPTOSTEER_ENABLE_TEST_HOOKS=OFF",
                )
            }
        }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(requireNotNull(signingProps.getProperty("storeFile")))
                storePassword = requireNotNull(signingProps.getProperty("storePassword"))
                keyAlias = requireNotNull(signingProps.getProperty("keyAlias"))
                keyPassword = requireNotNull(signingProps.getProperty("keyPassword"))
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isJniDebuggable = true
            externalNativeBuild.cmake.arguments += "-DCRYPTOSTEER_SANITIZERS=OFF"
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = true
            isMinifyEnabled = false
            externalNativeBuild.cmake.arguments += "-DCRYPTOSTEER_BENCHMARK_BUILD=ON"
        }
        release {
            ndk.debugSymbolLevel = "FULL"
            isMinifyEnabled = true
            isShrinkResources = true
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        create("fdroid") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = null
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            buildConfigField("boolean", "INSTALLATION_TELEMETRY_ENABLED", "false")
            buildConfigField("String", "DISTRIBUTION_LABEL", "\"F-Droid\"")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        disable += setOf("ObsoleteSdkInt")
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}


// AGP 9 creates the default local unit-test component for the tested build type
// (debug in this application). The release pipeline runs those JVM tests and
// separately compiles, lints, shrinks, signs, and verifies the release variant.

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("androidx.biometric:biometric:1.4.0-alpha04")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
