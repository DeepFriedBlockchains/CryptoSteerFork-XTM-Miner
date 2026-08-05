package com.thecryptosteer.xmrminer.cloud

import android.content.Context
import com.thecryptosteer.xmrminer.BuildConfig
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CloudApi(private val context: Context) {
    private val baseUrl = BuildConfig.CONTROL_API_BASE_URL
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    sealed interface LoginResult {
        data class Authenticated(val session: CloudSession) : LoginResult
        data class ApprovalRequired(val pending: PendingDeviceLogin) : LoginResult
    }

    suspend fun register(wallet: String, password: String, identity: DeviceIdentity): CloudSession {
        val body = credentials(wallet, password, identity)
        return parseSession(execute(Request.Builder().url("$baseUrl/v1/auth/register").post(body.toRequestBody(jsonType)).build()).second, wallet)
    }

    suspend fun login(wallet: String, password: String, identity: DeviceIdentity): LoginResult {
        val (code, body) = execute(Request.Builder().url("$baseUrl/v1/auth/login").post(credentials(wallet, password, identity).toRequestBody(jsonType)).build(), allow202 = true)
        val o = JSONObject(body)
        return if (code == 202) LoginResult.ApprovalRequired(PendingDeviceLogin(o.getString("pendingToken"), o.getString("expiresAt"))) else LoginResult.Authenticated(parseSession(body, wallet))
    }

    suspend fun recover(wallet: String, recoveryCode: String, newPassword: String, identity: DeviceIdentity): CloudSession {
        val body = JSONObject()
            .put("walletAddress", wallet.trim())
            .put("recoveryCode", recoveryCode.trim())
            .put("newPassword", newPassword)
            .put("device", JSONObject()
                .put("clientDeviceId", identity.clientDeviceId)
                .put("displayName", identity.displayName)
                .put("platform", "android")
                .put("appVersion", BuildConfig.VERSION_NAME))
            .toString()
        return parseSession(execute(Request.Builder().url("$baseUrl/v1/auth/recover").post(body.toRequestBody(jsonType)).build()).second, wallet)
    }

    suspend fun pendingStatus(token: String, wallet: String): LoginResult? {
        val requestBody = JSONObject().put("token", token).toString().toRequestBody(jsonType)
        val (_, body) = execute(Request.Builder().url("$baseUrl/v1/auth/device-status").post(requestBody).build())
        val o = JSONObject(body)
        return when (o.optString("status")) {
            "approved" -> LoginResult.Authenticated(parseSession(o.getJSONObject("tokens").toString(), wallet))
            "rejected", "expired" -> null
            else -> LoginResult.ApprovalRequired(PendingDeviceLogin(token, ""))
        }
    }

    suspend fun refresh(session: CloudSession): CloudSession {
        val body = JSONObject().put("refreshToken", session.refreshToken).toString()
        return parseSession(execute(Request.Builder().url("$baseUrl/v1/auth/refresh").post(body.toRequestBody(jsonType)).build()).second, session.walletAddress)
    }
    suspend fun logout(session: CloudSession) {
        val body = JSONObject().put("refreshToken", session.refreshToken).toString()
        execute(Request.Builder().url("$baseUrl/v1/auth/logout").header("Authorization", "Bearer ${session.accessToken}").post(body.toRequestBody(jsonType)).build())
    }

    suspend fun account(token: String): JSONObject = authGet("/v1/account", token)
    suspend fun rigs(token: String): List<RigSummary> = authGet("/v1/rigs", token).getJSONArray("items").mapObjects { o ->
        RigSummary(o.getString("deviceId"), o.optString("clientDeviceId"), o.optString("displayName"), o.optString("platform"), o.optString("appVersion"), o.optBoolean("online"), o.optString("state"), o.optDouble("hashrate"), o.optBoolean("mining"), o.optBoolean("currentDevice"), o.optNullable("lastHeartbeat"))
    }
    suspend fun pendingApprovals(token: String): List<PendingApproval> = authGet("/v1/devices/pending", token).getJSONArray("items").mapObjects { o -> PendingApproval(o.getString("deviceId"), o.optString("displayName"), o.optString("platform"), o.optString("appVersion"), o.optString("requestedAt"), o.optString("expiresAt")) }
    suspend fun resolveApproval(token: String, deviceId: String, approve: Boolean) { authPost("/v1/devices/$deviceId/${if (approve) "approve" else "reject"}", "{}", token) }
    suspend fun rigState(token: String, deviceId: String): RemoteRigState {
        val o = authGet("/v1/rigs/$deviceId/state", token)
        return CloudJson.remote(deviceId, o.optBoolean("online"), o.optString("lastHeartbeat"), o.getJSONObject("state"))
    }
    suspend fun command(token: String, deviceId: String, type: String, payload: JSONObject = JSONObject()): String = authPost("/v1/rigs/$deviceId/commands", JSONObject().put("type", type).put("payload", payload).toString(), token).getString("commandId")
    suspend fun pendingCommands(token: String): List<JSONObject> = authGet("/v1/commands/pending", token).getJSONArray("items").mapObjects { it }
    suspend fun ack(token: String, commandId: String, success: Boolean, result: JSONObject) { authPost("/v1/commands/$commandId/ack", JSONObject().put("success", success).put("result", result).toString(), token) }
    suspend fun telemetry(token: String, clientInstallationId: String, snapshotState: String, hashrate: Double, mining: Boolean, userNanos: Long, donationNanos: Long, payload: JSONObject): JSONObject = authPost("/v1/telemetry", JSONObject()
        .put("clientInstallationId", clientInstallationId)
        .put("state", snapshotState).put("hashrate", hashrate).put("mining", mining)
        .put("userMiningNanos", userNanos).put("donationMiningNanos", donationNanos).put("payload", payload).toString(), token)

    fun openWebSocket(token: String, listener: WebSocketListener): WebSocket {
        val wsBase = if (baseUrl.startsWith("https://")) "wss://${baseUrl.removePrefix("https://")}" else "ws://${baseUrl.removePrefix("http://")}"
        return client.newWebSocket(Request.Builder().url("$wsBase/v1/ws").header("Authorization", "Bearer $token").build(), listener)
    }

    suspend fun releasePublicKey(): String = execute(Request.Builder().url("$baseUrl/v1/releases/public-key").get().build()).second.let { JSONObject(it).getString("publicKeyBase64") }
    suspend fun latestMinerRelease(): JSONObject = execute(Request.Builder().url("$baseUrl/v1/releases/android/miner/latest").get().build()).second.let(::JSONObject)
    suspend fun download(url: String, onBytes: (ByteArray, Int) -> Unit, total: (Long) -> Unit) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Update download failed with HTTP ${response.code}.")
            val body = response.body ?: throw IOException("The update response was empty.")
            total(body.contentLength())
            body.byteStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val read=input.read(buffer); if (read<0) break; onBytes(buffer,read) } }
        }
    }

    private fun credentials(wallet: String, password: String, identity: DeviceIdentity): String = JSONObject()
        .put("walletAddress", wallet.trim()).put("password", password)
        .put("device", JSONObject().put("clientDeviceId", identity.clientDeviceId).put("displayName", identity.displayName).put("platform", "android").put("appVersion", BuildConfig.VERSION_NAME)).toString()
    private fun parseSession(raw: String, wallet: String): CloudSession = JSONObject(raw).let { CloudSession(it.getString("accessToken"), it.getString("accessExpiresAt"), it.getString("refreshToken"), it.getString("userId"), it.getString("deviceId"), wallet.trim()) }
    private suspend fun authGet(path: String, token: String): JSONObject = execute(Request.Builder().url(baseUrl+path).header("Authorization", "Bearer $token").get().build()).second.let(::JSONObject)
    private suspend fun authPost(path: String, raw: String, token: String): JSONObject = execute(Request.Builder().url(baseUrl+path).header("Authorization", "Bearer $token").post(raw.toRequestBody(jsonType)).build()).second.let { if(it.isBlank()) JSONObject() else JSONObject(it) }
    private suspend fun execute(request: Request, allow202: Boolean = false): Pair<Int,String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful && !(allow202 && response.code == 202)) {
                val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrDefault("Server request failed with HTTP ${response.code}.")
                throw CloudHttpException(response.code, message)
            }
            response.code to raw
        }
    }
}
class CloudHttpException(val status: Int, message: String): IOException(message)
private inline fun <T> JSONArray.mapObjects(block: (JSONObject)->T): List<T> = buildList { for(i in 0 until length()) add(block(getJSONObject(i))) }
private fun JSONObject.optNullable(key:String):String? = if(isNull(key)) null else optString(key).takeIf(String::isNotBlank)
