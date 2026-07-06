# 排障

[English](TROUBLESHOOTING.md) | 中文

先运行完整链路只读状态检查：

```bash
scripts/phone-agent-dev.sh status
```

`status` 是 Java、Spring、MySQL TCP 可达性、电话配置、Asterisk、生成的 Asterisk 配置、Asterisk 到 Spring 回调连通性、AMI 权限、SIP 注册、Eventlist BLF 订阅和 BLF 订阅观察者的首选诊断入口。数据库、认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证。它是只读命令，会输出修复方向，但不会启动服务、生成配置或修改 MySQL。

纯软件排障是开发和测试辅助路径，适合 Console/API 冒烟和 CI。它只覆盖 Spring、MySQL、Console、Codex 进程配置和本地 HTTP 检查。它不证明 Asterisk、电话注册、Eventlist BLF 订阅、BLF 订阅观察者、录音或 ASR 回调。完整电话排障使用 `.env.local` 导出的值；不要假定默认值仍然是 `1001`、`phone-agent-slots` 或 `601-608`。

## Spring Boot 无法启动

- 检查已配置的 JDK。开发脚本在启动和 status 检查中要求 Java 25。
- 检查 `server.address` 和 `server.port`。
- 检查 `runtime/logs` 或开发脚本创建的 tmux 会话。
- 先确认 MySQL 可达，再判断是否是 Web 层问题。

## MySQL 或 Flyway 失败

- 检查主机、端口、数据库、用户名和密码。
- 确认用户自行准备的 MySQL 服务和 `PHONE_AGENT_MYSQL_DATABASE` 数据库已存在且可访问。
- 开发脚本只检查 MySQL TCP 可达性。数据库认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证。
- 如果本机已经有 `mysql` 客户端，可选进行人工数据库排查：

```bash
mysql \
  -h"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" \
  -P"${PHONE_AGENT_MYSQL_PORT:-3306}" \
  -u"${PHONE_AGENT_MYSQL_USER:-phone_agent}" \
  -p"${PHONE_AGENT_MYSQL_PASSWORD:-change-me}" \
  "${PHONE_AGENT_MYSQL_DATABASE:-phone_agent}" \
  -e 'SELECT 1'
```

- Flyway migration 在 Spring 启动时执行。Flyway 失败说明 Spring 已连接到用户准备的数据库，但 schema migration 未完成。
- 不要默认删除或改写迁移历史。
- 修改 SIP 或 BLF 配置不需要数据库迁移。如果本地开发历史数据与新的 slot 布局冲突，请手工清理本地开发数据库或重建本地环境。

## Console 能打开但 API 失败

- 用浏览器开发者工具检查 `/api/codex-sessions`。
- 确认请求来自本机回环地址或允许的本机地址。
- 改 bind address 前，先排查 `CodexConsoleLoopbackGuard` 失败。

## Codex Terminal 无法打开

- 确认 `codex`、`tmux` 和 `ttyd` 已安装且可用。
- 确认工作区位于 `phone-agent.codex.allowed-workspace-roots` 内。
- 检查 ttyd 端口是否被本机阻塞。

## 呼叫座机失败

- 确认 Asterisk 正在运行。
- 确认 AMI 主机、端口、用户名和密钥。
- 确认 `PHONE_AGENT_RING_TARGET` 与配置的 SIP 端点一致，例如使用 `PHONE_AGENT_SIP_EXTENSION=1002` 时应为 `PJSIP/1002`。
- 确认电话按配置的 SIP 分机注册成功。
- 判断失败原因是电话注册问题还是 AMI 连接问题。

## 电话注册或 BLF 异常

- 导出 `.env.local` 后运行 `scripts/phone-agent-dev.sh status`。
- 检查配置后的 SIP 注册，不要固定看某个默认分机：

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
```

例如执行 `1001->1002` 修改后，应查找 `PHONE_AGENT_SIP_EXTENSION` 对应的 `1002` 联系记录。

- 检查配置后的 Eventlist BLF 订阅：

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
```

订阅应引用 `PHONE_AGENT_BLF_EVENTLIST_URI`，并使用 dialog event。

- 检查配置后的 BLF 订阅观察者：

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

如果 `PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804`，只要求 `801-804`。未配置 `601-608` 或 `805-808` 时，不应把它们缺失当作失败。

- 如果尚未配置真实电话或软电话，自动化 Asterisk 检查可能通过，但电话注册、Eventlist BLF 订阅和 BLF 订阅观察者仍然是 DOWN。这是需要用户协助完成的硬件配置状态，不代表完整电话链路通过。

## 生成的 Asterisk 配置漂移

如果 `.env.local` 已修改，但生成的 Asterisk 配置仍是旧值，运行：

```bash
scripts/phone-agent-dev.sh stop
scripts/phone-agent-dev.sh start
scripts/phone-agent-dev.sh status
```

然后验证：

```bash
cd ops/asterisk-mvp
docker compose config
cd ../..
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

SIP 和 BLF 值会在刷新生成的 Asterisk 配置和服务启动时从导出的环境变量读取。系统不会在运行中热重映射 active task，也不会通过数据库迁移改写本地历史开发数据。

## 录音或 ASR 失败

- 确认配置路径下存在 `ffmpeg`。
- 确认 Whisper 命令和模型路径有效。
- 确认录音文件非空。
- 检查 ASR 语言设置。

## 电话回复错误进入 Codex

- 确认 bridge 状态不是 `NO_REPLY`、`CANCELLED` 或陈旧状态。
- 确认 Codex tmux 会话仍然存在。
- 用 `phone-agent.codex.prompt-language` 确认提示词语言符合预期。

## 移动端 UI 异常

- 用真实浏览器视口验证，不要只看 DOM 或 CSS。
- 检查 390px 和 430px 宽度。
- 上报问题时附带截图。
