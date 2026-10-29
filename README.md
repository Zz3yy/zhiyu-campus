# 知愈校园 · 情绪花园

面向大学生考试压力和复习拖延的非诊断型支持 Web 应用。用花园和植物成长记录用户主动提供的情绪与行动，把“卡住了”转化为一个愿意开始的小步骤。

仓库：<https://github.com/Zz3yy/zhiyu-campus> · 主维护者：Zz3（[@Zz3yy](https://github.com/Zz3yy)）

## 核心体验

1. 在树屋描述考试压力或复习卡点，Agent 先澄清需求。
2. 推荐一个低门槛行动，展示时长、推荐理由和实际工具调用轨迹。
3. 用户点击确认后才创建任务，并关联植物成长。
4. 用户反馈完成、跳过、太难或无帮助，后续建议在授权范围内调整。

保留六种情绪植物、守园人物、认知日记、次日复盘和七天报告。个性化仅使用用户主动提供的信息和行动反馈；不读取课表、私人聊天或设备数据，不做心理诊断。涉及自伤、伤人或紧急危险时暂停普通流程，展示固定求助提示；规则可能漏报或误报，不代表真人值守或危机干预服务。

## AI 架构与实现边界

React 前端通过 Spring Boot 使用方舟兼容的 Function Calling。豆包负责意图理解、工具选择与回应生成；本地约束层校验阶段、授权、参数、目录和风险，失败时明确降级为本地引导。推荐阶段要求调用工具；页面显示实际轨迹，不能用“已配置”替代真实调用成功的证据。

| 工具 | 用途与约束 |
| --- | --- |
| `query_garden_state` | 查询用户明确授权范围内的近期花园资料或行动反馈 |
| `search_activities` | 在本轮已审核检索依据与行动策略范围内返回最多三个候选 |
| `propose_action` | 提出候选行动和理由，无创建任务副作用 |
| `create_task` | 模型直接调用被拒绝；仅用户点击后的确认接口可创建任务 |

云端模型、花园上下文、近期行动反馈各有独立授权。未授权、未配置或调用失败时使用本地引导。轻量 RAG 使用本地字符二元组向量与 pgvector 余弦检索，提供受限上下文及经过服务端核对的来源标签；这不是神经语义 embedding。尚未实现 MCP Server 和多模态。FastAPI 为独立预留服务，不参与当前树屋调用链。

知识库当前含 19 条行动与 2 条认知/复习引导，**均待人工审阅**。待审阅条目不进入 RAG 生成上下文；云端推荐缺少已审核依据时显示原目录的本地草稿建议与状态，不能宣称已审核内容的真实模型闭环已经验收。详情见[轻量 RAG 与内容审核](docs/轻量RAG与内容审核.md)、[逐条审核清单](docs/内容审核清单.md)。数据库模式会执行 V3 迁移，内存模式标注 memory-vector。

## 工程结构

```text
zhiyu-web/          React + TypeScript + Vite 前端
zhiyu-server/       Java 21 + Spring Boot，业务与 Agent 工具执行
zhiyu-ai-service/   FastAPI 独立预留服务（可选）
deploy/            PostgreSQL / Redis 的 Docker Compose 配置
scripts/           PowerShell 启动及可选本机环境脚本
docs/              环境、开发、验收与参赛交付文档
```

## 快速启动：本地演示

准备 Git、JDK 21、Maven 3.9+、Node.js 22.12+（建议 Node 24 LTS）、pnpm 12.4.2，并将命令加入 PATH，设置 `JAVA_HOME` 指向 JDK 21。当前锁文件使用 pnpm 12.4.2 验证。内存模式无需 Docker 或模型密钥。

```sh
git clone https://github.com/Zz3yy/zhiyu-campus.git
cd zhiyu-campus
```

终端一，在仓库根目录启动内存后端：

```sh
mvn -f zhiyu-server/pom.xml -B spring-boot:run -Dspring-boot.run.profiles=memory
```

终端二，在仓库根目录启动前端：

```sh
pnpm --dir zhiyu-web install --frozen-lockfile
pnpm --dir zhiyu-web dev --host 127.0.0.1
```

打开 <http://127.0.0.1:5173>，阅读说明并同意创建匿名花园，然后进入树屋。后端为 8080；内存模式重启会丢失数据。保持使用同一个主机名，避免 `localhost` 与 `127.0.0.1` 的浏览器凭证不同。

## 数据持久化与豆包

安装并启动 Docker 后，在根目录运行：

```sh
docker compose -f deploy/docker-compose.yml up -d --wait postgres redis
mvn -f zhiyu-server/pom.xml -B spring-boot:run
```

先停止已有的内存后端，避免占用 8080；如果此前设置过 `SPRING_PROFILES_ACTIVE=memory`，先清除此变量。默认数据库配置与 Compose 一致，Flyway 自动执行迁移。Compose 目前只提供本地开发基础设施，不是完整的生产部署。

Windows PowerShell 中，配置真实豆包可使用：

```powershell
.\scripts\start-doubao.ps1 -StartInfrastructure -RestartProjectBackend
```

脚本通过隐藏输入读取凭证，仅传给后端进程，不写入项目文件；由开发者自行运行，不要把真实 API Key 发到聊天或提交仓库。它不启动前端。其他系统可按[环境配置](docs/environment.md)和[豆包接入](docs/豆包接入.md)设置后端进程环境变量。

进入树屋后单独勾选“使用豆包”；花园资料与近期反馈默认不共享。切换授权会清空本次对话，但不能撤回已经发送给服务商的内容。

## 环境配置

- [环境与运行说明](docs/environment.md)：跨机器工具要求、配置字段、可选 Python 服务及 D 盘环境。
- [后端模型变量示例](zhiyu-server/.env.example)：字段说明；Spring 不会自动加载 `.env`。
- [数据库变量示例](deploy/.env.example)：后端进程变量参考；当前 Compose 使用固定开发值。
- [前端变量示例](zhiyu-web/.env.example)：可复制为 `zhiyu-web/.env.local`，只包含公开 API 地址。

维护者原有 D 盘环境可在每个 PowerShell 终端运行 `. .\scripts\env-d.ps1`。pnpm 安装时可加 `--store-dir D:/Pro/pnpm-store` 复用原缓存；其他机器无需此脚本或 D 盘目录。

## 验证

在仓库根目录运行：

```sh
mvn -f zhiyu-server/pom.xml -B test
pnpm --dir zhiyu-web build
```

后端测试采用内存存储及模拟模型供应商，不需要真实 API Key。E2E 需另行启动干净的内存后端（8080）和前端（5173），且本机安装 Microsoft Edge：

```sh
pnpm --dir zhiyu-web test:e2e
```

本次仓库规范化的验证范围和结果见[验证记录](docs/仓库规范化验收-2026-10-27.md)。Agent 的交互验收步骤与历史结果见[闭环验收](docs/复习Agent闭环验收-2026-10-23.md)。自动测试不代表真实模型质量或临床效果。

## 开发与参赛材料

- [产品构思与开发约定](产品构思与开发约定-v1.md)
- [开发、分支和提交约定](docs/开发流程.md)
- [仓库与参赛交付清单](docs/仓库与参赛交付清单.md)

Git 从 2026-10-26 的真实基线开始记录；此前开发文档不等同于 Git 历史，不回填或伪造旧提交。参赛团队成员及实际分工待报名信息确认，不虚构贡献。演示视频与技术文档尚未交付，要求以当届官方通知为准。

当前为本地开发版本。公开部署前仍需 HTTPS、限流、费用控制和安全评测；旧版 `X-User-Id` 接口默认关闭，不应在公开环境启用 `zhiyu.legacy-api.enabled=true`。仓库尚未指定开源许可证，公开可见不等于授予开源使用许可。
