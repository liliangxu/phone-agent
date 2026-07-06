# 贡献指南

[English](CONTRIBUTING.md) | 中文

Phone Agent 的贡献应保持本机优先的安全模型。Console 可以控制可写终端会话，因此 UI、脚本和部署改动都不能暗示未认证远程暴露是安全的。

## 开发环境

1. 安装 JDK、MySQL、Codex CLI、`tmux` 和 `ttyd`。
2. 复制 `.env.example` 到未跟踪的本地文件，并导出需要的变量。
3. 运行 `scripts/phone-agent-dev.sh status`。
4. Java 改动运行 `./gradlew test jacocoTestReport`。
5. Console JavaScript 改动运行 `node --test src/test/js/*.test.mjs`。

## 需求流程

非平凡产品、UI、状态、安全或集成改动应走本仓库使用的 fd-v5 流程：

- PRD 和 Requirement Cases。
- Technical Design 和 Technical Cases。
- 带测试的开发循环。
- UI 改动提供真实浏览器 rendered evidence。
- 交付审查和最终审计。

纯文档改动可以更轻量，但安全和依赖说明必须准确。

## 测试要求

- Java 可执行生产代码改动需要单元测试或契约测试，并提供 JaCoCo 证据。
- Console JavaScript 可执行改动需要 Node 测试和 V8 coverage，或等价 verifier。
- UI 视觉正确性必须提供真实浏览器或目标运行环境的 rendered evidence。DOM 检查、CSS 规则或 computed state 只能作为辅助诊断证据，不能单独证明视觉正确。
- 只能依赖硬件的电话流程，在贡献者没有设备时可以记录为人工验证或不可用。

## UI 改动

改 Console UI 时：

- 同时验证桌面和移动 viewport。
- 检查文本是否重叠、裁切，或在移动端依赖 hover 才能理解。
- 除非经过需求审查，不要破坏 `1/2/3/4/6` pane 布局和拖拽 session 行为。
- 保持中文和英文可见文案同步。

## 文档改动

面向开源用户的公共文档必须按语言分文件：

- 英文默认入口：`README.md`、`CONTRIBUTING.md`、`docs/*.md`。
- 中文版本：`README.zh-CN.md`、`CONTRIBUTING.zh-CN.md`、`docs/*.zh-CN.md`。
- 每组成对文档顶部都应包含语言切换链接。

## 安全改动

不要削弱 loopback 和本地 workspace 保护。如果改动需要远程访问，应作为新的安全设计记录，而不是扩展默认本地开发设置。
