package io.github.liliangxu.phoneagent.task;

public class TaskCreationException extends RuntimeException {
    private final TaskView task;

    TaskCreationException(String message, TaskView task, Throwable cause) {
        super(message, cause);
        this.task = task;
    }

    public TaskView task() {
        return task;
    }
}
