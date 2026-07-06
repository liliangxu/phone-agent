# 安全

[English](SECURITY.md) | 中文

Phone Agent 面向本地开发和本地实验环境。

## Console 风险

Console 可以通过 `ttyd` 和 `tmux` 打开连接到 Codex 的可写终端。能访问 Console 的人可能就能操作这些终端，因此 Console 访问应视为本机高权限访问。

## 默认绑定

应用默认配置为：

```properties
server.address=127.0.0.1
```

除非已有经过审查的安全设计，否则不要改变这个默认值。

开发脚本可以使用 `PHONE_AGENT_SPRING_BIND_ADDRESS=0.0.0.0`，让本机 Docker/Asterisk callback 访问 Spring；这不是生产远程访问模型，不能暴露到不可信网络。

## 远程访问要求

任何远程或共享部署前，都需要额外设计并审查：

- 认证。
- 终端和 API 操作授权。
- CSRF 防护。
- 网络防火墙规则。
- TLS 终止。
- Secret 管理。
- 终端操作审计日志。

本项目当前不包含这些控制。

## Secret

不要提交真实 AMI、MySQL、SIP 或设备凭证。`.env.example` 只提供占位值，本地覆盖文件应保持未跟踪。

## Workspace 边界

`phone-agent.codex.allowed-workspace-roots` 限制受管 Codex session 可启动的位置。没有经过审查的原因时，不要把它扩大到 `/` 或用户 home 目录。

## 硬件和网络

电话注册、Asterisk AMI 和录音依赖本机网络。AMI 和 SIP 服务应尽可能保留在可信本地接口上。
