package io.github.liliangxu.phoneagent.audio;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(List<String> command, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return new CommandResult(124, "", "Command timed out");
            }
            return new CommandResult(process.exitValue(), stdout.get(), stderr.get());
        } catch (IOException e) {
            return new CommandResult(127, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, "", "Command interrupted");
        } catch (ExecutionException e) {
            return new CommandResult(127, "", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
