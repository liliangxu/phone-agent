package io.github.liliangxu.phoneagent.task;

/**
 * Mutable slot state for a fixed BLF channel.
 * RESERVED is intentionally application-only: it prevents another task from
 * taking the slot while audio and BLF side effects are prepared.
 */
final class SlotRecord {
    private final int slot;
    private final String extension;
    private SlotStatus status = SlotStatus.IDLE;
    private String taskId;
    private String startedTaskId;

    SlotRecord(int slot, String extension) {
        this.slot = slot;
        this.extension = extension;
    }

    static SlotRecord restore(int slotNumber, SlotStatus status, String taskId, String startedTaskId) {
        SlotRecord slot = new SlotRecord(slotNumber, "");
        slot.status = status;
        slot.taskId = taskId;
        slot.startedTaskId = startedTaskId;
        return slot;
    }

    SlotRecord withExtension(String configuredExtension) {
        SlotRecord slot = new SlotRecord(this.slot, configuredExtension);
        slot.status = this.status;
        slot.taskId = this.taskId;
        slot.startedTaskId = this.startedTaskId;
        return slot;
    }

    int slot() {
        return slot;
    }

    String extension() {
        return extension;
    }

    SlotStatus status() {
        return status;
    }

    String taskId() {
        return taskId;
    }

    String startedTaskId() {
        return startedTaskId;
    }

    boolean idle() {
        return status == SlotStatus.IDLE;
    }

    void reserve() {
        this.status = SlotStatus.RESERVED;
        this.taskId = null;
        this.startedTaskId = null;
    }

    void reserveForTask(String taskId) {
        this.status = SlotStatus.RESERVED;
        this.taskId = taskId;
        this.startedTaskId = null;
    }

    void notified(String taskId) {
        this.status = SlotStatus.NOTIFIED;
        this.taskId = taskId;
        this.startedTaskId = null;
    }

    void pickedUp(String taskId) {
        this.status = SlotStatus.PICKED_UP;
        this.taskId = taskId;
        this.startedTaskId = taskId;
    }

    void recording(String taskId) {
        this.status = SlotStatus.RECORDING;
        this.startedTaskId = taskId;
    }

    void release() {
        this.status = SlotStatus.IDLE;
        this.taskId = null;
        this.startedTaskId = null;
    }
}
