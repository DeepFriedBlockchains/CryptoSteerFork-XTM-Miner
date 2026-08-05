package com.thecryptosteer.xmrminer.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.cloud.CloudApi
import com.thecryptosteer.xmrminer.cloud.ReleaseManifest
import com.thecryptosteer.xmrminer.cloud.UpdateUiState
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class UpdateManager(private val context: Context) {
    private val api = CloudApi(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("signed_update_state", Context.MODE_PRIVATE)
    private val checkInFlight = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    init {
        active.set(this)
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            checkNow(showBlockingIndicator = true)
        } else {
            mutableState.value = UpdateUiState(checking = false, blocked = false, available = false, enforcementEnabled = false, message = "Updates are provided by F-Droid.")
        }
    }

    fun checkNow(showBlockingIndicator: Boolean = false) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        if (!checkInFlight.compareAndSet(false, true)) return
        scope.launch {
            if (showBlockingIndicator) {
                mutableState.value = mutableState.value.copy(checking = true, error = "", message = "Checking the signed release channel…")
            }
            runCatching {
                val publicKey = api.releasePublicKey()
                verifyPinnedPublicKey(publicKey)
                val response = api.latestMinerRelease()
                val enforcement = response.optBoolean("updateEnforcementEnabled", true)
                if (!response.optBoolean("available", false)) {
                    return@runCatching UpdateUiState(
                        checking = false,
                        blocked = false,
                        available = false,
                        enforcementEnabled = enforcement,
                        message = "No published update is available.",
                    )
                }
                val manifestObject = response.getJSONObject("manifest")
                val manifest = parseManifest(manifestObject)
                verifyManifest(publicKey, manifest)
                require(manifest.platform == "android" && manifest.channel == "miner") { "The signed release targets a different platform or channel." }
                preferences.edit()
                    .putString(KEY_LAST_VERIFIED_MANIFEST, manifestObject.toString())
                    .putString(KEY_LAST_VERIFIED_PUBLIC_KEY, publicKey.trim())
                    .putBoolean(KEY_LAST_VERIFIED_ENFORCEMENT, enforcement)
                    .apply()
                val newer = manifest.versionCode > BuildConfig.VERSION_CODE.toLong()
                val blocked = newer && manifest.mandatory && enforcement
                UpdateUiState(
                    checking = false,
                    blocked = blocked,
                    available = newer,
                    enforcementEnabled = enforcement,
                    manifest = manifest,
                    message = when {
                        blocked -> "CryptoSteer ${manifest.versionName} is required before mining can continue."
                        newer -> "CryptoSteer ${manifest.versionName} is available."
                        else -> "CryptoSteer is current."
                    },
                )
            }.onSuccess { mutableState.value = it }
                .onFailure { failure ->
                    val cached = cachedRequiredState()
                    mutableState.value = if (cached != null) {
                        cached.copy(
                            checking = false,
                            error = "Live update verification failed; enforcing the last verified mandatory release: ${failure.message ?: failure.javaClass.simpleName}",
                        )
                    } else {
                        mutableState.value.copy(
                            checking = false,
                            blocked = false,
                            error = "Update verification failed: ${failure.message ?: failure.javaClass.simpleName}",
                            message = "No previously verified mandatory release is cached. Retry when the control service is reachable.",
                        )
                    }
                }
            checkInFlight.set(false)
        }
    }

    private fun cachedRequiredState(): UpdateUiState? {
        val rawManifest = preferences.getString(KEY_LAST_VERIFIED_MANIFEST, null) ?: return null
        val publicKey = preferences.getString(KEY_LAST_VERIFIED_PUBLIC_KEY, null) ?: return null
        return runCatching {
            val enforcement = preferences.getBoolean(KEY_LAST_VERIFIED_ENFORCEMENT, true)
            val manifest = parseManifest(JSONObject(rawManifest))
            verifyPinnedPublicKey(publicKey)
            verifyManifest(publicKey, manifest)
            val newer = manifest.versionCode > BuildConfig.VERSION_CODE.toLong()
            val blocked = newer && manifest.mandatory && enforcement
            UpdateUiState(
                checking = false,
                blocked = blocked,
                available = newer,
                enforcementEnabled = enforcement,
                manifest = manifest,
                message = if (blocked) {
                    "CryptoSteer ${manifest.versionName} is required before mining can continue."
                } else if (newer) {
                    "CryptoSteer ${manifest.versionName} is available."
                } else {
                    "CryptoSteer is current."
                },
            )
        }.getOrNull()
    }

    fun canInstallPackages(): Boolean = BuildConfig.SELF_UPDATE_ENABLED && context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun downloadAndInstall() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        val manifest = mutableState.value.manifest ?: return
        if (!mutableState.value.available) return
        scope.launch {
            runCatching {
                if (!canInstallPackages()) error("Allow CryptoSteer to install app updates, then press Update again.")
                val updateDir = File(context.cacheDir, "verified-updates").apply { mkdirs() }
                val apk = File(updateDir, "CryptoSteer-${manifest.versionCode}.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var total = manifest.fileSize.coerceAtLeast(1L)
                apk.outputStream().buffered().use { output ->
                    api.download(
                        manifest.downloadUrl,
                        onBytes = { bytes, count ->
                            output.write(bytes, 0, count)
                            digest.update(bytes, 0, count)
                            downloaded += count
                            mutableState.value = mutableState.value.copy(
                                progress = (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f),
                                message = "Downloading verified update…",
                                error = "",
                            )
                        },
                        total = { length -> if (length > 0L) total = length },
                    )
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                require(actualHash.equals(manifest.sha256, ignoreCase = true)) { "The downloaded APK hash does not match the signed manifest." }
                require(apk.length() == manifest.fileSize || manifest.fileSize <= 0L) { "The downloaded APK size does not match the signed manifest." }
                verifyArchive(apk, manifest)
                install(apk)
                mutableState.value = mutableState.value.copy(installing = true, progress = 1f, message = "Android is preparing the verified installer…")
            }.onFailure {
                mutableState.value = mutableState.value.copy(installing = false, error = it.message ?: "Update installation failed.", message = "Update not installed.")
            }
        }
    }

    private fun parseManifest(o: JSONObject): ReleaseManifest = ReleaseManifest(
        releaseId = o.getString("releaseId"),
        platform = o.getString("platform"),
        channel = o.getString("channel"),
        versionCode = o.getLong("versionCode"),
        versionName = o.getString("versionName"),
        sha256 = o.getString("sha256").lowercase(),
        fileSize = o.getLong("fileSize"),
        downloadUrl = o.getString("downloadUrl"),
        mandatory = o.optBoolean("mandatory", true),
        releaseNotes = o.optString("releaseNotes"),
        publishedAt = o.getString("publishedAt"),
        signature = o.getString("signature"),
        updateEnforcementEnabled = o.optBoolean("updateEnforcementEnabled", true),
    )

    private fun verifyPinnedPublicKey(publicKeyBase64: String) {
        val normalized = publicKeyBase64.trim()
        val compiled = BuildConfig.RELEASE_PUBLIC_KEY_BASE64.trim()
        if (compiled.isNotBlank()) {
            require(MessageDigest.isEqual(compiled.toByteArray(), normalized.toByteArray())) {
                "The server release key does not match the key compiled into this signed app."
            }
            return
        }
        val existing = preferences.getString(KEY_PINNED_PUBLIC_KEY, null)
        if (existing == null) {
            preferences.edit().putString(KEY_PINNED_PUBLIC_KEY, normalized).commit()
        } else {
            require(MessageDigest.isEqual(existing.toByteArray(), normalized.toByteArray())) {
                "The release signing key changed. Refusing updates until the app is upgraded through a trusted recovery release."
            }
        }
    }

    private fun verifyManifest(publicKeyBase64: String, manifest: ReleaseManifest) {
        require(manifest.versionCode > 0 && manifest.versionName.isNotBlank()) { "The release version is invalid." }
        require(manifest.sha256.matches(Regex("[0-9a-f]{64}"))) { "The release hash is invalid." }
        val uri = URI(manifest.downloadUrl)
        val controlUri = URI(BuildConfig.CONTROL_API_BASE_URL)
        require(uri.scheme.equals("https", true)) { "Release downloads must use HTTPS." }
        require(uri.host.equals(controlUri.host, ignoreCase = true) && uri.port == controlUri.port) {
            "The signed release download host does not match the configured control service."
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "The release download URL contains unsupported URL components."
        }
        require(uri.rawPath.startsWith("/v1/releases/download/")) { "The release download path is invalid." }
        val canonical = listOf(
            manifest.platform,
            manifest.channel,
            manifest.versionCode.toString(),
            manifest.versionName,
            manifest.sha256,
            uri.rawPath,
            manifest.mandatory.toString(),
            manifest.publishedAt,
        ).joinToString("|")
        val rawPublic = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        require(rawPublic.size == 32) { "The Ed25519 release key has an invalid length." }
        val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiPrefix + rawPublic))
        val verifier = Signature.getInstance("Ed25519").apply {
            initVerify(publicKey)
            update(canonical.toByteArray(Charsets.UTF_8))
        }
        require(verifier.verify(Base64.decode(manifest.signature, Base64.NO_WRAP))) { "The release manifest signature is invalid." }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, manifest: ReleaseManifest) {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android could not parse the downloaded APK.")
        require(info.packageName == BuildConfig.APPLICATION_ID) { "The APK belongs to ${info.packageName}, not CryptoSteer." }
        require(info.longVersionCode == manifest.versionCode) { "The APK version code does not match the signed manifest." }
        require(info.versionName == manifest.versionName) { "The APK version name does not match the signed manifest." }
        require(info.longVersionCode > BuildConfig.VERSION_CODE.toLong()) { "Downgrades and same-version replacements are not accepted by the in-app updater." }
        val archiveSigners = signingDigests(info.signingInfo)
        val installed = context.packageManager.getPackageInfo(
            BuildConfig.APPLICATION_ID,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
        val installedSigners = signingDigests(installed.signingInfo)
        require(archiveSigners.isNotEmpty() && archiveSigners.any(installedSigners::contains)) {
            "The APK is not signed by the installed CryptoSteer release identity."
        }
    }


    private fun signingDigests(info: android.content.pm.SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val signers = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signers.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(BuildConfig.APPLICATION_ID)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output, 256 * 1024)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun handleInstallStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmation)
                    mutableState.value = mutableState.value.copy(installing = true, message = "Confirm the signed CryptoSteer update in Android’s installer.", error = "")
                } else {
                    mutableState.value = mutableState.value.copy(installing = false, error = "Android did not provide an installation confirmation screen.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> mutableState.value = mutableState.value.copy(installing = false, blocked = false, message = "Update installed. Reopening CryptoSteer…", error = "")
            else -> mutableState.value = mutableState.value.copy(installing = false, error = "Android rejected the update${if (detail.isBlank()) "." else ": $detail"}", message = "Update not installed.")
        }
    }

    companion object {
        internal const val ACTION_INSTALL_RESULT = "com.thecryptosteer.xmrminer.UPDATE_INSTALL_RESULT"
        private const val KEY_PINNED_PUBLIC_KEY = "ed25519_public_key"
        private const val KEY_LAST_VERIFIED_MANIFEST = "last_verified_manifest"
        private const val KEY_LAST_VERIFIED_PUBLIC_KEY = "last_verified_public_key"
        private const val KEY_LAST_VERIFIED_ENFORCEMENT = "last_verified_enforcement"
        private val active = AtomicReference<UpdateManager?>()
        internal fun dispatch(intent: Intent) { active.get()?.handleInstallStatus(intent) }
    }
}
