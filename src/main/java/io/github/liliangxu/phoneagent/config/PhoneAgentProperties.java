package io.github.liliangxu.phoneagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "phone-agent")
public class PhoneAgentProperties implements InitializingBean {
    private static final Pattern SIP_TOKEN = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern RING_TARGET = Pattern.compile("[A-Za-z0-9_./:@+-]+");
    private static final Pattern NUMERIC_EXTENSION = Pattern.compile("[0-9]+");
    private static final int MAX_BLF_EXTENSIONS = 8;

    private Path runtimeDir = Path.of("runtime");
    private String sayCommand = "/usr/bin/say";
    private String ffmpegCommand = "ffmpeg";
    private final Asr asr = new Asr();
    private Duration commandTimeout = Duration.ofSeconds(30);
    private final Ami ami = new Ami();
    private final Codex codex = new Codex();
    private final Sip sip = new Sip();
    private final Ring ring = new Ring();
    private final Blf blf = new Blf();

    public Path getRuntimeDir() {
        return runtimeDir;
    }

    public void setRuntimeDir(Path runtimeDir) {
        this.runtimeDir = runtimeDir;
    }

    public String getSayCommand() {
        return sayCommand;
    }

    public void setSayCommand(String sayCommand) {
        this.sayCommand = sayCommand;
    }

    public String getFfmpegCommand() {
        return ffmpegCommand;
    }

    public void setFfmpegCommand(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public Duration getCommandTimeout() {
        return commandTimeout;
    }

    public void setCommandTimeout(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    public Ami getAmi() {
        return ami;
    }

    public Asr getAsr() {
        return asr;
    }

    public Codex getCodex() {
        return codex;
    }

    public Sip getSip() {
        return sip;
    }

    public Ring getRing() {
        return ring;
    }

    public Blf getBlf() {
        return blf;
    }

    /**
     * Validates startup-only SIP/BLF configuration as soon as Spring finishes
     * binding properties, so invalid phone mappings fail before any runtime
     * state, AMI command, or task scheduling side effect can occur.
     */
    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        validateSipToken("phone-agent.sip.extension", sip.extension);
        validateSipToken("phone-agent.sip.auth-id", sip.authId);
        validatePassword("phone-agent.sip.password", sip.password);
        validateRingTarget("phone-agent.ring.target", ring.target);
        validateSipToken("phone-agent.blf.eventlist-uri", blf.eventlistUri);
        blfSlots();
    }

    /**
     * Returns the configured BLF mapping in user-provided order. The first
     * extension maps to slot 1, the second to slot 2, and so on; values are not
     * sorted or inferred from historical database rows.
     */
    public List<BlfSlot> blfSlots() {
        List<String> extensions = blf.extensions == null ? List.of() : blf.extensions;
        if (extensions.isEmpty()) {
            throw invalid("phone-agent.blf.extensions must contain 1 to " + MAX_BLF_EXTENSIONS + " numeric extensions");
        }
        if (extensions.size() > MAX_BLF_EXTENSIONS) {
            throw invalid("phone-agent.blf.extensions must not contain more than " + MAX_BLF_EXTENSIONS + " extensions");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<BlfSlot> slots = new ArrayList<>();
        for (int i = 0; i < extensions.size(); i++) {
            String extension = extensions.get(i) == null ? "" : extensions.get(i).trim();
            if (!NUMERIC_EXTENSION.matcher(extension).matches()) {
                throw invalid("phone-agent.blf.extensions contains non-numeric extension: " + extension);
            }
            if (!unique.add(extension)) {
                throw invalid("phone-agent.blf.extensions contains duplicate extension: " + extension);
            }
            slots.add(new BlfSlot(i + 1, extension));
        }
        return List.copyOf(slots);
    }

    private static void validateSipToken(String name, String value) {
        if (value == null || value.isBlank() || !SIP_TOKEN.matcher(value).matches()) {
            throw invalid(name + " must match [A-Za-z0-9_.-]+");
        }
    }

    private static void validateRingTarget(String name, String value) {
        if (value == null || value.isBlank() || !RING_TARGET.matcher(value).matches()) {
            throw invalid(name + " must match [A-Za-z0-9_./:@+-]+");
        }
    }

    private static void validatePassword(String name, String value) {
        if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw invalid(name + " must not be blank or contain a newline");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public static class Sip {
        private String extension = "1001";
        private String authId = "1001";
        private String password = "1001";

        public String getExtension() {
            return extension;
        }

        public void setExtension(String extension) {
            this.extension = extension;
        }

        public String getAuthId() {
            return authId;
        }

        public void setAuthId(String authId) {
            this.authId = authId;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class Ring {
        private String target = "PJSIP/1001";

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    public static class Blf {
        private String eventlistUri = "phone-agent-slots";
        private List<String> extensions = new ArrayList<>(List.of("601", "602", "603", "604", "605", "606", "607", "608"));

        public String getEventlistUri() {
            return eventlistUri;
        }

        public void setEventlistUri(String eventlistUri) {
            this.eventlistUri = eventlistUri;
        }

        public List<String> getExtensions() {
            return extensions;
        }

        public void setExtensions(List<String> extensions) {
            this.extensions = extensions == null ? new ArrayList<>() : new ArrayList<>(extensions);
        }
    }

    public static class Ami {
        private String host = "127.0.0.1";
        private int port = 5038;
        private String username = "phone-agent";
        private String secret = "phone-agent";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class Asr {
        private String whisperCommand = "";
        private String modelPath = "";
        private String language = "zh";

        public String getWhisperCommand() {
            return whisperCommand;
        }

        public void setWhisperCommand(String whisperCommand) {
            this.whisperCommand = whisperCommand;
        }

        public String getModelPath() {
            return modelPath;
        }

        public void setModelPath(String modelPath) {
            this.modelPath = modelPath;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }
    }

    public static class Codex {
        private String codexCommand = "codex";
        private String tmuxCommand = "tmux";
        private String ttydCommand = "ttyd";
        private String promptLanguage = "zh-CN";
        private Path sessionsDir = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        private Path registryDir = Path.of("runtime", "codex-sessions");
        private List<Path> allowedWorkspaceRoots = new ArrayList<>(List.of(Path.of(System.getProperty("user.dir"))));
        private Duration pollInterval = Duration.ofSeconds(2);

        public String getCodexCommand() {
            return codexCommand;
        }

        public void setCodexCommand(String codexCommand) {
            this.codexCommand = codexCommand;
        }

        public String getTmuxCommand() {
            return tmuxCommand;
        }

        public void setTmuxCommand(String tmuxCommand) {
            this.tmuxCommand = tmuxCommand;
        }

        public String getTtydCommand() {
            return ttydCommand;
        }

        public void setTtydCommand(String ttydCommand) {
            this.ttydCommand = ttydCommand;
        }

        public String getPromptLanguage() {
            return promptLanguage;
        }

        public void setPromptLanguage(String promptLanguage) {
            this.promptLanguage = promptLanguage;
        }

        public Path getSessionsDir() {
            return sessionsDir;
        }

        public void setSessionsDir(Path sessionsDir) {
            this.sessionsDir = sessionsDir;
        }

        public Path getRegistryDir() {
            return registryDir;
        }

        public void setRegistryDir(Path registryDir) {
            this.registryDir = registryDir;
        }

        public List<Path> getAllowedWorkspaceRoots() {
            return allowedWorkspaceRoots;
        }

        public void setAllowedWorkspaceRoots(List<Path> allowedWorkspaceRoots) {
            this.allowedWorkspaceRoots = allowedWorkspaceRoots == null ? new ArrayList<>() : new ArrayList<>(allowedWorkspaceRoots);
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }
    }
}
