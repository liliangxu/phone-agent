# Phone Agent — Codex 的 SIP 座机桥接工具

[English](README.md) | 中文

![Phone Agent 封面](docs/assets/readme-cover.png)

Phone Agent 是一个把 SIP 座机连接到 Codex AI 编程智能体的本地桥接工具。它把 Asterisk、本地语音识别和受管 Codex 终端会话连接起来，让用户可以通过电话发起 Codex 工作，在需要输入时让已注册的座机振铃，并把语音回复写回对应会话。

本项目定位为本地开发和实验室工具，不是云端多租户服务。Console 可以操作连接到 Codex 的可写终端，因此默认应只在 loopback 上运行；如果要远程访问，必须先补充认证、CSRF、防火墙、TLS、secret 管理和终端操作审计设计。

![Phone Agent 做什么](docs/assets/what-it-does.png)

![Phone Agent Console 桌面截图](docs/assets/console-desktop.png)

## 支持场景

Phone Agent 支持本地 Spring/MySQL 服务、Codex Console、本地 Asterisk，以及你可控网络内的 SIP 电话或软电话。它不是托管 SaaS 产品，不提供公网加固方案，也不提供各电话品牌 UI 配置教程。

开源主路径是完整电话工作流：Spring、MySQL、Asterisk、AMI、SIP 注册、Eventlist BLF/dialog 订阅、振铃、录音和 ASR 回调检查。纯软件检查是开发和测试辅助路径，可用于 Console/API 冒烟和 CI；它不能证明完整 Phone Agent 链路跑通。

## 支持的电话接入

已验证样例设备是 Grandstream GXP1630，但它不是唯一支持设备。只要电话或软电话支持 SIP REGISTER 和 Eventlist BLF/dialog 订阅，就可以按同一字段接入。完整 BLF 红灯体验要求配置后的 SIP 注册和 Eventlist BLF/dialog 订阅都成立；只有基础 SIP 注册不代表 BLF 订阅观察者已验证。

Phone Agent 只说明需要同步的字段，不维护各厂商 UI 路径：SIP 服务器、SIP 用户、SIP 认证 ID、SIP 密码、Eventlist BLF URI 和 BLF 按键值。这些值来自 `.env.local` 和生成后的 Asterisk 配置。

## 能力

- 基于 Spring Boot、MySQL 和 Flyway 的本机服务。
- Codex Console：创建、查看、拖拽和排列受管 Codex session。
- 当 Codex 等待用户输入时，通过电话 bridge 提醒已注册的座机。
- ASR 回调会把电话回复写回对应的 Codex session。
- 全局 Ring Phone 控件可触发座机提醒。
- Console 页面提示语支持中文和英文。
- inbound 初始请求和 phone reply 回写提示词支持中文和英文模板。

## 运行要求

核心本机流程需要：

- JDK 25 或项目等价 toolchain。
- Spring Boot 可连接、由用户自行准备的 MySQL service 和 database。
- Codex CLI。
- `tmux`。
- `ttyd`。
- `curl` 和 `nc`，用于本机检查。

电话和语音链路需要：

- Docker 和 Compose plugin。
- Asterisk，通常通过 `ops/asterisk-mvp/docker-compose.yml` 启动，默认镜像为 `mlan/asterisk:latest`。
- Asterisk AMI 凭证。
- `ffmpeg`。
- Whisper 兼容 ASR 命令和模型。
- macOS `say` 或其他已配置 TTS 命令。
- 已注册的真实电话设备，例如 Grandstream GXP1630，用于硬件端到端验证。

没有真实座机时，仍可运行软件层开发冒烟测试；电话注册、振铃、录音和完整 ASR 回调验证需要本机电话环境。

## 安装

1. 克隆仓库。

```bash
git clone <repo-url>
cd phone-agent
```

2. 安装 JDK 25，并通过 `JAVA_HOME` 或 `PATH` 提供。

3. 安装核心本机工具：`curl`、`nc`，以及完整电话链路需要的工具。

4. 自行准备 MySQL。在启动前创建 MySQL service 和 `PHONE_AGENT_MYSQL_DATABASE` database；开发脚本只读取连接配置，不创建或管理 MySQL 容器、service 或 database。

## 配置

复制配置样例到本地未跟踪文件：

```bash
cp .env.example .env.local
```

按本机环境修改 `.env.local`。命令值既可以是 `PATH` 上的命令名，也可以是绝对可执行路径。真实 secret 和本机路径应留在 `.env.local`。

运行开发脚本前导出变量：

```bash
set -a
source .env.local
set +a
```

关键变量包括：

- `PHONE_AGENT_MYSQL_*`：MySQL。
- `PHONE_AGENT_AMI_*`：Asterisk AMI。
- `PHONE_AGENT_SIP_EXTENSION`、`PHONE_AGENT_SIP_AUTH_ID`、`PHONE_AGENT_SIP_PASSWORD`、`PHONE_AGENT_RING_TARGET`、`PHONE_AGENT_BLF_EVENTLIST_URI` 和 `PHONE_AGENT_BLF_EXTENSIONS`：电话账号、呼叫目标、Eventlist BLF URI 和 slot 到 BLF 的映射。
- `PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS` 和 `PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS`：生成 Asterisk transport 时使用的 external address。
- `PHONE_AGENT_FFMPEG_COMMAND`、`PHONE_AGENT_WHISPER_COMMAND`、`PHONE_AGENT_WHISPER_MODEL_PATH`：ASR。
- `PHONE_AGENT_CODEX_COMMAND`、`PHONE_AGENT_TMUX_COMMAND`、`PHONE_AGENT_TTYD_COMMAND`：受管 Codex terminal。
- `PHONE_AGENT_SPRING_BIND_ADDRESS`：Spring 默认 loopback；开发脚本可能设为 `0.0.0.0`，让 Asterisk Docker 回调访问 Spring。

MySQL 使用这些变量配置：

```bash
PHONE_AGENT_MYSQL_HOST=127.0.0.1
PHONE_AGENT_MYSQL_PORT=3306
PHONE_AGENT_MYSQL_DATABASE=phone_agent
PHONE_AGENT_MYSQL_USER=phone_agent
PHONE_AGENT_MYSQL_PASSWORD=change-me
```

Spring Boot 启动时运行 Flyway schema migration。脚本只检查 MySQL TCP 端点；数据库、认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证。脚本不会执行 `CREATE DATABASE`。

## 快速开始

开源用户主路径是完整电话工作流。

1. 从仓库根目录启动 Spring 和 Asterisk。

```bash
scripts/phone-agent-dev.sh start
```

`start` 会执行完整电话预检查，必要时刷新生成的 Asterisk 配置，启动 Asterisk 和 Spring，等待 Spring 健康状态，并输出 warning 级电话/BLF 启动后检查。

2. 检查完整本地链路。

```bash
scripts/phone-agent-dev.sh status
```

`status` 是只读命令，也是 Java、Spring、MySQL TCP 可达性、电话配置、Asterisk、生成的 Asterisk 配置、回调连通性、AMI 权限、SIP 注册、Eventlist BLF 订阅和 BLF 订阅观察者的首选诊断入口。数据库、认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证。

3. 打开 Console。

```text
http://127.0.0.1:8080/console/
```

4. 需要时查看日志。

```bash
scripts/phone-agent-dev.sh logs spring
scripts/phone-agent-dev.sh logs asterisk
scripts/phone-agent-dev.sh logs all -f
```

5. 停止脚本管理的运行时。

```bash
scripts/phone-agent-dev.sh stop
```

`stop` 只停止脚本管理的 Spring 进程和 Asterisk Compose 服务。它不会停止、删除、创建或清理你的 MySQL service 或 database。

## 架构

![Phone Agent 架构](docs/assets/architecture.svg)

## BLF 工作流

![Phone Agent BLF 工作流](docs/assets/blf-workflow.svg)

## 开发说明与测试

纯软件模式适合 Console/API 冒烟、文档契约、prompt formatter 检查和没有 Asterisk/电话的 CI。它不是完整 Phone Agent 证明，因为它跳过 Asterisk、SIP 注册、Eventlist BLF 订阅、BLF 订阅观察者、真实振铃、录音和 ASR 回调。

运行自动化测试：

```bash
./gradlew test jacocoTestReport
node --test src/test/js/*.test.mjs
```

维护者仍可用 software-only 等兼容保留/开发用脚本参数做聚焦冒烟测试。不要把这些参数放回面向用户的快速开始步骤。

## 电话和 Asterisk 硬件接入

快速开始会为完整工作流启动 Asterisk。本节用于理解和验证电话侧配置。

1. 通过仓库内 Compose 文件启动 Asterisk：

```bash
cd ops/asterisk-mvp
docker compose up -d
```

该 Compose service 使用 `mlan/asterisk:latest`，暴露 SIP/RTP 端口，把 AMI 绑定到 `127.0.0.1:5038`，挂载 `ops/asterisk-mvp` 作为 Asterisk 配置，并挂载 `runtime/sounds` 和 `runtime/recordings` 存放生成音频和录音。如果你的环境无法拉取该镜像，可把 `ops/asterisk-mvp/docker-compose.yml` 中的 image 换成等价 Asterisk 镜像，并重新验证 `manager.conf`、`pjsip.conf`、`extensions.conf`、AMI、SIP 和录音路径。

2. 配置座机。已验证的 GXP1630 设置：

- SIP 服务器：电话可访问的主机 IP，例如 `192.168.10.1`。
- SIP 用户 / 认证 ID / 密码：`1001` / `1001` / `1001`。
- Eventlist BLF URI：`phone-agent-slots`。
- BLF 按键值：`601` 到 `608`。

如果使用自定义值，修改 `.env.local` 并导出后，从仓库根目录运行 `scripts/phone-agent-dev.sh start`。`start` 会在启动 Asterisk 前刷新生成的 Asterisk 配置。修改 SIP 或 BLF 值不会触发数据库迁移，不会在运行中热重映射 active task；如果本地开发历史数据与新配置冲突，只能手工清理或重建。

3. 从仓库根目录启动完整电话工作流：

```bash
scripts/phone-agent-dev.sh start
```

需要完整链路只读检查时，可运行：

```bash
scripts/phone-agent-dev.sh status
```

4. 确认 Asterisk 可访问 Spring：

```bash
docker exec phone-agent-asterisk-mvp \
  sh -lc 'curl -fsS --max-time 2 http://host.docker.internal:8080/actuator/health'
```

5. 确认电话注册和 BLF 订阅：

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

详细拓扑、端口、SIP 设置、冒烟测试和恢复命令见[本地部署](docs/DEPLOYMENT.zh-CN.md)。

## 基本工作流

1. 打开 `http://127.0.0.1:8080/console/`。
2. 创建或打开受管 Codex session。
3. 在嵌入终端中工作，直到 Codex 需要更多用户输入。
4. 当 Codex 等待输入时，用 phone bridge 或全局 Ring Phone 控件提醒座机用户。
5. 座机用户接听、语音回复并结束录音。
6. ASR 转写录音，Phone Agent 把电话回复写回同一个 Codex session。

## Browser Cockpit

Console 是本地操作的浏览器 cockpit：

- 左侧：session 列表、状态、bridge 状态和电话操作。
- 右侧：受管 terminal panes，固定支持 `1/2/3/4/6` 布局。
- 语言控件：中文和英文 UI chrome。
- 响应式布局：桌面 cockpit 和 390px/430px 移动视图已有 rendered evidence。

UI chrome 支持本地化。Session 标题、历史任务文本、终端输出和用户生成内容按原文显示。

## 配置细节

Spring 默认配置位于 `src/main/resources/application.properties`。本机覆盖应通过环境变量、Spring 命令行参数或未跟踪的本地文件完成。

重要默认值：

- `server.address=127.0.0.1` 默认只绑定 loopback。
- `phone-agent.codex.prompt-language=zh-CN` 为服务端生成给 Codex 的 prompt 选择中文模板。
- `phone-agent.codex.allowed-workspace-roots[0]=${user.dir}` 默认把受管 session 限制在当前项目目录内。

开发脚本可能为了本地 Docker/Asterisk 回调把 Spring 绑定到 `0.0.0.0`。这不代表 Console 可以安全远程暴露；Console/API 的安全边界仍依赖 loopback 检查和本地网络假设。

`.env.example` 使用 `scripts/phone-agent-dev.sh` 实际读取的 `PHONE_AGENT_*` 变量名。脚本启动 Spring Boot 时，会把 MySQL、AMI、ASR、Codex 和 prompt language 配置透传给应用。

脚本读取 `PHONE_AGENT_MYSQL_HOST`、`PHONE_AGENT_MYSQL_PORT`、`PHONE_AGENT_MYSQL_DATABASE`、`PHONE_AGENT_MYSQL_USER` 和 `PHONE_AGENT_MYSQL_PASSWORD`。MySQL service 和 database 生命周期由用户负责；Flyway 在 Spring 启动时执行 schema migration。

## 纯软件检查

没有已注册座机时，仍可验证：

- Java 测试和 JS Console 测试。
- Console 加载、session 列表渲染、语言切换和响应式布局。
- 文档与配置结构。
- Prompt formatter 行为。

完整振铃、录音、ASR 回调和物理 BLF 流程需要本机 Asterisk 和电话设备。

## 文档

- [架构](docs/ARCHITECTURE.zh-CN.md) | [English](docs/ARCHITECTURE.md)
- [安全](docs/SECURITY.zh-CN.md) | [English](docs/SECURITY.md)
- [排障](docs/TROUBLESHOOTING.zh-CN.md) | [English](docs/TROUBLESHOOTING.md)
- [本地部署](docs/DEPLOYMENT.zh-CN.md) | [English](docs/DEPLOYMENT.md)
- [贡献指南](CONTRIBUTING.zh-CN.md) | [English](CONTRIBUTING.md)

## 已知限制

- 不提供托管 SaaS、多用户或多租户隔离。
- 不包含生产远程访问认证设计。
- 真实电话硬件验证依赖用户本地 Asterisk、网络和设备状态。

## 许可证

Phone Agent 使用 [MIT License](LICENSE)。
