# 知愈校园 · 情绪花园

面向大学生考试压力与复习启动的非诊断型支持 Web 应用。包含六种情绪植物、守园人物、播种、认知日记、微行动、成长、次日复盘和七天报告。树屋支持本地规则引导与可选豆包：先澄清，再提供一个小行动，用户确认后才创建任务。

参见 `docs/情绪花园交付记录-2026-10-15.md` 获取首轮实现范围。豆包配置见 `docs/豆包接入.md`；本轮流程、反馈规则、验证边界及演示步骤见 `docs/复习Agent闭环验收-2026-10-23.md`。配置齐全不等于真实模型调用成功，需检查每条回复的来源。

## 工程结构

```text
zhiyu-web/          React + TypeScript 前端
zhiyu-server/       Spring Boot 业务后端
zhiyu-ai-service/   FastAPI AI 服务
deploy/              Docker Compose 和部署配置
docs/                 开发过程文档
```

## 快速启动

### 1. 切换 D 盘开发环境

在项目根目录打开 PowerShell：

```powershell
. .\scripts\env-d.ps1
```

脚本会将 JDK 21、Maven、Python、pnpm 缓存和 Maven 本地仓库指向 `D:\Pro`。

### 2. 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d postgres redis
```

默认后端使用 PostgreSQL 和 Flyway。当前机器没有 Docker Desktop 时，可以先使用内存模式开发：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'memory'
```

### 3. 启动后端

```powershell
cd zhiyu-server
mvn -B spring-boot:run
```

内存模式会跳过 PostgreSQL、Flyway 和 Redis，数据只保存在进程内，适合页面和接口联调。

如果要在数据库模式下启用真实豆包，可在项目根目录的 VS Code PowerShell 终端使用安全启动脚本：

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\start-doubao.ps1 -StartInfrastructure -RestartProjectBackend
```

脚本会隐藏输入方舟 API Key，读取模型 ID，启动或等待 PostgreSQL/Redis，并启动数据库模式后端。API Key 只通过进程环境变量传递，不写入项目文件；脚本不会自动启动前端。若基础设施已手动启动，可省略 `-StartInfrastructure`；若 8080 没有旧后端，可省略 `-RestartProjectBackend`。

### 4. 启动 AI 服务（可选，当前花园不依赖它）

```powershell
cd zhiyu-ai-service
& 'D:\Pro\venvs\zhiyu-ai\Scripts\python.exe' -m uvicorn app.main:app --reload --port 8000
```

### 5. 启动前端

```powershell
cd zhiyu-web
pnpm install
pnpm dev
```

本机使用 `D:\Pro\pnpm\pnpm.cmd`（pnpm 12.4.2）。前端的 `zhiyu-web/pnpm-workspace.yaml` 配置 `nodeLinker: hoisted`，避免 Windows 下创建依赖符号链接失败；该版本未读取 `.npmrc` 中的布局设置。依赖缓存沿用 `D:/Pro/pnpm-store`，其他机器可按实际环境调整 `storeDir`。

在 `zhiyu-web` 目录可用 `pnpm config get nodeLinker` 检查结果应为 `hoisted`，再运行 `pnpm install --frozen-lockfile` 和 `pnpm build`，无需额外传入布局参数。E2E 仍需先启动内存模式后端（8080）和前端（5173），再运行 `pnpm test:e2e`。

打开 `http://127.0.0.1:5173`，阅读使用说明并同意创建匿名花园。当前默认使用 PostgreSQL，Flyway 自动执行 V1/V2。不要在不同主机名之间切换，否则浏览器会使用不同的匿名凭证。

进入花园后打开“树屋倾诉”，单独勾选“使用豆包”；最近三天的植物记录/未完成任务、近期行动反馈各有独立选项，默认不共享。启用“允许使用近期行动反馈”后，只发送近七天最多 8 条目录内复习行动的类型、完成/跳过、难度反馈和帮助评分，不发送该任务的自由文本或植物笔记。本地引导会参考同一范围的反馈，但不调用外部模型。切换授权会清空本次对话与输入，不能撤回此前已外发内容。

花园使用 `/api/garden` 和随机 Bearer 凭证；服务端仅保存凭证哈希。旧版依赖 `X-User-Id` 的接口默认关闭，旧数据表保留。不要在公开环境启用 `zhiyu.legacy-api.enabled=true`。当前服务用于本地开发，公开试用前仍需 HTTPS、限流及部署加固。

### 6. 常用验证命令

```powershell
# 前端生产构建
pnpm --dir zhiyu-web build

# 本地后端 8080 和前端 5173 已启动时，使用已安装的 Edge
pnpm --dir zhiyu-web test:e2e

# Spring Boot 编译和测试
mvn -f zhiyu-server/pom.xml -B test

# FastAPI 测试
& 'D:\Pro\venvs\zhiyu-ai\Scripts\python.exe' -m pytest -q zhiyu-ai-service
```

## 开发约定

- 业务功能按“需求、接口、实现、测试、文档”完成。
- 每个功能使用独立 `feature/*` 分支。
- 不将密钥写入代码，使用 `.env` 或本地配置文件。
- 心理健康内容使用合成数据或经过授权的匿名数据。
- AI 服务不可用时，业务后端必须提供安全降级提示。

## 当前迭代

Iteration 0：初始化项目和本地基础设施。

Iteration 1：已完成匿名进入、情绪记录、活动任务和趋势查询的非 AI 闭环；数据层同时提供 PostgreSQL JDBC 实现和内存开发模式。

情绪花园第一轮：已实现自助闭环、匿名花园隔离、数据库保存和删除。复习 Agent 当前采用工具调用型混合架构：豆包负责意图理解、工具选择和自然语言回应；服务端提供 `query_garden_state`、`search_activities`、`propose_action` 三个受限工具，负责查询已授权资料、检索审核目录和提出无副作用行动；`create_task` 只在用户点击行动卡片后由确认接口执行。阶段、目录边界、参数、风险和授权由本地约束层校验，失败时回到本地引导。这里使用的是方舟兼容的 Function Calling 协议，不宣称已实现 MCP Server；MCP 适配仍是后续可选工作。真实模型质量与更完整风险评估仍需单独验收。
