# Architecture

English | [中文](ARCHITECTURE.zh-CN.md)

Phone Agent is a local Spring Boot application that coordinates Codex terminal sessions and phone tasks.

## Modules

- `codex`: manages Codex sessions, ttyd terminals, JSONL polling, phone bridge state, and Console APIs.
- `task`: manages phone task slots, TTS audio, recordings, ASR jobs, and Asterisk AMI operations.
- `inbound`: accepts text or phone-recorded inbound intents and creates Codex sessions.
- `ring`: sends a global desk-phone ring/reminder.
- `sip`: contains the lightweight SIP registrar pieces used by the local phone setup.
- `config`: binds local runtime, ASR, AMI, and Codex settings.

## Main Data Flow

```text
Console -> /api/codex-sessions -> CodexSessionService
        -> tmux + Codex CLI + ttyd
        -> JSONL polling -> session state
        -> waiting event -> CodexPhoneBridgeService
        -> TaskService -> Asterisk/phone
        -> recording -> ASR -> phone reply prompt
        -> Codex tmux session
```

## Storage

MySQL stores durable task, inbound, session, and bridge state. Flyway migrations live under `src/main/resources/db/migration`.

Runtime files live under `runtime/` by default:

- TTS slot audio.
- Call recordings.
- Codex session registry files.
- Script logs and process metadata.

## Console

The Console is served from `src/main/resources/static/console`. It uses static HTML/CSS/JS and calls local JSON APIs. It is intentionally dense and operational: session list on the left, terminal cockpit on the right, with fixed `1/2/3/4/6` layouts.

The UI keeps language selection in `localStorage` and maps backend enum values to Chinese or English visible labels.

## Phone Bridge State

Codex waiting events create bridge records. Live bridge records can create phone tasks. Terminal states such as failed ASR, cancelled calls, no reply, and replied-to-Codex are retained so the Console can show status and decide whether cancel or renotify is meaningful.

## Prompt Boundaries

Inbound initial requests and phone replies use different prompt templates. A phone reply is explicitly framed as the continuation of the previous Codex turn, so short replies such as "continue" are not treated as brand-new tasks.
