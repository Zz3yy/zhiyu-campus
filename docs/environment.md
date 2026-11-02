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
| `VOLC_SPEECH_API_KEY` | 可选语音识别凭证，新版语音控制台使用；与方舟聊天配置独立，无默认值 |
| `VOLC_SPEECH_APP_ID` / `VOLC_SPEECH_ACCESS_TOKEN` | 旧版语音控制台使用的一对配置；已配置 `VOLC_SPEECH_API_KEY` 时优先使用新版鉴权 |
| `VOLC_SPEECH_ENDPOINT` | 默认 https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash；只允许 HTTPS，测试用本机 HTTP 除外 |
| `VOLC_SPEECH_REQUEST_TIMEOUT_MS` | 语音请求超时，默认 30000 毫秒，允许 1–60000 |
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

## 可选：树屋语音转写

需要在火山引擎语音控制台另行开通资源 `volc.bigasr.auc_turbo`（录音文件极速版识别）。已有方舟 Chat Completions 配置不能证明语音服务已开通。接口与格式依据：[火山引擎官方说明](https://www.volcengine.com/docs/6561/1631584)。

新版语音控制台在后端进程环境配置 `VOLC_SPEECH_API_KEY`；旧版使用 `VOLC_SPEECH_APP_ID` 与 `VOLC_SPEECH_ACCESS_TOKEN`。不要把值写入前端、Git 仓库或聊天，也不要打印环境变量。Spring 不自动加载 `.env.example`；配置变更后重启后端。页面状态只表示必需字段齐全，服务是否开通、权限和额度是否有效，需要真实转写另行验证。

浏览器需支持 `getUserMedia`、Web Audio 和 AudioWorklet，并从 HTTPS 或本机 `localhost` / `127.0.0.1` 打开。手机直接访问电脑的普通 HTTP 局域网 IP 通常不能使用麦克风，应使用 HTTPS。浏览器端录制并编码为 16 kHz、单声道、16 位 PCM WAV，上限 30 秒；不依赖浏览器私有语音识别接口，不需要 FFmpeg。

语音授权独立于豆包聊天授权。用户同意后按住说话（松开转写），也可点击开始、停止；转写结果可编辑，再放入输入框并点击发送。已有草稿不会被覆盖。未启用豆包时，发送的文字进入本地引导；启用豆包时进入原有 SSE / Agent 流程。创建任务仍需用户确认。

音频经 Spring Boot 转发到语音服务商，应用不写入数据库或文件；短录音 multipart 保存在内存。后端校验 WAV 头、真实数据长度、最长 30 秒及文件大小，最多同时处理两个转写请求；供应商响应有超时和大小上限。语音请求只含录音和通用调用标识，不含花园资料、对话历史或匿名花园凭证。不启用情绪、性别或说话人识别。取消、撤销语音授权或离开树屋会释放麦克风并停止等待；不能撤回服务商已经收到的音频，服务商留存以实际条款为准。

尚未配置、麦克风权限拒绝、无声或转写失败时保留文字入口。自动测试中的合成麦克风与模拟语音供应商不代表真实识别质量。真实验收时应亲口说出考试压力或复习卡点，核对转写，再确认发送并检查原有任务确认流程。

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
