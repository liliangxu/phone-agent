package io.github.liliangxu.phoneagent.inbound;

import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.github.liliangxu.phoneagent.codex.CodexSessionException;
import io.github.liliangxu.phoneagent.codex.CodexSessionService;
import io.github.liliangxu.phoneagent.codex.CodexSessionView;
import io.github.liliangxu.phoneagent.codex.CodexPromptFormatter;
import io.github.liliangxu.phoneagent.codex.CreateCodexSessionRequest;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;
import io.github.liliangxu.phoneagent.task.RecordingCallbackResult;
import io.github.liliangxu.phoneagent.task.RecordingStore;
import io.github.liliangxu.phoneagent.task.WhisperTranscriber;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@DependsOn("phoneAgentDatabaseInitializer")
public class InboundIntentService {
    private static final int MAX_TEXT_CODE_POINTS = 8000;
    private final JdbcInboundIntentRepository repository;
    private final RecordingStore recordingStore;
    private final WhisperTranscriber transcriber;
    private final CodexSessionService codexSessionService;
    private final CodexPromptFormatter promptFormatter;
    private final PhoneAgentProperties properties;
    private final Clock clock;
    private final Executor executor;
    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired
    public InboundIntentService(JdbcInboundIntentRepository repository, RecordingStore recordingStore,
                                WhisperTranscriber transcriber, CodexSessionService codexSessionService,
                                CodexPromptFormatter promptFormatter, PhoneAgentProperties properties, Clock clock) {
        this(repository, recordingStore, transcriber, codexSessionService, promptFormatter, properties, clock, Executors.newSingleThreadExecutor());
    }

    InboundIntentService(JdbcInboundIntentRepository repository, RecordingStore recordingStore,
                         WhisperTranscriber transcriber, CodexSessionService codexSessionService,
                         CodexPromptFormatter promptFormatter, PhoneAgentProperties properties, Clock clock, Executor executor) {
        this.repository = repository;
        this.recordingStore = recordingStore;
        this.transcriber = transcriber;
        this.codexSessionService = codexSessionService;
        this.promptFormatter = promptFormatter;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
    }

    public List<InboundIntentView> list() {
        return repository.list().stream().map(InboundIntentView::from).toList();
    }

    public Optional<InboundIntentView> get(String intentId) {
        return repository.get(intentId).map(InboundIntentView::from);
    }

    /**
     * Starts an audio-backed inbound intent before Asterisk begins recording.
     * The returned id is used as the recording file stem and callback key.
     */
    public InboundIntentView startAudioIntent(InboundIntentSource source) {
        OffsetDateTime now = now();
        InboundIntentRecord record = new InboundIntentRecord(nextId(now), source, InboundIntentInputType.AUDIO,
                InboundIntentStatus.PROCESSING_INPUT, InboundIntentInputStatus.RECORDING, now);
        repository.save(record);
        return InboundIntentView.from(record);
    }

    /**
     * Submits an already textual inbound intent. This is the common abstraction
     * future adapters can use without going through phone recording or ASR.
     */
    public InboundIntentView submitText(InboundIntentSource source, String text) {
        String cleaned = normalizeText(text);
        OffsetDateTime now = now();
        InboundIntentRecord record = new InboundIntentRecord(nextId(now), source, InboundIntentInputType.TEXT,
                InboundIntentStatus.RECEIVED, InboundIntentInputStatus.NONE, now);
        repository.save(record);
        if (cleaned.isEmpty()) {
            record.status(InboundIntentStatus.NO_CONTENT, now());
            repository.save(record);
            return InboundIntentView.from(record);
        }
        return createCodexForTranscript(record.intentId(), cleaned);
    }

    /**
     * Processes Asterisk's hangup callback for extension 0. Callback handling is
     * idempotent so duplicate h-extension callbacks cannot create duplicate
     * Codex sessions.
     */
    public RecordingCallbackResult completeAudioRecording(String intentId) {
        require(intentId);
        boolean claimed = repository.claimRecordingCallback(intentId, recordingStore.displayRecordingPath(intentId), now());
        if (!claimed) {
            return RecordingCallbackResult.DUPLICATE;
        }
        InboundIntentRecord record = require(intentId);
        if (!recordingStore.hasNonEmptyRecording(intentId)) {
            record.fail(InboundIntentFailureStage.RECORDING, "recording file is missing or empty", now());
            repository.save(record);
            return RecordingCallbackResult.PROCESSED;
        }
        record.inputStatus(InboundIntentInputStatus.RECORDED, now());
        repository.save(record);
        executor.execute(() -> transcribeAndCreateCodex(intentId));
        return RecordingCallbackResult.PROCESSED;
    }

    void transcribeAndCreateCodex(String intentId) {
        try {
            InboundIntentRecord record = require(intentId);
            record.inputStatus(InboundIntentInputStatus.TRANSCRIBING, now());
            repository.save(record);
            String transcript = normalizeText(transcriber.transcribe(intentId));
            completeTranscription(intentId, transcript);
        } catch (RuntimeException e) {
            fail(intentId, InboundIntentFailureStage.ASR, "ASR failed: " + rootMessage(e));
        }
    }

    void completeTranscription(String intentId, String transcript) {
        String cleaned = normalizeText(transcript);
        InboundIntentRecord record = require(intentId);
        record.inputStatus(InboundIntentInputStatus.TRANSCRIBED, now());
        if (cleaned.isEmpty()) {
            record.transcript(null, now());
            record.status(InboundIntentStatus.NO_CONTENT, now());
            repository.save(record);
            return;
        }
        record.transcript(cleaned, now());
        repository.save(record);
        createCodexForTranscript(intentId, cleaned);
    }

    private InboundIntentView createCodexForTranscript(String intentId, String transcript) {
        InboundIntentRecord record = require(intentId);
        if (record.codexSessionId() != null && !record.codexSessionId().isBlank()) {
            return InboundIntentView.from(record);
        }
        record.transcript(transcript, now());
        record.status(InboundIntentStatus.CREATING_CODEX, now());
        repository.save(record);
        try {
            CodexSessionView session = codexSessionService.create(new CreateCodexSessionRequest(
                    "Phone " + DateTimeFormatter.ofPattern("HH:mm:ss").format(now()),
                    null,
                    initialPrompt(record, transcript)
            ));
            record.codexSessionId(session.id(), now());
            record.status(InboundIntentStatus.CODEX_STARTED, now());
            repository.save(record);
            return InboundIntentView.from(record);
        } catch (RuntimeException e) {
            record.fail(InboundIntentFailureStage.CODEX_CREATE, rootMessage(e), now());
            repository.save(record);
            return InboundIntentView.from(record);
        }
    }

    private InboundIntentRecord require(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            throw new CodexSessionException("INBOUND_INTENT_VALIDATION_FAILED", "intentId must not be blank", HttpStatus.BAD_REQUEST);
        }
        return repository.get(intentId).orElseThrow(() -> new InboundIntentNotFoundException(intentId));
    }

    private void fail(String intentId, InboundIntentFailureStage stage, String message) {
        try {
            InboundIntentRecord record = require(intentId);
            record.fail(stage, message, now());
            repository.save(record);
        } catch (RuntimeException ignored) {
            // The async worker has no caller to report to; missing records are visible via logs/tests.
        }
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.strip();
    }

    /**
     * Formats all new inbound requests with source/type context while keeping
     * phone-reply continuation wording out of initial request prompts.
     */
    private String initialPrompt(InboundIntentRecord record, String transcript) {
        if (record.source() == InboundIntentSource.PHONE_EXTENSION_0
                && record.inputType() == InboundIntentInputType.AUDIO) {
            return promptFormatter.formatInboundInitial(transcript, properties.getCodex().getPromptLanguage());
        }
        return promptFormatter.formatInboundInitial(inboundContext(record, transcript), properties.getCodex().getPromptLanguage());
    }

    private String inboundContext(InboundIntentRecord record, String transcript) {
        String language = properties.getCodex().getPromptLanguage();
        if (language != null && language.toLowerCase().startsWith("zh")) {
            return "来源：" + record.source().name() + "，输入类型：" + record.inputType().name() + "。\n\n" + transcript;
        }
        return "Source: " + record.source().name() + ", input type: " + record.inputType().name() + ".\n\n" + transcript;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private String nextId(OffsetDateTime now) {
        return "ii-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(now) + "-" + "%04d".formatted(sequence.incrementAndGet());
    }
}
