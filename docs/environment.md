# 环境与运行说明

## 通用工具

| 工具 | 要求 |
| --- | --- |
| Git | 支持 clone / branch / push |
| Java | JDK 21；设置 JAVA_HOME |
| Maven | 3.9+；bin 加入 PATH |
| Node.js | 22.12+，建议 Node 24 LTS；本次验证为 24.21.0 |
| pnpm | 12.4.2（当前锁文件验证版本） |
| Docker Compose | 数据库模式需要；内存演示不需要 |
| Python | 3.12，仅独立 FastAPI 服务需要 |
| Microsoft Edge | 当前 Playwright E2E 使用 msedge 通道 |

仓库配置使用 Maven / pnpm 默认缓存位置，不强制 D 盘。前端 `pnpm-workspace.yaml` 保留 `nodeLinker: hoisted`，减少 Windows 符号链接权限问题。安装依赖时使用 `--frozen-lockfile`，不要为解决环境问题随意重建锁文件。

## 配置变量

所有值应由开发者在自己终端或部署平台配置。不要打印进程环境、提交真实配置或把凭证放入前端。

| 变量 | 作用与默认值 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 默认数据库模式；`memory` 使用进程内数据 |
| `SERVER_PORT` | 后端端口，默认 8080 |
| `POSTGRES_HOST` / `POSTGRES_PORT` | 默认 localhost / 5432 |
| `POSTGRES_DB` / `POSTGRES_USER` | 默认 zhiyu / zhiyu |
| `POSTGRES_PASSWORD` | 默认本地开发密码 zhiyu_local_password，仅供本机演示 |
| `REDIS_HOST` / `REDIS_PORT` | 默认 localhost / 6379 |
| `ARK_API_KEY` | 后端模型凭证，无默认值，由开发者私下配置 |
| `ARK_MODEL` | 开发者账号可用的模型或推理接入点 ID，无默认值 |
| `ARK_BASE_URL` | 默认 https://ark.cn-beijing.volces.com/api/v3 |
| `AI_SERVICE_URL` | 独立预留服务地址，默认 http://localhost:8000；树屋不依赖它 |
| `VITE_API_BASE_URL` | 前端公开后端地址，默认 http://127.0.0.1:8080；更改后重启 Vite，生产版需重新构建 |

`zhiyu-server/.env.example` 和 `deploy/.env.example` 仅为后端变量参考，Spring 不会自动加载它们。当前 Compose 未插值读取这些数据库变量；如果自定义数据库凭证，需同时调整数据库实例和后端进程配置。已有 Docker 数据卷不会因修改密码配置自动重设数据库密码。

可将 `zhiyu-web/.env.example` 复制为 `zhiyu-web/.env.local` 设置公开 API 地址。Vite 的 `VITE_*` 变量会进入浏览器产物，不能存放秘密。

## 启动与检查

从仓库根目录执行 [README](../README.md) 的内存或数据库启动步骤。前后端在不同终端运行，不要同时启动两个占用 8080 的后端。

PowerShell 如禁止执行项目脚本，可仅对当前终端执行 `Set-ExecutionPolicy -Scope Process Bypass`，再运行明确检查过的脚本。

## 可选：维护者 D 盘环境

仓库提供 `scripts/env-d.ps1`，适用于已安装以下工具的原开发电脑：

- `D:\Pro\jdk\jdk21`
- `D:\Pro\maven\apache-maven-3.9.16`
- `D:\Pro\pnpm` 和 `D:\Pro\node.exe`
- `D:\Pro\python312`

```powershell
. .\scripts\env-d.ps1
pnpm --dir zhiyu-web install --frozen-lockfile --store-dir D:/Pro/pnpm-store
```

脚本仅修改当前终端的 PATH、JAVA_HOME 和 MAVEN_OPTS，保留 Maven 缓存 `D:/Pro/m2`。pnpm 缓存用命令行参数显式选择。其他机器直接使用 PATH 上的工具即可。已有依赖若报告 store 不一致，先使用原来的 `--store-dir`；需要改缓存时在停止开发服务后重新安装依赖。

## 可选：FastAPI 服务

当前树屋直接调用 Spring Boot，以下服务不是主流程的启动前置条件。

在 `zhiyu-ai-service` 目录执行：

```sh
python -m venv .venv
```

Windows 使用 `.venv\Scripts\Activate.ps1` 激活；macOS / Linux 使用 `source .venv/bin/activate`。然后执行：

```sh
python -m pip install -r requirements.txt
python -m pytest -q
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

PowerShell 也可运行 `./run.ps1`；原开发电脑可显式传入 `-PythonCommand D:\Pro\venvs\zhiyu-ai\Scripts\python.exe`。

## 验证边界

后端测试使用内存和模拟供应商，无需真实模型凭证；前端生产构建不代表完整交互已验收。E2E 需 Edge 和独立的内存后端、前端。真实豆包连通性和内容质量应由开发者启动已配置服务后人工验证，不能由模拟测试结果推断。
