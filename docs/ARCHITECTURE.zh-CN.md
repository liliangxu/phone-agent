# 架构

[English](ARCHITECTURE.md) | 中文

Phone Agent 是一个本机 Spring Boot 应用，用来协调 Codex 终端会话和电话任务。

## 主要模块

- `codex`：管理 Codex session、ttyd 终端、JSONL polling、phone bridge 状态和 Console API。
- `task`：管理电话任务槽位、TTS 音频、录音、ASR job 和 Asterisk AMI 操作。
- `inbound`：接收文本或电话录音入口，并创建 Codex session。
- `ring`：发送全局座机提醒。
- `sip`：本机电话设置所需的轻量 SIP registrar 相关代码。
- `config`：绑定 runtime、ASR、AMI 和 Codex 配置。

## 核心数据流

```text
Console -> /api/codex-sessions -> CodexSessionService
        -> tmux + Codex CLI + ttyd
        -> JSONL polling -> session state
        -> waiting event -> CodexPhoneBridgeService
        -> TaskService -> Asterisk/phone
        -> recording -> ASR -> phone reply prompt
        -> Codex tmux session
```

## 存储

MySQL 持久化 task、inbound、session 和 bridge 状态。Flyway migration 位于 `src/main/resources/db/migration`。

运行时文件默认位于 `runtime/`：

- TTS slot 音频。
- 通话录音。
- Codex session registry 文件。
- 脚本日志和进程元数据。

## Console

Console 位于 `src/main/resources/static/console`，由静态 HTML/CSS/JS 组成，并调用本机 JSON API。它是偏操作台的密集界面：左侧 session 列表，右侧 terminal cockpit，固定支持 `1/2/3/4/6` pane 布局。

UI 语言选择保存在 `localStorage`，后端 enum 会映射成中文或英文用户可见状态。

## Phone Bridge 状态

Codex waiting event 会创建 bridge record。live bridge record 可以创建电话任务。ASR 失败、取消呼叫、无回复、已回复 Codex 等终态会被保留，用于 Console 展示状态并判断 cancel 或 renotify 是否有意义。

## Prompt 边界

Inbound 初始请求和 phone reply 使用不同 prompt 模板。phone reply 会被明确包装为上一轮 Codex 对话的继续，因此“继续”等短回复不会被误判成全新任务。
