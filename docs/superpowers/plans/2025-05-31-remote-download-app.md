# EH Viewer App 端远程下载实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 EhViewer Android App 添加远程下载功能，允许用户将下载任务推送到 NAS 端执行。

**Architecture:** 新增 RemotePushInfo 表记录推送记录，设置页面添加远程配置，下载逻辑分流到 NAS API，画廊列表和下载列表显示远程状态。

**Tech Stack:** Android / Kotlin & Java / GreenDAO / OkHttp / FastJSON

---

## 前置依赖

- NAS 端 API 已部署可用
- 需要了解现有下载流程（SpiderQueen）

---

## 文件结构

```
app/src/main/java/com/hippo/ehviewer/
├── dao/
│   └── RemotePushInfo.java              # 新增：推送记录实体
├── client/
│   └── RemoteDownloadClient.kt          # 新增：NAS API 客户端
├── ui/scene/
│   ├── settings/
│   │   └── RemoteDownloadSettingsFragment.java  # 新增：远程下载设置页
│   ├── DownloadsScene.java              # 修改：新增筛选器
│   └── gallery/list/
│       └── GalleryListScene.java        # 修改：显示已推送图标
├── widget/
│   └── RemotePushIconDrawable.java      # 新增：已推送图标
├── download/
│   ├── DownloadManager.java             # 修改：支持远程记录查询
│   └── DownloadAdapter.java             # 修改：支持远程记录显示
├── Settings.java                        # 修改：新增配置项
└── CommonOperations.java               # 修改：下载逻辑分流

daogenerator/
└── src/main/java/com/hippo/ehviewer/daogenerator/
    └── DaoGenerator.java                # 修改：新增 RemotePushInfo 表
```

---

## Task 1: DAO 生成器 - 新增 RemotePushInfo 表

**Files:**
- Modify: `daogenerator/src/main/java/com/hippo/ehviewer/daogenerator/DaoGenerator.java`

- [ ] **Step 1: 在 DaoGenerator.java 中添加 RemotePushInfo 实体**

在 `addSchema` 方法中，找到 DownloadInfo 实体定义后，添加以下代码：

```java
// RemotePushInfo - 远程推送记录
Entity remotePushInfo = schema.addEntity("RemotePushInfo");
remotePushInfo.addLongProperty("gid").primaryKey();
remotePushInfo.addStringProperty("token");
remotePushInfo.addStringProperty("title");
remotePushInfo.addStringProperty("titleJpn");
remotePushInfo.addStringProperty("thumb");
remotePushInfo.addIntProperty("category");
remotePushInfo.addStringProperty("posted");
remotePushInfo.addStringProperty("uploader");
remotePushInfo.addFloatProperty("rating");
remotePushInfo.addLongProperty("pushTime");
```

- [ ] **Step 2: 运行 DAO 生成器**

```bash
cd F:\projects\android\Ehviewer_CN_SXJ
./gradlew :daogenerator:executeDaoGenerator
```

验证：检查 `app/src/main/java/com/hippo/ehviewer/dao/` 目录下是否生成 `RemotePushInfo.java`、`RemotePushInfoDao.java` 等文件。

- [ ] **Step 3: 提交**

```bash
git add daogenerator/ app/src/main/java/com/hippo/ehviewer/dao/RemotePushInfo*
git commit -m "feat: add RemotePushInfo DAO entity for remote download tracking"
```

---

## Task 2: Settings.java - 新增配置项

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/Settings.java`

- [ ] **Step 1: 添加远程下载配置项**

在 `Settings.java` 中添加以下字段和方法：

```java
// 下载模式
public static final String KEY_DOWNLOAD_MODE = "download_mode";
public static final String DOWNLOAD_MODE_LOCAL = "local";
public static final String DOWNLOAD_MODE_REMOTE = "remote";

// 远程下载配置
public static final String KEY_REMOTE_NAS_ADDRESS = "remote_nas_address";
public static final String KEY_REMOTE_NAS_PORT = "remote_nas_port";
public static final String KEY_REMOTE_API_TOKEN = "remote_api_token";

// Cookie 同步状态
public static final String KEY_REMOTE_COOKIE_SYNCED = "remote_cookie_synced";
public static final String KEY_REMOTE_COOKIE_SYNC_TIME = "remote_cookie_sync_time";

// 下载模式 getter
public static String getDownloadMode() {
    return getSettings().getString(KEY_DOWNLOAD_MODE, DOWNLOAD_MODE_LOCAL);
}

public static void putDownloadMode(String mode) {
    getSettings().edit().putString(KEY_DOWNLOAD_MODE, mode).apply();
}

// 远程配置 getter
public static String getRemoteNasAddress() {
    return getSettings().getString(KEY_REMOTE_NAS_ADDRESS, "");
}

public static void putRemoteNasAddress(String address) {
    getSettings().edit().putString(KEY_REMOTE_NAS_ADDRESS, address).apply();
}

public static int getRemoteNasPort() {
    return getSettings().getInt(KEY_REMOTE_NAS_PORT, 8080);
}

public static void putRemoteNasPort(int port) {
    getSettings().edit().putInt(KEY_REMOTE_NAS_PORT, port).apply();
}

public static String getRemoteApiToken() {
    return getSettings().getString(KEY_REMOTE_API_TOKEN, "");
}

public static void putRemoteApiToken(String token) {
    getSettings().edit().putString(KEY_REMOTE_API_TOKEN, token).apply();
}

// Cookie 同步状态
public static boolean isRemoteCookieSynced() {
    return getSettings().getBoolean(KEY_REMOTE_COOKIE_SYNCED, false);
}

public static void putRemoteCookieSynced(boolean synced) {
    getSettings().edit().putBoolean(KEY_REMOTE_COOKIE_SYNCED, synced).apply();
}

public static long getRemoteCookieSyncTime() {
    return getSettings().getLong(KEY_REMOTE_COOKIE_SYNC_TIME, 0);
}

public static void putRemoteCookieSyncTime(long time) {
    getSettings().edit().putLong(KEY_REMOTE_COOKIE_SYNC_TIME, time).apply();
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/Settings.java
git commit -m "feat: add remote download settings keys"
```

---

## Task 3: RemoteDownloadClient - NAS API 客户端

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt`

- [ ] **Step 1: 创建 RemoteDownloadClient.kt**

```kotlin
package com.hippo.ehviewer.client

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.data.GalleryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 远程下载 API 客户端
 */
object RemoteDownloadClient {
    
    private const val TAG = "RemoteDownloadClient"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * 推送下载任务到 NAS
     * 
     * @param info 画廊信息
     * @param cookies EH cookies
     * @return PushResult
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
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext when (response.code) {
                    401 -> PushResult.Error("API Token 无效")
                    else -> PushResult.Error("请求失败: ${response.code}")
                }
            }
            
            val responseBody = response.body?.string()
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
     * 
     * @param cookies EH cookies
     * @return SyncResult
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
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext when (response.code) {
                    401 -> SyncResult.Error("API Token 无效")
                    else -> SyncResult.Error("请求失败: ${response.code}")
                }
            }
            
            val responseBody = response.body?.string()
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
            
            when (response.code) {
                200 -> TestResult.Success
                401 -> TestResult.Error("API Token 无效")
                else -> TestResult.Error("连接失败: ${response.code}")
            }
            
        } catch (e: IOException) {
            TestResult.Error("无法连接 NAS: ${e.message}")
        }
    }
    
    // 结果类型
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
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt
git commit -m "feat: add RemoteDownloadClient for NAS API communication"
```

---

## Task 4: CommonOperations - 下载逻辑分流

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/CommonOperations.java`

- [ ] **Step 1: 添加远程下载分支**

找到 `startDownload` 方法，添加远程模式判断：

```java
// 在文件顶部添加导入
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.RemoteDownloadClient;
import com.hippo.ehviewer.dao.RemotePushInfo;
import com.hippo.ehviewer.dao.RemotePushInfoDao;
import com.hippo.ehviewer.dao.DaoSession;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.launch;

// 在 startDownload 方法开头添加
public static void startDownload(@NonNull Context context, @NonNull GalleryInfo galleryInfo,
        @Nullable String label) {
    
    // 检查下载模式
    if (Settings.getDownloadMode().equals(Settings.DOWNLOAD_MODE_REMOTE)) {
        startRemoteDownload(context, galleryInfo);
        return;
    }
    
    // 原有本地下载逻辑...
}

// 新增方法：远程下载
private static void startRemoteDownload(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
    // 获取 cookies
    String cookies = getCookies(context);
    
    // 异步推送
    CoroutineScope scope = new CoroutineScope(Dispatchers.getIO());
    scope.launch(() -> {
        RemoteDownloadClient.PushResult result = RemoteDownloadClient.INSTANCE.pushDownload(galleryInfo, cookies);
        
        if (result instanceof RemoteDownloadClient.PushResult.Success) {
            // 保存推送记录
            saveRemotePushInfo(galleryInfo);
            
            // 显示成功提示
            withContext(Dispatchers.getMain(), () -> {
                Toast.makeText(context, "已推送到 NAS", Toast.LENGTH_SHORT).show();
            });
            
        } else if (result instanceof RemoteDownloadClient.PushResult.Skipped) {
            RemoteDownloadClient.PushResult.Skipped skipped = (RemoteDownloadClient.PushResult.Skipped) result;
            withContext(Dispatchers.getMain(), () -> {
                Toast.makeText(context, "已跳过: " + skipped.getReason(), Toast.LENGTH_SHORT).show();
            });
            
        } else if (result instanceof RemoteDownloadClient.PushResult.Error) {
            RemoteDownloadClient.PushResult.Error error = (RemoteDownloadClient.PushResult.Error) result;
            withContext(Dispatchers.getMain(), () -> {
                Toast.makeText(context, error.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    });
}

// 获取 EH cookies
private static String getCookies(@NonNull Context context) {
    SharedPreferences sharedPreferences = context.getSharedPreferences("eh_info", 0);
    String ipbMemberId = sharedPreferences.getString("ipb_member_id", "");
    String ipbPassHash = sharedPreferences.getString("ipb_pass_hash", "");
    String igneous = sharedPreferences.getString("igneous", "");
    
    StringBuilder sb = new StringBuilder();
    if (!TextUtils.isEmpty(ipbMemberId)) {
        sb.append("ipb_member_id=").append(ipbMemberId);
    }
    if (!TextUtils.isEmpty(ipbPassHash)) {
        if (sb.length() > 0) sb.append("; ");
        sb.append("ipb_pass_hash=").append(ipbPassHash);
    }
    if (!TextUtils.isEmpty(igneous)) {
        if (sb.length() > 0) sb.append("; ");
        sb.append("igneous=").append(igneous);
    }
    return sb.toString();
}

// 保存推送记录
private static void saveRemotePushInfo(@NonNull GalleryInfo galleryInfo) {
    try {
        DaoSession daoSession = EhDB.getDaoSession();
        RemotePushInfoDao dao = daoSession.getRemotePushInfoDao();
        
        RemotePushInfo info = new RemotePushInfo();
        info.setGid(galleryInfo.gid);
        info.setToken(galleryInfo.token);
        info.setTitle(galleryInfo.title);
        info.setTitleJpn(galleryInfo.titleJpn);
        info.setThumb(galleryInfo.thumb);
        info.setCategory(galleryInfo.category);
        info.setPosted(galleryInfo.posted);
        info.setUploader(galleryInfo.uploader);
        info.setRating(galleryInfo.rating);
        info.setPushTime(System.currentTimeMillis());
        
        dao.insertOrReplace(info);
    } catch (Exception e) {
        e.printStackTrace();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/CommonOperations.java
git commit -m "feat: add remote download mode support in CommonOperations"
```

---

## Task 5: RemoteDownloadSettingsFragment - 远程下载设置页

**Files:**
- Create: `app/src/main/res/xml/remote_download_settings.xml`
- Create: `app/src/main/java/com/hippo/ehviewer/ui/scene/settings/RemoteDownloadSettingsFragment.java`

- [ ] **Step 1: 创建设置 XML 文件**

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    
    <ListPreference
        android:key="download_mode"
        android:title="下载模式"
        android:entries="@array/download_mode_entries"
        android:entryValues="@array/download_mode_values"
        android:defaultValue="local"
        app:useSimpleSummaryProvider="true" />
    
    <PreferenceCategory
        android:key="remote_settings"
        android:title="远程模式配置">
        
        <EditTextPreference
            android:key="remote_nas_address"
            android:title="NAS 地址"
            android:dialogTitle="输入 NAS IP 或域名"
            android:inputType="textUri" />
        
        <EditTextPreference
            android:key="remote_nas_port"
            android:title="API 端口"
            android:dialogTitle="输入 API 端口"
            android:defaultValue="8080"
            android:inputType="number" />
        
        <EditTextPreference
            android:key="remote_api_token"
            android:title="API Token"
            android:dialogTitle="输入 API Token"
            android:inputType="textPassword" />
        
        <Preference
            android:key="test_connection"
            android:title="测试连接"
            android:summary="点击测试 NAS 连接" />
        
    </PreferenceCategory>
    
    <PreferenceCategory
        android:key="cookie_sync"
        android:title="Cookie 同步">
        
        <Preference
            android:key="cookie_sync_status"
            android:title="同步状态"
            android:selectable="false" />
        
        <Preference
            android:key="sync_cookie"
            android:title="同步 Cookie 到 NAS"
            android:summary="点击同步 EH Cookie 到 NAS" />
        
    </PreferenceCategory>
    
</PreferenceScreen>
```

- [ ] **Step 2: 创建字符串资源**

在 `res/values/strings.xml` 添加：

```xml
<string-array name="download_mode_entries">
    <item>本地下载</item>
    <item>远程下载</item>
</string-array>
<string-array name="download_mode_values">
    <item>local</item>
    <item>remote</item>
</string-array>
```

- [ ] **Step 3: 创建 Fragment**

```java
package com.hippo.ehviewer.ui.scene.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.RemoteDownloadClient;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.launch;
import kotlinx.coroutines.withContext;

public class RemoteDownloadSettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    
    private static final String TAG = "RemoteDownloadSettings";
    
    private Preference mRemoteSettingsCategory;
    private Preference mCookieSyncCategory;
    private Preference mTestConnection;
    private Preference mCookieSyncStatus;
    private Preference mSyncCookie;
    
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.remote_download_settings);
        
        mRemoteSettingsCategory = findPreference("remote_settings");
        mCookieSyncCategory = findPreference("cookie_sync");
        mTestConnection = findPreference("test_connection");
        mCookieSyncStatus = findPreference("cookie_sync_status");
        mSyncCookie = findPreference("sync_cookie");
        
        updateVisibility();
        updateCookieSyncStatus();
        
        mTestConnection.setOnPreferenceClickListener(preference -> {
            testConnection();
            return true;
        });
        
        mSyncCookie.setOnPreferenceClickListener(preference -> {
            syncCookie();
            return true;
        });
    }
    
    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }
    
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("download_mode".equals(key)) {
            updateVisibility();
        }
    }
    
    private void updateVisibility() {
        String mode = Settings.getDownloadMode();
        boolean isRemote = Settings.DOWNLOAD_MODE_REMOTE.equals(mode);
        
        mRemoteSettingsCategory.setVisible(isRemote);
        mCookieSyncCategory.setVisible(isRemote);
    }
    
    private void updateCookieSyncStatus() {
        boolean synced = Settings.isRemoteCookieSynced();
        long syncTime = Settings.getRemoteCookieSyncTime();
        
        String summary;
        if (synced && syncTime > 0) {
            String timeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                    .format(new java.util.Date(syncTime));
            summary = "已同步 (" + timeStr + ")";
        } else {
            summary = "未同步";
        }
        
        mCookieSyncStatus.setSummary(summary);
    }
    
    private void testConnection() {
        mTestConnection.setEnabled(false);
        mTestConnection.setSummary("测试中...");
        
        CoroutineScope scope = new CoroutineScope(Dispatchers.getIO());
        scope.launch(() -> {
            RemoteDownloadClient.TestResult result = 
                    RemoteDownloadClient.INSTANCE.testConnection();
            
            withContext(Dispatchers.getMain(), () -> {
                mTestConnection.setEnabled(true);
                
                if (result instanceof RemoteDownloadClient.TestResult.Success) {
                    mTestConnection.setSummary("连接成功");
                    Toast.makeText(requireContext(), "连接成功", Toast.LENGTH_SHORT).show();
                } else {
                    RemoteDownloadClient.TestResult.Error error = 
                            (RemoteDownloadClient.TestResult.Error) result;
                    mTestConnection.setSummary("连接失败");
                    Toast.makeText(requireContext(), error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }
    
    private void syncCookie() {
        mSyncCookie.setEnabled(false);
        mSyncCookie.setSummary("同步中...");
        
        // 获取 cookies
        String cookies = getCookies();
        
        if (TextUtils.isEmpty(cookies)) {
            mSyncCookie.setSummary(null);
            mSyncCookie.setEnabled(true);
            Toast.makeText(requireContext(), "未找到 EH Cookie，请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        
        CoroutineScope scope = new CoroutineScope(Dispatchers.getIO());
        scope.launch(() -> {
            RemoteDownloadClient.SyncResult result = 
                    RemoteDownloadClient.INSTANCE.syncCookies(cookies);
            
            withContext(Dispatchers.getMain(), () -> {
                mSyncCookie.setEnabled(true);
                
                if (result instanceof RemoteDownloadClient.SyncResult.Success) {
                    Settings.putRemoteCookieSynced(true);
                    Settings.putRemoteCookieSyncTime(System.currentTimeMillis());
                    mSyncCookie.setSummary("同步成功");
                    updateCookieSyncStatus();
                    Toast.makeText(requireContext(), "Cookie 同步成功", Toast.LENGTH_SHORT).show();
                } else {
                    RemoteDownloadClient.SyncResult.Error error = 
                            (RemoteDownloadClient.SyncResult.Error) result;
                    Settings.putRemoteCookieSynced(false);
                    mSyncCookie.setSummary(null);
                    Toast.makeText(requireContext(), error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }
    
    private String getCookies() {
        SharedPreferences sharedPreferences = requireContext()
                .getSharedPreferences("eh_info", 0);
        String ipbMemberId = sharedPreferences.getString("ipb_member_id", "");
        String ipbPassHash = sharedPreferences.getString("ipb_pass_hash", "");
        String igneous = sharedPreferences.getString("igneous", "");
        
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(ipbMemberId)) {
            sb.append("ipb_member_id=").append(ipbMemberId);
        }
        if (!TextUtils.isEmpty(ipbPassHash)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("ipb_pass_hash=").append(ipbPassHash);
        }
        if (!TextUtils.isEmpty(igneous)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("igneous=").append(igneous);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 添加到设置菜单**

在 `res/xml/settings.xml` 中添加：

```xml
<Preference
    android:fragment="com.hippo.ehviewer.ui.scene.settings.RemoteDownloadSettingsFragment"
    android:key="remote_download_settings"
    android:title="远程下载"
    android:summary="配置 NAS 远程下载" />
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/xml/ app/src/main/res/values/strings.xml app/src/main/java/com/hippo/ehviewer/ui/scene/settings/
git commit -m "feat: add remote download settings page"
```

---

## Task 6: DownloadsScene - 下载列表筛选器

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/DownloadsScene.java`

- [ ] **Step 1: 添加筛选器 Tab**

在 `DownloadsScene` 中添加顶部筛选器：

```java
// 添加常量
private static final int FILTER_ALL = 0;
private static final int FILTER_LOCAL = 1;
private static final int FILTER_REMOTE = 2;

private int mCurrentFilter = FILTER_ALL;

// 添加 Tab 控件
private TabLayout mFilterTabLayout;

// 在 onCreateView 中添加 Tab
private void setupFilterTabs(View view) {
    mFilterTabLayout = (TabLayout) view.findViewById(R.id.filter_tab_layout);
    if (mFilterTabLayout != null) {
        mFilterTabLayout.addTab(mFilterTabLayout.newTab().setText("全部"));
        mFilterTabLayout.addTab(mFilterTabLayout.newTab().setText("本地"));
        mFilterTabLayout.addTab(mFilterTabLayout.newTab().setText("远程"));
        
        mFilterTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mCurrentFilter = tab.getPosition();
                refresh();
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
}

// 修改数据加载逻辑
private List<?> loadData() {
    switch (mCurrentFilter) {
        case FILTER_LOCAL:
            // 原有本地下载列表
            return loadLocalDownloads();
        case FILTER_REMOTE:
            // 新增远程推送列表
            return loadRemotePushes();
        case FILTER_ALL:
        default:
            // 合并列表
            return loadAllDownloads();
    }
}

// 加载远程推送记录
private List<RemotePushInfo> loadRemotePushes() {
    try {
        DaoSession daoSession = EhDB.getDaoSession();
        RemotePushInfoDao dao = daoSession.getRemotePushInfoDao();
        return dao.queryBuilder()
                .orderDesc(RemotePushInfoDao.Properties.PushTime)
                .list();
    } catch (Exception e) {
        e.printStackTrace();
        return Collections.emptyList();
    }
}

// 合并列表
private List<Object> loadAllDownloads() {
    List<Object> result = new ArrayList<>();
    
    // 本地下载
    List<DownloadInfo> localDownloads = loadLocalDownloads();
    result.addAll(localDownloads);
    
    // 远程推送（排除已存在的 gid）
    Set<Long> localGids = new HashSet<>();
    for (DownloadInfo info : localDownloads) {
        localGids.add(info.gid);
    }
    
    List<RemotePushInfo> remotePushes = loadRemotePushes();
    for (RemotePushInfo push : remotePushes) {
        if (!localGids.contains(push.getGid())) {
            result.add(push);
        }
    }
    
    // 按时间排序
    Collections.sort(result, (a, b) -> {
        long timeA = getTimeForItem(a);
        long timeB = getTimeForItem(b);
        return Long.compare(timeB, timeA); // 降序
    });
    
    return result;
}

private long getTimeForItem(Object item) {
    if (item instanceof DownloadInfo) {
        return ((DownloadInfo) item).time;
    } else if (item instanceof RemotePushInfo) {
        return ((RemotePushInfo) item).getPushTime();
    }
    return 0;
}
```

- [ ] **Step 2: 添加布局中的 TabLayout**

在 `res/layout/scene_downloads.xml` 中添加：

```xml
<com.google.android.material.tabs.TabLayout
    android:id="@+id/filter_tab_layout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/colorPrimary"
    app:tabTextColor="@android:color/white"
    app:tabSelectedTextColor="@android:color/white"
    app:tabIndicatorColor="@android:color/white" />
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/ui/scene/DownloadsScene.java app/src/main/res/layout/scene_downloads.xml
git commit -m "feat: add download filter tabs for remote pushes"
```

---

## Task 7: DownloadAdapter - 支持远程记录显示

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/download/DownloadAdapter.java`

- [ ] **Step 1: 修改 Adapter 支持混合类型**

```java
// 添加类型常量
private static final int TYPE_LOCAL = 0;
private static final int TYPE_REMOTE = 1;

@Override
public int getItemViewType(int position) {
    Object item = getItem(position);
    return item instanceof RemotePushInfo ? TYPE_REMOTE : TYPE_LOCAL;
}

@Override
public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    switch (viewType) {
        case TYPE_REMOTE:
            View remoteView = mInflater.inflate(R.layout.item_download_remote, parent, false);
            return new RemoteViewHolder(remoteView);
        default:
            // 原有本地下载 ViewHolder
            return super.onCreateViewHolder(parent, viewType);
    }
}

@Override
public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
    int viewType = getItemViewType(position);
    
    if (viewType == TYPE_REMOTE) {
        bindRemoteViewHolder((RemoteViewHolder) holder, (RemotePushInfo) getItem(position));
    } else {
        super.onBindViewHolder(holder, position);
    }
}

private void bindRemoteViewHolder(RemoteViewHolder holder, RemotePushInfo info) {
    // 显示标题
    holder.title.setText(info.getTitle());
    
    // 显示缩略图
    if (!TextUtils.isEmpty(info.getThumb())) {
        Glide.with(holder.thumb.getContext())
                .load(info.getThumb())
                .into(holder.thumb);
    }
    
    // 显示推送时间
    String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm")
            .format(new Date(info.getPushTime()));
    holder.time.setText(timeStr);
    
    // 显示远程图标
    holder.statusIcon.setImageResource(R.drawable.ic_cloud_upload);
    
    // 点击事件
    holder.itemView.setOnClickListener(v -> {
        showRemoteDetailDialog(info);
    });
    
    // 长按事件
    holder.itemView.setOnLongClickListener(v -> {
        showRemoteOptionsDialog(info);
        return true;
    });
}

// 远程详情对话框
private void showRemoteDetailDialog(RemotePushInfo info) {
    new AlertDialog.Builder(mContext)
            .setTitle(info.getTitle())
            .setMessage("已推送到 NAS\n推送时间: " + 
                    new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(info.getPushTime())))
            .setPositiveButton("查看详情", (dialog, which) -> {
                // 打开画廊详情页
                Bundle args = new Bundle();
                args.putLong(GalleryInfoScene.KEY_GID, info.getGid());
                args.putString(GalleryInfoScene.KEY_TOKEN, info.getToken());
                startScene(new Announcer(GalleryInfoScene.class).setArgs(args));
            })
            .setNegativeButton("重新推送", (dialog, which) -> {
                rePushToRemote(info);
            })
            .setNeutralButton("关闭", null)
            .show();
}

// 重新推送
private void rePushToRemote(RemotePushInfo info) {
    CoroutineScope scope = new CoroutineScope(Dispatchers.getIO());
    scope.launch(() -> {
        GalleryInfo galleryInfo = new GalleryInfo();
        galleryInfo.gid = info.getGid();
        galleryInfo.token = info.getToken();
        galleryInfo.title = info.getTitle();
        galleryInfo.titleJpn = info.getTitleJpn();
        galleryInfo.thumb = info.getThumb();
        galleryInfo.category = info.getCategory();
        galleryInfo.pages = 0;
        
        String cookies = getCookies();
        RemoteDownloadClient.PushResult result = 
                RemoteDownloadClient.INSTANCE.pushDownload(galleryInfo, cookies);
        
        withContext(Dispatchers.getMain(), () -> {
            if (result instanceof RemoteDownloadClient.PushResult.Success) {
                Toast.makeText(mContext, "重新推送成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(mContext, "推送失败", Toast.LENGTH_SHORT).show();
            }
        });
    });
}

// RemoteViewHolder 内部类
static class RemoteViewHolder extends RecyclerView.ViewHolder {
    ImageView thumb;
    TextView title;
    TextView time;
    ImageView statusIcon;
    
    RemoteViewHolder(View itemView) {
        super(itemView);
        thumb = itemView.findViewById(R.id.thumb);
        title = itemView.findViewById(R.id.title);
        time = itemView.findViewById(R.id.time);
        statusIcon = itemView.findViewById(R.id.status_icon);
    }
}
```

- [ ] **Step 2: 创建远程列表项布局**

`res/layout/item_download_remote.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:padding="8dp">
    
    <ImageView
        android:id="@+id/thumb"
        android:layout_width="60dp"
        android:layout_height="80dp"
        android:scaleType="centerCrop"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />
    
    <TextView
        android:id="@+id/title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:ellipsize="end"
        android:maxLines="2"
        android:textSize="14sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toEndOf="@id/thumb"
        app:layout_constraintTop_toTopOf="parent" />
    
    <TextView
        android:id="@+id/time"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:textColor="#888888"
        android:textSize="12sp"
        app:layout_constraintStart_toEndOf="@id/thumb"
        app:layout_constraintTop_toBottomOf="@id/title" />
    
    <ImageView
        android:id="@+id/status_icon"
        android:layout_width="24dp"
        android:layout_height="24dp"
        android:src="@drawable/ic_cloud_upload"
        android:tint="@color/colorPrimary"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
    
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: 创建图标资源**

`res/drawable/ic_cloud_upload.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#000000"
        android:pathData="M19.35,10.04C18.67,6.59 15.64,4 12,4c-3.04,0 -5.63,1.99 -6.56,4.75C2.69,9.35 0.5,11.86 0.5,15c0,3.31 2.69,6 6,6h12.5c2.76,0 5 -2.24 5 -5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM14,13v4h-4v-4H7l5,-5 5,5h-3z" />
</vector>
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/download/DownloadAdapter.java app/src/main/res/layout/item_download_remote.xml app/src/main/res/drawable/ic_cloud_upload.xml
git commit -m "feat: add remote push item support in download adapter"
```

---

## Task 8: GalleryListScene - 显示已推送图标

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java`
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/adapter/GalleryAdapter.java`

- [ ] **Step 1: 在 GalleryAdapter 中添加已推送图标判断**

```java
// 添加字段
private Set<Long> mRemotePushedGids = new HashSet<>();

// 添加方法：更新已推送 gid 列表
public void updateRemotePushedGids(Set<Long> gids) {
    mRemotePushedGids = gids;
    notifyDataSetChanged();
}

// 检查是否已推送
private boolean isRemotePushed(long gid) {
    return mRemotePushedGids.contains(gid);
}

// 在 onBindViewHolder 中显示已推送图标
@Override
public void onBindViewHolder(ViewHolder holder, int position) {
    // 原有逻辑...
    
    // 添加已推送图标显示
    if (isRemotePushed(info.gid)) {
        holder.mRemoteIcon.setVisibility(View.VISIBLE);
    } else {
        holder.mRemoteIcon.setVisibility(View.GONE);
    }
}
```

- [ ] **Step 2: 加载已推送 gid 列表**

```java
// 在 GalleryListScene 中添加方法
private void loadRemotePushedGids() {
    new Thread(() -> {
        try {
            DaoSession daoSession = EhDB.getDaoSession();
            RemotePushInfoDao dao = daoSession.getRemotePushInfoDao();
            List<RemotePushInfo> list = dao.queryBuilder()
                    .list();
            
            Set<Long> gids = new HashSet<>();
            for (RemotePushInfo info : list) {
                gids.add(info.getGid());
            }
            
            getActivity().runOnUiThread(() -> {
                if (mGalleryAdapter != null) {
                    mGalleryAdapter.updateRemotePushedGids(gids);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }).start();
}

// 在 onResume 中调用
@Override
public void onResume() {
    super.onResume();
    loadRemotePushedGids();
}
```

- [ ] **Step 3: 添加远程图标到列表项布局**

在 `res/layout/gallery_list_item.xml`（或相应布局）中添加：

```xml
<ImageView
    android:id="@+id/remote_icon"
    android:layout_width="16dp"
    android:layout_height="16dp"
    android:src="@drawable/ic_cloud_upload"
    android:visibility="gone"
    app:tint="@color/colorPrimary"
    <!-- 调整位置到右下角，与已下载图标并列 -->
    />
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java app/src/main/java/com/hippo/ehviewer/ui/adapter/GalleryAdapter.java app/src/main/res/layout/
git commit -m "feat: show remote pushed icon in gallery list"
```

---

## Task 9: RemotePushIconDrawable - 自定义图标

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/widget/RemotePushIconDrawable.java`

- [ ] **Step 1: 创建 RemotePushIconDrawable**

```java
package com.hippo.ehviewer.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 远程推送图标 - 云朵 + 上传箭头
 */
public class RemotePushIconDrawable extends Drawable {
    
    private final Paint mPaint;
    private final Path mCloudPath;
    private final Path mArrowPath;
    private final int mSize;
    
    public RemotePushIconDrawable(int color, int size) {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        
        mSize = size;
        mCloudPath = new Path();
        mArrowPath = new Path();
        
        createPaths();
    }
    
    private void createPaths() {
        float scale = mSize / 24f;
        
        // 云朵路径
        mCloudPath.moveTo(6f * scale, 18f * scale);
        mCloudPath.cubicTo(4f * scale, 18f * scale, 2f * scale, 16f * scale, 2f * scale, 13.5f * scale);
        mCloudPath.cubicTo(2f * scale, 11f * scale, 4f * scale, 9f * scale, 6.5f * scale, 9f * scale);
        mCloudPath.cubicTo(7f * scale, 6f * scale, 10f * scale, 4f * scale, 13f * scale, 4f * scale);
        mCloudPath.cubicTo(17f * scale, 4f * scale, 20f * scale, 7f * scale, 20f * scale, 11f * scale);
        mCloudPath.cubicTo(22f * scale, 11.5f * scale, 22f * scale, 14f * scale, 20f * scale, 16f * scale);
        mCloudPath.cubicTo(19f * scale, 18f * scale, 17f * scale, 18f * scale, 17f * scale, 18f * scale);
        mCloudPath.lineTo(6f * scale, 18f * scale);
        
        // 上传箭头路径
        mArrowPath.moveTo(12f * scale, 16f * scale);
        mArrowPath.lineTo(12f * scale, 8f * scale);
        mArrowPath.moveTo(9f * scale, 11f * scale);
        mArrowPath.lineTo(12f * scale, 8f * scale);
        mArrowPath.lineTo(15f * scale, 11f * scale);
    }
    
    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawPath(mCloudPath, mPaint);
        
        mPaint.setStrokeWidth(1.5f);
        mPaint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(mArrowPath, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }
    
    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }
    
    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }
    
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
    
    @Override
    public int getIntrinsicWidth() {
        return mSize;
    }
    
    @Override
    public int getIntrinsicHeight() {
        return mSize;
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/widget/RemotePushIconDrawable.java
git commit -m "feat: add RemotePushIconDrawable for custom cloud icon"
```

---

## Task 10: EhDB - 添加远程推送信息操作

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/EhDB.java`

- [ ] **Step 1: 添加远程推送信息相关方法**

```java
// 添加 RemotePushInfoDao 访问方法
public static RemotePushInfoDao getRemotePushInfoDao() {
    return sDaoSession.getRemotePushInfoDao();
}

// 添加远程推送信息查询
public static List<RemotePushInfo> getAllRemotePushInfo() {
    return getRemotePushInfoDao().queryBuilder()
            .orderDesc(RemotePushInfoDao.Properties.PushTime)
            .list();
}

// 检查是否已推送
public static boolean isRemotePushed(long gid) {
    return getRemotePushInfoDao().queryBuilder()
            .where(RemotePushInfoDao.Properties.Gid.eq(gid))
            .count() > 0;
}

// 删除远程推送记录
public static void deleteRemotePushInfo(long gid) {
    getRemotePushInfoDao().queryBuilder()
            .where(RemotePushInfoDao.Properties.Gid.eq(gid))
            .buildDelete()
            .executeDeleteWithoutDetachingEntities();
}

// 获取所有已推送 gid 集合
public static Set<Long> getRemotePushedGids() {
    List<RemotePushInfo> list = getAllRemotePushInfo();
    Set<Long> gids = new HashSet<>();
    for (RemotePushInfo info : list) {
        gids.add(info.getGid());
    }
    return gids;
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/hippo/ehviewer/EhDB.java
git commit -m "feat: add remote push info database operations"
```

---

## Task 11: 集成测试与调试

**Files:**
- None (manual testing)

- [ ] **Step 1: 构建 Debug APK**

```bash
cd F:\projects\android\Ehviewer_CN_SXJ
./gradlew app:assembleDebug
```

- [ ] **Step 2: 安装到设备测试**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: 测试功能点**

1. 本地下载模式正常工作（无回归）
2. 切换到远程模式，配置 NAS 地址和 Token
3. 测试连接功能
4. Cookie 同步功能
5. 推送下载任务
6. 画廊列表显示已推送图标
7. 下载列表显示远程记录
8. 重新推送功能

- [ ] **Step 4: 修复发现的问题**

如发现问题，逐一修复并提交。

---

## 自审查

**1. Spec 覆盖检查:**
- ✓ 1.1 RemotePushInfo 表 - 完成
- ✓ 1.2 设置界面 - 完成（RemoteDownloadSettingsFragment）
- ✓ 1.3 下载触发逻辑 - 完成（CommonOperations 分流）
- ✓ 1.4 画廊列表显示 - 完成（GalleryAdapter 已推送图标）
- ✓ 1.5 下载列表页 - 完成（DownloadsScene 筛选器）
- ✓ 1.6 API 请求格式 - 完成（RemoteDownloadClient）

**2. 占位符扫描:**
- 无 TBD、TODO 等占位符

**3. 类型一致性:**
- RemotePushInfo 字段与 Java/Kotlin 代码一致
- Settings 键名一致

**4. 依赖检查:**
- 需要添加 kotlinx-coroutines-android 依赖（如果尚未添加）

---

## 后续工作

完成本计划后，App 端可与 NAS 端 API 对接。建议按以下顺序执行：

1. **NAS 端先部署**：确保 API 可访问
2. **App 端开发**：按本计划实现
3. **集成测试**：端到端功能验证
4. **发布**：Release 版本构建

---

**Plan complete.** Execute with `superpowers:executing-plans` or `superpowers:subagent-driven-development`.
