package io.github.liliangxu.phoneagent.task;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
    }
}
