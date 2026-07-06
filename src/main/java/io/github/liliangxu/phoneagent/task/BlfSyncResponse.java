package io.github.liliangxu.phoneagent.task;

import java.util.List;

public record BlfSyncResponse(
        boolean success,
        String reason,
        List<BlfSyncSlotResult> slots,
        List<Integer> failedSlots,
        String error
) {
    static BlfSyncResponse inProgress(String reason) {
        return new BlfSyncResponse(false, reason, List.of(), List.of(), "BLF_SYNC_IN_PROGRESS");
    }
}
