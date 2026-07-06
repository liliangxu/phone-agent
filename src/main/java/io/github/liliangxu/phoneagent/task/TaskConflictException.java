package io.github.liliangxu.phoneagent.task;

public class TaskConflictException extends RuntimeException {
    TaskConflictException(int slot, String taskId) {
        super("Task " + taskId + " does not match slot " + slot);
    }
}
