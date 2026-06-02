# EhViewer 远程下载功能设计文档

## 概述

为 EhViewer App 添加远程下载功能，允许用户将下载任务推送到 NAS 端执行，支持代理轮换、自动去重和 Web 管理界面。

## 目标

1. App 端最小改动，通过 API 与 NAS 通信
2. NAS 端独立部署 Docker 容器，复用现有 aria2
3. 支持多代理轮换，自动故障处理
4. 自动去重，避免重复下载
5. Web 界面管理下载任务

## 系统架构

```
┌─────────────────┐         ┌─────────────────────────────────────────────┐
│   EhViewer App  │         │                   NAS                        │
│                 │         │                                             │
│  ┌───────────┐  │  HTTP   │  ┌─────────────────────────────────────┐   │
│  │  设置界面  │◄─┼─────────┼─►│  EH Downloader Manager (Docker)     │   │
│  │ 远程配置   │  │         │  │                                     │   │
│  └───────────┘  │         │  │  ┌─────────────┐  ┌──────────────┐  │   │
│                 │         │  │  │ FastAPI     │  │ Vue 3 前端   │  │   │
│  ┌───────────┐  │         │  │  │ 后端        │  │ Element Plus │  │   │
│  │ 画廊列表   │  │         │  │  └──────┬──────┘  └──────────────┘  │   │
│  │ 已推送图标 │  │         │  │         │                         │   │
│  └───────────┘  │         │  │         ▼                         │   │
│                 │         │  │  ┌─────────────┐  ┌──────────────┐  │   │
│  ┌───────────┐  │         │  │  │ SQLite      │  │ 代理池管理   │  │   │
│  │ 下载列表   │  │         │  │  │ 任务状态    │  │ 状态追踪     │  │   │
│  │ 筛选器     │  │         │  │  └─────────────┘  └──────────────┘  │   │
│  └───────────┘  │         │  │                                     │   │
│                 │         │  └──────────────┬──────────────────────┘   │
└─────────────────┘         │                 │                          │
                            │                 ▼                          │
                            │         ┌─────────────┐                   │
                            │         │ aria2-pro   │                   │
                            │         │ (现有容器)   │                   │
                            │         └──────┬──────┘                   │
                            │                │                          │
                            │                ▼                          │
                            │    ┌─────────────────────────────────┐    │
                            │    │ trojan-sg-1 │ trojan-jp-1 │ ...  │    │
                            │    └─────────────────────────────────┘    │
                            └─────────────────────────────────────────────┘
```

---

## 一、App 端设计

### 1.1 数据模型

新增 `RemotePushInfo` 表（GreenDAO）：

| 字段        | 类型    | 说明           |
|------------|---------|---------------|
| gid        | Long    | 画廊 ID（主键）|
| token      | String  | 画廊 token     |
| title      | String  | 标题          |
| titleJpn   | String  | 日文标题       |
| thumb      | String  | 缩略图 URL     |
| category   | Int     | 分类          |
| posted     | String  | 发布时间       |
| uploader   | String  | 上传者         |
| rating     | Float   | 评分          |
| pushTime   | Long    | 推送时间戳     |

### 1.2 设置界面

在现有设置页面的"下载"分类下新增：

**下载模式选择：**
- 选项：`本地下载` / `远程下载`
- 默认：本地下载

**远程模式配置（选择远程后显示）：**
- NAS 地址（IP 或域名）
- API 端口（默认 8080）
- API Token
- 测试连接按钮

**Cookie 同步区域：**
- 显示当前 Cookie 同步状态：`已同步` / `未同步` / `同步失败`
- 显示上次同步时间
- `同步 Cookie 到 NAS` 按钮
  - 点击后发送当前 EH cookies 到 NAS
  - 显示同步进度（loading）
  - 成功：toast "同步成功"，更新状态和时间
  - 失败：toast "同步失败：{错误原因}"

### 1.2.1 Cookie 同步机制

**触发场景：**
1. **手动触发**：用户在设置页面点击"同步 Cookie 到 NAS"按钮
2. **自动触发（可选）**：
   - 用户登录成功后自动同步
   - App 启动时检测 NAS Cookie 过期，提示同步

**同步流程：**
```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│    App      │     │   NAS API    │     │   EH API       │
└──────┬──────┘     └──────┬───────┘     └────────┬────────┘
       │                    │                      │
       │ 1. 获取本地 Cookie │                      │
       │──► SharedPreferences│                     │
       │                    │                      │
       │ 2. PUT /api/v1/config/cookies             │
       │───────────────────►│                      │
       │                    │                      │
       │                    │ 3. 验证 Cookie       │
       │                    │─────────────────────►│
       │                    │                      │
       │                    │ 4. 返回用户信息       │
       │                    │◄─────────────────────│
       │                    │                      │
       │ 5. 返回结果        │                      │
       │◄───────────────────│                      │
       │ {valid: true}      │                      │
       │                    │                      │
       │ 6. 更新本地同步状态 │                      │
       │──► 记录同步时间    │                      │
       │                    │                      │
```

**本地状态记录（SharedPreferences）：**
```
remote_cookie_synced: boolean    # 是否已同步
remote_cookie_sync_time: long    # 上次同步时间戳
```

### 1.3 下载触发逻辑

**本地模式：**
- 走现有 SpiderQueen 流程（无变化）

**远程模式：**
1. 发送 POST 请求到 NAS API
2. 成功：显示 toast "已推送"，写入 RemotePushInfo 表
3. 失败：显示错误提示"无法连接 NAS"
4. **不**加入本地下载列表

### 1.4 画廊列表显示

在画廊列表项右下角新增"已推送"图标：
- 云朵 + 箭头图标
- 查询条件：RemotePushInfo 表中存在该 gid
- 与现有"已下载"图标并列显示

### 1.5 下载列表页

**顶部筛选器：**
- Tab 选项：`全部` | `本地` | `远程`
- 本地：查询 DownloadInfo 表
- 远程：查询 RemotePushInfo 表
- 全部：合并两个数据源，按时间排序

**列表项显示：**
- 本地下载：现有图标（下载箭头）
- 远程推送：新图标（云朵 + 箭头）

**点击远程记录弹窗：**
- 显示画廊基本信息
- 显示推送时间
- 操作按钮：`查看详情` | `重新推送`

### 1.6 API 请求格式

**推送下载请求：**
```
POST {nas_address}:{port}/api/v1/download
Headers:
  Authorization: Bearer {token}
  Content-Type: application/json

Body:
{
  "gid": 1234567,
  "token": "abc123",
  "title": "Gallery Title",
  "title_jpn": "ギャラリータイトル",
  "thumb": "https://...",
  "category": 2,
  "page_count": 200,
  "cookies": "ipb_member_id=xxx; ipb_pass_hash=xxx"
}

Response:
{
  "task_id": "uuid-xxx",
  "status": "queued" | "skipped",
  "skip_reason": "gid_duplicate" | "title_similar" | null
}
```

---

## 二、NAS 端后端设计

### 2.1 技术栈

- 运行环境：uv (Python 包管理)
- 后端框架：FastAPI
- 数据库：SQLite
- 下载引擎：aria2 (通过 JSON-RPC)
- 容器化：Docker

### 2.2 项目结构

```
eh-downloader/
├── pyproject.toml
├── uv.lock
├── Dockerfile
├── docker-compose.yml
├── app/
│   ├── __init__.py
│   ├── main.py                 # FastAPI 入口
│   ├── config.py               # 配置管理
│   ├── models/
│   │   ├── __init__.py
│   │   ├── task.py             # 任务模型
│   │   └── proxy.py            # 代理模型
│   ├── api/
│   │   ├── __init__.py
│   │   ├── download.py         # 下载相关 API
│   │   ├── task.py             # 任务管理 API
│   │   ├── proxy.py            # 代理状态 API
│   │   └── config.py           # 配置相关 API
│   ├── services/
│   │   ├── __init__.py
│   │   ├── downloader.py       # 下载服务（核心）
│   │   ├── aria2_client.py     # aria2 RPC 客户端
│   │   ├── eh_api.py           # EH API 调用
│   │   ├── proxy_pool.py       # 代理池管理
│   │   ├── dedup.py            # 去重服务
│   │   └── archiver.py         # 归档服务
│   └── db/
│       ├── __init__.py
│       └── database.py         # SQLite 操作
├── frontend/                   # Vue 3 前端
└── data/                       # 挂载目录
    ├── eh_downloader.db
    └── logs/
```

### 2.3 数据模型

**Task 表：**

| 字段          | 类型     | 说明                    |
|--------------|----------|-------------------------|
| id           | String   | 任务 ID（UUID）          |
| gid          | Long     | 画廊 ID                  |
| token        | String   | 画廊 token               |
| title        | String   | 标题                     |
| title_jpn    | String   | 日文标题                  |
| thumb        | String   | 缩略图 URL                |
| category     | Int      | 分类                     |
| page_count   | Int      | 总页数                   |
| status       | String   | queued/downloading/completed/failed/skipped |
| skip_reason  | String   | 跳过原因                 |
| skip_detail  | String   | 跳过详情（匹配的 gid 等） |
| progress     | Int      | 已下载页数               |
| current_proxy| String   | 当前使用的代理            |
| created_at   | DateTime | 创建时间                 |
| updated_at   | DateTime | 更新时间                 |
| push_from    | String   | 来源：app/manual         |

**ProxyState 表：**

| 字段           | 类型     | 说明              |
|---------------|----------|------------------|
| name          | String   | 代理名称（主键）    |
| status        | String   | active/paused     |
| download_count| Int      | 当前周期下载计数    |
| trigger_509_count | Int  | 累计触发 509 次数   |
| last_509_time | DateTime | 最后触发 509 时间   |
| last_used     | DateTime | 最后使用时间       |
| paused_until  | DateTime | 暂停截止时间        |
| last_rotation | DateTime | 上次轮换时间       |
| adaptive_threshold | Int | 动态调整的阈值     |

**Config 表：**

| 字段              | 类型     | 说明              |
|------------------|----------|------------------|
| key              | String   | 配置键（主键）     |
| value            | String   | 配置值            |
| updated_at       | DateTime | 更新时间          |

### 2.4 API 设计

**POST /api/v1/download**
- 描述：推送下载请求
- 认证：Bearer Token
- 请求体：见 1.6 节

**GET /api/v1/tasks**
- 描述：获取任务列表
- 参数：`status`, `page`, `size`

**GET /api/v1/tasks/{task_id}**
- 描述：获取单个任务详情

**POST /api/v1/tasks/{task_id}/retry**
- 描述：重试失败任务

**POST /api/v1/tasks/{task_id}/force-download**
- 描述：强制下载已跳过任务

**DELETE /api/v1/tasks/{task_id}**
- 描述：删除任务

**GET /api/v1/proxies**
- 描述：获取代理池状态

**POST /api/v1/proxies/{name}/toggle**
- 描述：启用/暂停代理

**POST /api/v1/archive**
- 描述：触发归档

**GET /api/v1/config**
- 描述：获取配置

**PUT /api/v1/config**
- 描述：更新配置

**PUT /api/v1/config/cookies**
- 描述：同步/更新 EH cookies
- 认证：Bearer Token
- 请求体：
```json
{
  "cookies": "ipb_member_id=xxx; ipb_pass_hash=xxx; igneous=xxx"
}
```
- 响应：
```json
{
  "valid": true,
  "message": "Cookie 验证成功",
  "username": "xxx"  // EH 用户名，验证成功时返回
}
```
- 错误响应：
```json
{
  "valid": false,
  "message": "Cookie 无效或已过期"
}
```

**GET /api/v1/config/cookies/status**
- 描述：获取 Cookie 状态
- 响应：
```json
{
  "has_cookies": true,
  "last_validated": "2025-05-31T10:00:00Z",
  "username": "xxx"
}
```

### 2.5 下载服务核心逻辑

```
DownloaderService:
  1. 后台线程轮询 queued 状态任务
  2. 执行深度去重检查
  3. 获取图片 URL 列表（调用 EH API）
  4. 逐张提交到 aria2 下载
  5. 监控下载进度
  6. 处理失败重试
```

### 2.6 目录规划

| 目录 | 位置 | 用途 |
|------|------|------|
| `/volume1/docker/eh-downloader/downloads/` | volume1 (SSD) | 下载临时目录 |
| `/volume1/docker/eh-downloader/data/` | volume1 (SSD) | 数据库、日志 |
| `/volume2/anime/doujinshi/series/lai1/` | volume2 (HDD) | 归档最终目录 |
| `/volume2/anime/doujinshi/duplicated/` | volume2 (HDD) | 重复文件目录 |

---

## 三、NAS 端前端设计

### 3.1 技术栈

- 框架：Vue 3 + Vite
- UI 组件库：Element Plus
- 构建产物放入后端静态目录

### 3.2 页面结构

**主导航：**
- 任务列表
- 代理状态
- 配置

**任务列表页：**
- 状态 Tab 筛选：全部 / 待下载 / 下载中 / 已完成 / 已跳过 / 失败
- 任务卡片：封面、标题、状态、进度、操作按钮
- 批量操作：删除、重试
- 归档按钮（顶部）

**代理状态面板：**
- 各代理状态、下载计数、暂停倒计时
- 手动暂停/启用按钮
- 重置计数按钮

**配置弹窗：**
- Cookies 编辑
- 相似度阈值配置（默认 0.1）
- API Token 管理

---

## 四、关键流程

### 4.1 完整下载流程

1. App 发送下载请求到 NAS API
2. NAS 快速检查 gid 是否已存在任务
3. 创建任务，返回 task_id
4. 后台调度器取出任务
5. 深度去重检查（查询 ClickHouse）
6. 获取图片 URL 列表（调用 EH API，使用代理）
7. 逐张提交到 aria2 下载
8. 监控进度，更新任务状态
9. 下载完成后等待归档

### 4.2 去重流程

**快速检查（API 调用时）：**
- 查询 SQLite Task 表，检查 gid 是否已存在

**深度检查（下载前）：**
1. 查询 ClickHouse：gid 重复检查
   - 如果 page_count <= 已有 filecount → 跳过（已有更完整版本）
   - 如果 page_count > 已有 filecount → **增量下载**：
     - 跳过已存在的页（检查归档目录中的图片）
     - 只下载缺少的页
     - 下载完成后更新 ClickHouse 的 filecount

2. 查询 ClickHouse：标题相似度检查
   - 使用 `ngramDistanceCaseInsensitiveUTF8` 函数
   - 阈值 0.1（可配置）
   - 如果相似且 page_count <= 已有 → 跳过
3. decensored 替换检查
   - 待下载包含 decensored，已下载不包含 → 替换下载

### 4.3 代理轮换流程

#### 背景：EH 递增惩罚机制

EH 对连续触发限频有递增惩罚：
- 首次触发 509：等待 5min
- 二次触发：等待 10min
- 三次触发：等待更长时间
- 持续触发会导致 IP 被废

因此需要**主动避让**而非被动响应。

#### 策略：预emptive 轮换 + 梯度冷却

**核心思想**：
- 在接近限频前主动切换代理
- 每个代理使用后需要足够"休息"时间
- 触发限频后采用梯度冷却，避免递增惩罚

#### 代理池配置

```yaml
proxies:
  - name: trojan-sg-1
    type: trojan
    server: www.ocfeee.site
    port: 1393
  - name: trojan-jp-1
    type: trojan
    server: www.coc.icu
    port: 443
  - name: trojan-hk-2
    type: trojan
    server: www.0111999.xyz
    port: 11210

# 注意：不使用 trojan-hk-1（有问题）
```

#### 轮换规则

**1. 预emptive 切换（主动避让）**
- 单代理下载阈值：**500 张**（可配置）
- 达到阈值前主动切换到下一个代理
- 不等触发 509 再切换

**2. 最小轮换间隔**
- 每个代理两次使用之间至少间隔 **10 分钟**
- 让 EH 有足够时间"遗忘"该 IP
- 避免"刚用完马上再用"触发递增惩罚

**3. 梯度冷却时间**
- 首次触发 509：暂停该代理 **1 小时**
- 二次触发 509：暂停该代理 **4 小时**
- 三次触发 509：暂停该代理 **12 小时**
- 四次及以上：暂停 **24 小时**

**4. 全局冷却检测**
- 如果所有代理都处于暂停状态
- 等待最早恢复的代理恢复后继续
- 不强行使用暂停中的代理

**5. 动态阈值调整**
- 监控每个代理的历史表现
- 如果某代理连续多次都在阈值内触发 509
- 自动降低该代理的阈值（如 300 张）

#### 配置参数

| 参数 | 默认值 | 说明 |
|-----|--------|------|
| PRE_SWITCH_THRESHOLD | 500 | 预emptive 切换阈值（张数）|
| MIN_ROTATION_INTERVAL | 600 | 最小轮换间隔（秒）|
| COOLDOWN_BASE | 3600 | 冷却基准时间（秒）|
| COOLDOWN_MULTIPLIER | 2 | 冷却时间倍率 |
| COOLDOWN_MAX | 86400 | 最大冷却时间（秒）|
| SLOW_DOWNLOAD_THRESHOLD | 10 | 慢速下载阈值（KB/s）|

#### 状态追踪

**ProxyState 表：**

| 字段                | 类型     | 说明                  |
|--------------------|----------|----------------------|
| name               | String   | 代理名称（主键）       |
| status             | String   | active/paused        |
| download_count     | Int      | 当前周期下载计数       |
| trigger_509_count  | Int      | 累计触发 509 次数      |
| last_509_time      | DateTime | 最后触发 509 时间      |
| last_used          | DateTime | 最后使用时间          |
| paused_until       | DateTime | 暂停截止时间          |
| last_rotation      | DateTime | 上次轮换时间          |
| adaptive_threshold | Int      | 动态调整的阈值         |

#### 流程示例

```
任务：下载 2000 页漫画

正常轮换：
┌────────────────────────────────────────────────────────────┐
│ 时间  │ 代理       │ 下载数 │ 状态变化                     │
├───────┼────────────┼────────┼──────────────────────────────┤
│ 00:00 │ trojan-sg-1│   500  │ 达到阈值，切换                 │
│ 00:05 │ trojan-jp-1│   500  │ 达到阈值，切换                 │
│ 00:10 │ trojan-hk-2│   500  │ 达到阈值，切换                 │
│ 00:15 │ trojan-sg-1│   500  │ 已过 10min 间隔，可以使用       │
│ 00:20 │ trojan-jp-1│    ?   │ 继续下载...                    │
└────────────────────────────────────────────────────────────┘

触发 509 的处理：
┌────────────────────────────────────────────────────────────┐
│ 时间  │ 代理       │ 事件           │ 处理                  │
├───────┼────────────┼────────────────┼──────────────────────┤
│ 00:08 │ trojan-hk-2│ 触发 509       │ 暂停 1 小时，切换代理  │
│       │            │ (第 480 张)    │ trigger_509_count=1   │
│ 00:08 │ trojan-sg-1│ 接管下载       │ 继续下载              │
│       │            │                │ trojan-hk-2 冷却中    │
│ 01:08 │ trojan-hk-2│ 冷却结束       │ 恢复可用，计数清零     │
│       │            │                │ adaptive_threshold=500│
└────────────────────────────────────────────────────────────┘

再次触发 509（递增惩罚）：
┌────────────────────────────────────────────────────────────┐
│ 时间  │ 代理       │ 事件           │ 处理                  │
├───────┼────────────┼────────────────┼──────────────────────┤
│ 01:15 │ trojan-hk-2│ 再次触发 509   │ 暂停 4 小时           │
│       │            │                │ trigger_509_count=2   │
│       │            │                │ adaptive_threshold=300│
└────────────────────────────────────────────────────────────┘
```

#### 故障处理

| 错误类型 | 处理方式 |
|---------|---------|
| 代理连接失败 | 暂停代理 1 小时，切换下一个 |
| 509 错误 | 梯度冷却（1h → 4h → 12h → 24h）|
| 其他 HTTP 错误 | 重试 3 次，仍失败则标记任务失败 |
| 图片不存在 | 不视为代理问题，标记单张失败 |
| 下载超时 | 可能是网络问题，暂停代理 30min |

### 4.5 aria2 任务失败处理

#### 问题场景

aria2 任务失败可能由以下原因导致：
- 代理连接失败（代理本身不可用）
- 代理被限频（509 错误）
- Cookie 过期（403 Forbidden）
- 图片 URL 失效（404 Not Found）
- 网络超时

#### 监控机制

**重要：aria2 重试配置**

当前 aria2 配置了无限重试（`max-tries=0`），这会加剧限频问题。需要：

1. **修改 aria2 配置**（通过管理服务动态设置）：
```
max-tries=1        # 只尝试 1 次，失败后立即停止
retry-wait=0       # 不等待
```

2. **重试逻辑由管理服务接管**：
- aria2 任务失败后，管理服务分析原因
- 根据错误类型决定是否切换代理重试
- 避免同一代理无限重试导致 IP 被废

**方案：轮询 + 错误码分析**

```
┌─────────────────────────────────────────────────────────┐
│                 DownloadMonitor（后台线程）               │
├─────────────────────────────────────────────────────────┤
│  每 30 秒执行：                                          │
│  1. 查询 SQLite 中所有 downloading 状态任务              │
│  2. 对每个任务，查询 aria2 任务状态                      │
│  3. 分析失败原因，决定处理策略                           │
│  4. 执行重试或切换代理                                   │
│                                                         │
│  关键：aria2 max-tries=1，重试由管理服务控制              │
└─────────────────────────────────────────────────────────┘
```

#### 错误类型识别与处理

| aria2 错误码 | HTTP 状态码 | 原因 | 处理策略 |
|--------------|------------|------|---------|
| 1 (错误) | - | 连接失败 | 切换代理，暂停当前代理 1h |
| 2 (超时) | - | 网络超时 | 切换代理，暂停当前代理 30min |
| 3 (资源缺失) | 404 | 图片不存在 | 标记单张失败，继续下一张 |
| 5 (网络问题) | 509 | IP 限频 | 梯度冷却当前代理 |
| 5 (网络问题) | 403 | Cookie 过期 | 暂停所有下载，通知用户 |
| 5 (网络问题) | 503 | 服务暂时不可用 | 等待 5min 后重试 |
| 其他 | - | 未知错误 | 重试 3 次后标记失败 |

#### Cookie 过期处理

**检测条件：**
- HTTP 403 Forbidden
- 响应内容包含 "Your IP address has been banned"
- EH API 返回空数据

**处理流程：**
```
1. 检测到 Cookie 过期
   ↓
2. 暂停所有下载任务
   ↓
3. 标记 Cookie 状态为 "expired"
   ↓
4. Web 界面显示警告："Cookie 已过期，请更新"
   ↓
5. 用户在 Web 界面或 App 更新 Cookie
   ↓
6. 验证新 Cookie 有效
   ↓
7. 恢复下载
```

#### 单张图片重试逻辑

**aria2 任务提交配置：**
```
# 提交每个 aria2 任务时设置
aria2.add_uri(url, options={
  "all-proxy": current_proxy,      # 当前代理
  "max-tries": 1,                   # 只尝试 1 次
  "retry-wait": 0,                  # 不等待重试
  "timeout": 60,                    # 60 秒超时
  "connect-timeout": 10,            # 10 秒连接超时
})
```

**重试逻辑（管理服务控制）：**
```
ImageDownloadHandler:
  max_retries = 3
  retry_delay = 10  # 秒
  
  for attempt in range(max_retries):
    # 提交 aria2 任务，设置 max-tries=1
    result = aria2.add_uri(url, options={
      "all-proxy": current_proxy,
      "max-tries": 1,
      "retry-wait": 0
    })
    
    if result.success:
      return success
    
    error = analyze_error(result.error_code, result.http_status)
    
    match error:
      case ProxyFailed:
        current_proxy = proxy_pool.switch_proxy()
        retry_with_new_proxy = True
      case RateLimited:
        current_proxy = proxy_pool.handle_509(current_proxy)
        retry_with_new_proxy = True
      case CookieExpired:
        pause_all_downloads()
        notify_cookie_expired()
        return failure
      case ImageNotFound:
        return failure  # 不重试
      case NetworkError:
        sleep(retry_delay)
        continue
    
    if retry_with_new_proxy:
      # 用新代理重新提交 aria2 任务
      continue
  
  return failure
```

#### Cookie 更新方式

**方式一：App 同步**
- App 设置中点击"同步 Cookie 到 NAS"
- 发送 API 请求更新 Cookie

**方式二：Web 界面手动更新**
- 登录 EH 网站，获取 Cookie
- 在 NAS Web 管理界面的配置页粘贴 Cookie
- 点击"验证并保存"

**API：**
```
PUT /api/v1/config/cookies
Headers:
  Authorization: Bearer {token}
Body:
{
  "cookies": "ipb_member_id=xxx; ipb_pass_hash=xxx; igneous=xxx"
}

Response:
{
  "valid": true,
  "message": "Cookie 验证成功"
}
```

#### 失败任务恢复

**用户操作入口：**
- 单个任务重试：点击"重试"按钮
- 批量重试：选择多个失败任务，点击"批量重试"
- 全局重试：点击"重试所有失败任务"

**重试行为：**
- 清除原有 aria2 任务
- 使用当前可用代理重新下载
- 如果是 Cookie 过期导致的失败，需先更新 Cookie

### 4.4 归档流程

**触发方式：**
- 定时任务：每天凌晨自动执行
- 手动触发：Web 界面点击"归档"按钮

**归档步骤：**
1. 扫描下载临时目录
2. 检查下载完整性（图片数量匹配）
3. 移动到归档目录：`/volume2/anime/doujinshi/series/lai1/`
4. 重命名：使用日文标题
5. 更新 ClickHouse 数据：
   - **ehentai 表**：更新 `is_local = 1`，必要时更新 `filecount`
   - **collection 表**：插入新记录，包含：
     - `gid`：画廊 ID
     - `rel_path`：归档后的相对路径
     - `thumb`：缩略图路径
     - `images`：图片文件名数组
6. 更新 SQLite 任务状态

**ClickHouse 表结构说明：**

`lai.collection` 表用于在线漫画浏览服务，记录已归档漫画的文件信息：

| 字段 | 类型 | 说明 |
|-----|------|------|
| ctype | UInt8 | 收藏类型（固定为 1）|
| ftype | UInt8 | 文件类型（固定为 1）|
| gid | Int64 | 画廊 ID |
| pid | Int64 | 父目录 ID（固定为 0）|
| rel_path | String | 相对于归档根目录的路径 |
| thumb | String | 缩略图路径 |
| images | Array(String) | 图片文件名数组 |
| create_time | DateTime | 创建时间 |

**归档时需要同时维护两张表：**

```sql
-- 1. 更新 ehentai 表
ALTER TABLE lai.ehentai 
UPDATE is_local = 1, filecount = {actual_page_count}
WHERE gid = {gid};

-- 2. 插入 collection 表
INSERT INTO lai.collection 
(ctype, ftype, gid, pid, rel_path, thumb, images, create_time)
VALUES (1, 1, {gid}, 0, '{rel_path}', '{thumb}', {images}, now());
```

---

## 五、Docker 部署

### 5.1 docker-compose.yml

```yaml
version: '3.8'

services:
  eh-downloader:
    build: .
    container_name: eh-downloader
    restart: always
    networks:
      - aria2_aria2-net
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
      - /volume1/docker/eh-downloader/downloads:/downloads
      - /volume2/anime/doujinshi/series/lai1:/archive
    environment:
      - ARIA2_RPC_URL=http://aria2-pro:6800/jsonrpc
      - ARIA2_RPC_SECRET=silist0111
      - API_TOKEN=your-secure-token
      - CLICKHOUSE_HOST=192.168.0.19
      - CLICKHOUSE_PORT=34121
      - CLICKHOUSE_USER=default
      - CLICKHOUSE_PASSWORD=961114

networks:
  aria2_aria2-net:
    external: true
```

### 5.2 环境变量

| 变量名 | 说明 | 默认值 |
|-------|------|--------|
| ARIA2_RPC_URL | aria2 RPC 地址 | - |
| ARIA2_RPC_SECRET | aria2 RPC 密钥 | - |
| API_TOKEN | API 认证 Token | - |
| CLICKHOUSE_HOST | ClickHouse 主机 | localhost |
| CLICKHOUSE_PORT | ClickHouse 端口 | 8123 |
| CLICKHOUSE_USER | ClickHouse 用户 | default |
| CLICKHOUSE_PASSWORD | ClickHouse 密码 | - |
| SIMILARITY_THRESHOLD | 标题相似度阈值 | 0.1 |
| PRE_SWITCH_THRESHOLD | 预emptive 切换阈值（张数）| 500 |
| MIN_ROTATION_INTERVAL | 最小轮换间隔（秒）| 600 |
| COOLDOWN_BASE | 冷却基准时间（秒）| 3600 |
| COOLDOWN_MULTIPLIER | 冷却时间倍率 | 2 |
| COOLDOWN_MAX | 最大冷却时间（秒）| 86400 |
| SLOW_DOWNLOAD_THRESHOLD | 慢速下载阈值（KB/s）| 10 |

---

## 六、App 端代码改动点

### 6.1 新增文件

- `dao/RemotePushInfo.java` - 新实体类
- `ui/scene/settings/RemoteDownloadSettingsFragment.java` - 远程下载设置
- `ui/widget/RemotePushIconDrawable.java` - 已推送图标

### 6.2 修改文件

- `Settings.java` - 新增远程下载配置项
- `DownloadsScene.java` - 新增筛选器、远程记录显示
- `DownloadAdapter.java` - 支持远程记录类型
- `GalleryListScene.java` / `GalleryAdapter.java` - 显示已推送图标
- `CommonOperations.java` - 下载触发逻辑分流
- `daogenerator/DaoGenerator.java` - 新增 RemotePushInfo 表

### 6.3 DAO 生成器改动

在 `daogenerator` 模块中新增：

```java
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

---

## 七、风险与限制

### 7.1 已知限制

1. **App 端不同步 NAS 状态**：App 只记录推送成功，不追踪 NAS 端实际下载进度
2. **token 可能过期**：如果 token 过期，NAS 端需要重新获取，可能失败
3. **Cookies 同步延迟**：App 推送 cookies 到 NAS，若 NAS 端 cookies 过期需要手动更新

### 7.2 后续优化方向

1. 支持 App 端查看 NAS 端下载进度（需 WebSocket 或轮询）
2. 支持 App 端取消 NAS 端任务
3. 更细粒度的代理故障检测和恢复
4. 支持更多代理类型（不仅仅是 trojan）

---

## 八、开发计划

### Phase 1：NAS 端核心功能
- 搭建 FastAPI 项目骨架
- 实现 aria2 RPC 客户端
- 实现代理池管理
- 实现下载服务核心逻辑
- 实现 EH API 调用
- 实现去重服务

### Phase 2：NAS 端 Web 界面
- 搭建 Vue 3 项目
- 实现任务列表页
- 实现代理状态页
- 实现配置页
- Docker 化部署

### Phase 3：App 端改动
- 修改 DAO 生成器
- 实现远程下载设置
- 修改下载触发逻辑
- 实现已推送图标显示
- 修改下载列表页

### Phase 4：集成测试
- App 与 NAS 联调
- 代理轮换测试
- 去重逻辑测试
- 归档流程测试
