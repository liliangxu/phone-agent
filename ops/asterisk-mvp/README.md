# Asterisk MVP for GXP1630 Verification

This directory contains the Asterisk configuration used to verify a directly
connected Grandstream GXP1630 and run the Phone Agent MVP BLF slots.

Network assumptions:

- Mac USB LAN: `192.168.10.1/24`
- GXP1630 LAN: `192.168.10.2/24`
- SIP extension: `1001`
- SIP password: `1001`
- Test extensions: `701`, `702`, `703`
- Phone Agent Eventlist BLF values: `601` through `608`

Phone account settings:

- Account Active: `Yes`
- SIP Server: `192.168.10.1`
- SIP User ID: `1001`
- Authenticate ID: `1001`
- Authenticate Password: `1001`
- Name: `GXP1630`

Run Asterisk:

```bash
mkdir -p runtime/sounds/slots runtime/sounds/prompts runtime/recordings
cd ops/asterisk-mvp
docker compose up -d
```

The Compose file pulls `mlan/asterisk:latest`. If your environment cannot pull
that image, replace it with an equivalent Asterisk image and revalidate AMI,
PJSIP, dialplan, sound, and recording paths.

Run Spring Boot on the Mac host:

```bash
./gradlew bootRun
```

By default the app expects:

```text
phone-agent.asr.whisper-command=whisper
phone-agent.asr.model-path=models/whisper/ggml-small.bin
phone-agent.asr.language=zh
```

For a temporary no-ASR smoke test, override the command with a harmless command
such as `/bin/echo`, but real transcription requires a working `whisper.cpp`
command and model.

Important ports:

```text
5060/udp             SIP from GXP1630
10000-10020/udp      RTP audio
127.0.0.1:5038/tcp   AMI for Spring Boot
```

Configure the GXP1630 Eventlist BLF keys as:

```text
Eventlist BLF URI: phone-agent-slots
MPK1-8 value: 601, 602, 603, 604, 605, 606, 607, 608
```

Submit a task:

```bash
curl -X POST http://127.0.0.1:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"text":"Please reply to this test message on the phone."}'
```

Check Asterisk can reach Spring Boot:

```bash
docker exec phone-agent-asterisk-mvp \
  sh -lc 'curl -fsS --max-time 2 http://host.docker.internal:8080/actuator/health'
```

Reload Asterisk after changing files in this directory:

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'dialplan reload'
```

If `pjsip.conf` changes, recreate the container instead of relying on an
interactive `pjsip reload` command, which is not available in the tested image:

```bash
cd ops/asterisk-mvp
docker compose up -d --force-recreate
```

Before phone validation, confirm the loaded dialplan includes the MVP recording
path:

```bash
docker exec phone-agent-asterisk-mvp \
  asterisk -rx 'dialplan show 601@phone-agent-mvp' | grep -E 'Playback\\(beep\\)|Record\\('
```

Useful Asterisk checks:

```bash
docker exec phone-agent-asterisk-mvp asterisk -rx 'manager show settings'
docker exec phone-agent-asterisk-mvp asterisk -rx 'core show hints'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show contacts'
docker exec phone-agent-asterisk-mvp asterisk -rx 'pjsip show subscriptions inbound'
```
