package io.github.liliangxu.phoneagent.task;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AsyncAsrJobQueue implements AsrJobQueue {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final WhisperTranscriber transcriber;
    private final TaskService taskService;

    public AsyncAsrJobQueue(WhisperTranscriber transcriber, @Lazy TaskService taskService) {
        this.transcriber = transcriber;
        this.taskService = taskService;
    }

    @Override
    public void submit(String taskId) {
        executor.submit(() -> {
            try {
                taskService.markTranscribing(taskId);
                taskService.completeAsrSuccess(taskId, transcriber.transcribe(taskId));
            } catch (RuntimeException e) {
                taskService.completeAsrFailure(taskId, e.getMessage());
            }
        });
    }
}
