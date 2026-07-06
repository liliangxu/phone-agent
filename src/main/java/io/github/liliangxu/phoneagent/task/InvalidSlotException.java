package io.github.liliangxu.phoneagent.task;

public class InvalidSlotException extends RuntimeException {
    public InvalidSlotException(int slot) {
        super("Invalid slot: " + slot);
    }
}
