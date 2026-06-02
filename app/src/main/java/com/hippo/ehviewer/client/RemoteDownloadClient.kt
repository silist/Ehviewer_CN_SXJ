package com.hippo.ehviewer.client

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 远程下载 API 客户端
 * 与 NAS 端 API 通信，推送下载任务
 */
object RemoteDownloadClient {

    private const val TAG = "RemoteDownloadClient"

    private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ==================== 结果类型 ====================

    sealed class PushResult {
        data class Success(val taskId: String) : PushResult()
        data class Skipped(val reason: String?) : PushResult()
        data class Error(val message: String) : PushResult()
    }

    sealed class SyncResult {
        data class Success(val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    sealed class TestResult {
        object Success : TestResult()
        data class Error(val message: String) : TestResult()
    }

    // ==================== 公共 API（Java 调用的阻塞方法） ====================

    /**
     * 推送下载任务到 NAS（阻塞方法，供 Java 调用）
     */
    @JvmStatic
    fun pushDownloadBlocking(info: GalleryInfo, cookies: String): PushResult {
        return runBlocking { pushDownload(info, cookies) }
    }

    /**
     * 同步 Cookie 到 NAS（阻塞方法，供 Java 调用）
     */
    @JvmStatic
    fun syncCookiesBlocking(cookies: String): SyncResult {
        return runBlocking { syncCookies(cookies) }
    }

    /**
     * 测试 NAS 连接（阻塞方法，供 Java 调用）
     */
    @JvmStatic
    fun testConnectionBlocking(): TestResult {
        return runBlocking { testConnection() }
    }

    // ==================== 内部实现（suspend 函数） ====================

    /**
     * 推送下载任务到 NAS
     */
    suspend fun pushDownload(info: GalleryInfo, cookies: String): PushResult = withContext(Dispatchers.IO) {
        val address = Settings.getRemoteNasAddress()
        val port = Settings.getRemoteNasPort()
        val token = Settings.getRemoteApiToken()

        if (address.isBlank() || token.isBlank()) {
            return@withContext PushResult.Error("远程下载未配置")
        }

        val url = "http://$address:$port/api/v1/download"

        val body = JSONObject().apply {
            put("gid", info.gid)
            put("token", info.token)
            put("title", info.title ?: "")
            put("title_jpn", info.titleJpn ?: "")
            put("thumb", info.thumb ?: "")
            put("category", info.category)
            put("page_count", info.pages)
            put("cookies", cookies)
        }

        val requestBody = RequestBody.create(JSON_MEDIA_TYPE, body.toString())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext when (response.code()) {
                    401 -> PushResult.Error("API Token 无效")
                    else -> PushResult.Error("请求失败: ${response.code()}")
                }
            }

            val responseBody = response.body()?.string()
                ?: return@withContext PushResult.Error("响应为空")

            val json = JSONObject(responseBody)
            val taskId = json.optString("task_id")
            val status = json.optString("status", "queued")
            val skipReason = json.optString("skip_reason")

            if (status == "skipped") {
                PushResult.Skipped(skipReason)
            } else {
                PushResult.Success(taskId)
            }

        } catch (e: IOException) {
            PushResult.Error("无法连接 NAS: ${e.message}")
        }
    }

    /**
     * 同步 Cookie 到 NAS
     */
    suspend fun syncCookies(cookies: String): SyncResult = withContext(Dispatchers.IO) {
        val address = Settings.getRemoteNasAddress()
        val port = Settings.getRemoteNasPort()
        val token = Settings.getRemoteApiToken()

        if (address.isBlank() || token.isBlank()) {
            return@withContext SyncResult.Error("远程下载未配置")
        }

        val url = "http://$address:$port/api/v1/config/cookies"

        val body = JSONObject().apply {
            put("cookies", cookies)
        }

        val requestBody = RequestBody.create(JSON_MEDIA_TYPE, body.toString())

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .put(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext when (response.code()) {
                    401 -> SyncResult.Error("API Token 无效")
                    else -> SyncResult.Error("请求失败: ${response.code()}")
                }
            }

            val responseBody = response.body()?.string()
                ?: return@withContext SyncResult.Error("响应为空")

            val json = JSONObject(responseBody)
            val valid = json.optBoolean("valid", false)
            val message = json.optString("message", "")

            if (valid) {
                SyncResult.Success(message)
            } else {
                SyncResult.Error(message)
            }

        } catch (e: IOException) {
            SyncResult.Error("无法连接 NAS: ${e.message}")
        }
    }

    /**
     * 测试 NAS 连接
     */
    suspend fun testConnection(): TestResult = withContext(Dispatchers.IO) {
        val address = Settings.getRemoteNasAddress()
        val port = Settings.getRemoteNasPort()
        val token = Settings.getRemoteApiToken()

        if (address.isBlank()) {
            return@withContext TestResult.Error("请输入 NAS 地址")
        }
        if (token.isBlank()) {
            return@withContext TestResult.Error("请输入 API Token")
        }

        val url = "http://$address:$port/api/v1/tasks?page=1&size=1"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()

            when (response.code()) {
                200 -> TestResult.Success
                401 -> TestResult.Error("API Token 无效")
                else -> TestResult.Error("连接失败: ${response.code()}")
            }

        } catch (e: IOException) {
            TestResult.Error("无法连接 NAS: ${e.message}")
        }
    }
}