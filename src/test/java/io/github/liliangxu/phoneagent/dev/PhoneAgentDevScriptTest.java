package io.github.liliangxu.phoneagent.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneAgentDevScriptTest {
    private static final Path SCRIPT = Path.of("scripts/phone-agent-dev.sh");

    @TempDir
    Path tempDir;

    @Test
    void scriptIsExecutableAndPrintsUsageForMissingCommand() throws Exception {
        assertTrue(Files.isExecutable(SCRIPT));

        ScriptResult result = runScript();

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh start"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh status"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh stop"));
        assertTrue(result.output().contains("Development compatibility:"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh init [--software-only|--phone] [--yes]"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh doctor [--software-only|--phone]"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh start [--software-only|--phone]"));
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh logs [spring|asterisk|all] [-f]"));
    }

    @Test
    void initRejectsUnknownArgumentWithoutSideEffects() throws Exception {
        ScriptResult result = runScript("init", "--bad");

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh init [--software-only|--phone] [--yes]"));
    }

    @Test
    void statusReportsFullPhoneDiagnosticsWithoutStartingServices() throws Exception {
        ScriptResult result = runScript("status");

        assertTrue(result.exitCode() == 0 || result.exitCode() == 3);
        assertTrue(result.output().contains("Java:"));
        assertTrue(result.output().contains("Spring:"));
        assertTrue(result.output().contains("MySQL:"));
        assertTrue(result.output().contains("Phone config:"));
        assertTrue(result.output().contains("Asterisk:"));
        assertTrue(result.output().contains("Asterisk config:"));
    }

    @Test
    void logsRejectsUnknownTarget() throws Exception {
        ScriptResult result = runScript("logs", "unknown");

        assertEquals(4, result.exitCode());
        assertTrue(result.output().contains("scripts/phone-agent-dev.sh logs [spring|asterisk|all] [-f]"));
    }

    @Test
    void startPassesHostReachableSpringBindAddressForAsteriskCallbacks() throws Exception {
        String script = Files.readString(SCRIPT);

        assertTrue(script.contains("RUNTIME_DIR_VALUE=\"${PHONE_AGENT_RUNTIME_DIR:-runtime}\""));
        assertTrue(script.contains("\"--phone-agent.runtime-dir=$RUNTIME_DIR\""));
        assertTrue(script.contains("SPRING_BIND_ADDRESS=\"${PHONE_AGENT_SPRING_BIND_ADDRESS:-0.0.0.0}\""));
        assertTrue(script.contains("\"--server.address=$SPRING_BIND_ADDRESS\""));
        assertTrue(script.contains("local datasource_url=\"jdbc:mysql://$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE"));
        assertTrue(script.contains("\"--spring.datasource.username=$MYSQL_USER\""));
        assertTrue(script.contains("\"--spring.datasource.password=$MYSQL_PASSWORD\""));
        assertTrue(script.contains("\"--phone-agent.ami.username=$AMI_USER\""));
        assertTrue(script.contains("\"--phone-agent.sip.extension=$SIP_EXTENSION\""));
        assertTrue(script.contains("\"--phone-agent.sip.auth-id=$SIP_AUTH_ID\""));
        assertTrue(script.contains("\"--phone-agent.sip.password=$SIP_PASSWORD\""));
        assertTrue(script.contains("\"--phone-agent.ring.target=$RING_TARGET\""));
        assertTrue(script.contains("\"--phone-agent.blf.eventlist-uri=$BLF_EVENTLIST_URI\""));
        assertTrue(script.contains("\"--phone-agent.blf.extensions=$BLF_EXTENSIONS\""));
        assertTrue(script.contains("\"--phone-agent.asr.model-path=$WHISPER_MODEL_PATH\""));
        assertTrue(script.contains("\"--phone-agent.codex.prompt-language=$CODEX_PROMPT_LANGUAGE\""));
        assertTrue(script.contains("BOOT_JAR_PATH=\"build/libs/phone-agent.jar\""));
        assertTrue(script.contains("\"$BOOT_JAR_PATH\""));
        assertTrue(!script.contains("build/libs/phone-agent-1.0-SNAPSHOT.jar"));
        assertTrue(script.contains("SPRING_TMUX_SESSION=\"${PHONE_AGENT_SPRING_TMUX_SESSION:-phone-agent-spring}\""));
        assertTrue(script.contains("\"$TMUX_COMMAND\" new-session -d -s \"$SPRING_TMUX_SESSION\""));
        assertTrue(script.contains("starting Spring as a background process"));
        assertTrue(script.contains("trap \"kill $spring_tail_pid $asterisk_tail_pid"));
        assertTrue(!script.contains("trap 'kill \"$spring_tail_pid\" \"$asterisk_tail_pid\""));
    }

    @Test
    void scriptDoesNotContainContributorLocalJdkPathAndSafelyQuotesGeneratedRunner() throws Exception {
        String script = Files.readString(SCRIPT);

        assertTrue(!script.matches("(?s).*/Users/[^/]+/.*"));
        assertTrue(script.contains("configured_command_available \"$FFMPEG_COMMAND\""));
        assertTrue(script.contains("configured_command_available \"$WHISPER_COMMAND\""));
        assertTrue(script.contains("REQUIRED_JAVA_MAJOR=25"));
        assertTrue(script.contains("require_java_25"));
        assertTrue(script.contains("prepare_spring_run_log"));
        assertTrue(script.contains("printf ' %q' \"$arg\""));
        assertTrue(script.contains("printf ' >> %q 2>&1"));
        assertTrue(!script.contains("cat > \"$SPRING_RUNNER\" <<RUNNER"));
    }

    @Test
    void initYesUsesFakeDependenciesAndDoesNotForceRecreateAsterisk() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        Path model = tempDir.resolve("models").resolve("ggml-small.bin");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "inspect" ]]; then exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"manager show command Originate"* ]]; then echo "Privilege: originate,all"; fi
                  exit 0
                fi
                if [[ "$1" == "restart" ]]; then exit 0; fi
                if [[ "$1" == "compose" ]]; then exit 0; fi
                exit 0
                """);
        for (String command : List.of("ffmpeg", "whisper", "tmux", "ttyd", "codex", "curl")) {
            writeFakeCommand(fakeBin, command, "#!/usr/bin/env bash\nexit 0\n");
        }
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  printf 'Response: Success\\r\\nMessage: Authentication accepted\\r\\n'
                fi
                exit 0
                """);

        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("init", "--yes"), fakeBin, fakeLog, model,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()));

        assertEquals(0, result.exitCode(), result.output());
        String commands = Files.exists(fakeLog) ? Files.readString(fakeLog) : "";
        assertTrue(commands.contains("docker restart phone-agent-asterisk-mvp"));
        assertTrue(!commands.contains("--force-recreate"));
        assertTrue(!commands.contains("docker rm"));
        assertTrue(result.output().contains("[manual] Configure Eventlist BLF URI: phone-agent-slots"));
        assertTrue(Files.readString(configDir.resolve("pjsip.conf")).contains("[1001-auth]"));
        assertTrue(Files.readString(configDir.resolve("extensions.conf")).contains("exten => 608,hint,Custom:phone-agent-slot-8"));
    }

    @Test
    void initPhoneGeneratesCustomEightSlotAsteriskConfig() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        Path model = tempDir.resolve("models").resolve("ggml-small.bin");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model");
        installFullInitFakes(fakeBin);
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("init", "--phone", "--yes"), fakeBin, fakeLog, model, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_SIP_AUTH_ID", "auth-1002",
                "PHONE_AGENT_SIP_PASSWORD", "custom-secret",
                "PHONE_AGENT_RING_TARGET", "PJSIP/1002",
                "PHONE_AGENT_BLF_EVENTLIST_URI", "custom-slots",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,802,803,804,805,806,807,808"
        ));

        assertTrue(result.exitCode() == 0 || result.exitCode() == 3, result.output());
        String pjsip = Files.readString(configDir.resolve("pjsip.conf"));
        String extensions = Files.readString(configDir.resolve("extensions.conf"));
        assertTrue(pjsip.contains("[1002]"));
        assertTrue(pjsip.contains("[1002-auth]"));
        assertTrue(pjsip.contains("username=auth-1002"));
        assertTrue(pjsip.contains("password=custom-secret"));
        assertTrue(pjsip.contains("[custom-slots]"));
        assertTrue(pjsip.contains("list_item=801"));
        assertTrue(pjsip.contains("list_item=808"));
        assertTrue(extensions.contains("exten => 801,hint,Custom:phone-agent-slot-1"));
        assertTrue(extensions.contains("exten => 808,hint,Custom:phone-agent-slot-8"));
        assertTrue(extensions.contains("/internal/asterisk/slots/8/start"));
        assertTrue(extensions.contains("Playback(phone-agent/slots/slot-8)"));
        assertTrue(extensions.contains("/internal/asterisk/recordings/start?slot=8"));
        assertTrue(extensions.contains("Playback(phone-agent/prompts/reply-after-beep)"));
        assertTrue(extensions.contains("Playback(beep)"));
        assertTrue(extensions.contains("exten => h,1"));
        assertTrue(extensions.contains("exten => 0,1,Answer()"));
        assertTrue(extensions.contains("[phone-agent-ring]"));
        assertTrue(extensions.contains("Playback(phone-agent/prompts/ring-phone)"));
    }

    @Test
    void initPhoneGeneratesCustomFourSlotExternalAddressConfigWithoutOldSlots() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        Path model = tempDir.resolve("models").resolve("ggml-small.bin");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model");
        installFullInitFakes(fakeBin);
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("init", "--yes", "--phone"), fakeBin, fakeLog, model, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS", "10.0.0.20",
                "PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS", "asterisk.local:5060",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,802,803,804"
        ));

        assertEquals(0, result.exitCode(), result.output());
        String pjsip = Files.readString(configDir.resolve("pjsip.conf"));
        String extensions = Files.readString(configDir.resolve("extensions.conf"));
        assertTrue(pjsip.contains("external_signaling_address=10.0.0.20"));
        assertTrue(pjsip.contains("external_media_address=asterisk.local:5060"));
        assertTrue(extensions.contains("exten => 804,hint,Custom:phone-agent-slot-4"));
        assertTrue(!extensions.contains("exten => 805,hint"));
        assertTrue(!extensions.contains("exten => 601,hint"));
        assertTrue(!extensions.contains("/internal/asterisk/slots/5/start"));
    }

    @Test
    void invalidPhoneConfigFailsBeforeRenderingAndDoesNotPrintPassword() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 22\n");
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\nexit 1\n");
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("doctor", "--phone"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_SIP_PASSWORD", "top-secret-value",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,801"
        ));

        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("duplicated"));
        assertTrue(result.output().contains("Phone config: DOWN"));
        assertTrue(!result.output().contains("top-secret-value"));
    }

    @Test
    void initPhoneWithInvalidBlfSkipsRuntimeActionsAndManualChecklist() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        installFullInitFakes(fakeBin);
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("init", "--phone", "--yes"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_BLF_EXTENSIONS", "801,not-a-number,802"
        ));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("PHONE_AGENT_BLF_EXTENSIONS value 'not-a-number' must be numeric"));
        assertTrue(result.output().contains("fix PHONE_AGENT_SIP_*, PHONE_AGENT_RING_TARGET, PHONE_AGENT_BLF_*"));
        assertTrue(!result.output().contains("[manual] Configure Eventlist BLF URI"));
        assertTrue(!result.output().contains("[manual] Configure BLF keys"));
        assertTrue(!result.output().contains("[manual] Configure BLF keys: 801,802"));
        assertTrue(!Files.exists(configDir.resolve("pjsip.conf")));
        assertTrue(!Files.exists(configDir.resolve("extensions.conf")));
        String commands = Files.readString(fakeLog);
        assertTrue(!commands.contains("docker restart phone-agent-asterisk-mvp"));
        assertTrue(!commands.contains("docker compose"));
    }

    @Test
    void initSoftwareOnlySkipsPhoneDependenciesAndChecklistWithFakePath() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "inspect" ]]; then exit 0; fi
                if [[ "$1" == "exec" ]]; then exit 0; fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\necho \"nc $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("init", "--software-only", "--yes"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()));

        assertEquals(0, result.exitCode(), result.output());
        String output = result.output();
        assertTrue(output.contains("software-only mode: Asterisk, BLF, ASR command, Codex CLI, tmux, ttyd, and phone checklist skipped"));
        assertTrue(!output.contains("PHONE_AGENT_FFMPEG_COMMAND"));
        assertTrue(!output.contains("Configure Eventlist BLF URI"));
        assertTrue(!Files.exists(configDir.resolve("pjsip.conf")));
        assertTrue(!Files.exists(configDir.resolve("extensions.conf")));
    }

    @Test
    void startPhoneAutoRefreshesGeneratedConfigDriftBeforeSpringProof() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        installPhoneStartFakes(fakeBin);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 1\n");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo false; exit 0; fi
                if [[ "$1" == "compose" && "$*" == *" up -d"* ]]; then exit 1; fi
                exit 0
                """);
        Path configDir = createAsteriskConfigDir();
        writeDefaultGeneratedConfig(configDir);

        ScriptResult result = runScriptWithEnv(List.of("start", "--phone"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,802,803,804"
        ));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("generated Asterisk config drift detected; refreshing before Asterisk start"));
        assertTrue(result.output().contains("mysql tcp reachable; database/auth/Flyway will be verified by Spring startup"));
        assertTrue(!result.output().contains("mysql reachable 127.0.0.1:3307/phone_agent"));
        assertTrue(!result.output().contains("run scripts/phone-agent-dev.sh init --phone"));
        assertTrue(Files.readString(configDir.resolve("pjsip.conf")).contains("[1002]"));
        assertTrue(Files.readString(configDir.resolve("extensions.conf")).contains("exten => 804,hint,Custom:phone-agent-slot-4"));
        String commands = Files.exists(fakeLog) ? Files.readString(fakeLog) : "";
        assertTrue(!commands.contains("mysql "));
    }

    @Test
    void startDefaultPhoneWorkflowBlocksOnPerDependencyPreflight() throws Exception {
        assertStartPreflightFailure(Map.of(), "docker", "missing required dependency: docker");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_FFMPEG_COMMAND", "/missing/ffmpeg"), "PHONE_AGENT_FFMPEG_COMMAND", "missing required dependency:");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_WHISPER_COMMAND", "/missing/whisper"), "PHONE_AGENT_WHISPER_COMMAND", "missing required dependency:");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_TMUX_COMMAND", "/missing/tmux"), "PHONE_AGENT_TMUX_COMMAND", "missing required dependency:");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_TTYD_COMMAND", "/missing/ttyd"), "PHONE_AGENT_TTYD_COMMAND", "missing required dependency:");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_CODEX_COMMAND", "/missing/codex"), "PHONE_AGENT_CODEX_COMMAND", "missing required dependency:");
        assertStartPreflightFailure(Map.of("PHONE_AGENT_BLF_EXTENSIONS", "601,not-a-number"), "PHONE_AGENT_BLF_EXTENSIONS", "must be numeric");
    }

    @Test
    void startDefaultPhoneWorkflowRejectsJavaBelow25BeforeMysqlOrRuntimeActions() throws Exception {
        Path fakeBin = tempDir.resolve("default-java-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("default-java.log");
        installPhoneStartFakes(fakeBin);
        writeFakeJava(fakeBin, "1.8.0_441");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\necho \"nc $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java 25 required"));
        assertTrue(result.output().contains("1.8.0_441"));
        assertTrue(!result.output().contains("mysql unreachable"));
        assertTrue(!Files.exists(configDir.resolve("pjsip.conf")));
        String commands = Files.readString(fakeLog);
        assertTrue(!commands.contains("mysql "));
        assertTrue(!commands.contains("docker compose -f"));
    }

    @Test
    void startDefaultPhoneWorkflowRejectsMysqlTcpDownBeforeAsteriskOrSpringStart() throws Exception {
        Path fakeBin = tempDir.resolve("default-mysql-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("default-mysql.log");
        installPhoneStartFakes(fakeBin);
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\necho \"nc $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 1\n");
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        Path configDir = createAsteriskConfigDir();
        Path runtimeDir = tempDir.resolve("default-mysql-runtime");

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString()
                ));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("mysql unreachable at 127.0.0.1:3307"));
        assertTrue(Files.readString(configDir.resolve("pjsip.conf")).contains("[1001]"));
        String commands = Files.readString(fakeLog);
        assertTrue(commands.contains("nc -z -w 1 127.0.0.1 3307"));
        assertTrue(!commands.contains("mysql "));
        assertTrue(!commands.contains("docker compose -f"));
        assertTrue(!commands.contains("restart"));
        assertTrue(!Files.exists(runtimeDir.resolve("logs")));
    }

    @Test
    void startBlocksWhenDockerComposeUnavailable() throws Exception {
        Path fakeBin = tempDir.resolve("compose-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("compose.log");
        installPhoneStartFakes(fakeBin);
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 1; fi
                exit 0
                """);

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", createAsteriskConfigDir().toString()));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("docker compose"));
    }

    @Test
    void startUsesTcpOnlyEvenWhenMysqlClientExists() throws Exception {
        Path fakeBin = tempDir.resolve("mysql-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("mysql.log");
        installPhoneStartFakes(fakeBin);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeCommand(fakeBin, "mysql", """
                #!/usr/bin/env bash
                echo "mysql $*" >> "$PHONE_AGENT_FAKE_LOG"
                exit 0
                """);
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo false; exit 0; fi
                if [[ "$1" == "compose" && "$3" == *"docker-compose.yml" && "$4" == "up" ]]; then exit 1; fi
                exit 0
                """);
        Path configDir = createAsteriskConfigDir();

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("mysql tcp reachable; database/auth/Flyway will be verified by Spring startup"));
        assertTrue(!result.output().contains("mysql reachable 127.0.0.1:3307/phone_agent"));
        String commands = Files.readString(fakeLog);
        assertTrue(!commands.contains("mysql "));
        assertTrue(!commands.contains("docker exec mysql"));
        assertTrue(!commands.contains("CREATE DATABASE"));
    }

    @Test
    void externalMysqlTcpCheckLetsAsteriskSpringProveReadiness() throws Exception {
        Path fakeBin = tempDir.resolve("no-mysql-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("no-mysql.log");
        installPhoneStartFakes(fakeBin);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\necho \"nc $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo false; exit 0; fi
                if [[ "$1" == "compose" ]]; then exit 1; fi
                exit 0
                """);

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", createAsteriskConfigDir().toString(),
                        "__PATH_ONLY", "true"
                ));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("mysql tcp reachable; database/auth/Flyway will be verified by Spring startup"));
        assertTrue(!result.output().contains("mysql reachable 127.0.0.1:3307/phone_agent"));
        assertTrue(result.output().contains("docker compose failed"));
    }

    @Test
    void doctorPhoneUsesConfiguredRegistrationSubscriptionAndWatcherValues() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"manager show command Originate"* ]]; then echo "Privilege: originate,all"; exit 0; fi
                  if [[ "$*" == *"pjsip show contacts"* ]]; then echo "Contact: 1002/sip:phone"; exit 0; fi
                  if [[ "$*" == *"pjsip show subscriptions inbound"* ]]; then echo "custom-slots/dialog"; exit 0; fi
                  if [[ "$*" == *"core show hints"* ]]; then
                    echo "801@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers 1"
                    echo "802@phone-agent-mvp     : Custom:phone-agent-slot-2  State:Idle Watchers 1"
                    echo "803@phone-agent-mvp     : Custom:phone-agent-slot-3  State:Idle Watchers 1"
                    echo "804@phone-agent-mvp     : Custom:phone-agent-slot-4  State:Idle Watchers 1"
                    exit 0
                  fi
                  exit 0
                fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  printf 'Response: Success\\r\\nMessage: Authentication accepted\\r\\n'
                fi
                exit 0
                """);
        Path configDir = createAsteriskConfigDir();
        writeGeneratedConfig(configDir, Map.of(
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_BLF_EVENTLIST_URI", "custom-slots",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,802,803,804"
        ));

        ScriptResult result = runScriptWithEnv(List.of("doctor", "--phone"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString(),
                "PHONE_AGENT_SIP_EXTENSION", "1002",
                "PHONE_AGENT_BLF_EVENTLIST_URI", "custom-slots",
                "PHONE_AGENT_BLF_EXTENSIONS", "801,802,803,804"
        ));

        assertTrue(result.exitCode() == 0 || result.exitCode() == 3, result.output());
        assertTrue(result.output().contains("Phone config: UP - sip=1002"));
        assertTrue(result.output().contains("Phone registration: UP - 1002 registered"));
        assertTrue(result.output().contains("BLF subscription: UP - custom-slots/dialog active"));
        assertTrue(result.output().contains("BLF watchers: UP - 801,802,803,804 Watchers >= 1"));
        assertTrue(!result.output().contains("805"));
        assertTrue(!result.output().contains("blf=601"), result.output());
        assertTrue(!result.output().contains("601@phone-agent-mvp"), result.output());
        assertTrue(!result.output().contains("missing watchers for: 601"), result.output());
    }

    @Test
    void statusAndDoctorTreatWatcherCountsAtLeastOneAsReady() throws Exception {
        for (String watcherCount : List.of("1", "2", "10")) {
            String hintsScript = """
                    echo "  601@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers %s"
                    """.formatted(watcherCount).stripTrailing();

            ScriptResult status = runPhoneDiagnosticsWithHints(List.of("status"), hintsScript, Map.of(
                    "PHONE_AGENT_BLF_EXTENSIONS", "601"
            ));
            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains("BLF watchers: UP - 601 Watchers >= 1"), status.output());

            ScriptResult doctor = runPhoneDiagnosticsWithHints(List.of("doctor", "--phone"), hintsScript, Map.of(
                    "PHONE_AGENT_BLF_EXTENSIONS", "601"
            ));
            assertEquals(0, doctor.exitCode(), doctor.output());
            assertTrue(doctor.output().contains("BLF watchers: UP - 601 Watchers >= 1"), doctor.output());
        }
    }

    @Test
    void doctorReportsWatcherZeroMissingHintAndMissingWatcherNumberAsNotReady() throws Exception {
        String hintsScript = """
                echo "601@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers 0"
                echo "603@phone-agent-mvp     : Custom:phone-agent-slot-3  State:Idle Watchers"
                echo "604@phone-agent-mvp     : Custom:phone-agent-slot-4  State:Idle Watchers 1"
                """.stripTrailing();

        ScriptResult result = runPhoneDiagnosticsWithHints(List.of("doctor", "--phone"), hintsScript, Map.of(
                "PHONE_AGENT_BLF_EXTENSIONS", "601,602,603,604"
        ));

        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("BLF watchers: DOWN - missing watchers for: 601 602 603"), result.output());
        assertTrue(!result.output().contains("missing watchers for: 604"), result.output());
    }

    @Test
    void startPostChecksDoNotAutoSyncAndDoNotBlockWhenBlfWatchersAreNotReady() throws Exception {
        String hintsScript = """
                echo "601@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers 0"
                """.stripTrailing();

        StartFixture fixture = prepareHealthyPhoneStartFixture(hintsScript, true, Map.of(
                "PHONE_AGENT_BLF_EXTENSIONS", "601"
        ));

        ScriptResult result = runScriptWithEnv(List.of("start", "--phone"), fixture.fakeBin(), fixture.fakeLog(), null,
                fixture.env());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[warn] blf watchers missing for: 601"), result.output());
        assertTrue(result.output().contains("[manual] If physical BLF lamps are stale after the phone subscribes"), result.output());
        String commands = Files.readString(fixture.fakeLog());
        assertTrue(!commands.contains("/internal/admin/blf/sync"), commands);
        assertTrue(!commands.contains("-X POST"), commands);
    }

    @Test
    void startPostChecksWarnWhenAsteriskHintQueryFailsWithoutAutoSync() throws Exception {
        StartFixture fixture = prepareHealthyPhoneStartFixture("", false, Map.of(
                "PHONE_AGENT_BLF_EXTENSIONS", "601"
        ));

        ScriptResult result = runScriptWithEnv(List.of("start", "--phone"), fixture.fakeBin(), fixture.fakeLog(), null,
                fixture.env());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("[warn] could not query Asterisk BLF hints"), result.output());
        String commands = Files.readString(fixture.fakeLog());
        assertTrue(!commands.contains("/internal/admin/blf/sync"), commands);
    }

    @Test
    void doctorReportsAmiDownWhenLoginFails() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"manager show command Originate"* ]]; then echo "Privilege: originate,all"; fi
                  exit 0
                fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 22\n");
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  printf 'Response: Error\\r\\nMessage: Authentication failed\\r\\n'
                fi
                exit 0
                """);

        ScriptResult result = runScriptWithEnv(List.of("doctor"), fakeBin, fakeLog, null);

        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("AMI permissions"));
        assertTrue(result.output().contains("DOWN"));
        assertTrue(Files.readString(fakeLog).contains("nc -w 3 127.0.0.1 5038"));
    }

    @Test
    void startRejectsJavaBelow25BeforeCheckingMysqlOrPhoneStack() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeJava(fakeBin, "1.8.0_441");

        ScriptResult result = runScriptWithEnv(List.of("start", "--software-only"), fakeBin, fakeLog, null);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java 25 required"));
        assertTrue(result.output().contains("1.8.0_441"));
        assertTrue(!result.output().contains("mysql unreachable"));
        assertTrue(!result.output().contains("Asterisk"));
    }

    @Test
    void startRecognizesManagedHealthySpringAndRejectsNonManagedPortOwner() throws Exception {
        Path fakeBin = tempDir.resolve("spring-port-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("spring-port.log");
        Path runtimeDir = tempDir.resolve("runtime");
        Files.createDirectories(runtimeDir);
        long currentPid = ProcessHandle.current().pid();
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), Long.toString(currentPid));
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "curl", """
                #!/usr/bin/env bash
                echo '{"status":"UP"}'
                exit 0
                """);
        writeFakeCommand(fakeBin, "lsof", """
                #!/usr/bin/env bash
                echo 'COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME'
                echo "java      $PHONE_AGENT_TEST_PORT_PID user   10u  IPv4  0t0  TCP *:8080 (LISTEN)"
                exit 0
                """);
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\nexit 0\n");
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\nexit 0\n");

        ScriptResult healthy = runScriptWithEnv(List.of("start", "--software-only"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                "PHONE_AGENT_TEST_PORT_PID", Long.toString(currentPid)
        ));
        assertEquals(0, healthy.exitCode(), healthy.output());
        assertTrue(healthy.output().contains("spring already healthy"));

        Files.writeString(runtimeDir.resolve("phone-agent.pid"), "999999");
        ScriptResult nonManaged = runScriptWithEnv(List.of("start", "--software-only"), fakeBin, fakeLog, null, Map.of(
                "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                "PHONE_AGENT_TEST_PORT_PID", Long.toString(currentPid)
        ));
        assertEquals(1, nonManaged.exitCode(), nonManaged.output());
        assertTrue(nonManaged.output().contains("non-managed process"));
    }

    @Test
    void statusIsFullReadOnlyDiagnosticWithTcpOnlyMysql() throws Exception {
        StatusFixture fixture = prepareStatusFixture(StatusFakeProfile.allUp(), Map.of());

        ScriptResult result = runScriptWithEnv(List.of("status"), fixture.fakeBin(), fixture.fakeLog(), null, Map.of(
                "PHONE_AGENT_RUNTIME_DIR", fixture.runtimeDir().toString(),
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", fixture.configDir().toString()
        ));

        assertEquals(0, result.exitCode(), result.output());
        for (String label : List.of("Java:", "Spring:", "MySQL:", "Phone config:", "Asterisk:",
                "Asterisk config:", "Asterisk to Spring:", "AMI permissions:", "Phone registration:",
                "BLF subscription:", "BLF watchers:")) {
            assertTrue(result.output().contains(label), label + "\n" + result.output());
        }
        assertTrue(!result.output().contains("Flyway:"), result.output());
        assertTrue(result.output().contains("database/auth/Flyway are verified by Spring startup logs/health"), result.output());
        for (String line : List.of("Spring: UP", "MySQL: UP", "Asterisk: UP", "Asterisk config: UP",
                "Asterisk to Spring: UP", "AMI permissions: UP", "Phone registration: UP",
                "BLF subscription: UP", "BLF watchers: UP")) {
            assertTrue(result.output().contains(line), line + "\n" + result.output());
        }
        fixture.assertReadOnly();
        String commands = Files.readString(fixture.fakeLog());
        assertTrue(commands.contains("nc -z -w 1 127.0.0.1 3307"));
        assertTrue(!commands.contains("mysql "));
        assertNoServiceMutationCommands(commands);
    }

    @Test
    void statusReportsEachPhoneStackDownBranchWithoutMutatingState() throws Exception {
        assertStatusDown(StatusFakeProfile.allUp().withSpringUp(false), Map.of(),
                "Spring: DOWN", "run scripts/phone-agent-dev.sh start");
        assertStatusDown(StatusFakeProfile.allUp().withMysqlTcpUp(false), Map.of(),
                "MySQL: DOWN", "127.0.0.1:3307 unreachable; prepare external MySQL service/database");
        assertStatusDown(StatusFakeProfile.allUp().withAsteriskRunning(false), Map.of(),
                "Asterisk: DOWN", "docker compose -f");
        assertStatusDown(StatusFakeProfile.allUp(), Map.of("PHONE_AGENT_SIP_EXTENSION", "1002"),
                "Asterisk config: DOWN", "status is read-only; run scripts/phone-agent-dev.sh start to refresh generated config");
        assertStatusDown(StatusFakeProfile.allUp().withCallbackUp(false), Map.of(),
                "Asterisk to Spring: DOWN", "unavailable from container");
        assertStatusDown(StatusFakeProfile.allUp().withAmiUp(false), Map.of(),
                "AMI permissions: DOWN", "check ");
        assertStatusDown(StatusFakeProfile.allUp().withSipRegistered(false), Map.of(),
                "Phone registration: DOWN", "register configured SIP extension 1001");
        assertStatusDown(StatusFakeProfile.allUp().withBlfSubscribed(false), Map.of(),
                "BLF subscription: DOWN", "check Eventlist BLF URI phone-agent-slots");
        assertStatusDown(StatusFakeProfile.allUp().withBlfWatchersUp(false), Map.of(),
                "BLF watchers: DOWN", "missing watchers for: 608");
    }

    @Test
    void statusUsesTcpOnlyMysqlWhenMysqlClientIsMissingWithoutMutatingState() throws Exception {
        StatusFixture fixture = prepareStatusFixture(StatusFakeProfile.allUp().withoutMysqlClient(), Map.of());

        ScriptResult result = runScriptWithEnv(List.of("status"), fixture.fakeBin(), fixture.fakeLog(), null, Map.of(
                "PHONE_AGENT_RUNTIME_DIR", fixture.runtimeDir().toString(),
                "PHONE_AGENT_ASTERISK_CONFIG_DIR", fixture.configDir().toString(),
                "__PATH_ONLY", "true"
        ));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("MySQL: UP"), result.output());
        assertTrue(result.output().contains("database/auth/Flyway are verified by Spring startup logs/health"), result.output());
        assertTrue(!result.output().contains("Flyway:"), result.output());
        fixture.assertReadOnly();
        String commands = Files.exists(fixture.fakeLog()) ? Files.readString(fixture.fakeLog()) : "";
        assertTrue(commands.contains("nc -z -w 1 127.0.0.1 3307"));
        assertTrue(!commands.contains("mysql "));
        assertNoServiceMutationCommands(commands);
    }

    @Test
    void logsPreserveRuntimeStateAndDoNotCallMysql() throws Exception {
        Path fakeBin = tempDir.resolve("logs-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("logs.log");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "logs" ]]; then
                  if [[ "$2" == "-f" ]]; then echo "asterisk follow"; else echo "asterisk once"; fi
                  exit 0
                fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "tail", """
                #!/usr/bin/env bash
                last=""
                for arg in "$@"; do last="$arg"; done
                cat "$last"
                exit 0
                """);
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        Path runtimeDir = tempDir.resolve("missing-runtime");
        Path configDir = createAsteriskConfigDir();
        writeGeneratedConfig(configDir, Map.of());

        ScriptResult result = runScriptWithEnv(List.of("logs", "spring"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()
                ));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("spring log directory not found"));
        assertTrue(!Files.exists(runtimeDir.resolve("logs")));

        Files.createDirectories(runtimeDir.resolve("logs"));
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), "4242");
        Path springLog = runtimeDir.resolve("logs").resolve("spring.log");
        Files.writeString(springLog, "line\n");
        Path pjsip = configDir.resolve("pjsip.conf");
        Path extensions = configDir.resolve("extensions.conf");
        String pjsipBefore = Files.readString(pjsip);
        String extensionsBefore = Files.readString(extensions);
        FileTime pjsipMtimeBefore = Files.getLastModifiedTime(pjsip);
        FileTime extensionsMtimeBefore = Files.getLastModifiedTime(extensions);
        FileTime springLogMtimeBefore = Files.getLastModifiedTime(springLog);
        long springLogSizeBefore = Files.size(springLog);
        ScriptResult follow = runScriptWithEnv(List.of("logs", "spring", "-f"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()
                ));
        assertEquals(0, follow.exitCode(), follow.output());
        assertTrue(follow.output().contains("line"));

        ScriptResult asterisk = runScriptWithEnv(List.of("logs", "asterisk"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()
                ));
        assertEquals(0, asterisk.exitCode(), asterisk.output());
        assertTrue(asterisk.output().contains("asterisk once"));

        ScriptResult all = runScriptWithEnv(List.of("logs", "all"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()
                ));
        assertEquals(0, all.exitCode(), all.output());
        assertTrue(all.output().contains("== spring =="));
        assertTrue(all.output().contains("line"));
        assertTrue(all.output().contains("== asterisk =="));
        assertTrue(all.output().contains("asterisk once"));

        ScriptResult allFollow = runScriptWithEnv(List.of("logs", "all", "-f"), fakeBin, fakeLog, null,
                Map.of(
                        "PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString(),
                        "PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()
                ));
        assertEquals(0, allFollow.exitCode(), allFollow.output());
        assertTrue(allFollow.output().contains("[spring] line"), allFollow.output());
        assertTrue(allFollow.output().contains("[asterisk] asterisk follow"), allFollow.output());

        assertEquals("4242", Files.readString(runtimeDir.resolve("phone-agent.pid")));
        assertEquals(pjsipBefore, Files.readString(pjsip));
        assertEquals(extensionsBefore, Files.readString(extensions));
        assertEquals(pjsipMtimeBefore, Files.getLastModifiedTime(pjsip));
        assertEquals(extensionsMtimeBefore, Files.getLastModifiedTime(extensions));
        assertEquals(springLogMtimeBefore, Files.getLastModifiedTime(springLog));
        assertEquals(springLogSizeBefore, Files.size(springLog));
        String commands = Files.exists(fakeLog) ? Files.readString(fakeLog) : "";
        assertTrue(commands.contains("docker logs --tail 200 phone-agent-asterisk-mvp"));
        assertTrue(commands.contains("docker logs -f --tail 120 phone-agent-asterisk-mvp"));
        assertTrue(!commands.contains("mysql "));
        assertNoServiceMutationCommands(commands);
    }

    @Test
    void stopDoesNotTouchMysql() throws Exception {
        Path fakeBin = tempDir.resolve("stop-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("stop.log");
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" ]]; then exit 0; fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        Path runtimeDir = tempDir.resolve("stop-runtime");
        Files.createDirectories(runtimeDir);

        ScriptResult result = runScriptWithEnv(List.of("stop"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString()));

        assertEquals(0, result.exitCode(), result.output());
        String commands = Files.readString(fakeLog);
        assertTrue(commands.contains("docker compose -f"));
        assertTrue(!commands.contains("mysql "));
        assertTrue(!commands.contains("docker stop mysql"));
        assertTrue(!commands.contains("docker rm mysql"));
    }

    @Test
    void stopKillsManagedSpringPidAndLeavesMysqlUntouched() throws Exception {
        Path fakeBin = tempDir.resolve("stop-managed-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("stop-managed.log");
        installStopFakes(fakeBin);
        Path runtimeDir = tempDir.resolve("stop-managed-runtime");
        Files.createDirectories(runtimeDir);
        Process managed = new ProcessBuilder("sleep", "30").start();
        try {
            Files.writeString(runtimeDir.resolve("phone-agent.pid"), Long.toString(managed.pid()));

            ScriptResult result = runScriptWithEnv(List.of("stop"), fakeBin, fakeLog, null,
                    Map.of("PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString()));

            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("spring stopped"));
            assertTrue(!managed.isAlive());
            assertTrue(!Files.exists(runtimeDir.resolve("phone-agent.pid")));
            assertStopDidNotTouchMysql(Files.readString(fakeLog));
        } finally {
            managed.destroyForcibly();
        }
    }

    @Test
    void stopRemovesStaleSpringPidAndLeavesMysqlUntouched() throws Exception {
        Path fakeBin = tempDir.resolve("stop-stale-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("stop-stale.log");
        installStopFakes(fakeBin);
        Path runtimeDir = tempDir.resolve("stop-stale-runtime");
        Files.createDirectories(runtimeDir);
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), "999999");

        ScriptResult result = runScriptWithEnv(List.of("stop"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString()));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("removed stale Spring pid file: 999999"));
        assertTrue(!Files.exists(runtimeDir.resolve("phone-agent.pid")));
        assertStopDidNotTouchMysql(Files.readString(fakeLog));
    }

    @Test
    void startFailsWhenAsteriskConfigDirectoryIsUnwritable() throws Exception {
        Path fakeBin = tempDir.resolve("unwritable-bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("unwritable.log");
        installPhoneStartFakes(fakeBin);
        Path regularFileParent = tempDir.resolve("regular-file-parent");
        Files.writeString(regularFileParent, "not a directory");
        Path configDir = regularFileParent.resolve("asterisk");

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null,
                Map.of("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString()));

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains("generated Asterisk config missing"));
        assertTrue(!Files.exists(configDir.resolve("pjsip.conf")));
        assertTrue(!Files.exists(configDir.resolve("extensions.conf")));
    }

    @Test
    void softwareOnlyDoctorSkipsPhoneAndAsteriskChecks() throws Exception {
        Path fakeBin = tempDir.resolve("bin");
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("fake-commands.log");
        writeFakeCommand(fakeBin, "curl", "#!/usr/bin/env bash\nexit 22\n");
        writeFakeCommand(fakeBin, "nc", "#!/usr/bin/env bash\necho \"nc $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 1\n");
        writeFakeJava(fakeBin, "25.0.1");

        ScriptResult result = runScriptWithEnv(List.of("doctor", "--software-only"), fakeBin, fakeLog, null);

        assertEquals(3, result.exitCode(), result.output());
        assertTrue(result.output().contains("Java: UP"));
        assertTrue(result.output().contains("Phone stack: SKIPPED - software-only mode"));
        assertTrue(!result.output().contains("Asterisk:"));
        assertTrue(!result.output().contains("Phone registration"));
        assertTrue(!result.output().contains("BLF subscription"));
    }

    private static ScriptResult runScript(String... args) throws Exception {
        return runScriptWithEnv(List.of(args), null, null, null);
    }

    private static ScriptResult runScriptWithEnv(List<String> args, Path fakeBin, Path fakeLog, Path model) throws Exception {
        return runScriptWithEnv(args, fakeBin, fakeLog, model, Map.of());
    }

    private static ScriptResult runScriptWithEnv(
            List<String> args,
            Path fakeBin,
            Path fakeLog,
            Path model,
            Map<String, String> extraEnv
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(SCRIPT.toString());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("JAVA_HOME");
        Map<String, String> effectiveExtraEnv = new HashMap<>(extraEnv);
        boolean pathOnly = "true".equals(effectiveExtraEnv.remove("__PATH_ONLY"));
        if (fakeBin != null) {
            String path = pathOnly ? fakeBin + ":/bin:/usr/bin:/usr/sbin:/sbin" : fakeBin + ":" + builder.environment().get("PATH");
            builder.environment().put("PATH", path);
        }
        if (fakeLog != null) {
            builder.environment().put("PHONE_AGENT_FAKE_LOG", fakeLog.toString());
        }
        if (model != null) {
            builder.environment().put("PHONE_AGENT_WHISPER_MODEL_PATH", model.toString());
        }
        builder.environment().putAll(effectiveExtraEnv);
        Process process = builder.start();

        boolean exited = process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("script did not exit within 10 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ScriptResult(process.exitValue(), output);
    }

    private static void writeFakeCommand(Path fakeBin, String command, String content) throws Exception {
        Path file = fakeBin.resolve(command);
        Files.writeString(file, content.stripLeading());
        assertTrue(file.toFile().setExecutable(true));
    }

    private static void writeFakeJava(Path fakeBin, String version) throws Exception {
        writeFakeCommand(fakeBin, "java", """
                #!/usr/bin/env bash
                if [[ "$1" == "-version" ]]; then
                  echo 'java version "%s"' >&2
                  exit 0
                fi
                exit 0
                """.formatted(version));
    }

    private Path createAsteriskConfigDir() throws Exception {
        Path configDir = tempDir.resolve("asterisk-" + System.nanoTime());
        Files.createDirectories(configDir);
        Files.copy(Path.of("ops/asterisk-mvp/manager.conf"), configDir.resolve("manager.conf"));
        return configDir;
    }

    private void installFullInitFakes(Path fakeBin) throws Exception {
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "inspect" ]]; then exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"manager show command Originate"* ]]; then echo "Privilege: originate,all"; fi
                  exit 0
                fi
                if [[ "$1" == "restart" ]]; then exit 0; fi
                if [[ "$1" == "compose" ]]; then exit 0; fi
                exit 0
                """);
        for (String command : List.of("ffmpeg", "whisper", "tmux", "ttyd", "codex", "curl")) {
            writeFakeCommand(fakeBin, command, "#!/usr/bin/env bash\nexit 0\n");
        }
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  printf 'Response: Success\\r\\nMessage: Authentication accepted\\r\\n'
                fi
                exit 0
                """);
    }

    private void installPhoneStartFakes(Path fakeBin) throws Exception {
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                exit 0
                """);
        for (String command : List.of("ffmpeg", "whisper", "tmux", "ttyd", "codex", "curl")) {
            writeFakeCommand(fakeBin, command, "#!/usr/bin/env bash\nexit 0\n");
        }
        writeFakeJava(fakeBin, "25.0.1");
    }

    private void assertStartPreflightFailure(Map<String, String> env, String expectedDetail, String expectedMessage) throws Exception {
        Path fakeBin = tempDir.resolve("preflight-bin-" + System.nanoTime());
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("preflight.log");
        if (!"docker".equals(expectedDetail)) {
            installPhoneStartFakes(fakeBin);
        } else {
            writeFakeCommand(fakeBin, "docker", "#!/usr/bin/env bash\nexit 1\n");
            for (String command : List.of("ffmpeg", "whisper", "tmux", "ttyd", "codex", "curl")) {
                writeFakeCommand(fakeBin, command, "#!/usr/bin/env bash\nexit 0\n");
            }
            writeFakeJava(fakeBin, "25.0.1");
        }
        var effectiveEnv = new HashMap<>(env);
        effectiveEnv.put("PHONE_AGENT_ASTERISK_CONFIG_DIR", createAsteriskConfigDir().toString());

        ScriptResult result = runScriptWithEnv(List.of("start"), fakeBin, fakeLog, null, effectiveEnv);

        assertEquals(1, result.exitCode(), result.output());
        assertTrue(result.output().contains(expectedMessage), result.output());
        assertTrue(result.output().contains(expectedDetail), result.output());
    }

    private ScriptResult runPhoneDiagnosticsWithHints(
            List<String> args,
            String hintsScript,
            Map<String, String> configEnv
    ) throws Exception {
        Path fakeBin = tempDir.resolve("diagnostics-bin-" + System.nanoTime());
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("diagnostics-" + System.nanoTime() + ".log");
        Path runtimeDir = tempDir.resolve("diagnostics-runtime-" + System.nanoTime());
        Files.createDirectories(runtimeDir.resolve("logs"));
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), Long.toString(ProcessHandle.current().pid()));
        installPhoneDiagnosticFakes(fakeBin, fakeLog, hintsScript, true, configEnv);
        Path configDir = createAsteriskConfigDir();
        writeGeneratedConfig(configDir, configEnv);
        Files.writeString(runtimeDir.resolve("logs").resolve("spring.log"), "diagnostics fixture\n");

        Map<String, String> effectiveEnv = new HashMap<>(configEnv);
        effectiveEnv.put("PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString());
        effectiveEnv.put("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString());
        return runScriptWithEnv(args, fakeBin, fakeLog, null, effectiveEnv);
    }

    private StartFixture prepareHealthyPhoneStartFixture(
            String hintsScript,
            boolean hintsSucceeds,
            Map<String, String> configEnv
    ) throws Exception {
        Path fakeBin = tempDir.resolve("start-post-bin-" + System.nanoTime());
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("start-post-" + System.nanoTime() + ".log");
        Path runtimeDir = tempDir.resolve("start-post-runtime-" + System.nanoTime());
        Files.createDirectories(runtimeDir.resolve("logs"));
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), Long.toString(ProcessHandle.current().pid()));
        installPhoneDiagnosticFakes(fakeBin, fakeLog, hintsScript, hintsSucceeds, configEnv);
        for (String command : List.of("ffmpeg", "whisper", "tmux", "ttyd", "codex")) {
            writeFakeCommand(fakeBin, command, "#!/usr/bin/env bash\nexit 0\n");
        }
        Path configDir = createAsteriskConfigDir();
        writeGeneratedConfig(configDir, configEnv);
        Files.writeString(runtimeDir.resolve("logs").resolve("spring.log"), "start fixture\n");

        Map<String, String> effectiveEnv = new HashMap<>(configEnv);
        effectiveEnv.put("PHONE_AGENT_RUNTIME_DIR", runtimeDir.toString());
        effectiveEnv.put("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString());
        return new StartFixture(fakeBin, fakeLog, effectiveEnv);
    }

    /**
     * Installs read-only fakes for status, doctor, and already-healthy start
     * paths so watcher parsing can be tested without touching local services.
     */
    private void installPhoneDiagnosticFakes(
            Path fakeBin,
            Path fakeLog,
            String hintsScript,
            boolean hintsSucceeds,
            Map<String, String> configEnv
    ) throws Exception {
        String sipExtension = configEnv.getOrDefault("PHONE_AGENT_SIP_EXTENSION", "1001");
        String eventlistUri = configEnv.getOrDefault("PHONE_AGENT_BLF_EVENTLIST_URI", "phone-agent-slots");
        String coreHintsBranch = hintsSucceeds
                ? hintsScript + "\n                    exit 0"
                : "exit 42";
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo true; exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"curl -fsS"* ]]; then exit 0; fi
                  if [[ "$*" == *"manager show command Originate"* ]]; then echo "Privilege: originate,all"; exit 0; fi
                  if [[ "$*" == *"pjsip show contacts"* ]]; then echo "Contact: %s/sip:phone"; exit 0; fi
                  if [[ "$*" == *"pjsip show subscriptions inbound"* ]]; then echo "%s/dialog"; exit 0; fi
                  if [[ "$*" == *"core show hints"* ]]; then
                %s
                  fi
                  exit 0
                fi
                if [[ "$1" == "compose" ]]; then exit 0; fi
                exit 0
                """.formatted(sipExtension, eventlistUri, coreHintsBranch.indent(4).stripTrailing()));
        writeFakeCommand(fakeBin, "curl", """
                #!/usr/bin/env bash
                echo '{"status":"UP"}'
                exit 0
                """);
        writeFakeCommand(fakeBin, "lsof", """
                #!/usr/bin/env bash
                echo 'COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME'
                echo "java      %s user   10u  IPv4  0t0  TCP *:8080 (LISTEN)"
                exit 0
                """.formatted(ProcessHandle.current().pid()));
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  printf 'Response: Success\\r\\nMessage: Authentication accepted\\r\\n'
                  exit 0
                fi
                exit 0
                """);
    }

    /**
     * Builds a status fixture around a healthy baseline so each DOWN scenario can
     * flip exactly one fake dependency while preserving read-only state checks.
     */
    private StatusFixture prepareStatusFixture(StatusFakeProfile profile, Map<String, String> configEnv) throws Exception {
        Path fakeBin = tempDir.resolve("status-bin-" + System.nanoTime());
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("status-" + System.nanoTime() + ".log");
        Path runtimeDir = tempDir.resolve("status-runtime-" + System.nanoTime());
        Files.createDirectories(runtimeDir.resolve("logs"));
        Files.writeString(runtimeDir.resolve("phone-agent.pid"), Long.toString(ProcessHandle.current().pid()));
        installStatusFakes(fakeBin, profile);

        Path configDir = createAsteriskConfigDir();
        writeGeneratedConfig(configDir, Map.of());
        Path springLog = runtimeDir.resolve("logs").resolve("spring.log");
        Files.writeString(springLog, "status must not rewrite this log\n");

        return StatusFixture.capture(fakeBin, fakeLog, runtimeDir, configDir, springLog);
    }

    private void assertStatusDown(
            StatusFakeProfile profile,
            Map<String, String> env,
            String expectedLine,
            String expectedDetail
    ) throws Exception {
        assertStatusResult(profile, env, 3, expectedLine, expectedDetail);
    }

    private void assertStatusResult(
            StatusFakeProfile profile,
            Map<String, String> env,
            int expectedExitCode,
            String expectedLine,
            String expectedDetail
    ) throws Exception {
        StatusFixture fixture = prepareStatusFixture(profile, env);
        Map<String, String> effectiveEnv = new HashMap<>(env);
        effectiveEnv.put("PHONE_AGENT_RUNTIME_DIR", fixture.runtimeDir().toString());
        effectiveEnv.put("PHONE_AGENT_ASTERISK_CONFIG_DIR", fixture.configDir().toString());

        ScriptResult result = runScriptWithEnv(List.of("status"), fixture.fakeBin(), fixture.fakeLog(), null, effectiveEnv);

        assertEquals(expectedExitCode, result.exitCode(), result.output());
        assertTrue(result.output().contains(expectedLine), result.output());
        assertTrue(result.output().contains(expectedDetail), result.output());
        fixture.assertReadOnly();
        String commands = Files.exists(fixture.fakeLog()) ? Files.readString(fixture.fakeLog()) : "";
        assertNoServiceMutationCommands(commands);
        assertTrue(!commands.contains("mysql "));
    }

    private void installStatusFakes(Path fakeBin, StatusFakeProfile profile) throws Exception {
        String watcherLines = profile.blfWatchersUp()
                ? """
                    echo "601@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers 1"
                    echo "602@phone-agent-mvp     : Custom:phone-agent-slot-2  State:Idle Watchers 1"
                    echo "603@phone-agent-mvp     : Custom:phone-agent-slot-3  State:Idle Watchers 1"
                    echo "604@phone-agent-mvp     : Custom:phone-agent-slot-4  State:Idle Watchers 1"
                    echo "605@phone-agent-mvp     : Custom:phone-agent-slot-5  State:Idle Watchers 1"
                    echo "606@phone-agent-mvp     : Custom:phone-agent-slot-6  State:Idle Watchers 1"
                    echo "607@phone-agent-mvp     : Custom:phone-agent-slot-7  State:Idle Watchers 1"
                    echo "608@phone-agent-mvp     : Custom:phone-agent-slot-8  State:Idle Watchers 1"
                    """
                : """
                    echo "601@phone-agent-mvp     : Custom:phone-agent-slot-1  State:Idle Watchers 1"
                    echo "602@phone-agent-mvp     : Custom:phone-agent-slot-2  State:Idle Watchers 1"
                    echo "603@phone-agent-mvp     : Custom:phone-agent-slot-3  State:Idle Watchers 1"
                    echo "604@phone-agent-mvp     : Custom:phone-agent-slot-4  State:Idle Watchers 1"
                    echo "605@phone-agent-mvp     : Custom:phone-agent-slot-5  State:Idle Watchers 1"
                    echo "606@phone-agent-mvp     : Custom:phone-agent-slot-6  State:Idle Watchers 1"
                    echo "607@phone-agent-mvp     : Custom:phone-agent-slot-7  State:Idle Watchers 1"
                    echo "608@phone-agent-mvp     : Custom:phone-agent-slot-8  State:Idle Watchers 0"
                    """;
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "inspect" && "$2" == "-f" ]]; then echo %s; exit 0; fi
                if [[ "$1" == "exec" ]]; then
                  if [[ "$*" == *"curl -fsS"* ]]; then exit %s; fi
                  if [[ "$*" == *"manager show command Originate"* ]]; then
                    %s
                    exit 0
                  fi
                  if [[ "$*" == *"pjsip show contacts"* ]]; then
                    %s
                    exit 0
                  fi
                  if [[ "$*" == *"pjsip show subscriptions inbound"* ]]; then
                    %s
                    exit 0
                  fi
                  if [[ "$*" == *"core show hints"* ]]; then
                %s
                    exit 0
                  fi
                  exit 0
                fi
                exit 0
                """.formatted(
                profile.asteriskRunning() ? "true" : "false",
                profile.callbackUp() ? "0" : "22",
                profile.amiUp() ? "echo \"Privilege: originate,all\"" : ":",
                profile.sipRegistered() ? "echo \"Contact: 1001/sip:phone\"" : ":",
                profile.blfSubscribed() ? "echo \"phone-agent-slots/dialog\"" : ":",
                watcherLines.indent(4).stripTrailing()
        ));
        writeFakeCommand(fakeBin, "curl", """
                #!/usr/bin/env bash
                if [[ "%s" == "true" ]]; then
                  echo '{"status":"UP"}'
                  exit 0
                fi
                exit 22
                """.formatted(profile.springUp()));
        writeFakeCommand(fakeBin, "lsof", """
                #!/usr/bin/env bash
                echo 'COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME'
                echo "java      %s user   10u  IPv4  0t0  TCP *:8080 (LISTEN)"
                exit 0
                """.formatted(ProcessHandle.current().pid()));
        writeFakeJava(fakeBin, "25.0.1");
        writeFakeCommand(fakeBin, "nc", """
                #!/usr/bin/env bash
                echo "nc $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$*" == *"5038"* ]]; then
                  cat >/dev/null
                  if [[ "%s" == "true" ]]; then
                    printf 'Response: Success\\r\\nMessage: Authentication accepted\\r\\n'
                  else
                    printf 'Response: Error\\r\\nMessage: Authentication failed\\r\\n'
                  fi
                  exit 0
                fi
                exit %s
                """.formatted(profile.amiUp(), profile.mysqlTcpUp() ? "0" : "1"));
    }

    private void installStopFakes(Path fakeBin) throws Exception {
        writeFakeCommand(fakeBin, "docker", """
                #!/usr/bin/env bash
                echo "docker $*" >> "$PHONE_AGENT_FAKE_LOG"
                if [[ "$1" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" && "$2" == "version" ]]; then exit 0; fi
                if [[ "$1" == "compose" ]]; then exit 0; fi
                exit 0
                """);
        writeFakeCommand(fakeBin, "mysql", "#!/usr/bin/env bash\necho \"mysql $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
        writeFakeCommand(fakeBin, "tmux", "#!/usr/bin/env bash\necho \"tmux $*\" >> \"$PHONE_AGENT_FAKE_LOG\"\nexit 0\n");
    }

    private static void assertStopDidNotTouchMysql(String commands) {
        assertTrue(commands.contains("docker compose -f"));
        assertTrue(commands.contains(" stop"));
        assertTrue(!commands.contains("mysql "));
        assertTrue(!commands.contains("docker stop mysql"));
        assertTrue(!commands.contains("docker rm mysql"));
        assertTrue(!commands.contains("docker exec mysql"));
        assertTrue(!commands.contains("CREATE DATABASE"));
    }

    private static void assertNoServiceMutationCommands(String commands) {
        assertTrue(!commands.contains(" compose -f "));
        assertTrue(!commands.contains(" compose up"));
        assertTrue(!commands.contains("docker restart"));
        assertTrue(!commands.contains("docker stop"));
        assertTrue(!commands.contains("docker rm"));
        assertTrue(!commands.contains(" restart"));
        assertTrue(!commands.contains(" stop"));
        assertTrue(!commands.contains("CREATE DATABASE"));
        assertTrue(!commands.contains("docker exec mysql"));
    }

    private void writeDefaultGeneratedConfig(Path configDir) throws Exception {
        writeGeneratedConfig(configDir, Map.of());
    }

    private void writeGeneratedConfig(Path configDir, Map<String, String> env) throws Exception {
        Path fakeBin = tempDir.resolve("render-bin-" + System.nanoTime());
        Files.createDirectories(fakeBin);
        Path fakeLog = tempDir.resolve("render.log");
        Path model = tempDir.resolve("render-model-" + System.nanoTime()).resolve("ggml-small.bin");
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model");
        installFullInitFakes(fakeBin);
        ArrayList<String> args = new ArrayList<>(List.of("init", "--phone", "--yes"));
        java.util.HashMap<String, String> renderEnv = new java.util.HashMap<>(env);
        renderEnv.put("PHONE_AGENT_ASTERISK_CONFIG_DIR", configDir.toString());
        ScriptResult result = runScriptWithEnv(args, fakeBin, fakeLog, model, renderEnv);
        assertEquals(0, result.exitCode(), result.output());
    }

    private record StatusFakeProfile(
            boolean springUp,
            boolean mysqlTcpUp,
            boolean asteriskRunning,
            boolean callbackUp,
            boolean amiUp,
            boolean sipRegistered,
            boolean blfSubscribed,
            boolean blfWatchersUp
    ) {
        static StatusFakeProfile allUp() {
            return new StatusFakeProfile(true, true, true, true, true, true, true, true);
        }

        StatusFakeProfile withSpringUp(boolean value) {
            return new StatusFakeProfile(value, mysqlTcpUp, asteriskRunning, callbackUp, amiUp,
                    sipRegistered, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withMysqlTcpUp(boolean value) {
            return new StatusFakeProfile(springUp, value, asteriskRunning, callbackUp, amiUp,
                    sipRegistered, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withAsteriskRunning(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, value, callbackUp, amiUp,
                    sipRegistered, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withCallbackUp(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, asteriskRunning, value, amiUp,
                    sipRegistered, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withAmiUp(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, asteriskRunning, callbackUp, value,
                    sipRegistered, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withSipRegistered(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, asteriskRunning, callbackUp, amiUp,
                    value, blfSubscribed, blfWatchersUp);
        }

        StatusFakeProfile withBlfSubscribed(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, asteriskRunning, callbackUp, amiUp,
                    sipRegistered, value, blfWatchersUp);
        }

        StatusFakeProfile withBlfWatchersUp(boolean value) {
            return new StatusFakeProfile(springUp, mysqlTcpUp, asteriskRunning, callbackUp, amiUp,
                    sipRegistered, blfSubscribed, value);
        }

        StatusFakeProfile withoutMysqlClient() {
            return this;
        }
    }

    private record StartFixture(Path fakeBin, Path fakeLog, Map<String, String> env) {
    }

    private record StatusFixture(
            Path fakeBin,
            Path fakeLog,
            Path runtimeDir,
            Path configDir,
            Path pjsip,
            Path extensions,
            Path springLog,
            String pjsipBefore,
            String extensionsBefore,
            String pidBefore,
            FileTime pjsipMtimeBefore,
            FileTime extensionsMtimeBefore,
            FileTime springLogMtimeBefore,
            long springLogSizeBefore
    ) {
        static StatusFixture capture(Path fakeBin, Path fakeLog, Path runtimeDir, Path configDir, Path springLog) throws Exception {
            Path pjsip = configDir.resolve("pjsip.conf");
            Path extensions = configDir.resolve("extensions.conf");
            return new StatusFixture(
                    fakeBin,
                    fakeLog,
                    runtimeDir,
                    configDir,
                    pjsip,
                    extensions,
                    springLog,
                    Files.readString(pjsip),
                    Files.readString(extensions),
                    Files.readString(runtimeDir.resolve("phone-agent.pid")),
                    Files.getLastModifiedTime(pjsip),
                    Files.getLastModifiedTime(extensions),
                    Files.getLastModifiedTime(springLog),
                    Files.size(springLog)
            );
        }

        void assertReadOnly() throws Exception {
            assertEquals(pjsipBefore, Files.readString(pjsip));
            assertEquals(extensionsBefore, Files.readString(extensions));
            assertEquals(pjsipMtimeBefore, Files.getLastModifiedTime(pjsip));
            assertEquals(extensionsMtimeBefore, Files.getLastModifiedTime(extensions));
            assertEquals(springLogMtimeBefore, Files.getLastModifiedTime(springLog));
            assertEquals(springLogSizeBefore, Files.size(springLog));
            assertEquals(pidBefore, Files.readString(runtimeDir.resolve("phone-agent.pid")));
        }
    }

    private record ScriptResult(int exitCode, String output) {
    }
}
