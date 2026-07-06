# Security

English | [中文](SECURITY.zh-CN.md)

Phone Agent is designed for local development and local lab use.

## Console Risk

The Console can open writable terminal sessions connected to Codex through `ttyd` and `tmux`. Anyone who can access the Console may be able to operate those terminals. Treat Console access as privileged local access.

## Default Binding

The application default is:

```properties
server.address=127.0.0.1
```

Keep this default unless you have a reviewed security design.

The dev script can use `PHONE_AGENT_SPRING_BIND_ADDRESS=0.0.0.0` so local Docker/Asterisk callbacks can reach Spring. That setting is not a production remote-access model. Do not expose it to untrusted networks.

## Remote Access Requirements

Before any remote or shared deployment, add and review:

- Authentication.
- Authorization for terminal and API actions.
- CSRF protection.
- Network firewall rules.
- TLS termination.
- Secret management.
- Audit logging for terminal actions.

Those controls are not included in the current project.

## Secrets

Do not commit real AMI, MySQL, SIP, or device credentials. `.env.example` uses placeholder values. Local overrides should stay untracked.

## Workspace Boundaries

`phone-agent.codex.allowed-workspace-roots` limits where managed Codex sessions may start. Do not broaden it to `/` or a user home directory without a reviewed reason.

## Hardware and Network

Phone registration, Asterisk AMI, and recordings depend on the local network. Keep AMI and SIP services on trusted local interfaces whenever possible.
