# 画廊归档状态查询与显示

## 概述

在远程下载模式下，画廊列表加载一批画廊后，查询 NAS ClickHouse 中 `lai.ehentai` 表，判断哪些 gid 已有归档记录。如果画廊在 NAS 上有归档或已推送记录，在画廊右下角显示云朵图标。

## 目标

1. 用户开启远程下载模式后，能看到画廊是否已在 NAS 上有归档
2. 不全量同步存量数据，采用批量查询方式
3. 内存缓存避免重复请求，刷新后清空缓存
4. 远程查询失败不影响正常浏览体验

## 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        GalleryListScene                              │
│                                                                      │
│  ┌─────────────────┐    ┌──────────────────┐    ┌────────────────┐ │
│  │ onGetGalleryList│───►│ ArchiveStatus    │───►│ Adapter 判断   │ │
│  │ Success()       │    │ Query Flow       │    │ 显示图标       │ │
│  └─────────────────┘    └──────────────────┘    └────────────────┘ │
│                                 │                                    │
│                    ┌────────────┴────────────┐                       │
│                    │                         │                       │
│            ┌───────▼───────┐         ┌───────▼───────┐              │
│            │ RemotePushInfo│         │ ClickHouse    │              │
│            │ (本地查询)     │         │ (远程查询)    │              │
│            └───────────────┘         └───────────────┘              │
│                                              │                      │
└──────────────────────────────────────────────│──────────────────────┘
                                               │
                               ┌───────────────▼───────────────┐
                               │         NAS API               │
                               │  POST /api/v1/ehentai/        │
                               │       batch-check             │
                               │                               │
                               │  ┌─────────────────────────┐  │
                               │  │     ClickHouse          │  │
                               │  │  lai.ehentai 表         │  │
                               │  │  is_local = 1           │  │
                               │  └─────────────────────────┘  │
                               └───────────────────────────────┘
```

---

## 一、触发条件

功能仅在以下条件满足时生效：

1. **远程下载模式开启**：`Settings.getDownloadMode() == DOWNLOAD_MODE_REMOTE`
2. **NAS 配置有效**：地址和 Token 非空（查询时会检查）

---

## 二、图标显示规则

| 状态 | 图标 | 说明 |
|------|------|------|
| 本地已下载 | 下载箭头 `v_download_x24` | `DownloadManager.containDownloadInfo(gid)` |
| 已推送 OR 已归档 | 云朵 `v_cloud_primary_x24` | `RemotePushInfo` 有记录 OR ClickHouse 有记录 |
| 以上都不满足 | 不显示图标 | |

**优先级：** 本地下载 > 远程状态（已推送/已归档）

---

## 三、NAS API 设计

### POST /api/v1/ehentai/batch-check

**描述：** 批量查询 gid 是否在 ClickHouse `lai.ehentai` 表中有归档记录

**请求：**

```http
POST /api/v1/ehentai/batch-check
Headers:
  Authorization: Bearer {token}
  Content-Type: application/json

Body:
{
  "gids": [123456, 123457, 123458]
}
```

**响应：**

```json
{
  "existing_gids": [123456, 123458]
}
```

**说明：**

- `existing_gids`：在 ClickHouse 中存在记录（`is_local = 1`）的 gid 列表
- 单次请求 gid 数量限制：建议不超过 100 个
- 认证失败：返回 401
- 服务异常：返回 500

**ClickHouse 查询 SQL：**

```sql
SELECT gid FROM lai.ehentai
WHERE gid IN (123456, 123457, 123458)
AND is_local = 1;
```

---

## 四、App 端设计

### 4.1 新增文件

| 文件路径 | 说明 |
|---------|------|
| `app/src/main/java/com/hippo/ehviewer/client/ArchiveStatusCache.java` | 内存缓存类，存储已归档的 gid 集合 |

### 4.2 修改文件

| 文件路径 | 改动说明 |
|---------|---------|
| `app/src/main/java/com/hippo/ehviewer/client/RemoteDownloadClient.kt` | 新增 `batchCheckArchived` 方法 |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryListScene.java` | 在 `onGetGalleryListSuccess` 中触发归档状态查询 |
| `app/src/main/java/com/hippo/ehviewer/ui/scene/gallery/list/GalleryAdapterNew.java` | 合并判断逻辑，接收 `ArchiveStatusCache` |

### 4.3 ArchiveStatusCache 类设计

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

### 4.4 RemoteDownloadClient 新增方法

```kotlin
sealed class BatchCheckResult {
    data class Success(val existingGids: Set<Long>) : BatchCheckResult()
    data class Error(val message: String) : BatchCheckResult()
}

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

/**
 * 阻塞版本，供 Java 调用
 */
@JvmStatic
fun batchCheckArchivedBlocking(gids: List<Long>): BatchCheckResult {
    return runBlocking { batchCheckArchived(gids) }
}
```

### 4.5 查询流程

```
GalleryListScene.onGetGalleryListSuccess(result)
    │
    ├── 1. 检查是否远程下载模式
    │       Settings.getDownloadMode() == DOWNLOAD_MODE_REMOTE ?
    │       └── 否 → 跳过后续步骤
    │
    ├── 2. 检查 NAS 配置是否有效
    │       address/token 非空 ?
    │       └── 否 → 跳过远程查询
    │
    ├── 3. 收集当前页所有 gid
    │       result.galleryInfoList.map { it.gid }
    │
    ├── 4. 查询本地 RemotePushInfo（同步）
    │       已推送的 gid 集合
    │
    ├── 5. 过滤出未推送的 gid
    │       gids - remotePushedGids
    │
    ├── 6. 调用 NAS API 批量查询（异步，executorService）
    │       RemoteDownloadClient.batchCheckArchivedBlocking(unpushedGids)
    │
    ├── 7. 结果存入 ArchiveStatusCache
    │       cache.addAll(result.existingGids)
    │
    └── 8. 通知适配器刷新（Handler.post）
            mAdapter.notifyDataSetChanged()
```

### 4.6 GalleryAdapterNew 改动

**接收 ArchiveStatusCache：**

```java
// 构造函数新增参数
private ArchiveStatusCache mArchiveStatusCache;

public GalleryAdapterNew(..., ArchiveStatusCache archiveStatusCache) {
    // ...
    mArchiveStatusCache = archiveStatusCache;
}
```

**onBindViewHolder 改动：**

```java
// 原逻辑（第266-276行）
boolean isLocalDownloaded = mDownloadManager.containDownloadInfo(gi.gid);
boolean isRemotePushed = !isLocalDownloaded && EhDB.isRemotePushed(gi.gid);
if (isLocalDownloaded) {
    holder.downloaded.setVisibility(View.VISIBLE);
} else if (isRemotePushed) {
    holder.downloaded.setVisibility(View.VISIBLE);
    holder.downloaded.setImageResource(R.drawable.v_cloud_primary_x24);
} else {
    holder.downloaded.setVisibility(View.GONE);
}

// 改动后
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

---

## 五、错误处理

### 5.1 异常场景处理

| 异常场景 | 处理方式 |
|---------|---------|
| NAS 未配置（地址或 Token 为空） | 跳过远程查询，仅使用本地 RemotePushInfo 判断 |
| 网络请求失败（连接超时、无法连接） | 跳过远程查询，不影响列表显示 |
| API 返回 401（Token 无效） | 跳过远程查询，打印日志 |
| API 返回 500（服务异常） | 跳过远程查询，打印日志 |
| 返回结果解析失败 | 跳过远程查询，打印日志 |

**原则：** 远程查询失败不应影响用户正常浏览画廊列表，降级为仅使用本地数据判断。

### 5.2 性能优化

| 场景 | 处理方式 |
|-----|---------|
| gid 数量超过 API 单次限制（100） | 分批请求 |
| 用户快速滑动翻页 | 只查询当前显示页，不累积请求 |
| 缓存命中 | 直接使用缓存，不重复请求 |

---

## 六、缓存生命周期

| 时机 | 操作 |
|-----|------|
| `GalleryListScene.onCreateView2()` | 创建 `ArchiveStatusCache` 实例 |
| `GalleryListScene.onDestroyView()` | 清空缓存 |
| 用户主动刷新列表 | 清空缓存后重新查询 |

**不持久化：** 仅内存缓存，不写入 SharedPreferences 或数据库。

---

## 七、测试要点

### 7.1 App 端测试场景

| 场景 | 预期结果 |
|-----|---------|
| 未开启远程下载模式 | 不触发远程查询，图标仅显示本地下载状态 |
| 远程下载模式开启，NAS 未配置 | 跳过远程查询，图标显示本地下载 + RemotePushInfo 状态 |
| 远程下载模式开启，NAS 配置有效 | 正常查询 ClickHouse，正确显示归档状态 |
| 同一 gid 多次出现在列表中 | 第二次命中缓存，不重复请求 |
| 网络断开时浏览列表 | 降级为本地判断，不崩溃 |
| 快速滑动多页 | 各页独立查询，不阻塞 UI |
| 主动刷新列表 | 缓存清空，重新查询 |

### 7.2 NAS API 测试场景

| 场景 | 预期结果 |
|-----|---------|
| 空 gid 列表 | 返回空 existing_gids |
| 部分 gid 有归档记录 | 返回匹配的 gid 列表 |
| 全部 gid 无归档记录 | 返回空 existing_gids |
| 无效 Token | 返回 401 |
| ClickHouse 连接失败 | 返回 500 |

---

## 八、实现计划分离说明

本 spec 文档包含完整的系统设计，但实现计划分为两部分：

1. **App 端实现计划** — 在当前项目中执行
2. **NAS API 端实现计划** — 在 NAS eh-downloader 项目中执行

两个实现计划将分别生成，便于在不同项目/仓库中独立实现。