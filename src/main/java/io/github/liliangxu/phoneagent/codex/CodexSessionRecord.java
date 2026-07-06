package io.github.liliangxu.phoneagent.codex;

import java.time.OffsetDateTime;

/**
 * Mutable persisted state for one Console-owned Codex terminal session.
 * All writes should go through {@link CodexSessionStore} so file persistence
 * and concurrent poll updates stay serialized.
 */
public class CodexSessionRecord {
    private String id;
    private String title;
    private String cwd;
    private CodexSessionStatus status;
    private String tmuxName;
    private Integer ttydPort;
    private String ttydUrl;
    private Long ttydPid;
    private String threadId;
    private String jsonlPath;
    private String lastAssistantMessage;
    private boolean waitingMarker;
    private String lastRelevantEventTimestamp;
    private long lastProcessedJsonlSize;
    private String errorMessage;
    private String phoneBridgeErrorCode;
    private String phoneBridgeErrorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private long startedAtEpochSecond;
    private boolean initialPromptSubmitted;

    public CodexSessionRecord copy() {
        CodexSessionRecord copy = new CodexSessionRecord();
        copy.id = id;
        copy.title = title;
        copy.cwd = cwd;
        copy.status = status;
        copy.tmuxName = tmuxName;
        copy.ttydPort = ttydPort;
        copy.ttydUrl = ttydUrl;
        copy.ttydPid = ttydPid;
        copy.threadId = threadId;
        copy.jsonlPath = jsonlPath;
        copy.lastAssistantMessage = lastAssistantMessage;
        copy.waitingMarker = waitingMarker;
        copy.lastRelevantEventTimestamp = lastRelevantEventTimestamp;
        copy.lastProcessedJsonlSize = lastProcessedJsonlSize;
        copy.errorMessage = errorMessage;
        copy.phoneBridgeErrorCode = phoneBridgeErrorCode;
        copy.phoneBridgeErrorMessage = phoneBridgeErrorMessage;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.startedAtEpochSecond = startedAtEpochSecond;
        copy.initialPromptSubmitted = initialPromptSubmitted;
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public CodexSessionStatus getStatus() {
        return status;
    }

    public void setStatus(CodexSessionStatus status) {
        this.status = status;
    }

    public String getTmuxName() {
        return tmuxName;
    }

    public void setTmuxName(String tmuxName) {
        this.tmuxName = tmuxName;
    }

    public Integer getTtydPort() {
        return ttydPort;
    }

    public void setTtydPort(Integer ttydPort) {
        this.ttydPort = ttydPort;
    }

    public String getTtydUrl() {
        return ttydUrl;
    }

    public void setTtydUrl(String ttydUrl) {
        this.ttydUrl = ttydUrl;
    }

    public Long getTtydPid() {
        return ttydPid;
    }

    public void setTtydPid(Long ttydPid) {
        this.ttydPid = ttydPid;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getJsonlPath() {
        return jsonlPath;
    }

    public void setJsonlPath(String jsonlPath) {
        this.jsonlPath = jsonlPath;
    }

    public String getLastAssistantMessage() {
        return lastAssistantMessage;
    }

    public void setLastAssistantMessage(String lastAssistantMessage) {
        this.lastAssistantMessage = lastAssistantMessage;
    }

    public boolean isWaitingMarker() {
        return waitingMarker;
    }

    public void setWaitingMarker(boolean waitingMarker) {
        this.waitingMarker = waitingMarker;
    }

    public String getLastRelevantEventTimestamp() {
        return lastRelevantEventTimestamp;
    }

    public void setLastRelevantEventTimestamp(String lastRelevantEventTimestamp) {
        this.lastRelevantEventTimestamp = lastRelevantEventTimestamp;
    }

    public long getLastProcessedJsonlSize() {
        return lastProcessedJsonlSize;
    }

    public void setLastProcessedJsonlSize(long lastProcessedJsonlSize) {
        this.lastProcessedJsonlSize = lastProcessedJsonlSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getPhoneBridgeErrorCode() {
        return phoneBridgeErrorCode;
    }

    public void setPhoneBridgeErrorCode(String phoneBridgeErrorCode) {
        this.phoneBridgeErrorCode = phoneBridgeErrorCode;
    }

    public String getPhoneBridgeErrorMessage() {
        return phoneBridgeErrorMessage;
    }

    public void setPhoneBridgeErrorMessage(String phoneBridgeErrorMessage) {
        this.phoneBridgeErrorMessage = phoneBridgeErrorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getStartedAtEpochSecond() {
        return startedAtEpochSecond;
    }

    public void setStartedAtEpochSecond(long startedAtEpochSecond) {
        this.startedAtEpochSecond = startedAtEpochSecond;
    }

    public boolean isInitialPromptSubmitted() {
        return initialPromptSubmitted;
    }

    public void setInitialPromptSubmitted(boolean initialPromptSubmitted) {
        this.initialPromptSubmitted = initialPromptSubmitted;
    }
}
