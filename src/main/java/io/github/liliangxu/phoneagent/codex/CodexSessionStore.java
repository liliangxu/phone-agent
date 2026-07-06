package io.github.liliangxu.phoneagent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * MySQL-backed registry for Console-owned Codex sessions. On startup it imports
 * old JSON registry files idempotently, then treats MySQL as the only source of
 * truth for session polling and Console APIs.
 */
@Component
@DependsOn("phoneAgentDatabaseInitializer")
public class CodexSessionStore {
    private final Path legacyRegistryDir;
    private final ObjectMapper objectMapper;
    private final JdbcCodexSessionRepository repository;
    private final Map<String, CodexSessionRecord> inMemorySessions;

    @Autowired
    public CodexSessionStore(PhoneAgentProperties properties, ObjectMapper objectMapper, JdbcCodexSessionRepository repository) {
        this(properties.getCodex().getRegistryDir(), objectMapper, repository);
    }

    CodexSessionStore(Path legacyRegistryDir, ObjectMapper objectMapper, JdbcCodexSessionRepository repository) {
        this.legacyRegistryDir = legacyRegistryDir;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.repository = repository;
        this.inMemorySessions = null;
        importLegacyJson();
    }

    CodexSessionStore(Path legacyRegistryDir, ObjectMapper objectMapper) {
        this.legacyRegistryDir = legacyRegistryDir;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.repository = null;
        this.inMemorySessions = new LinkedHashMap<>();
        importLegacyJson();
    }

    public List<CodexSessionRecord> list() {
        if (repository == null) {
            return inMemorySessions.values().stream()
                    .map(CodexSessionRecord::copy)
                    .sorted(Comparator.comparing(CodexSessionRecord::getCreatedAt))
                    .toList();
        }
        return repository.list().stream()
                .map(CodexSessionRecord::copy)
                .sorted(Comparator.comparing(CodexSessionRecord::getCreatedAt))
                .toList();
    }

    public Optional<CodexSessionRecord> get(String id) {
        if (repository == null) {
            CodexSessionRecord record = inMemorySessions.get(id);
            return record == null ? Optional.empty() : Optional.of(record.copy());
        }
        return repository.get(id).map(CodexSessionRecord::copy);
    }

    public CodexSessionRecord put(CodexSessionRecord record) {
        CodexSessionRecord copy = record.copy();
        if (repository == null) {
            inMemorySessions.put(copy.getId(), copy);
            return copy.copy();
        }
        repository.save(copy);
        return copy.copy();
    }

    public Optional<CodexSessionRecord> update(String id, UnaryOperator<CodexSessionRecord> updater) {
        if (repository == null) {
            CodexSessionRecord current = inMemorySessions.get(id);
            if (current == null) {
                return Optional.empty();
            }
            CodexSessionRecord updated = updater.apply(current.copy());
            inMemorySessions.put(id, updated.copy());
            return Optional.of(updated.copy());
        }
        Optional<CodexSessionRecord> current = repository.get(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        CodexSessionRecord updated = updater.apply(current.get().copy());
        repository.save(updated);
        return Optional.of(updated.copy());
    }

    public CodexSessionRecord update(String id, java.util.Map<String, Object> changes) {
        return update(id, current -> {
            Object threadId = changes.get("threadId");
            if (threadId instanceof String value) {
                current.setThreadId(value);
            }
            Object message = changes.get("lastAssistantMessage");
            if (message instanceof String value) {
                current.setLastAssistantMessage(value);
            }
            return current;
        }).orElseThrow(() -> new CodexSessionNotFoundException(id));
    }

    public CodexSessionRecord merge(String id, java.util.Map<String, Object> changes) {
        return update(id, changes);
    }

    private void importLegacyJson() {
        if (!Files.isDirectory(legacyRegistryDir)) {
            return;
        }
        try (var stream = Files.list(legacyRegistryDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(this::importOne);
        } catch (IOException ignored) {
            // A broken legacy registry should not prevent MySQL-backed startup.
        }
    }

    private void importOne(Path path) {
        try {
            CodexSessionRecord record = objectMapper.readValue(path.toFile(), CodexSessionRecord.class);
            if (record.getId() != null && !record.getId().isBlank()) {
                if (repository == null) {
                    inMemorySessions.putIfAbsent(record.getId(), record);
                } else if (!repository.exists(record.getId())) {
                    repository.save(record);
                }
            }
        } catch (IOException ignored) {
            // Corrupt legacy files are intentionally skipped; the file remains for manual inspection.
        }
    }
}
