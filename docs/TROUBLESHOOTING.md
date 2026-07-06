# Troubleshooting

English | [中文](TROUBLESHOOTING.zh-CN.md)

Start with the read-only full-chain status command:

```bash
scripts/phone-agent-dev.sh status
```

`status` is the first diagnostic entry for Java, Spring, MySQL TCP reachability, phone configuration, Asterisk, generated Asterisk config, Asterisk-to-Spring callback reachability, AMI permissions, SIP registration, Eventlist BLF subscription, and BLF watchers. Database, authentication, and Flyway readiness are verified by Spring startup health and logs. It is read-only; it reports repair directions but does not start services, generate config, or modify MySQL.

Software-only troubleshooting is a developer/testing aid for Console/API smoke and CI. It stops at Spring, MySQL, Console, Codex process setup, and local HTTP checks. It does not prove Asterisk, phone registration, Eventlist BLF subscriptions, BLF watchers, recording, or ASR callback behavior. Full phone troubleshooting uses the values exported from `.env.local`; do not assume the defaults are still `1001`, `phone-agent-slots`, or `601-608`.

## Spring Boot Does Not Start

- Check the configured JDK. The dev script requires Java 25 for startup and status checks.
- Check `server.address` and `server.port`.
- Check `runtime/logs` or the tmux session created by the dev script.
- Verify MySQL is reachable before assuming the web layer is broken.

## MySQL or Flyway Fails

- Confirm host, port, database, username, and password.
- Confirm the user-prepared MySQL service and `PHONE_AGENT_MYSQL_DATABASE` database exist and are reachable.
- The dev script checks MySQL TCP reachability only. Database authentication and Flyway readiness are verified by Spring startup health and logs.
- Optional manual database debugging, if you already have a `mysql` client:

```bash
mysql \
  -h"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" \
  -P"${PHONE_AGENT_MYSQL_PORT:-3306}" \
  -u"${PHONE_AGENT_MYSQL_USER:-phone_agent}" \
  -p"${PHONE_AGENT_MYSQL_PASSWORD:-change-me}" \
  "${PHONE_AGENT_MYSQL_DATABASE:-phone_agent}" \
  -e 'SELECT 1'
```

- Flyway migrations run during Spring startup. A Flyway failure means Spring connected to the user-prepared database but could not complete schema migration.
- Do not delete or rewrite migration history as a default fix.
- Changing SIP or BLF configuration does not require a database migration. If historical local development data conflicts with a new slot layout, manually clean the local development database or rebuild the local environment.

## Console Loads but APIs Fail

- Open browser devtools and check `/api/codex-sessions`.
- Confirm requests come from loopback or an allowed local address.
- Check `CodexConsoleLoopbackGuard` failures before changing bind addresses.

## Codex Terminal Does Not Open

- Confirm `codex`, `tmux`, and `ttyd` are installed.
- Confirm the workspace path is inside `phone-agent.codex.allowed-workspace-roots`.
- Check that the ttyd port is not blocked locally.

## Ring Phone Fails

- Confirm Asterisk is running.
- Confirm AMI host, port, username, and secret.
- Confirm `PHONE_AGENT_RING_TARGET` matches the configured SIP endpoint, for example `PJSIP/1002` when using `PHONE_AGENT_SIP_EXTENSION=1002`.
- Confirm the phone is registered as the configured SIP extension.
- Check whether the failure is a phone registration issue or an AMI connection issue.

## Phone Registration or BLF Is Down

- Run `scripts/phone-agent-dev.sh status` after exporting `.env.local`.
- Check configured SIP registration, not a fixed extension:

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
```

Look for the contact for `PHONE_AGENT_SIP_EXTENSION`, such as `1002` after a `1001->1002` change.

- Check the configured Eventlist BLF subscription:

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
```

The subscription should reference `PHONE_AGENT_BLF_EVENTLIST_URI` and dialog events.

- Check BLF watchers for the configured extension list:

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

For `PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804`, only `801-804` should be required. Do not treat missing `601-608` or `805-808` as a failure when they are not configured.

- If no physical phone or softphone has been configured, automated Asterisk checks can pass while phone registration, Eventlist BLF subscription, and BLF watchers remain down. That is a user-assisted hardware setup state, not a complete phone-path pass.

## Generated Asterisk Config Drift

If `.env.local` changes but generated Asterisk config still has old values, run:

```bash
scripts/phone-agent-dev.sh stop
scripts/phone-agent-dev.sh start
scripts/phone-agent-dev.sh status
```

Then validate:

```bash
cd ops/asterisk-mvp
docker compose config
cd ../..
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
```

SIP and BLF values are read from the exported environment when generated Asterisk config is refreshed and services start. There is no runtime hot remap of active tasks, and no database migration rewrites historical local development data.

## Recording or ASR Fails

- Confirm `ffmpeg` exists at the configured path.
- Confirm the Whisper command and model path are valid.
- Confirm the recording file is non-empty.
- Check the ASR language setting.

## Phone Reply Reaches Codex Incorrectly

- Confirm the bridge status is not `NO_REPLY`, `CANCELLED`, or stale.
- Confirm the Codex tmux session still exists.
- Confirm prompt language is configured as expected with `phone-agent.codex.prompt-language`.

## UI Looks Broken on Mobile

- Verify in a real browser viewport, not only DOM or CSS inspection.
- Check 390px and 430px widths.
- Capture a screenshot if reporting the issue.
