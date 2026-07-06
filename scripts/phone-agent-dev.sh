#!/usr/bin/env bash

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR_VALUE="${PHONE_AGENT_RUNTIME_DIR:-runtime}"
if [[ "$RUNTIME_DIR_VALUE" = /* ]]; then
  RUNTIME_DIR="$RUNTIME_DIR_VALUE"
else
  RUNTIME_DIR="$REPO_ROOT/$RUNTIME_DIR_VALUE"
fi
LOG_DIR="$RUNTIME_DIR/logs"
PID_FILE="$RUNTIME_DIR/phone-agent.pid"
SPRING_LOG="$LOG_DIR/spring.log"
SPRING_RUNNER="$RUNTIME_DIR/run-spring.sh"
SOUNDS_DIR="$RUNTIME_DIR/sounds"
RECORDINGS_DIR="$RUNTIME_DIR/recordings"
CODEX_REGISTRY_DIR="$RUNTIME_DIR/codex-sessions"

COMPOSE_FILE="$REPO_ROOT/ops/asterisk-mvp/docker-compose.yml"
ASTERISK_CONFIG_DIR="${PHONE_AGENT_ASTERISK_CONFIG_DIR:-$REPO_ROOT/ops/asterisk-mvp}"
ASTERISK_MANAGER_CONF="$ASTERISK_CONFIG_DIR/manager.conf"
ASTERISK_EXTENSIONS_CONF="$ASTERISK_CONFIG_DIR/extensions.conf"
ASTERISK_PJSIP_CONF="$ASTERISK_CONFIG_DIR/pjsip.conf"
ASTERISK_CONTAINER="${PHONE_AGENT_ASTERISK_CONTAINER:-phone-agent-asterisk-mvp}"
MYSQL_HOST="${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${PHONE_AGENT_MYSQL_PORT:-3307}"
MYSQL_DATABASE="${PHONE_AGENT_MYSQL_DATABASE:-phone_agent}"
MYSQL_USER="${PHONE_AGENT_MYSQL_USER:-root}"
MYSQL_PASSWORD="${PHONE_AGENT_MYSQL_PASSWORD:-root}"
WHISPER_MODEL_PATH="${PHONE_AGENT_WHISPER_MODEL_PATH:-$REPO_ROOT/models/whisper/ggml-small.bin}"
FFMPEG_COMMAND="${PHONE_AGENT_FFMPEG_COMMAND:-ffmpeg}"
WHISPER_COMMAND="${PHONE_AGENT_WHISPER_COMMAND:-whisper}"
ASR_LANGUAGE="${PHONE_AGENT_ASR_LANGUAGE:-zh}"
SAY_COMMAND="${PHONE_AGENT_SAY_COMMAND:-say}"
CODEX_COMMAND="${PHONE_AGENT_CODEX_COMMAND:-codex}"
TMUX_COMMAND="${PHONE_AGENT_TMUX_COMMAND:-tmux}"
TTYD_COMMAND="${PHONE_AGENT_TTYD_COMMAND:-ttyd}"
CODEX_PROMPT_LANGUAGE="${PHONE_AGENT_CODEX_PROMPT_LANGUAGE:-zh-CN}"
SPRING_URL="${PHONE_AGENT_SPRING_URL:-http://127.0.0.1:8080}"
CALLBACK_URL="${PHONE_AGENT_CALLBACK_URL:-http://host.docker.internal:8080}"
SIP_EXTENSION="${PHONE_AGENT_SIP_EXTENSION:-1001}"
SIP_AUTH_ID="${PHONE_AGENT_SIP_AUTH_ID:-1001}"
SIP_PASSWORD="${PHONE_AGENT_SIP_PASSWORD:-1001}"
RING_TARGET="${PHONE_AGENT_RING_TARGET:-PJSIP/1001}"
BLF_EVENTLIST_URI="${PHONE_AGENT_BLF_EVENTLIST_URI:-phone-agent-slots}"
BLF_EXTENSIONS="${PHONE_AGENT_BLF_EXTENSIONS:-601,602,603,604,605,606,607,608}"
ASTERISK_EXTERNAL_SIGNALING_ADDRESS="${PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS:-192.168.10.1}"
ASTERISK_EXTERNAL_MEDIA_ADDRESS="${PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS:-192.168.10.1}"
SPRING_PORT="${PHONE_AGENT_SPRING_PORT:-8080}"
SPRING_BIND_ADDRESS="${PHONE_AGENT_SPRING_BIND_ADDRESS:-0.0.0.0}"
SPRING_TMUX_SESSION="${PHONE_AGENT_SPRING_TMUX_SESSION:-phone-agent-spring}"
AMI_HOST="${PHONE_AGENT_AMI_HOST:-127.0.0.1}"
AMI_PORT="${PHONE_AGENT_AMI_PORT:-5038}"
AMI_USER="${PHONE_AGENT_AMI_USER:-phone-agent}"
AMI_SECRET="${PHONE_AGENT_AMI_SECRET:-phone-agent}"
START_TIMEOUT_SECONDS="${PHONE_AGENT_START_TIMEOUT_SECONDS:-60}"
REQUIRED_JAVA_MAJOR=25
BOOT_JAR_PATH="build/libs/phone-agent.jar"

EXIT_PREFLIGHT=1
EXIT_TIMEOUT=2
EXIT_UNHEALTHY=3
EXIT_USAGE=4

usage() {
  cat <<'USAGE'
Usage:
  scripts/phone-agent-dev.sh start
  scripts/phone-agent-dev.sh status
  scripts/phone-agent-dev.sh stop
  scripts/phone-agent-dev.sh logs [spring|asterisk|all] [-f]

Development compatibility:
  scripts/phone-agent-dev.sh init [--software-only|--phone] [--yes]
  scripts/phone-agent-dev.sh doctor [--software-only|--phone]
  scripts/phone-agent-dev.sh start [--software-only|--phone]
USAGE
}

info() {
  printf '%s\n' "$*"
}

ok() {
  printf '[ok] %s\n' "$*"
}

warn() {
  printf '[warn] %s\n' "$*"
}

fail() {
  printf '[fail] %s\n' "$*" >&2
}

manual() {
  printf '[manual] %s\n' "$*"
}

action() {
  printf '[action] %s\n' "$*"
}

have_cmd() {
  command -v "$1" >/dev/null 2>&1
}

pid_is_running() {
  local pid="$1"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" >/dev/null 2>&1
}

read_pid() {
  [[ -f "$PID_FILE" ]] && tr -d '[:space:]' < "$PID_FILE"
}

spring_pid_status() {
  local pid
  pid="$(read_pid)"
  if [[ -z "$pid" ]]; then
    printf 'NO_PID'
  elif pid_is_running "$pid"; then
    printf 'RUNNING pid=%s' "$pid"
  else
    printf 'STALE_PID pid=%s' "$pid"
  fi
}

spring_health_up() {
  have_cmd curl || return 1
  local pid owner
  pid="$(read_pid)"
  [[ -n "$pid" ]] && pid_is_running "$pid" || return 1
  owner="$(port_owner || true)"
  [[ -n "$owner" && "$owner" == *"pid=$pid "* ]] || return 1
  curl -fsS --max-time 2 "$SPRING_URL/actuator/health" 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'
}

mysql_tcp_up() {
  if have_cmd nc; then
    nc -z -w 1 "$MYSQL_HOST" "$MYSQL_PORT" >/dev/null 2>&1
    return $?
  fi

  return 1
}

docker_available() {
  have_cmd docker && docker version >/dev/null 2>&1
}

docker_compose_available() {
  have_cmd docker && docker compose version >/dev/null 2>&1
}

configured_command_available() {
  local command_value="$1"
  if [[ "$command_value" == */* ]]; then
    [[ -x "$command_value" ]]
  else
    have_cmd "$command_value"
  fi
}

configured_command_label() {
  local name="$1"
  local value="$2"
  if [[ "$name" == "$value" ]]; then
    printf '%s' "$name"
  else
    printf '%s=%s' "$name" "$value"
  fi
}

java_available() {
  java_command >/dev/null 2>&1
}

java_command() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    [[ -x "$JAVA_HOME/bin/java" ]] || return 1
    printf '%s/bin/java' "$JAVA_HOME"
  else
    command -v java || return 1
  fi
}

# Parses both modern (`25.0.1`) and legacy (`1.8.0_441`) version formats so
# PATH mistakes fail before Gradle builds with one JDK and Spring runs on another.
java_version_output() {
  local java_bin="$1"
  "$java_bin" -version 2>&1 | head -n 1
}

java_major_version_from_output() {
  local version_output="$1"
  sed -nE 's/.* version "([^"]+)".*/\1/p' <<<"$version_output" \
    | awk -F. '{ if ($1 == "1") print $2; else print $1; exit }'
}

require_java_25() {
  local java_bin version_output major
  if ! java_bin="$(java_command)"; then
    fail "missing required dependency: java"
    return 1
  fi
  version_output="$(java_version_output "$java_bin")"
  major="$(java_major_version_from_output "$version_output")"
  if [[ "$major" != "$REQUIRED_JAVA_MAJOR" ]]; then
    fail "Java $REQUIRED_JAVA_MAJOR required; current $java_bin reports: $version_output"
    fail "set JAVA_HOME to a JDK $REQUIRED_JAVA_MAJOR installation or put JDK $REQUIRED_JAVA_MAJOR first on PATH"
    return 1
  fi
  ok "java $REQUIRED_JAVA_MAJOR: $java_bin"
}

container_running() {
  local name="$1"
  docker_available || return 1
  [[ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null)" == "true" ]]
}

container_exists() {
  local name="$1"
  docker_available || return 1
  docker inspect "$name" >/dev/null 2>&1
}

asterisk_rx() {
  docker_available || return 1
  docker exec "$ASTERISK_CONTAINER" asterisk -rx "$1" 2>/dev/null
}

contains_csv_token() {
  local value="$1"
  local token="$2"
  tr ',' '\n' <<<"$value" | sed 's/[[:space:]]//g' | grep -qx "$token"
}

# Parses and validates phone-facing configuration once per command path so
# Asterisk generation, doctor checks, and Spring startup cannot drift apart.
validate_phone_config() {
  local unhealthy=0
  [[ "$SIP_EXTENSION" =~ ^[A-Za-z0-9_.-]+$ ]] || { fail "PHONE_AGENT_SIP_EXTENSION must match [A-Za-z0-9_.-]+"; unhealthy=1; }
  [[ "$SIP_AUTH_ID" =~ ^[A-Za-z0-9_.-]+$ ]] || { fail "PHONE_AGENT_SIP_AUTH_ID must match [A-Za-z0-9_.-]+"; unhealthy=1; }
  [[ "$BLF_EVENTLIST_URI" =~ ^[A-Za-z0-9_.-]+$ ]] || { fail "PHONE_AGENT_BLF_EVENTLIST_URI must match [A-Za-z0-9_.-]+"; unhealthy=1; }
  [[ "$RING_TARGET" =~ ^[A-Za-z0-9_./:@+-]+$ ]] || { fail "PHONE_AGENT_RING_TARGET must match [A-Za-z0-9_./:@+-]+"; unhealthy=1; }
  [[ "$ASTERISK_EXTERNAL_SIGNALING_ADDRESS" =~ ^[A-Za-z0-9_.:-]+$ ]] || {
    fail "PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS must match [A-Za-z0-9_.:-]+"
    unhealthy=1
  }
  [[ "$ASTERISK_EXTERNAL_MEDIA_ADDRESS" =~ ^[A-Za-z0-9_.:-]+$ ]] || {
    fail "PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS must match [A-Za-z0-9_.:-]+"
    unhealthy=1
  }
  if [[ -z "$SIP_PASSWORD" || "$SIP_PASSWORD" == *$'\n'* || "$SIP_PASSWORD" == *$'\r'* ]]; then
    fail "PHONE_AGENT_SIP_PASSWORD must be nonblank and must not contain CR/LF"
    unhealthy=1
  fi

  BLF_EXTENSION_ARRAY=()
  local raw token count=0 seen=" "
  IFS=',' read -r -a raw <<<"$BLF_EXTENSIONS"
  for token in "${raw[@]}"; do
    token="${token//[[:space:]]/}"
    if [[ -z "$token" ]]; then
      fail "PHONE_AGENT_BLF_EXTENSIONS contains an empty value"
      unhealthy=1
      continue
    fi
    if [[ ! "$token" =~ ^[0-9]+$ ]]; then
      fail "PHONE_AGENT_BLF_EXTENSIONS value '$token' must be numeric"
      unhealthy=1
      continue
    fi
    if [[ "$seen" == *" $token "* ]]; then
      fail "PHONE_AGENT_BLF_EXTENSIONS value '$token' is duplicated"
      unhealthy=1
      continue
    fi
    seen+="$token "
    BLF_EXTENSION_ARRAY+=("$token")
    count=$((count + 1))
  done
  if (( count < 1 || count > 8 )); then
    fail "PHONE_AGENT_BLF_EXTENSIONS must contain 1..8 unique numeric values"
    unhealthy=1
  fi
  return "$unhealthy"
}

blf_extensions_display() {
  local joined='' ext
  for ext in "${BLF_EXTENSION_ARRAY[@]}"; do
    if [[ -z "$joined" ]]; then
      joined="$ext"
    else
      joined+=",$ext"
    fi
  done
  printf '%s' "$joined"
}

# Parses a configured hint line and treats any numeric Watchers value >= 1 as
# ready. Missing hint lines, missing numbers, and Watchers 0 are not ready.
blf_extension_has_ready_watcher() {
  local hints="$1"
  local ext="$2"
  local line count
  line="$(grep -E "^[[:space:]]*${ext}@phone-agent-mvp[[:space:]]" <<<"$hints" | head -n 1)"
  [[ -n "$line" ]] || return 1
  count="$(sed -nE 's/.*Watchers[[:space:]]+([0-9]+).*/\1/p' <<<"$line" | head -n 1)"
  [[ "$count" =~ ^[0-9]+$ ]] && (( count >= 1 ))
}

render_pjsip_config_to() {
  local target="$1"
  {
    printf '%s\n' '; Generated by scripts/phone-agent-dev.sh start.'
    printf '%s\n' '; Re-run start after changing PHONE_AGENT_SIP_* or PHONE_AGENT_BLF_* values.'
    printf '\n[global]\n'
    printf 'type=global\n'
    printf 'user_agent=phone-agent-asterisk-mvp\n'
    printf '\n[transport-udp]\n'
    printf 'type=transport\n'
    printf 'protocol=udp\n'
    printf 'bind=0.0.0.0:5060\n'
    printf 'external_signaling_address=%s\n' "$ASTERISK_EXTERNAL_SIGNALING_ADDRESS"
    printf 'external_media_address=%s\n' "$ASTERISK_EXTERNAL_MEDIA_ADDRESS"
    printf '\n[%s]\n' "$SIP_EXTENSION"
    printf 'type=endpoint\n'
    printf 'transport=transport-udp\n'
    printf 'context=phone-agent-mvp\n'
    printf 'subscribe_context=phone-agent-mvp\n'
    printf 'disallow=all\n'
    printf 'allow=ulaw,alaw\n'
    printf 'auth=%s-auth\n' "$SIP_EXTENSION"
    printf 'aors=%s\n' "$SIP_EXTENSION"
    printf 'direct_media=no\n'
    printf 'rtp_symmetric=yes\n'
    printf 'force_rport=yes\n'
    printf 'rewrite_contact=no\n'
    printf '\n[%s-auth]\n' "$SIP_EXTENSION"
    printf 'type=auth\n'
    printf 'auth_type=userpass\n'
    printf 'username=%s\n' "$SIP_AUTH_ID"
    printf 'password=%s\n' "$SIP_PASSWORD"
    printf '\n[%s]\n' "$SIP_EXTENSION"
    printf 'type=aor\n'
    printf 'max_contacts=1\n'
    printf 'remove_existing=yes\n'
    printf '\n[%s]\n' "$BLF_EVENTLIST_URI"
    printf 'type=resource_list\n'
    printf 'event=dialog\n'
    local ext
    for ext in "${BLF_EXTENSION_ARRAY[@]}"; do
      printf 'list_item=%s\n' "$ext"
    done
    printf 'notification_batch_interval=500\n'
  } > "$target"
}

render_slot_extension_block() {
  local ext="$1"
  local slot="$2"
  printf '\nexten => %s,1,Answer()\n' "$ext"
  printf ' same => n,Set(__PHONE_AGENT_SLOT=%s)\n' "$slot"
  printf ' same => n,Set(TASK_ID_RAW=${SHELL(curl -fsS --max-time 2 "%s/internal/asterisk/slots/%s/start" || true)})\n' "$CALLBACK_URL" "$slot"
  printf ' same => n,Set(__PHONE_AGENT_TASK_ID=${TASK_ID_RAW})\n'
  printf ' same => n,GotoIf($["${PHONE_AGENT_TASK_ID}" = ""]?no_task)\n'
  printf ' same => n,Playback(phone-agent/slots/slot-%s)\n' "$slot"
  printf ' same => n,Playback(phone-agent/prompts/reply-after-beep)\n'
  printf ' same => n,Playback(beep)\n'
  printf ' same => n,Set(RECORDING_START_RESULT=${SHELL(curl -fsS --max-time 2 -X POST "%s/internal/asterisk/recordings/start?slot=%s&taskId=${URIENCODE(${PHONE_AGENT_TASK_ID})}" >/dev/null && printf OK || true)})\n' "$CALLBACK_URL" "$slot"
  printf ' same => n,Record(/var/spool/asterisk/phone-agent/recordings/${PHONE_AGENT_TASK_ID}.wav,3,60,k)\n'
  printf ' same => n,Hangup()\n'
  printf ' same => n(no_task),Playback(phone-agent/prompts/no-task)\n'
  printf ' same => n,Hangup()\n'
}

render_extensions_config_to() {
  local target="$1"
  {
    printf '%s\n' '; Generated by scripts/phone-agent-dev.sh start.'
    printf '%s\n' '; Dialplan for Phone Agent BLF slots and inbound phone intents.'
    printf '\n[phone-agent-mvp]\n'
    printf 'exten => 701,1,Answer()\n same => n,Wait(1)\n same => n,Playback(demo-congrats)\n same => n,Wait(2)\n same => n,Hangup()\n'
    printf '\nexten => 702,1,Answer()\n same => n,Playback(demo-congrats)\n same => n,Hangup()\n'
    printf '\nexten => 703,1,Answer()\n same => n,Playback(tt-monkeys)\n same => n,Hangup()\n'
    local index ext slot
    for index in "${!BLF_EXTENSION_ARRAY[@]}"; do
      slot=$((index + 1))
      ext="${BLF_EXTENSION_ARRAY[$index]}"
      printf '\nexten => %s,hint,Custom:phone-agent-slot-%s\n' "$ext" "$slot"
    done
    for index in "${!BLF_EXTENSION_ARRAY[@]}"; do
      slot=$((index + 1))
      ext="${BLF_EXTENSION_ARRAY[$index]}"
      render_slot_extension_block "$ext" "$slot"
    done
    printf '\nexten => h,1,GotoIf($["${PHONE_AGENT_INBOUND_INTENT_ID}" != ""]?inbound_done)\n'
    printf ' same => n,GotoIf($["${PHONE_AGENT_SLOT}" = "" | "${PHONE_AGENT_TASK_ID}" = ""]?done)\n'
    printf ' same => n,System(curl -fsS --max-time 5 -X POST "%s/internal/asterisk/recordings?slot=${URIENCODE(${PHONE_AGENT_SLOT})}&taskId=${URIENCODE(${PHONE_AGENT_TASK_ID})}" || true)\n' "$CALLBACK_URL"
    printf ' same => n,Goto(done)\n'
    printf ' same => n(inbound_done),System(curl -fsS --max-time 5 -X POST "%s/internal/asterisk/inbound-intents/phone/recordings?intentId=${URIENCODE(${PHONE_AGENT_INBOUND_INTENT_ID})}" || true)\n' "$CALLBACK_URL"
    printf ' same => n(done),NoOp(phone-agent h extension done)\n'
    printf '\nexten => 0,1,Answer()\n'
    printf ' same => n,Set(INTENT_ID_RAW=${SHELL(curl -fsS --max-time 2 "%s/internal/asterisk/inbound-intents/phone/start" || true)})\n' "$CALLBACK_URL"
    printf ' same => n,Set(__PHONE_AGENT_INBOUND_INTENT_ID=${INTENT_ID_RAW})\n'
    printf ' same => n,GotoIf($["${PHONE_AGENT_INBOUND_INTENT_ID}" = ""]?no_intent)\n'
    printf ' same => n,Playback(phone-agent/prompts/inbound-intent)\n'
    printf ' same => n,Playback(beep)\n'
    printf ' same => n,Record(/var/spool/asterisk/phone-agent/recordings/${PHONE_AGENT_INBOUND_INTENT_ID}.wav,3,120,k)\n'
    printf ' same => n,Hangup()\n'
    printf ' same => n(no_intent),Playback(phone-agent/prompts/no-task)\n'
    printf ' same => n,Hangup()\n'
    printf '\n[phone-agent-ring]\n'
    printf 'exten => s,1,Answer()\n'
    printf ' same => n,Playback(phone-agent/prompts/ring-phone)\n'
    printf ' same => n,Hangup()\n'
  } > "$target"
}

render_asterisk_config() {
  validate_phone_config || return 1
  mkdir -p "$ASTERISK_CONFIG_DIR"
  local pjsip_tmp extensions_tmp
  pjsip_tmp="$(mktemp "$ASTERISK_CONFIG_DIR/pjsip.conf.tmp.XXXXXX")" || return 1
  extensions_tmp="$(mktemp "$ASTERISK_CONFIG_DIR/extensions.conf.tmp.XXXXXX")" || {
    rm -f "$pjsip_tmp"
    return 1
  }
  render_pjsip_config_to "$pjsip_tmp" && render_extensions_config_to "$extensions_tmp" || {
    rm -f "$pjsip_tmp" "$extensions_tmp"
    return 1
  }
  mv "$pjsip_tmp" "$ASTERISK_PJSIP_CONF"
  mv "$extensions_tmp" "$ASTERISK_EXTENSIONS_CONF"
  ok "rendered Asterisk config for SIP $SIP_EXTENSION and BLF $(blf_extensions_display)"
}

check_generated_asterisk_config_matches_env() {
  validate_phone_config || return 1
  [[ -f "$ASTERISK_PJSIP_CONF" && -f "$ASTERISK_EXTENSIONS_CONF" ]] || {
    fail "generated Asterisk config missing; run scripts/phone-agent-dev.sh start to refresh it"
    return 1
  }
  local pjsip_expected extensions_expected unhealthy=0
  pjsip_expected="$(mktemp)" || return 1
  extensions_expected="$(mktemp)" || {
    rm -f "$pjsip_expected"
    return 1
  }
  render_pjsip_config_to "$pjsip_expected"
  render_extensions_config_to "$extensions_expected"
  if ! cmp -s "$pjsip_expected" "$ASTERISK_PJSIP_CONF"; then
    fail "pjsip.conf drift detected for configured SIP/BLF values"
    unhealthy=1
  fi
  if ! cmp -s "$extensions_expected" "$ASTERISK_EXTENSIONS_CONF"; then
    fail "extensions.conf drift detected for configured SIP/BLF values"
    unhealthy=1
  fi
  rm -f "$pjsip_expected" "$extensions_expected"
  if (( unhealthy != 0 )); then
    action "status is read-only; run scripts/phone-agent-dev.sh start to refresh generated Asterisk config"
  fi
  return "$unhealthy"
}

# Compares generated Asterisk config with the current environment without
# modifying the managed config files.
asterisk_config_matches_env() {
  validate_phone_config || return 1
  [[ -f "$ASTERISK_PJSIP_CONF" && -f "$ASTERISK_EXTENSIONS_CONF" ]] || return 1
  local pjsip_expected extensions_expected rc=0
  pjsip_expected="$(mktemp)" || return 1
  extensions_expected="$(mktemp)" || {
    rm -f "$pjsip_expected"
    return 1
  }
  render_pjsip_config_to "$pjsip_expected"
  render_extensions_config_to "$extensions_expected"
  cmp -s "$pjsip_expected" "$ASTERISK_PJSIP_CONF" || rc=1
  cmp -s "$extensions_expected" "$ASTERISK_EXTENSIONS_CONF" || rc=1
  rm -f "$pjsip_expected" "$extensions_expected"
  return "$rc"
}

# Start owns generated Asterisk config freshness. Missing or drifted files are
# regenerated before Asterisk starts so users do not need a separate init step.
sync_asterisk_config_for_start() {
  validate_phone_config || return 1
  if [[ ! -f "$ASTERISK_PJSIP_CONF" || ! -f "$ASTERISK_EXTENSIONS_CONF" ]]; then
    warn "generated Asterisk config missing; refreshing before Asterisk start"
    render_asterisk_config || return 1
  elif ! asterisk_config_matches_env; then
    warn "generated Asterisk config drift detected; refreshing before Asterisk start"
    render_asterisk_config || return 1
  else
    ok "generated Asterisk config matches environment"
  fi
  check_asterisk_config_files
}

manager_permission_line() {
  local key="$1"
  awk -v key="$key" '
    /^\[phone-agent\]/ {in_user=1; next}
    /^\[/ && in_user {exit}
    in_user && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
      sub(/^[^=]*=/, "", $0)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
      print
      exit
    }
  ' "$ASTERISK_MANAGER_CONF" 2>/dev/null
}

check_asterisk_manager_config() {
  local unhealthy=0
  if [[ ! -f "$ASTERISK_MANAGER_CONF" ]]; then
    fail "missing Asterisk manager config: $ASTERISK_MANAGER_CONF"
    return 1
  fi
  if ! grep -q '^\[phone-agent\]' "$ASTERISK_MANAGER_CONF"; then
    fail "manager.conf missing [phone-agent]"
    unhealthy=1
  fi
  local read_perms write_perms
  read_perms="$(manager_permission_line read)"
  write_perms="$(manager_permission_line write)"
  for perm in command originate; do
    if ! contains_csv_token "$read_perms" "$perm"; then
      fail "manager.conf [phone-agent] read missing '$perm'"
      unhealthy=1
    fi
    if ! contains_csv_token "$write_perms" "$perm"; then
      fail "manager.conf [phone-agent] write missing '$perm'"
      unhealthy=1
    fi
  done
  (( unhealthy == 0 ))
}

check_asterisk_extensions_config() {
  validate_phone_config || return 1
  local unhealthy=0 index ext slot
  if [[ ! -f "$ASTERISK_EXTENSIONS_CONF" ]]; then
    fail "missing Asterisk extensions config: $ASTERISK_EXTENSIONS_CONF"
    return 1
  fi
  grep -q '^\[phone-agent-mvp\]' "$ASTERISK_EXTENSIONS_CONF" || { fail "extensions.conf missing [phone-agent-mvp]"; unhealthy=1; }
  for index in "${!BLF_EXTENSION_ARRAY[@]}"; do
    slot=$((index + 1))
    ext="${BLF_EXTENSION_ARRAY[$index]}"
    grep -q "exten => $ext,hint,Custom:phone-agent-slot-$slot" "$ASTERISK_EXTENSIONS_CONF" || {
      fail "extensions.conf missing hint for $ext"
      unhealthy=1
    }
    grep -q "Set(__PHONE_AGENT_SLOT=$slot)" "$ASTERISK_EXTENSIONS_CONF" || {
      fail "extensions.conf missing slot marker for $ext"
      unhealthy=1
    }
    grep -q "/internal/asterisk/slots/$slot/start" "$ASTERISK_EXTENSIONS_CONF" || {
      fail "extensions.conf missing slot start callback for $ext"
      unhealthy=1
    }
    grep -q "/internal/asterisk/recordings/start?slot=$slot" "$ASTERISK_EXTENSIONS_CONF" || {
      fail "extensions.conf missing recording start callback for $ext"
      unhealthy=1
    }
    grep -q "Playback(phone-agent/slots/slot-$slot)" "$ASTERISK_EXTENSIONS_CONF" || {
      fail "extensions.conf missing slot playback for $ext"
      unhealthy=1
    }
  done
  grep -q 'Playback(phone-agent/prompts/reply-after-beep)' "$ASTERISK_EXTENSIONS_CONF" || {
    fail "extensions.conf missing reply-after-beep playback"
    unhealthy=1
  }
  grep -q 'Playback(beep)' "$ASTERISK_EXTENSIONS_CONF" || { fail "extensions.conf missing beep playback"; unhealthy=1; }
  grep -q 'Record(/var/spool/asterisk/phone-agent/recordings/' "$ASTERISK_EXTENSIONS_CONF" || {
    fail "extensions.conf missing recording command"
    unhealthy=1
  }
  grep -q 'exten => h,1' "$ASTERISK_EXTENSIONS_CONF" || { fail "extensions.conf missing h extension"; unhealthy=1; }
  grep -q 'exten => 0,1,Answer()' "$ASTERISK_EXTENSIONS_CONF" || { fail "extensions.conf missing phone 0 entry"; unhealthy=1; }
  grep -q '^\[phone-agent-ring\]' "$ASTERISK_EXTENSIONS_CONF" || { fail "extensions.conf missing [phone-agent-ring]"; unhealthy=1; }
  grep -q 'Playback(phone-agent/prompts/ring-phone)' "$ASTERISK_EXTENSIONS_CONF" || {
    fail "extensions.conf missing ring-phone playback"
    unhealthy=1
  }
  (( unhealthy == 0 ))
}

check_asterisk_pjsip_config() {
  validate_phone_config || return 1
  local unhealthy=0
  if [[ ! -f "$ASTERISK_PJSIP_CONF" ]]; then
    fail "missing Asterisk pjsip config: $ASTERISK_PJSIP_CONF"
    return 1
  fi
  grep -q "^\[$SIP_EXTENSION\]" "$ASTERISK_PJSIP_CONF" || { fail "pjsip.conf missing [$SIP_EXTENSION]"; unhealthy=1; }
  grep -q "^\[$SIP_EXTENSION-auth\]" "$ASTERISK_PJSIP_CONF" || { fail "pjsip.conf missing [$SIP_EXTENSION-auth]"; unhealthy=1; }
  grep -q 'subscribe_context[[:space:]]*=[[:space:]]*phone-agent-mvp' "$ASTERISK_PJSIP_CONF" || {
    fail "pjsip.conf missing subscribe_context=phone-agent-mvp"
    unhealthy=1
  }
  grep -q "username[[:space:]]*=[[:space:]]*$SIP_AUTH_ID" "$ASTERISK_PJSIP_CONF" || {
    fail "pjsip.conf missing configured auth username"
    unhealthy=1
  }
  grep -q "^\[$BLF_EVENTLIST_URI\]" "$ASTERISK_PJSIP_CONF" || {
    fail "pjsip.conf missing Eventlist resource $BLF_EVENTLIST_URI"
    unhealthy=1
  }
  grep -q "external_signaling_address[[:space:]]*=[[:space:]]*$ASTERISK_EXTERNAL_SIGNALING_ADDRESS" "$ASTERISK_PJSIP_CONF" || {
    fail "pjsip.conf missing configured external signaling address"
    unhealthy=1
  }
  grep -q "external_media_address[[:space:]]*=[[:space:]]*$ASTERISK_EXTERNAL_MEDIA_ADDRESS" "$ASTERISK_PJSIP_CONF" || {
    fail "pjsip.conf missing configured external media address"
    unhealthy=1
  }
  local ext
  for ext in "${BLF_EXTENSION_ARRAY[@]}"; do
    grep -q "list_item[[:space:]]*=[[:space:]]*$ext" "$ASTERISK_PJSIP_CONF" || {
      fail "pjsip.conf missing Eventlist item $ext"
      unhealthy=1
    }
  done
  (( unhealthy == 0 ))
}

check_asterisk_config_files() {
  local unhealthy=0
  check_asterisk_manager_config || unhealthy=1
  check_asterisk_extensions_config || unhealthy=1
  check_asterisk_pjsip_config || unhealthy=1
  check_generated_asterisk_config_matches_env || unhealthy=1
  if (( unhealthy == 0 )); then
    ok "asterisk config files look complete"
  fi
  return "$unhealthy"
}

check_ami_permissions_runtime() {
  check_asterisk_manager_config || return 1
  if ! container_running "$ASTERISK_CONTAINER"; then
    return 1
  fi
  if ! asterisk_rx 'manager show command Originate' | grep -q 'Privilege: originate,all'; then
    return 1
  fi
  # This uses the same AMI credentials as Spring to catch stale manager reloads,
  # bad secrets, or a closed host port before start/doctor report AMI as usable.
  local ami_response
  ami_response="$(
    {
      sleep 0.2
      printf 'Action: Login\r\nUsername: %s\r\nSecret: %s\r\nEvents: off\r\n\r\n' "$AMI_USER" "$AMI_SECRET"
      sleep 0.2
      printf 'Action: Logoff\r\n\r\n'
      sleep 0.2
    } | nc -w 3 "$AMI_HOST" "$AMI_PORT" 2>/dev/null
  )" || return 1
  grep -q 'Response: Success' <<<"$ami_response"
}

confirm_or_skip() {
  local question="$1"
  local assume_yes="$2"
  local allow_assume_yes="${3:-true}"
  if [[ "$assume_yes" == 'true' && "$allow_assume_yes" == 'true' ]]; then
    ok "$question yes (--yes)"
    return 0
  fi
  if [[ ! -t 0 ]]; then
    warn "$question skipped (non-interactive; rerun without --yes for prompt)"
    return 1
  fi
  local answer
  printf '%s [y/N] ' "$question"
  read -r answer
  case "$answer" in
    y|Y|yes|YES)
      return 0
      ;;
    *)
      warn "$question skipped"
      return 1
      ;;
  esac
}

# Validates the minimum tools required to run Spring and the Console.
require_software_start_dependencies() {
  local missing=()
  configured_command_available curl || missing+=("curl")

  if (( ${#missing[@]} > 0 )); then
    fail "missing required dependency: ${missing[*]}"
    return 1
  fi

  require_java_25
}

# Validates local commands that are needed for the full phone/Asterisk workflow.
# MySQL is intentionally checked separately because this script must not manage it.
require_phone_start_dependencies() {
  local missing=()
  configured_command_available docker || missing+=("docker")
  configured_command_available "$FFMPEG_COMMAND" || missing+=("$(configured_command_label PHONE_AGENT_FFMPEG_COMMAND "$FFMPEG_COMMAND")")
  configured_command_available "$WHISPER_COMMAND" || missing+=("$(configured_command_label PHONE_AGENT_WHISPER_COMMAND "$WHISPER_COMMAND")")
  configured_command_available "$TMUX_COMMAND" || missing+=("$(configured_command_label PHONE_AGENT_TMUX_COMMAND "$TMUX_COMMAND")")
  configured_command_available "$TTYD_COMMAND" || missing+=("$(configured_command_label PHONE_AGENT_TTYD_COMMAND "$TTYD_COMMAND")")
  configured_command_available "$CODEX_COMMAND" || missing+=("$(configured_command_label PHONE_AGENT_CODEX_COMMAND "$CODEX_COMMAND")")
  configured_command_available curl || missing+=("curl")

  if ! docker_compose_available; then
    missing+=("docker compose")
  fi

  if (( ${#missing[@]} > 0 )); then
    fail "missing required dependency: ${missing[*]}"
    return 1
  fi

  require_java_25
}

# Checks only the externally managed MySQL TCP endpoint. Database, auth, and
# Flyway readiness are intentionally proven by Spring startup and logs.
require_mysql_ready() {
  if ! mysql_tcp_up; then
    fail "mysql unreachable at $MYSQL_HOST:$MYSQL_PORT"
    fail "prepare an external MySQL service and database, then set PHONE_AGENT_MYSQL_HOST/PORT/DATABASE/USER/PASSWORD"
    return 1
  fi

  ok "mysql tcp reachable; database/auth/Flyway will be verified by Spring startup"
  return 0
}

ensure_runtime_dirs() {
  mkdir -p "$RUNTIME_DIR" "$LOG_DIR" "$SOUNDS_DIR" "$RECORDINGS_DIR" "$CODEX_REGISTRY_DIR"
  ok "runtime directories ready"
}

print_environment_summary() {
  info 'Phone Agent init'
  info "Repo: $REPO_ROOT"
  info "Spring: $SPRING_URL"
  info "MySQL: $MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE (external service)"
  info "Asterisk: $ASTERISK_CONTAINER"
  info "SIP extension: $SIP_EXTENSION"
  info "Ring target: $RING_TARGET"
  info "Eventlist BLF URI: $BLF_EVENTLIST_URI"
  info "BLF extensions: $BLF_EXTENSIONS"
  info "Console: $SPRING_URL/console/"
}

check_software_dependencies_for_init() {
  local unhealthy=0 missing=()
  local command_names=(curl nc)
  local command_values=(curl nc)
  local index
  for index in "${!command_names[@]}"; do
    if configured_command_available "${command_values[$index]}"; then
      ok "$(configured_command_label "${command_names[$index]}" "${command_values[$index]}")"
    else
      missing+=("$(configured_command_label "${command_names[$index]}" "${command_values[$index]}")")
    fi
  done
  require_java_25 || unhealthy=1

  if (( ${#missing[@]} > 0 )); then
    fail "missing required dependency: ${missing[*]}"
    action "install missing MacPorts packages as needed, for example: sudo port install ffmpeg whisper"
    unhealthy=1
  fi
  return "$unhealthy"
}

check_phone_dependencies_for_init() {
  local unhealthy=0 missing=()
  local command_names=(docker PHONE_AGENT_FFMPEG_COMMAND PHONE_AGENT_WHISPER_COMMAND PHONE_AGENT_TMUX_COMMAND PHONE_AGENT_TTYD_COMMAND PHONE_AGENT_CODEX_COMMAND)
  local command_values=(docker "$FFMPEG_COMMAND" "$WHISPER_COMMAND" "$TMUX_COMMAND" "$TTYD_COMMAND" "$CODEX_COMMAND")
  local index
  for index in "${!command_names[@]}"; do
    if configured_command_available "${command_values[$index]}"; then
      ok "$(configured_command_label "${command_names[$index]}" "${command_values[$index]}")"
    else
      missing+=("$(configured_command_label "${command_names[$index]}" "${command_values[$index]}")")
    fi
  done
  if docker_compose_available; then
    ok "docker compose"
  else
    missing+=("docker compose")
  fi
  if (( ${#missing[@]} > 0 )); then
    fail "missing required dependency: ${missing[*]}"
    action "install missing MacPorts packages as needed, for example: sudo port install ffmpeg whisper"
    unhealthy=1
  fi
  return "$unhealthy"
}

check_whisper_model_for_init() {
  if [[ -f "$WHISPER_MODEL_PATH" ]]; then
    ok "whisper model: $WHISPER_MODEL_PATH"
    return 0
  fi
  warn "whisper model missing: $WHISPER_MODEL_PATH"
  action "place ggml-small.bin at $WHISPER_MODEL_PATH"
  return 1
}

init_mysql() {
  local assume_yes="$1"
  if [[ "$assume_yes" == 'true' ]]; then
    ok "external MySQL check (--yes)"
  fi
  if require_mysql_ready; then
    return 0
  fi
  action "create the database outside this script, then rerun with PHONE_AGENT_MYSQL_* pointing to it"
  return 1
}

init_asterisk_runtime() {
  local assume_yes="$1"
  local config_ok="$2"
  local unhealthy=0
  if [[ "$config_ok" != 'true' ]]; then
    warn "asterisk runtime actions skipped until config files are fixed"
    return 1
  fi
  docker_compose_available || { fail "docker compose unavailable; cannot manage Asterisk"; return 1; }

  if ! container_exists "$ASTERISK_CONTAINER"; then
    warn "asterisk container missing: $ASTERISK_CONTAINER"
    if confirm_or_skip "Start Asterisk Docker now?" "$assume_yes"; then
      docker compose -f "$COMPOSE_FILE" up -d || unhealthy=1
    else
      action "docker compose -f $COMPOSE_FILE up -d"
      unhealthy=1
    fi
  elif ! container_running "$ASTERISK_CONTAINER"; then
    warn "asterisk container is stopped: $ASTERISK_CONTAINER"
    if confirm_or_skip "Start Asterisk Docker now?" "$assume_yes"; then
      docker compose -f "$COMPOSE_FILE" up -d || unhealthy=1
    else
      action "docker compose -f $COMPOSE_FILE up -d"
      unhealthy=1
    fi
  else
    ok "asterisk container running: $ASTERISK_CONTAINER"
    if confirm_or_skip "Restart Asterisk now to load current config?" "$assume_yes"; then
      docker restart "$ASTERISK_CONTAINER" >/dev/null || unhealthy=1
    fi
    if confirm_or_skip "Force recreate Asterisk container now? Usually only needed for container drift." "$assume_yes" false; then
      docker compose -f "$COMPOSE_FILE" up -d --force-recreate || unhealthy=1
    fi
  fi
  return "$unhealthy"
}

print_phone_manual_checklist() {
  manual "Configure SIP user: $SIP_EXTENSION"
  manual "Configure SIP auth ID: $SIP_AUTH_ID"
  manual "Configure SIP password from PHONE_AGENT_SIP_PASSWORD"
  manual "Configure Eventlist BLF URI: $BLF_EVENTLIST_URI"
  manual "Configure BLF keys: $(blf_extensions_display)"
}

cmd_init() {
  local assume_yes='false'
  local mode='phone'
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --yes)
        assume_yes='true'
        ;;
      --software-only)
        mode='software-only'
        ;;
      --phone)
        mode='phone'
        ;;
      *)
        usage
        return "$EXIT_USAGE"
        ;;
    esac
    shift
  done

  local unhealthy=0
  print_environment_summary
  ensure_runtime_dirs || unhealthy=1
  check_software_dependencies_for_init || unhealthy=1
  init_mysql "$assume_yes" || unhealthy=1
  if [[ "$mode" == 'phone' ]]; then
    local phone_config_ok='true'
    if ! validate_phone_config; then
      unhealthy=1
      phone_config_ok='false'
    fi
    if [[ "$phone_config_ok" == 'true' ]]; then
      check_phone_dependencies_for_init || unhealthy=1
      check_whisper_model_for_init || true
      local asterisk_config_ok='false'
      if render_asterisk_config && check_asterisk_config_files; then
        asterisk_config_ok='true'
      else
        unhealthy=1
      fi
      init_asterisk_runtime "$assume_yes" "$asterisk_config_ok" || true
      print_phone_manual_checklist
    else
      action "fix PHONE_AGENT_SIP_*, PHONE_AGENT_RING_TARGET, PHONE_AGENT_BLF_*, and external address values before rerunning phone init"
    fi
  else
    ok "software-only mode: Asterisk, BLF, ASR command, Codex CLI, tmux, ttyd, and phone checklist skipped"
  fi

  if (( unhealthy == 0 )); then
    ok "init completed"
    action "next: scripts/phone-agent-dev.sh start --$mode"
    return 0
  fi
  warn "init completed with issues"
  action "fix the failed items above, then rerun scripts/phone-agent-dev.sh init --$mode"
  return "$EXIT_PREFLIGHT"
}

remove_stale_pid_for_start() {
  local pid
  pid="$(read_pid)"
  if [[ -n "$pid" ]] && ! pid_is_running "$pid"; then
    warn "removing stale Spring pid file: $pid"
    rm -f "$PID_FILE"
  fi
}

port_owner() {
  have_cmd lsof || return 1
  lsof -nP -iTCP:"$SPRING_PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 {print $1 " pid=" $2 " " $9}' | head -n 1
}

# Avoids taking over an unrelated process on the configured Spring port.
# Health is only accepted when the pid file and port owner both identify this
# app, so another Spring service cannot accidentally satisfy our preflight.
ensure_spring_port_available() {
  spring_health_up && return 0

  local owner pid
  owner="$(port_owner || true)"
  [[ -z "$owner" ]] && return 0

  pid="$(read_pid)"
  if [[ -n "$pid" ]] && [[ "$owner" == *"pid=$pid "* ]]; then
    return 0
  fi

  fail "port $SPRING_PORT is occupied by a non-managed process: $owner"
  return 1
}

start_asterisk() {
  if container_running "$ASTERISK_CONTAINER"; then
    ok "asterisk container already running"
    return 0
  fi

  info "starting Asterisk Docker..."
  if docker compose -f "$COMPOSE_FILE" up -d; then
    ok "asterisk container started"
    return 0
  fi

  fail "docker compose failed; rerun: docker compose -f $COMPOSE_FILE up -d"
  return 1
}

# Keeps each Spring start attempt isolated while preserving spring.log as the
# stable file that troubleshooting commands and documentation can reference.
prepare_spring_run_log() {
  local run_id run_log
  run_id="$(date +%Y%m%d-%H%M%S)"
  run_log="$LOG_DIR/spring-$run_id.log"
  : > "$run_log"
  rm -f "$LOG_DIR/spring.log"
  if ln -s "$(basename "$run_log")" "$LOG_DIR/spring.log" 2>/dev/null; then
    ok "spring run log: $run_log"
  else
    warn "could not update spring.log symlink; using run log directly: $run_log"
  fi
  SPRING_LOG="$run_log"
}

start_spring() {
  if spring_health_up; then
    ok "spring already healthy at $SPRING_URL"
    return 0
  fi

  remove_stale_pid_for_start
  ensure_spring_port_available || return 1

  mkdir -p "$LOG_DIR"
  prepare_spring_run_log
  info "building Spring Boot jar..."
  (
    cd "$REPO_ROOT" || exit 1
    ./gradlew bootJar >> "$SPRING_LOG" 2>&1
  ) || {
    fail "gradle bootJar failed; inspect $SPRING_LOG"
    tail -n 80 "$SPRING_LOG" 2>/dev/null || true
    return "$EXIT_PREFLIGHT"
  }

  info "starting Spring Boot..."
  local local_java
  local_java="$(java_command)"

  if configured_command_available "$TMUX_COMMAND"; then
    "$TMUX_COMMAND" has-session -t "$SPRING_TMUX_SESSION" >/dev/null 2>&1 \
      && "$TMUX_COMMAND" kill-session -t "$SPRING_TMUX_SESSION" >/dev/null 2>&1 || true
  fi
  local datasource_url="jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE?useUnicode=true&characterEncoding=utf8&connectionTimeZone=LOCAL"
  local -a spring_args=(
    "$local_java"
    "-jar"
    "$BOOT_JAR_PATH"
    "--server.port=$SPRING_PORT"
    "--server.address=$SPRING_BIND_ADDRESS"
    "--phone-agent.runtime-dir=$RUNTIME_DIR"
    "--spring.datasource.url=$datasource_url"
    "--spring.datasource.username=$MYSQL_USER"
    "--spring.datasource.password=$MYSQL_PASSWORD"
    "--phone-agent.ami.host=$AMI_HOST"
    "--phone-agent.ami.port=$AMI_PORT"
    "--phone-agent.ami.username=$AMI_USER"
    "--phone-agent.ami.secret=$AMI_SECRET"
    "--phone-agent.sip.extension=$SIP_EXTENSION"
    "--phone-agent.sip.auth-id=$SIP_AUTH_ID"
    "--phone-agent.sip.password=$SIP_PASSWORD"
    "--phone-agent.ring.target=$RING_TARGET"
    "--phone-agent.blf.eventlist-uri=$BLF_EVENTLIST_URI"
    "--phone-agent.blf.extensions=$BLF_EXTENSIONS"
    "--phone-agent.ffmpeg-command=$FFMPEG_COMMAND"
    "--phone-agent.asr.whisper-command=$WHISPER_COMMAND"
    "--phone-agent.asr.model-path=$WHISPER_MODEL_PATH"
    "--phone-agent.asr.language=$ASR_LANGUAGE"
    "--phone-agent.say-command=$SAY_COMMAND"
    "--phone-agent.codex.codex-command=$CODEX_COMMAND"
    "--phone-agent.codex.tmux-command=$TMUX_COMMAND"
    "--phone-agent.codex.ttyd-command=$TTYD_COMMAND"
    "--phone-agent.codex.prompt-language=$CODEX_PROMPT_LANGUAGE"
  )
  {
    printf '#!/usr/bin/env bash\n'
    printf 'cd %q || exit 1\n' "$REPO_ROOT"
    printf 'exec'
    local arg
    for arg in "${spring_args[@]}"; do
      printf ' %q' "$arg"
    done
    printf ' >> %q 2>&1\n' "$SPRING_LOG"
  } > "$SPRING_RUNNER"
  chmod +x "$SPRING_RUNNER"

  local app_pid
  if configured_command_available "$TMUX_COMMAND"; then
    "$TMUX_COMMAND" new-session -d -s "$SPRING_TMUX_SESSION" "$SPRING_RUNNER" || {
      fail "failed to start Spring tmux session: $SPRING_TMUX_SESSION"
      return "$EXIT_PREFLIGHT"
    }
    app_pid="$("$TMUX_COMMAND" display-message -p -t "$SPRING_TMUX_SESSION" '#{pane_pid}' 2>/dev/null || true)"
  else
    warn "$(configured_command_label PHONE_AGENT_TMUX_COMMAND "$TMUX_COMMAND") unavailable; starting Spring as a background process"
    "$SPRING_RUNNER" &
    app_pid="$!"
  fi
  printf '%s\n' "$app_pid" > "$PID_FILE"

  local pid deadline
  pid="$(read_pid)"
  deadline=$((SECONDS + START_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if spring_health_up; then
      ok "spring started pid=$pid"
      return 0
    fi
    if [[ -n "$pid" ]] && ! pid_is_running "$pid"; then
      break
    fi
    sleep 2
  done

  fail "spring did not become healthy within ${START_TIMEOUT_SECONDS}s"
  if [[ -n "$pid" ]] && pid_is_running "$pid"; then
    kill "$pid" >/dev/null 2>&1 || true
  fi
  if configured_command_available "$TMUX_COMMAND"; then
    "$TMUX_COMMAND" kill-session -t "$SPRING_TMUX_SESSION" >/dev/null 2>&1 || true
  fi
  tail -n 80 "$SPRING_LOG" 2>/dev/null || true
  return "$EXIT_TIMEOUT"
}

post_start_checks() {
  if docker exec "$ASTERISK_CONTAINER" sh -lc "curl -fsS --max-time 2 $CALLBACK_URL/actuator/health >/dev/null" 2>/dev/null; then
    ok "asterisk callback reachable: $CALLBACK_URL"
  else
    warn "asterisk cannot reach Spring callback: $CALLBACK_URL"
  fi

  if check_ami_permissions_runtime; then
    ok "ami permissions include command/originate"
  else
    warn "ami permissions are incomplete; check $ASTERISK_MANAGER_CONF"
  fi

  if asterisk_rx 'pjsip show contacts' | grep -q "$SIP_EXTENSION/"; then
    ok "phone registered: $SIP_EXTENSION"
  else
    warn "phone not registered: register configured extension $SIP_EXTENSION"
  fi

  local subscriptions hints missing_watchers=() ext
  subscriptions="$(asterisk_rx 'pjsip show subscriptions inbound' || true)"
  if grep -q "$BLF_EVENTLIST_URI/dialog" <<<"$subscriptions"; then
    ok "blf subscription active: $BLF_EVENTLIST_URI/dialog"
  else
    warn "blf subscription missing: check Eventlist BLF URI $BLF_EVENTLIST_URI"
  fi

  if hints="$(asterisk_rx 'core show hints')"; then
    for ext in "${BLF_EXTENSION_ARRAY[@]}"; do
      if ! blf_extension_has_ready_watcher "$hints" "$ext"; then
        missing_watchers+=("$ext")
      fi
    done
    if (( ${#missing_watchers[@]} == 0 )); then
      ok "blf watchers ready: $(blf_extensions_display)"
    else
      warn "blf watchers missing for: ${missing_watchers[*]}"
      manual "If physical BLF lamps are stale after the phone subscribes, open Console and click Sync BLF."
    fi
  else
    warn "could not query Asterisk BLF hints; check Asterisk status and Eventlist BLF subscription"
  fi
}

# Normalizes the public mode flags without changing legacy bare start/doctor
# behavior, which still means the full phone workflow.
parse_mode() {
  local default_mode="$1"
  local requested="${2:-}"
  case "$requested" in
    '')
      printf '%s' "$default_mode"
      ;;
    --software-only)
      printf 'software-only'
      ;;
    --phone)
      printf 'phone'
      ;;
    *)
      return 1
      ;;
  esac
}

cmd_start() {
  local mode
  mode="$(parse_mode phone "${1:-}")" || { usage; return "$EXIT_USAGE"; }
  if [[ $# -gt 0 ]]; then
    shift
  fi
  [[ $# -eq 0 ]] || { usage; return "$EXIT_USAGE"; }

  if [[ "$mode" == 'software-only' ]]; then
    require_software_start_dependencies || return "$EXIT_PREFLIGHT"
  else
    require_phone_start_dependencies || return "$EXIT_PREFLIGHT"
    sync_asterisk_config_for_start || return "$EXIT_PREFLIGHT"
    ok "docker available"
  fi

  require_mysql_ready || return "$EXIT_PREFLIGHT"

  if [[ "$mode" == 'phone' ]]; then
    start_asterisk || return "$EXIT_PREFLIGHT"
  else
    ok "software-only mode: Asterisk, BLF, ASR command, and phone checks skipped"
  fi

  start_spring
  local rc=$?
  if (( rc != 0 )); then
    return "$rc"
  fi

  if [[ "$mode" == 'phone' ]]; then
    post_start_checks
  fi

  info "Console: $SPRING_URL/console/"
  if [[ "$mode" == 'phone' ]]; then
    info "Try: click 呼叫座机"
    info "Try: dial 0 from GXP1630"
  else
    info "Try: open Console, switch language, and create or inspect Codex sessions"
  fi
  return 0
}

stop_spring() {
  local pid
  pid="$(read_pid)"
  if [[ -z "$pid" ]]; then
    ok "spring already stopped"
    return 0
  fi

  if ! pid_is_running "$pid"; then
    warn "removed stale Spring pid file: $pid"
    rm -f "$PID_FILE"
    return 0
  fi

  info "stopping Spring pid=$pid..."
  kill "$pid" >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 20))
  while (( SECONDS < deadline )); do
    pid_is_running "$pid" || break
    sleep 1
  done
  if pid_is_running "$pid"; then
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
  if configured_command_available "$TMUX_COMMAND"; then
    "$TMUX_COMMAND" kill-session -t "$SPRING_TMUX_SESSION" >/dev/null 2>&1 || true
  fi
  rm -f "$PID_FILE"
  ok "spring stopped"
}

cmd_stop() {
  stop_spring

  if docker_compose_available; then
    docker compose -f "$COMPOSE_FILE" stop
    ok "asterisk stopped"
  else
    warn "docker compose unavailable; could not stop Asterisk"
  fi
}

cmd_status() {
  run_full_status_diagnostics phone
}

doctor_line() {
  local name="$1"
  local status="$2"
  local detail="$3"
  printf '%s: %s' "$name" "$status"
  [[ -n "$detail" ]] && printf ' - %s' "$detail"
  printf '\n'
}

cmd_doctor() {
  local mode
  mode="$(parse_mode phone "${1:-}")" || { usage; return "$EXIT_USAGE"; }
  if [[ $# -gt 0 ]]; then
    shift
  fi
  [[ $# -eq 0 ]] || { usage; return "$EXIT_USAGE"; }

  run_full_status_diagnostics "$mode"
}

# Performs read-only dependency checks and prints actionable details. It never
# starts services, retries BLF, changes database rows, or removes pid files.
run_full_status_diagnostics() {
  local mode="$1"
  local unhealthy=0

  local java_bin version_output java_major
  if java_bin="$(java_command)"; then
    version_output="$(java_version_output "$java_bin")"
    java_major="$(java_major_version_from_output "$version_output")"
  else
    version_output='java command not found'
    java_major=''
  fi
  if [[ "$java_major" == "$REQUIRED_JAVA_MAJOR" ]]; then
    doctor_line "Java" "UP" "JDK $REQUIRED_JAVA_MAJOR at $java_bin"
  else
    doctor_line "Java" "DOWN" "JDK $REQUIRED_JAVA_MAJOR required; current ${java_bin:-java} reports: $version_output"
    unhealthy=1
  fi

  if spring_health_up; then
    doctor_line 'Spring' 'UP' "$SPRING_URL"
  else
    doctor_line 'Spring' 'DOWN' "run scripts/phone-agent-dev.sh start or inspect $SPRING_LOG"
    unhealthy=1
  fi

  if mysql_tcp_up; then
    doctor_line 'MySQL' 'UP' "$MYSQL_HOST:$MYSQL_PORT tcp reachable; database/auth/Flyway are verified by Spring startup logs/health"
  else
    doctor_line 'MySQL' 'DOWN' "$MYSQL_HOST:$MYSQL_PORT unreachable; prepare external MySQL service/database"
    unhealthy=1
  fi

  if [[ "$mode" == 'software-only' ]]; then
    doctor_line 'Phone stack' 'SKIPPED' 'software-only mode'
    (( unhealthy == 0 )) || return "$EXIT_UNHEALTHY"
    return 0
  fi

  if validate_phone_config; then
    doctor_line 'Phone config' 'UP' "sip=$SIP_EXTENSION ring=$RING_TARGET eventlist=$BLF_EVENTLIST_URI blf=$(blf_extensions_display)"
  else
    doctor_line 'Phone config' 'DOWN' 'fix PHONE_AGENT_SIP_*, PHONE_AGENT_RING_TARGET, PHONE_AGENT_BLF_*, and external address values'
    unhealthy=1
  fi

  if container_running "$ASTERISK_CONTAINER"; then
    doctor_line 'Asterisk' 'UP' "container=$ASTERISK_CONTAINER"
  else
    doctor_line 'Asterisk' 'DOWN' "run: docker compose -f $COMPOSE_FILE up -d"
    unhealthy=1
  fi

  if check_asterisk_config_files >/dev/null 2>&1; then
    doctor_line 'Asterisk config' 'UP' "$ASTERISK_CONFIG_DIR"
  else
    doctor_line 'Asterisk config' 'DOWN' "status is read-only; run scripts/phone-agent-dev.sh start to refresh generated config"
    unhealthy=1
  fi

  if container_running "$ASTERISK_CONTAINER"; then
    if docker exec "$ASTERISK_CONTAINER" sh -lc "curl -fsS --max-time 2 $CALLBACK_URL/actuator/health >/dev/null" 2>/dev/null; then
      doctor_line 'Asterisk to Spring' 'UP' "$CALLBACK_URL reachable"
    else
      doctor_line 'Asterisk to Spring' 'DOWN' "$CALLBACK_URL unavailable from container"
      unhealthy=1
    fi

    if check_ami_permissions_runtime; then
      doctor_line 'AMI permissions' 'UP' 'command/originate'
    else
      doctor_line 'AMI permissions' 'DOWN' "check $ASTERISK_MANAGER_CONF"
      unhealthy=1
    fi

    local contacts subscriptions hints
    contacts="$(asterisk_rx 'pjsip show contacts' || true)"
    if grep -q "$SIP_EXTENSION/" <<<"$contacts"; then
      doctor_line 'Phone registration' 'UP' "$SIP_EXTENSION registered"
    else
      doctor_line 'Phone registration' 'DOWN' "register configured SIP extension $SIP_EXTENSION"
      unhealthy=1
    fi

    subscriptions="$(asterisk_rx 'pjsip show subscriptions inbound' || true)"
    if grep -q "$BLF_EVENTLIST_URI/dialog" <<<"$subscriptions"; then
      doctor_line 'BLF subscription' 'UP' "$BLF_EVENTLIST_URI/dialog active"
    else
      doctor_line 'BLF subscription' 'DOWN' "check Eventlist BLF URI $BLF_EVENTLIST_URI"
      unhealthy=1
    fi

    if hints="$(asterisk_rx 'core show hints')"; then
      local missing_watchers=()
      local ext
      for ext in "${BLF_EXTENSION_ARRAY[@]}"; do
        if ! blf_extension_has_ready_watcher "$hints" "$ext"; then
          missing_watchers+=("$ext")
        fi
      done
      if (( ${#missing_watchers[@]} == 0 )); then
        doctor_line 'BLF watchers' 'UP' "$(blf_extensions_display) Watchers >= 1"
      else
        doctor_line 'BLF watchers' 'DOWN' "missing watchers for: ${missing_watchers[*]}"
        unhealthy=1
      fi
    else
      doctor_line 'BLF watchers' 'DOWN' 'could not query Asterisk BLF hints'
      unhealthy=1
    fi
  fi

  (( unhealthy == 0 )) || return "$EXIT_UNHEALTHY"
}

show_spring_logs() {
  local follow="$1"
  if [[ ! -d "$LOG_DIR" ]]; then
    warn "spring log directory not found: $LOG_DIR"
    return 0
  fi
  if [[ "$follow" == 'true' ]]; then
    if [[ -e "$SPRING_LOG" ]]; then
      tail -n 120 -F "$SPRING_LOG"
    else
      warn "spring log not found: $SPRING_LOG"
    fi
  else
    tail -n 200 "$SPRING_LOG" 2>/dev/null || warn "spring log not found: $SPRING_LOG"
  fi
}

show_asterisk_logs() {
  local follow="$1"
  if ! docker_available; then
    fail "docker unavailable"
    return "$EXIT_PREFLIGHT"
  fi

  if [[ "$follow" == 'true' ]]; then
    docker logs -f --tail 120 "$ASTERISK_CONTAINER"
  else
    docker logs --tail 200 "$ASTERISK_CONTAINER"
  fi
}

cmd_logs() {
  local target="${1:-all}"
  local follow='false'
  if [[ "${2:-}" == '-f' ]]; then
    follow='true'
  elif [[ -n "${2:-}" ]]; then
    usage
    return "$EXIT_USAGE"
  fi

  case "$target" in
    spring)
      show_spring_logs "$follow"
      ;;
    asterisk)
      show_asterisk_logs "$follow"
      ;;
    all)
      if [[ "$follow" == 'true' ]]; then
        show_spring_logs true 2>&1 | sed -u 's/^/[spring] /' &
        local spring_tail_pid=$!
        show_asterisk_logs true 2>&1 | sed -u 's/^/[asterisk] /' &
        local asterisk_tail_pid=$!
        trap "kill $spring_tail_pid $asterisk_tail_pid >/dev/null 2>&1 || true" INT TERM EXIT
        wait
      else
        info '== spring =='
        show_spring_logs false
        info '== asterisk =='
        show_asterisk_logs false
      fi
      ;;
    *)
      usage
      return "$EXIT_USAGE"
      ;;
  esac
}

main() {
  local command="${1:-}"
  case "$command" in
    init)
      shift
      cmd_init "$@"
      ;;
    start)
      shift
      cmd_start "$@"
      ;;
    stop)
      shift
      [[ $# -eq 0 ]] || { usage; return "$EXIT_USAGE"; }
      cmd_stop
      ;;
    status)
      shift
      [[ $# -eq 0 ]] || { usage; return "$EXIT_USAGE"; }
      cmd_status
      ;;
    doctor)
      shift
      cmd_doctor "$@"
      ;;
    logs)
      shift
      cmd_logs "$@"
      ;;
    *)
      usage
      return "$EXIT_USAGE"
      ;;
  esac
}

main "$@"
