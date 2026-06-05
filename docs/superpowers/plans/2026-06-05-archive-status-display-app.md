# 画廊归档状态查询 - App 端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在远程下载模式下，画廊列表加载后查询 NAS ClickHouse 已归档 gid，并在列表项右下角显示云朵图标。

**Architecture:** 
- 新增 `ArchiveStatusCache` 内存缓存类存储已归档 gid
- `RemoteDownloadClient` 新增 `batchCheckArchived` API 方法
- `GalleryListScene` 在数据加载成功后触发异步查询
- `GalleryAdapterNew` 合并本地下载/已推送/已归档判断逻辑

**Tech Stack:** Java/Kotlin, OkHttp, coroutines, RecyclerView Adapter

---

## File Structure

| 文件 | 操作 | 责任 |
|------|------|------|
| `app/src/main/java/com/hippo/ehviewer/client/ArchiveStatusCache.java` | 创建 | 内存缓存：已归档 gid 集合 |
| `app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt` | 修改 | 新增 `batchCheckArchived` 方法 |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryAdapterNew.java` | 修改 | 接收 cache，合并判断逻辑 |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java` | 修改 | 创建 cache，触发查询 |

---

## Task 1: 创建 ArchiveStatusCache 类

**Files:**
- Create: `app/src/main/java/com/hippo/ehviewer/client/ArchiveStatusCache.java`

- [ ] **Step 1: 创建 ArchiveStatusCache.java 文件**

```java
package com.hippo.ehviewer.client;

import java.util.HashSet;
import java.util.Set;

/**
 * 内存缓存：已归档到 NAS 的 gid 集合
 * 生命周期：随 GalleryListScene 销毁而清除
 */
public class ArchiveStatusCache {
    private final Set<Long> archivedGids = new HashSet<>();

    /**
     * 添加已归档的 gid 集合
     */
    public void addAll(Set<Long> gids) {
        if (gids != null) {
            archivedGids.addAll(gids);
        }
    }

    /**
     * 检查 gid 是否已归档
     */
    public boolean contains(long gid) {
        return archivedGids.contains(gid);
    }

    /**
     * 清空缓存（刷新列表时调用）
     */
    public void clear() {
        archivedGids.clear();
    }
}
```

- [ ] **Step 2: 验证文件创建成功**

Run: `ls app/src/main/java/com/hippo/ehviewer/client/ArchiveStatusCache.java`
Expected: 文件存在

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/client/ArchiveStatusCache.java
git commit -m "feat: add ArchiveStatusCache for storing archived gid set"
```

---

## Task 2: RemoteDownloadClient 新增 batchCheckArchived 方法

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt`

- [ ] **Step 1: 在 RemoteDownloadClient.kt 中新增 BatchCheckResult sealed class**

在 `RemoteDownloadClient.kt` 文件第 44 行（`sealed class TestResult` 之后）添加：

```kotlin
    sealed class BatchCheckResult {
        data class Success(val existingGids: Set<Long>) : BatchCheckResult()
        data class Error(val message: String) : BatchCheckResult()
    }
```

- [ ] **Step 2: 新增 batchCheckArchived suspend 函数**

在 `RemoteDownloadClient.kt` 文件第 232 行（`testConnection` 方法之后）添加：

```kotlin
    /**
     * 批量查询 gid 是否在 ClickHouse 中有归档记录
     */
    suspend fun batchCheckArchived(gids: List<Long>): BatchCheckResult = withContext(Dispatchers.IO) {
        val address = Settings.getRemoteNasAddress()
        val port = Settings.getRemoteNasPort()
        val token = Settings.getRemoteApiToken()

        if (address.isBlank() || token.isBlank() || gids.isEmpty()) {
            return@withContext BatchCheckResult.Error("远程下载未配置或 gid 列表为空")
        }

        val url = "http://$address:$port/api/v1/ehentai/batch-check"

        val body = JSONObject().apply {
            put("gids", gids)
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
                    401 -> BatchCheckResult.Error("API Token 无效")
                    else -> BatchCheckResult.Error("请求失败: ${response.code()}")
                }
            }

            val responseBody = response.body()?.string()
                ?: return@withContext BatchCheckResult.Error("响应为空")

            val json = JSONObject(responseBody)
            val existingGidsArray = json.optJSONArray("existing_gids")
            val existingGids = existingGidsArray?.let { arr ->
                (0 until arr.length()).map { arr.getLong(it) }.toSet()
            } ?: emptySet()

            BatchCheckResult.Success(existingGids)

        } catch (e: IOException) {
            BatchCheckResult.Error("无法连接 NAS: ${e.message}")
        }
    }
```

- [ ] **Step 3: 新增 batchCheckArchivedBlocking 阻塞方法供 Java 调用**

紧接着上一步添加的代码后，添加：

```kotlin
    /**
     * 批量查询 gid 是否在 ClickHouse 中有归档记录（阻塞方法，供 Java 调用）
     */
    @JvmStatic
    fun batchCheckArchivedBlocking(gids: List<Long>): BatchCheckResult {
        return runBlocking { batchCheckArchived(gids) }
    }
```

- [ ] **Step 4: 验证编译**

Run: `./gradlew app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（无编译错误）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt
git commit -m "feat: add batchCheckArchived API for querying ClickHouse archived gids"
```

---

## Task 3: GalleryAdapterNew 接收 ArchiveStatusCache 并修改判断逻辑

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryAdapterNew.java`

- [ ] **Step 1: 添加 ArchiveStatusCache 成员变量和构造函数参数**

在 `GalleryAdapterNew.java` 第 86 行（`private DownloadManager mDownloadManager;` 之后）添加：

```java
    private ArchiveStatusCache mArchiveStatusCache;
```

修改构造函数（第 91-113 行），添加 `ArchiveStatusCache` 参数：

```java
    public GalleryAdapterNew(@NonNull LayoutInflater inflater, @NonNull Resources resources,
                             @NonNull RecyclerView recyclerView, int type, boolean showFavourited, 
                             ExecutorService executor, boolean showReadProgress,
                             ArchiveStatusCache archiveStatusCache) {
        this.executor = executor;
        this.showReadProgress = showReadProgress;
        mInflater = inflater;
        mResources = resources;
        mRecyclerView = recyclerView;
        mLayoutManager = new AutoStaggeredGridLayoutManager(0, StaggeredGridLayoutManager.VERTICAL);
        mPaddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar);
        mShowFavourite = showFavourited;
        mArchiveStatusCache = archiveStatusCache;

        mRecyclerView.setAdapter(this);
        mRecyclerView.setLayoutManager(mLayoutManager);

        View calculator = inflater.inflate(R.layout.item_gallery_list_thumb_height, null);
        ViewUtils.measureView(calculator, 1024, ViewGroup.LayoutParams.WRAP_CONTENT);
        mListThumbHeight = calculator.getMeasuredHeight();
        mListThumbWidth = mListThumbHeight * 2 / 3;

        setType(type);

        mDownloadManager = EhApplication.getDownloadManager(inflater.getContext());
    }
```

- [ ] **Step 2: 修改 onBindViewHolder 中的图标判断逻辑**

找到 `onBindViewHolder` 方法中处理 `downloaded` 图标的代码（约第 266-276 行），替换为：

```java
                boolean isLocalDownloaded = mDownloadManager.containDownloadInfo(gi.gid);
                boolean isRemotePushed = EhDB.isRemotePushed(gi.gid);
                boolean isArchived = mArchiveStatusCache != null && mArchiveStatusCache.contains(gi.gid);

                if (isLocalDownloaded) {
                    holder.downloaded.setVisibility(View.VISIBLE);
                    holder.downloaded.setImageResource(R.drawable.v_download_x24);
                } else if (isRemotePushed || isArchived) {
                    holder.downloaded.setVisibility(View.VISIBLE);
                    holder.downloaded.setImageResource(R.drawable.v_cloud_primary_x24);
                } else {
                    holder.downloaded.setVisibility(View.GONE);
                }
```

- [ ] **Step 3: 添加 import 语句**

在文件顶部 import 区域添加：

```java
import com.hippo.ehviewer.client.ArchiveStatusCache;
```

- [ ] **Step 4: 验证编译**

Run: `./gradlew app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL（无编译错误）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryAdapterNew.java
git commit -m "feat: GalleryAdapterNew accepts ArchiveStatusCache and merges download status logic"
```

---

## Task 4: GalleryListScene 创建缓存并触发查询

**Files:**
- Modify: `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java`

- [ ] **Step 1: 添加 ArchiveStatusCache 成员变量**

在 `GalleryListScene.java` 第 297 行（`private FavouriteStatusRouter.Listener mFavouriteStatusRouterListener;` 之后）添加：

```java
    @Nullable
    private ArchiveStatusCache mArchiveStatusCache;
```

- [ ] **Step 2: 在 onCreateView2 中创建 ArchiveStatusCache 并传递给 Adapter**

找到 `onCreateView2` 方法中创建 `GalleryListAdapter` 的代码（约第 668-669 行）：

```java
        mAdapter = new GalleryListAdapter(inflater, resources,
                mRecyclerView, Settings.getListMode());
```

修改为：

```java
        mArchiveStatusCache = new ArchiveStatusCache();
        mAdapter = new GalleryListAdapter(inflater, resources,
                mRecyclerView, Settings.getListMode(), mArchiveStatusCache);
```

- [ ] **Step 3: 修改 GalleryListAdapter 内部类构造函数**

找到 `GalleryListAdapter` 内部类（约第 2069-2087 行），修改构造函数：

```java
    private class GalleryListAdapter extends GalleryAdapterNew {

        public GalleryListAdapter(@NonNull LayoutInflater inflater,
                                  @NonNull Resources resources, @NonNull RecyclerView recyclerView, 
                                  int type, ArchiveStatusCache archiveStatusCache) {
            super(inflater, resources, recyclerView, type, true, executorService, showReadProgress, archiveStatusCache);
        }

        @Override
        public int getItemCount() {
            return null != mHelper ? mHelper.size() : 0;
        }

        @Nullable
        @Override
        public GalleryInfo getDataAt(int position) {
            return null != mHelper ? mHelper.getDataAtEx(position) : null;
        }

    }
```

- [ ] **Step 4: 在 onDestroyView 中清空缓存**

在 `onDestroyView` 方法（约第 918-951 行）中，在 `mAdapter = null;` 之前添加：

```java
        if (null != mArchiveStatusCache) {
            mArchiveStatusCache.clear();
            mArchiveStatusCache = null;
        }
```

- [ ] **Step 5: 在 onGetGalleryListSuccess 中触发归档状态查询**

修改 `onGetGalleryListSuccess` 方法（约第 1961-1977 行）：

```java
    private void onGetGalleryListSuccess(GalleryListParser.Result result, int taskId) {
        if (mHelper != null && mSearchBarMover != null &&
                mHelper.isCurrentTask(taskId)) {
            String emptyString;
            if (result.customErrorString == null) {
                emptyString = getResources2().getString(mUrlBuilder.getMode() == ListUrlBuilder.MODE_SUBSCRIPTION && result.noWatchedTags
                        ? R.string.gallery_list_empty_hit_subscription
                        : R.string.gallery_list_empty_hit);
            } else {
                emptyString = result.customErrorString;
            }

            mHelper.setEmptyString(emptyString);
            mHelper.onGetPageData(taskId, result, result.galleryInfoList);

            // 触发归档状态查询（仅在远程下载模式）
            queryArchiveStatus(result.galleryInfoList);
        }
    }
```

- [ ] **Step 6: 添加 queryArchiveStatus 方法**

在 `onGetGalleryListFailure` 方法之后添加：

```java
    /**
     * 查询画廊归档状态（仅在远程下载模式下）
     */
    private void queryArchiveStatus(List<GalleryInfo> galleryInfoList) {
        // 检查是否远程下载模式
        if (!Settings.DOWNLOAD_MODE_REMOTE.equals(Settings.getDownloadMode())) {
            return;
        }

        // 检查 NAS 配置
        String address = Settings.getRemoteNasAddress();
        String token = Settings.getRemoteApiToken();
        if (address == null || address.isEmpty() || token == null || token.isEmpty()) {
            return;
        }

        // 检查缓存和列表是否有效
        if (mArchiveStatusCache == null || galleryInfoList == null || galleryInfoList.isEmpty()) {
            return;
        }

        // 收集当前页所有 gid
        List<Long> allGids = new ArrayList<>();
        for (GalleryInfo gi : galleryInfoList) {
            allGids.add(gi.gid);
        }

        // 过滤出未在 RemotePushInfo 中的 gid
        List<Long> unpushedGids = new ArrayList<>();
        for (Long gid : allGids) {
            if (!EhDB.isRemotePushed(gid)) {
                unpushedGids.add(gid);
            }
        }

        if (unpushedGids.isEmpty()) {
            return;
        }

        // 异步查询 NAS
        executorService.submit(() -> {
            try {
                RemoteDownloadClient.BatchCheckResult result = 
                    RemoteDownloadClient.batchCheckArchivedBlocking(unpushedGids);

                if (result instanceof RemoteDownloadClient.BatchCheckResult.Success) {
                    Set<Long> existingGids = ((RemoteDownloadClient.BatchCheckResult.Success) result).getExistingGids();
                    
                    // 存入缓存
                    mArchiveStatusCache.addAll(existingGids);

                    // 通知适配器刷新（主线程）
                    if (mAdapter != null && mRecyclerView != null) {
                        mRecyclerView.post(() -> {
                            if (mAdapter != null) {
                                mAdapter.notifyDataSetChanged();
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "queryArchiveStatus failed: " + e.getMessage());
            }
        });
    }
```

- [ ] **Step 7: 在 GalleryListHelper.refresh 时清空缓存**

找到 `GalleryListHelper` 类中的 `getPageData` 方法，需要在刷新时清空缓存。但更简单的方式是在调用 `mHelper.refresh()` 前清空。

找到现有的 refresh 调用位置，在调用前添加缓存清空。主要位置：
- `onNewArguments` 方法（约第 334-337 行）
- `onTagClick` 方法（约第 854 行）

在 `mHelper.refresh()` 调用前添加：

```java
                if (mArchiveStatusCache != null) {
                    mArchiveStatusCache.clear();
                }
                mHelper.refresh();
```

需要修改多处，统一处理。

- [ ] **Step 8: 添加 import 语句**

在文件顶部 import 区域添加：

```java
import com.hippo.ehviewer.client.ArchiveStatusCache;
import com.hippo.ehviewer.client.RemoteDownloadClient;
import java.util.ArrayList;
import java.util.Set;
```

- [ ] **Step 9: 验证编译**

Run: `./gradlew app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL（无编译错误）

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java
git commit -m "feat: GalleryListScene triggers archive status query in remote download mode"
```

---

## Task 5: 验证完整功能

- [ ] **Step 1: 构建 debug APK**

Run: `./gradlew app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 安装 APK 进行手动测试**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: 安装成功

- [ ] **Step 3: 手动测试场景**

测试清单：
1. 未开启远程下载模式 → 列表正常显示，不触发远程查询
2. 开启远程下载模式但 NAS 未配置 → 列表正常显示，仅显示本地下载/已推送状态
3. 开启远程下载模式且 NAS 配置有效 → 云朵图标正确显示已归档画廊
4. 刷新列表 → 缓存清空，重新查询
5. 网络断开时浏览 → 不崩溃，降级为本地判断

- [ ] **Step 4: 最终 Commit（如有遗漏）**

```bash
git status
# 如有未提交的改动，补充提交
```

---

## Summary

本实现计划完成以下改动：

1. **新增 ArchiveStatusCache** — 内存缓存已归档 gid
2. **RemoteDownloadClient 新增 API** — batchCheckArchived 方法
3. **GalleryAdapterNew 修改** — 合并三种状态判断
4. **GalleryListScene 修改** — 创建缓存、触发异步查询、刷新时清空

**注意事项：**
- NAS API 端实现需在另一个项目中完成（见 NAS API 实现计划）
- 远程查询失败不影响正常浏览
- 缓存仅在会话级别，不持久化