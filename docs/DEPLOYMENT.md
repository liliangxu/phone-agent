# Phone Agent Local Deployment

English | [中文](DEPLOYMENT.zh-CN.md)

This document describes the local deployment validated with a Grandstream GXP1630 and the Codex Phone Bridge. GXP1630 is a validated example device, not the only supported phone. Any SIP phone or softphone used for the full BLF workflow must support SIP REGISTER and Eventlist BLF/dialog subscription.

## Topology

```text
GXP1630 phone
  192.168.10.2/24
  SIP account 1001/1001
  Eventlist BLF 601-608
        |
        | SIP 5060/udp, RTP 10000-10020/udp
        v
Asterisk Docker container
  container: phone-agent-asterisk-mvp
  host ports: 5060/udp, 10000-10020/udp, 127.0.0.1:5038/tcp
        |
        | HTTP callbacks to host.docker.internal:8080
        | AMI controlled by Spring via 127.0.0.1:5038
        v
Spring Boot on the Mac host
  dev-script bind: 0.0.0.0:8080
  local console URL: http://127.0.0.1:8080/console/
  MySQL: 127.0.0.1:3307/phone_agent
  runtime dir: ./runtime
```

Spring Boot runs on the Mac host, not inside Docker. Asterisk runs in Docker and reaches Spring through `host.docker.internal:8080`. The dev script binds Spring to a host-reachable address so callbacks work; the Codex Console and Codex APIs still reject non-loopback requests through `CodexConsoleLoopbackGuard`.

The checked-in Asterisk Compose file uses `mlan/asterisk:latest`. That image provides the Asterisk runtime for the local lab setup; the repository supplies the config files mounted into the container. If your Docker registry cannot pull that image, use an equivalent Asterisk image and revalidate AMI, PJSIP, dialplan, sound, and recording paths before testing phone flows.

## Host Prerequisites

Install or configure:

- JDK 25, available through `JAVA_HOME` or `PATH`.
- Docker with the Compose plugin.
- A user-prepared MySQL service and database reachable from the host.
- `ffmpeg`, a Whisper-compatible command, and a Whisper model file.
- `tmux`, `ttyd`, `codex`, `curl`, and `nc`.
- macOS `say` or another TTS command configured through `PHONE_AGENT_SAY_COMMAND`.

Copy `.env.example` to an untracked local file such as `.env.local`, edit it for your machine, then export the values before running the dev script:

```bash
set -a
source .env.local
set +a
```

Commands in `.env.local` may be command names on `PATH` or absolute executable paths. Local machine paths and real secrets should stay in `.env.local`, not in committed files.

## Default Phone Configuration

The default full-phone example is generated from these values:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PHONE_AGENT_SIP_EXTENSION` | `1001` | Asterisk endpoint/aor and phone SIP user. |
| `PHONE_AGENT_SIP_AUTH_ID` | `1001` | Asterisk auth username and phone authenticate ID. |
| `PHONE_AGENT_SIP_PASSWORD` | `1001` | Asterisk auth password and phone authenticate password. |
| `PHONE_AGENT_RING_TARGET` | `PJSIP/1001` | AMI Originate channel for Ring Phone. |
| `PHONE_AGENT_BLF_EVENTLIST_URI` | `phone-agent-slots` | Asterisk resource list and phone Eventlist BLF URI. |
| `PHONE_AGENT_BLF_EXTENSIONS` | `601,602,603,604,605,606,607,608` | Ordered BLF key values; item 1 maps to slot 1. |
| `PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS` | `192.168.10.1` | Generated PJSIP transport external signaling address. |
| `PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS` | `192.168.10.1` | Generated PJSIP transport external media address. |

Mapping from local configuration to phone-side fields:

| Phone-side field | Source value |
| --- | --- |
| SIP server | Asterisk host IP, usually `PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS`. |
| SIP user | `PHONE_AGENT_SIP_EXTENSION`. |
| SIP auth ID | `PHONE_AGENT_SIP_AUTH_ID`. |
| SIP password | `PHONE_AGENT_SIP_PASSWORD`. |
| Eventlist BLF URI | `PHONE_AGENT_BLF_EVENTLIST_URI`. |
| BLF key values | `PHONE_AGENT_BLF_EXTENSIONS`, in the same order. |

Phone Agent documents these fields instead of vendor-specific UI paths. It does not provide hosted SaaS setup, public internet exposure guidance, or phone-brand UI tutorials.

## Phone Configuration

GXP1630 account:

```text
Account Active: Yes
SIP Server: 192.168.10.1
SIP User ID: 1001
Authenticate ID: 1001
Authenticate Password: 1001
Name: GXP1630
```

GXP1630 Eventlist BLF:

```text
Eventlist BLF URI: phone-agent-slots
MPK1-8 mode: Eventlist BLF
MPK1-8 account: Account 1
MPK1-8 values: 601, 602, 603, 604, 605, 606, 607, 608
```

The validated Mac-side direct Ethernet address is `192.168.10.1/24`; the phone is `192.168.10.2/24`.

The full BLF red-light experience requires the configured SIP registration and the Eventlist BLF/dialog subscription. A successful SIP registration without subscriptions is not enough to prove BLF watchers.

## Custom SIP and BLF Examples

After editing `.env.local`, export it and start the full workflow. `start` refreshes generated Asterisk configuration before Asterisk starts:

```bash
set -a
source .env.local
set +a
scripts/phone-agent-dev.sh stop
scripts/phone-agent-dev.sh start
scripts/phone-agent-dev.sh status
```

Example `1001->1002`:

```bash
PHONE_AGENT_SIP_EXTENSION=1002
PHONE_AGENT_SIP_AUTH_ID=1002
PHONE_AGENT_SIP_PASSWORD=change-me
PHONE_AGENT_RING_TARGET=PJSIP/1002
```

Example `601-608->801-808`:

```bash
PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804,805,806,807,808
```

Example `801-804`:

```bash
PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804
```

When only `801-804` is configured, the active runtime has four slots. Slot 1 maps to `801`, slot 4 maps to `804`, and slot 5 is outside the configured phone setup.

Changing SIP or BLF configuration requires exporting the new values and running `scripts/phone-agent-dev.sh start` so generated Asterisk config is refreshed before startup. This release has no database migration for historical local development data, does not hot-remap active tasks at runtime, and does not automatically rewrite old task/slot state. If old local data conflicts with the new slot layout, manually clean the local development database or rebuild the local environment.

## Startup

Preferred full phone startup from the repository root:

```bash
scripts/phone-agent-dev.sh start
```

The full phone workflow checks Docker, Asterisk inputs, external MySQL connectivity, Java 25, `ffmpeg`, `whisper`, `tmux`, `ttyd`, and `codex`, refreshes generated Asterisk config when needed, then starts Asterisk and the Spring Boot jar. It does not create, start, stop, or manage a MySQL service, container, or database.

Software-only startup is a developer/testing aid for Console/API smoke and CI. It is not the deployment proof for Phone Agent because it skips Asterisk, phone registration, Eventlist BLF subscriptions, BLF watchers, ringing, recording, and ASR callback behavior.

For Asterisk-only startup, the service is defined in `ops/asterisk-mvp/docker-compose.yml`:

```yaml
image: mlan/asterisk:latest
ports:
  - "5060:5060/udp"
  - "10000-10020:10000-10020/udp"
  - "127.0.0.1:5038:5038/tcp"
```

Manual startup:

```bash
nc -z -w 1 "${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}" "${PHONE_AGENT_MYSQL_PORT:-3306}"
cd ops/asterisk-mvp
docker compose up -d
cd ../..
./gradlew bootRun
```

If you choose to run MySQL in Docker, treat it as your own user-provided MySQL service and still connect through `PHONE_AGENT_MYSQL_HOST`, `PHONE_AGENT_MYSQL_PORT`, `PHONE_AGENT_MYSQL_DATABASE`, `PHONE_AGENT_MYSQL_USER`, and `PHONE_AGENT_MYSQL_PASSWORD`. The Phone Agent dev script does not create that container or database.

Default Spring configuration is in `src/main/resources/application.properties`; `scripts/phone-agent-dev.sh start` overrides `server.address` to `0.0.0.0` unless `PHONE_AGENT_SPRING_BIND_ADDRESS` is set:

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

Spring startup runs Flyway migrations, restores persisted session/task/slot/bridge state from MySQL, generates prompt audio, recovers incomplete phone state, and refreshes BLF state.

## Health Checks

Use:

```bash
scripts/phone-agent-dev.sh status
```

`status` is the first read-only diagnostic entry for the complete phone path. It reports Java, Spring, MySQL TCP reachability, phone configuration, Asterisk, generated Asterisk config, Asterisk-to-Spring callback reachability, AMI permissions, SIP registration, Eventlist BLF subscription, and BLF watchers. Database, authentication, and Flyway readiness are verified by Spring startup health and logs. Legacy doctor/software-only commands may exist for maintainers, but they are not the public deployment check.

Manual checks:

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

Expected phone and BLF state:

```text
Configured SIP extension contact exists
Configured Eventlist BLF URI has 1 active dialog subscription
Configured BLF extensions have Watchers 1
idle slots are State:Idle
```

## Standalone Phone Task Smoke Test

Submit a task:

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/tasks \
  -H 'Content-Type: application/json' \
  --data '{"text":"After the prompt, please say I heard the test message, then press pound to finish."}'
```

Expected API state:

```json
{"taskId":"...","slot":1,"status":"NOTIFIED"}
```

Phone-side flow:

1. The corresponding BLF key turns red.
2. Press the red BLF key.
3. Listen to the message and reply prompt.
4. Speak after the prompt.
5. Press `#` or hang up.
6. The BLF key returns to green after recording completion.

Check result:

```bash
curl -fsS http://127.0.0.1:8080/api/tasks/<taskId>
```

Expected final standalone task state:

```text
status=ASR_DONE
recordingFile=runtime/recordings/<taskId>.wav
replyText is non-empty
```

## Codex Phone Bridge Smoke Test

1. Open `http://127.0.0.1:8080/console/`.
2. Create a managed Codex session from the Console.
3. Use the embedded tmux/Codex terminal until Codex asks for user input.
4. After the poller detects `WAITING_USER`, one BLF key should turn red.
5. Press the red BLF key, listen to the message, speak, then press `#` or hang up.
6. The slot should return to green after recording is saved.
7. The Console should show bridge progress through ASR and reply.
8. On success, the spoken reply is pasted back into the same managed Codex tmux session with a prefix that explicitly marks it as the user's phone reply.

Console actions:

```text
Cancel Phone Reminder: available before pickup.
Renotify: available for FAILED_TASK_CREATE, FAILED_BLF_NOTIFY, or CANCELLED bridge states.
```

Renotify reuses the same bridge and creates at most one active replacement phone task.

## Runtime State

MySQL is the source of truth for:

```text
codex_sessions
phone_tasks
phone_slots
codex_phone_bridges
```

Runtime audio and recordings remain on disk:

```text
runtime/sounds
runtime/recordings
runtime/asr-input
```

Optional manual database debugging, if you already have a `mysql` client:

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

To stop Spring and Asterisk without clearing MySQL state:

```bash
scripts/phone-agent-dev.sh stop
```

To refresh stale BLF physical state while preserving MySQL task ownership:

```bash
curl -fsS -X POST 'http://127.0.0.1:8080/internal/admin/blf/sync?reason=manual'
```

If Asterisk itself is stale, recreate it:

```bash
cd ops/asterisk-mvp
docker compose up -d --force-recreate
```

Then restart Spring Boot so startup recovery and BLF sync run against the fresh Asterisk container.

## Useful Diagnostics

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
