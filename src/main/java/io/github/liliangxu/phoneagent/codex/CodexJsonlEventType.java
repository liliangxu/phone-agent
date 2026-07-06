package io.github.liliangxu.phoneagent.codex;

/**
 * Last Codex event observed while incrementally scanning a session JSONL file.
 * The session service uses this signal to distinguish a quiet RUNNING session
 * from a newly completed turn that should trigger phone notification.
 */
public enum CodexJsonlEventType {
    NONE,
    AGENT_MESSAGE,
    TASK_STARTED,
    TASK_COMPLETE,
    USER_MESSAGE
}
