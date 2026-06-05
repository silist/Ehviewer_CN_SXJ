# 画廊归档状态查询 - NAS API 端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 NAS eh-downloader 项目中新增 `/api/v1/ehentai/batch-check` API，批量查询 ClickHouse 中已归档的 gid。

**Architecture:** 
- 新增 FastAPI 路由处理 batch-check 请求
- 使用现有 ClickHouse 连接查询 `lai.ehentai` 表
- 返回 `is_local = 1` 的 gid 列表

**Tech Stack:** Python, FastAPI, ClickHouse, async

---

## File Structure

假设 eh-downloader 项目结构如下（根据 spec 文档）：

```
eh-downloader/
├── app/
│   ├── api/
│   │   ├── __init__.py
│   │   ├── download.py
│   │   ├── task.py
│   │   └── config.py
│   │   └── ehentai.py      ← 新增
│   ├── services/
│   │   ├── eh_api.py       ← 可能需要修改（ClickHouse 查询）
│   └── ...
```

| 文件 | 操作 | 责任 |
|------|------|------|
| `app/api/ehentai.py` | 创建 | 新增 batch-check API 路由 |
| `app/api/__init__.py` | 修改 | 注册新路由 |
| `app/services/clickhouse.py` | 创建/修改 | ClickHouse 查询服务（如不存在） |

---

## Task 1: 创建 ClickHouse 查询服务

**Files:**
- Create: `app/services/clickhouse.py`（如不存在）
- Modify: `app/services/clickhouse.py`（如已存在）

- [ ] **Step 1: 创建 ClickHouse 服务文件**

```python
# app/services/clickhouse.py

import asyncio
from typing import List, Set
from clickhouse_driver import Client
from app.config import settings


class ClickHouseService:
    """ClickHouse 查询服务"""
    
    _client: Client = None
    
    @classmethod
    def get_client(cls) -> Client:
        """获取 ClickHouse 客户端（懒加载）"""
        if cls._client is None:
            cls._client = Client(
                host=settings.CLICKHOUSE_HOST,
                port=settings.CLICKHOUSE_PORT,
                user=settings.CLICKHOUSE_USER,
                password=settings.CLICKHOUSE_PASSWORD,
            )
        return cls._client
    
    @classmethod
    async def query_existing_gids(cls, gids: List[int]) -> Set[int]:
        """
        查询 gid 是否在 ehentai 表中有归档记录
        
        Args:
            gids: 待查询的 gid 列表
            
        Returns:
            存在记录（is_local = 1）的 gid 集合
        """
        if not gids:
            return set()
        
        client = cls.get_client()
        
        # 构建查询 SQL
        query = """
            SELECT gid FROM lai.ehentai
            WHERE gid IN (%s)
            AND is_local = 1
        """
        
        # 执行查询（clickhouse_driver 不支持异步，需要在线程池中执行）
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None,
            lambda: client.execute(query, (gids,))
        )
        
        # 提取 gid
        existing_gids = {row[0] for row in result}
        return existing_gids
```

- [ ] **Step 2: 添加 ClickHouse 配置项（如不存在）**

在 `app/config.py` 中确认或添加：

```python
# ClickHouse 配置
CLICKHOUSE_HOST: str = os.getenv("CLICKHOUSE_HOST", "localhost")
CLICKHOUSE_PORT: int = int(os.getenv("CLICKHOUSE_PORT", "9000"))  # native port
CLICKHOUSE_USER: str = os.getenv("CLICKHOUSE_USER", "default")
CLICKHOUSE_PASSWORD: str = os.getenv("CLICKHOUSE_PASSWORD", "")
```

- [ ] **Step 3: 添加依赖（如不存在）**

在 `pyproject.toml` 或 `requirements.txt` 中添加：

```
clickhouse-driver>=0.2.0
```

- [ ] **Step 4: Commit**

```bash
git add app/services/clickhouse.py app/config.py pyproject.toml
git commit -m "feat: add ClickHouse service for querying archived gids"
```

---

## Task 2: 创建 ehentai API 路由

**Files:**
- Create: `app/api/ehentai.py`

- [ ] **Step 1: 创建 ehentai.py API 文件**

```python
# app/api/ehentai.py

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from typing import List, Set

from app.services.clickhouse import ClickHouseService
from app.api.auth import verify_token  # 假设已有认证模块


router = APIRouter(prefix="/api/v1/ehentai", tags=["ehentai"])


class BatchCheckRequest(BaseModel):
    """批量检查请求"""
    gids: List[int]


class BatchCheckResponse(BaseModel):
    """批量检查响应"""
    existing_gids: List[int]


@router.post("/batch-check", response_model=BatchCheckResponse)
async def batch_check_archived(
    request: BatchCheckRequest,
    token_valid: bool = Depends(verify_token),
):
    """
    批量查询 gid 是否在 ClickHouse 中有归档记录
    
    Args:
        request: 包含 gid 列表的请求体
        
    Returns:
        存在归档记录的 gid 列表
    """
    if not token_valid:
        raise HTTPException(status_code=401, detail="API Token 无效")
    
    # 参数校验
    if not request.gids:
        return BatchCheckResponse(existing_gids=[])
    
    if len(request.gids) > 100:
        raise HTTPException(
            status_code=400, 
            detail="单次请求 gid 数量不能超过 100"
        )
    
    try:
        # 查询 ClickHouse
        existing_gids = await ClickHouseService.query_existing_gids(request.gids)
        
        return BatchCheckResponse(existing_gids=list(existing_gids))
        
    except Exception as e:
        # 查询失败，返回 500
        raise HTTPException(
            status_code=500,
            detail=f"ClickHouse 查询失败: {str(e)}"
        )
```

- [ ] **Step 2: Commit**

```bash
git add app/api/ehentai.py
git commit -m "feat: add batch-check API for querying archived gids"
```

---

## Task 3: 注册路由到主应用

**Files:**
- Modify: `app/api/__init__.py`
- Modify: `app/main.py`

- [ ] **Step 1: 在 api/__init__.py 中导出路由**

```python
# app/api/__init__.py

from app.api.download import router as download_router
from app.api.task import router as task_router
from app.api.proxy import router as proxy_router
from app.api.config import router as config_router
from app.api.ehentai import router as ehentai_router  # 新增

__all__ = [
    "download_router",
    "task_router",
    "proxy_router",
    "config_router",
    "ehentai_router",
]
```

- [ ] **Step 2: 在 main.py 中注册路由**

```python
# app/main.py

from fastapi import FastAPI
from app.api import (
    download_router,
    task_router,
    proxy_router,
    config_router,
    ehentai_router,  # 新增
)

app = FastAPI(title="EH Downloader Manager")

# 注册路由
app.include_router(download_router)
app.include_router(task_router)
app.include_router(proxy_router)
app.include_router(config_router)
app.include_router(ehentai_router)  # 新增
```

- [ ] **Step 3: Commit**

```bash
git add app/api/__init__.py app/main.py
git commit -m "feat: register ehentai batch-check router"
```

---

## Task 4: 验证 API 功能

- [ ] **Step 1: 启动开发服务器**

Run: `uvicorn app.main:app --reload`
Expected: 服务器启动成功

- [ ] **Step 2: 测试 API 接口**

```bash
# 测试空 gid 列表
curl -X POST http://localhost:8080/api/v1/ehentai/batch-check \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gids": []}'

# Expected: {"existing_gids": []}

# 测试有效 gid 列表（需替换为真实 gid）
curl -X POST http://localhost:8080/api/v1/ehentai/batch-check \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gids": [123456, 123457]}'

# Expected: {"existing_gids": [...]} 或 {"existing_gids": []}

# 测试无效 token
curl -X POST http://localhost:8080/api/v1/ehentai/batch-check \
  -H "Authorization: Bearer invalid-token" \
  -H "Content-Type: application/json" \
  -d '{"gids": [123456]}'

# Expected: 401 Unauthorized
```

- [ ] **Step 3: 测试超过限制的 gid 数量**

```bash
# 构造超过 100 个 gid 的请求（模拟）
curl -X POST http://localhost:8080/api/v1/ehentai/batch-check \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gids": [1,2,3,...101]}'

# Expected: 400 Bad Request
```

---

## Task 5: 更新 Docker 配置

**Files:**
- Modify: `docker-compose.yml`（如需要）

- [ ] **Step 1: 确认 ClickHouse 环境变量已配置**

在 `docker-compose.yml` 中确认：

```yaml
services:
  eh-downloader:
    environment:
      - CLICKHOUSE_HOST=192.168.0.19
      - CLICKHOUSE_PORT=9000  # native port, 注意 HTTP port 是 8123
      - CLICKHOUSE_USER=default
      - CLICKHOUSE_PASSWORD=961114
```

**注意：** `clickhouse-driver` 使用 native port（默认 9000），不是 HTTP port（8123）。根据 spec 文档，ClickHouse 端口配置为 34121，需要确认这是 native port 还是 HTTP port。

- [ ] **Step 2: 重新构建并部署**

```bash
docker-compose build
docker-compose up -d
```

- [ ] **Step 3: 验证部署后 API 可用**

```bash
curl http://nas-address:8080/api/v1/ehentai/batch-check \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"gids": [123456]}'
```

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "docs: update ClickHouse port configuration note"
```

---

## Summary

本实现计划完成以下改动：

1. **ClickHouse 查询服务** — `app/services/clickhouse.py`
2. **batch-check API 路由** — `app/api/ehentai.py`
3. **路由注册** — `app/api/__init__.py` 和 `app/main.py`
4. **Docker 配置确认** — 确保环境变量正确

**注意事项：**
- ClickHouse native port 与 HTTP port 区别：driver 使用 native port（9000），HTTP API 使用 HTTP port（8123）
- App 端请求的端口是 FastAPI 服务端口（8080），不是 ClickHouse 端口
- 单次请求限制 100 个 gid，避免查询超时