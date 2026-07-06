# Phone Agent 本地部署

[English](DEPLOYMENT.md) | 中文

本文档描述使用 Grandstream GXP1630 和 Codex Phone Bridge 验证过的本地部署方式。GXP1630 是已验证样例设备，不是唯一支持设备。完整 BLF 流程使用的 SIP 电话或软电话必须支持 SIP REGISTER 和 Eventlist BLF/dialog 订阅。

## 拓扑

```text
GXP1630 电话
  192.168.10.2/24
  SIP 账号 1001/1001
  Eventlist BLF 601-608
        |
        | SIP 5060/udp, RTP 10000-10020/udp
        v
Asterisk Docker 容器
  容器: phone-agent-asterisk-mvp
  主机端口: 5060/udp, 10000-10020/udp, 127.0.0.1:5038/tcp
        |
        | HTTP 回调到 host.docker.internal:8080
        | Spring 通过 127.0.0.1:5038 控制 AMI
        v
Spring Boot 运行在 Mac 主机
  开发脚本绑定: 0.0.0.0:8080
  本地 Console 地址: http://127.0.0.1:8080/console/
  MySQL: 127.0.0.1:3307/phone_agent
  运行时目录: ./runtime
```

Spring Boot 运行在 Mac 主机上，不在 Docker 内。Asterisk 在 Docker 中运行，并通过 `host.docker.internal:8080` 访问 Spring。开发脚本会把 Spring 绑定到主机可达地址以支持回调；Codex Console 和 Codex API 仍通过 `CodexConsoleLoopbackGuard` 拒绝非本机回环请求。

仓库内 Asterisk Compose 文件使用 `mlan/asterisk:latest`。该镜像提供本地实验环境的 Asterisk 运行时；仓库提供挂载到容器内的配置文件。如果你的 Docker 镜像仓库无法拉取该镜像，可以改用等价 Asterisk 镜像，但需要重新验证 AMI、PJSIP、拨号计划、提示音和录音路径后再测试电话流程。

## 主机前置条件

请安装或配置：

- JDK 25，通过 `JAVA_HOME` 或 `PATH` 提供。
- Docker 和 Compose 插件。
- 主机可访问、由用户自行准备的 MySQL 服务和数据库。
- `ffmpeg`、Whisper 兼容命令和 Whisper 模型文件。
- `tmux`、`ttyd`、`codex`、`curl` 和 `nc`。
- macOS `say` 或通过 `PHONE_AGENT_SAY_COMMAND` 配置的其他 TTS 命令。

复制 `.env.example` 到未跟踪的本地文件，例如 `.env.local`，按本机环境修改后在运行开发脚本前导出：

```bash
set -a
source .env.local
set +a
```

`.env.local` 中的命令既可以是 `PATH` 上的命令名，也可以是绝对可执行路径。本机路径和真实密钥应保留在 `.env.local`，不要提交到仓库。

## 默认电话配置

默认完整电话样例由这些值生成：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `PHONE_AGENT_SIP_EXTENSION` | `1001` | Asterisk 端点/AOR 和电话 SIP 用户。 |
| `PHONE_AGENT_SIP_AUTH_ID` | `1001` | Asterisk 认证用户名和电话认证 ID。 |
| `PHONE_AGENT_SIP_PASSWORD` | `1001` | Asterisk 认证密码和电话认证密码。 |
| `PHONE_AGENT_RING_TARGET` | `PJSIP/1001` | Ring Phone 的 AMI Originate 通道。 |
| `PHONE_AGENT_BLF_EVENTLIST_URI` | `phone-agent-slots` | Asterisk 资源列表和电话 Eventlist BLF URI。 |
| `PHONE_AGENT_BLF_EXTENSIONS` | `601,602,603,604,605,606,607,608` | 有序 BLF 按键值；第 1 项映射 slot 1。 |
| `PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS` | `192.168.10.1` | 生成 PJSIP 传输配置的外部信令地址。 |
| `PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS` | `192.168.10.1` | 生成 PJSIP 传输配置的外部媒体地址。 |

本机配置到电话侧字段的映射：

| 电话侧字段 | 来源值 |
| --- | --- |
| SIP 服务器 | Asterisk 主机 IP，通常是 `PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS`。 |
| SIP 用户 | `PHONE_AGENT_SIP_EXTENSION`。 |
| SIP 认证 ID | `PHONE_AGENT_SIP_AUTH_ID`。 |
| SIP 密码 | `PHONE_AGENT_SIP_PASSWORD`。 |
| Eventlist BLF URI | `PHONE_AGENT_BLF_EVENTLIST_URI`。 |
| BLF 按键值 | `PHONE_AGENT_BLF_EXTENSIONS`，顺序保持一致。 |

Phone Agent 只说明这些字段，不维护各厂商 UI 路径。它不提供托管 SaaS 配置、公网暴露指导或电话品牌 UI 教程。

## 电话配置

GXP1630 账号：

```text
Account Active: Yes
SIP Server: 192.168.10.1
SIP User ID: 1001
Authenticate ID: 1001
Authenticate Password: 1001
Name: GXP1630
```

GXP1630 Eventlist BLF：

```text
Eventlist BLF URI: phone-agent-slots
MPK1-8 mode: Eventlist BLF
MPK1-8 account: Account 1
MPK1-8 values: 601, 602, 603, 604, 605, 606, 607, 608
```

已验证的 Mac 直连网卡地址是 `192.168.10.1/24`；电话地址是 `192.168.10.2/24`。

完整 BLF 红灯体验要求配置后的 SIP 注册和 Eventlist BLF/dialog 订阅都成立。只有 SIP 注册成功，不足以证明 BLF 订阅观察者已生效。

## 自定义 SIP 和 BLF 示例

修改 `.env.local` 后导出变量，并启动完整工作流。`start` 会在 Asterisk 启动前刷新生成的 Asterisk 配置：

```bash
set -a
source .env.local
set +a
scripts/phone-agent-dev.sh stop
scripts/phone-agent-dev.sh start
scripts/phone-agent-dev.sh status
```

示例 `1001->1002`：

```bash
PHONE_AGENT_SIP_EXTENSION=1002
PHONE_AGENT_SIP_AUTH_ID=1002
PHONE_AGENT_SIP_PASSWORD=change-me
PHONE_AGENT_RING_TARGET=PJSIP/1002
```

示例 `601-608->801-808`：

```bash
PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804,805,806,807,808
```

示例 `801-804`：

```bash
PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804
```

只配置 `801-804` 时，运行时只有 4 个 active slot。slot 1 映射 `801`，slot 4 映射 `804`，slot 5 不属于当前电话配置。

修改 SIP 或 BLF 配置后，需要导出新值并运行 `scripts/phone-agent-dev.sh start`，让生成的 Asterisk 配置在启动前刷新。本轮不会为本地历史数据执行数据库迁移，不会在运行中热重映射 active task，也不会自动改写旧 task/slot 状态。如果旧本地数据与新 slot 布局冲突，请手工清理本地开发数据库或重建本地环境。

## 启动

推荐在仓库根目录启动完整电话链路：

```bash
scripts/phone-agent-dev.sh start
```

完整电话工作流会检查 Docker、Asterisk 输入、外部 MySQL 连通性、Java 25、`ffmpeg`、`whisper`、`tmux`、`ttyd` 和 `codex`，必要时刷新生成的 Asterisk 配置，然后启动 Asterisk 和 Spring Boot jar。脚本不会创建、启动、停止或管理 MySQL 服务、容器或数据库。

纯软件启动是开发和测试辅助路径，适合 Console/API 冒烟和 CI。它不是 Phone Agent 部署证明，因为它跳过 Asterisk、电话注册、Eventlist BLF 订阅、BLF 订阅观察者、振铃、录音和 ASR 回调。

如果只启动 Asterisk，服务定义位于 `ops/asterisk-mvp/docker-compose.yml`：

```yaml
image: mlan/asterisk:latest
ports:
  - "5060:5060/udp"
  - "10000-10020:10000-10020/udp"
  - "127.0.0.1:5038:5038/tcp"
```

手动启动：

```bash
nc -z -w 1 "${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" "${PHONE_AGENT_MYSQL_PORT:-3306}"
cd ops/asterisk-mvp
docker compose up -d
cd ../..
./gradlew bootRun
```

如果你选择用 Docker 运行 MySQL，它仍然是你自行提供的 MySQL 服务，并通过 `PHONE_AGENT_MYSQL_HOST`、`PHONE_AGENT_MYSQL_PORT`、`PHONE_AGENT_MYSQL_DATABASE`、`PHONE_AGENT_MYSQL_USER` 和 `PHONE_AGENT_MYSQL_PASSWORD` 连接。Phone Agent 开发脚本不会创建该容器或数据库。

Spring 默认配置位于 `src/main/resources/application.properties`；除非设置了 `PHONE_AGENT_SPRING_BIND_ADDRESS`，`scripts/phone-agent-dev.sh start` 会把 `server.address` 覆盖为 `0.0.0.0`：

```properties
server.address=127.0.0.1
server.port=8080
phone-agent.runtime-dir=runtime
phone-agent.asr.whisper-command=whisper
phone-agent.asr.model-path=models/whisper/ggml-small.bin
phone-agent.ami.host=127.0.0.1
phone-agent.ami.port=5038
spring.datasource.url=jdbc:mysql://127.0.0.1:3307/phone_agent?useUnicode=true&characterEncoding=utf8&connectionTimeZone=LOCAL
spring.datasource.username=root
spring.datasource.password=root
```

Spring 启动时会运行 Flyway migration，从 MySQL 恢复 session/task/slot/bridge 状态，生成提示音频，恢复未完成电话状态，并刷新 BLF 状态。

## 健康检查

使用：

```bash
scripts/phone-agent-dev.sh status
```

`status` 是完整电话路径的首选只读诊断入口。它报告 Java、Spring、MySQL TCP 可达性、电话配置、Asterisk、生成的 Asterisk 配置、Asterisk 到 Spring 回调连通性、AMI 权限、SIP 注册、Eventlist BLF 订阅和 BLF 订阅观察者。数据库、认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证。doctor 和 software-only 等兼容保留命令可供维护者使用，但不是公开部署检查。

手动检查：

```bash
cd ops/asterisk-mvp
docker compose config
cd ../..
curl -fsS http://127.0.0.1:8080/actuator/health
docker exec phone-agent-asterisk-mvp sh -lc \
  'curl -fsS --max-time 2 http://host.docker.internal:8080/actuator/health'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

预期电话和 BLF 状态：

```text
配置的 SIP 分机存在联系记录
配置的 Eventlist BLF URI 有 1 个活跃 dialog 订阅
配置的 BLF 分机有 Watchers 1
空闲 slot 为 State:Idle
```

## 独立电话任务冒烟测试

提交任务：

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/tasks \
  -H 'Content-Type: application/json' \
  --data '{"text":"请在听到提示后说，我听到了测试消息，然后按井号键结束。"}'
```

预期 API 状态：

```json
{"taskId":"...","slot":1,"status":"NOTIFIED"}
```

电话侧流程：

1. 对应 BLF 键变红。
2. 按下红色 BLF 键。
3. 听取消息和回复提示。
4. 提示音后说话。
5. 按 `#` 或挂断。
6. 录音完成后 BLF 键回到绿色。

检查结果：

```bash
curl -fsS http://127.0.0.1:8080/api/tasks/<taskId>
```

预期独立任务终态：

```text
status=ASR_DONE
recordingFile=runtime/recordings/<taskId>.wav
replyText is non-empty
```

## Codex Phone Bridge 冒烟测试

1. 打开 `http://127.0.0.1:8080/console/`。
2. 在 Console 创建受管 Codex session。
3. 使用嵌入的 tmux/Codex terminal，直到 Codex 请求用户输入。
4. Poller 检测到 `WAITING_USER` 后，一个 BLF 键应变红。
5. 按红色 BLF 键，听消息，说话，然后按 `#` 或挂断。
6. 录音保存后 slot 应回到绿色。
7. Console 应展示 bridge 从 ASR 到 reply 的进度。
8. 成功后，语音回复会被粘贴回同一个受管 Codex tmux 会话，并带有明确标记“用户电话回复”的前缀。

Console 操作：

```text
Cancel Phone Reminder: pickup 前可用。
Renotify: bridge 状态为 FAILED_TASK_CREATE、FAILED_BLF_NOTIFY 或 CANCELLED 时可用。
```

Renotify 复用同一个 bridge，最多创建一个 active replacement phone task。

## 运行时状态

MySQL 是以下数据的事实来源：

```text
codex_sessions
phone_tasks
phone_slots
codex_phone_bridges
```

运行时音频和录音保留在磁盘：

```text
runtime/sounds
runtime/recordings
runtime/asr-input
```

如果本机已经有 `mysql` 客户端，可选进行人工数据库排查：

```bash
mysql \
  -h"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" \
  -P"${PHONE_AGENT_MYSQL_PORT:-3306}" \
  -u"${PHONE_AGENT_MYSQL_USER:-phone_agent}" \
  -p"${PHONE_AGENT_MYSQL_PASSWORD:-change-me}" \
  "${PHONE_AGENT_MYSQL_DATABASE:-phone_agent}" \
  -e 'SELECT task_id,status,slot,bridge_id FROM phone_tasks ORDER BY created_at DESC LIMIT 10'
mysql \
  -h"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" \
  -P"${PHONE_AGENT_MYSQL_PORT:-3306}" \
  -u"${PHONE_AGENT_MYSQL_USER:-phone_agent}" \
  -p"${PHONE_AGENT_MYSQL_PASSWORD:-change-me}" \
  "${PHONE_AGENT_MYSQL_DATABASE:-phone_agent}" \
  -e 'SELECT bridge_id,status,task_id,slot FROM codex_phone_bridges ORDER BY created_at DESC LIMIT 10'
```

停止 Spring 和 Asterisk，但保留 MySQL 状态：

```bash
scripts/phone-agent-dev.sh stop
```

保留 MySQL task ownership，同时刷新 stale BLF 物理状态：

```bash
curl -fsS -X POST 'http://127.0.0.1:8080/internal/admin/blf/sync?reason=manual'
```

如果 Asterisk 自身状态异常，重建它：

```bash
cd ops/asterisk-mvp
docker compose up -d --force-recreate
```

然后重启 Spring Boot，让启动恢复和 BLF sync 对新的 Asterisk 容器生效。

## 常用诊断

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
docker ps --filter name=phone-agent-asterisk-mvp
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show channels verbose'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
docker exec phone-agent-asterisk-mvp asterisk -rx 'dialplan show 601@phone-agent-mvp'
scripts/phone-agent-dev.sh status
scripts/phone-agent-dev.sh logs spring
scripts/phone-agent-dev.sh logs asterisk
scripts/phone-agent-dev.sh logs all -f
```
