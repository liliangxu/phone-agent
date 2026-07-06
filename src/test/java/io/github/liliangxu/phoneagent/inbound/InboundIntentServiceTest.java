package io.github.liliangxu.phoneagent.inbound;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.github.liliangxu.phoneagent.codex.CodexSessionService;
import io.github.liliangxu.phoneagent.codex.CodexSessionStatus;
import io.github.liliangxu.phoneagent.codex.CodexSessionView;
import io.github.liliangxu.phoneagent.codex.CodexPromptFormatter;
import io.github.liliangxu.phoneagent.codex.CreateCodexSessionRequest;
import io.github.liliangxu.phoneagent.config.PhoneAgentProperties;
import io.github.liliangxu.phoneagent.task.RecordingCallbackResult;
import io.github.liliangxu.phoneagent.task.RecordingStore;
import io.github.liliangxu.phoneagent.task.WhisperTranscriber;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundIntentServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-11T02:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    JdbcInboundIntentRepository repository;
    RecordingStore recordingStore;
    WhisperTranscriber transcriber;
    CodexSessionService codexSessionService;
    InboundIntentService service;
    PhoneAgentProperties properties;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inbound_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new JdbcInboundIntentRepository(new JdbcTemplate(dataSource));
        recordingStore = new RecordingStore(tempDir.resolve("recordings"));
        transcriber = mock(WhisperTranscriber.class);
        codexSessionService = mock(CodexSessionService.class);
        properties = new PhoneAgentProperties();
        service = new InboundIntentService(repository, recordingStore, transcriber, codexSessionService,
                new CodexPromptFormatter(), properties, CLOCK, Runnable::run);
    }

    @Test
    void textIntentKeepsInboundContextWithoutPhonePrompt() {
        when(codexSessionService.create(any(CreateCodexSessionRequest.class))).thenReturn(session("cs-1"));

        InboundIntentView view = service.submitText(InboundIntentSource.TEXT_API, " 帮我检查测试失败 ");

        assertEquals(InboundIntentStatus.CODEX_STARTED, view.status());
        assertEquals(InboundIntentInputType.TEXT, view.inputType());
        assertEquals(InboundIntentInputStatus.NONE, view.inputStatus());
        assertEquals("帮我检查测试失败", view.transcript());
        assertEquals("cs-1", view.codexSessionId());
        org.mockito.ArgumentCaptor<CreateCodexSessionRequest> captor = org.mockito.ArgumentCaptor.forClass(CreateCodexSessionRequest.class);
        verify(codexSessionService).create(captor.capture());
        assertNull(captor.getValue().cwd());
        assertEquals(textApiPrompt("帮我检查测试失败"), captor.getValue().initialPrompt());
        assertFalse(captor.getValue().initialPrompt().contains("上一轮的问题或请求"));
    }

    @Test
    void blankTextIntentDoesNotCreateCodex() {
        InboundIntentView view = service.submitText(InboundIntentSource.TEXT_API, "  ");

        assertEquals(InboundIntentStatus.NO_CONTENT, view.status());
        assertNull(view.transcript());
        verify(codexSessionService, never()).create(any());
    }

    @Test
    void phoneRecordingTranscribesAndCreatesCodex() throws Exception {
        when(codexSessionService.create(any(CreateCodexSessionRequest.class))).thenReturn(session("cs-phone"));
        InboundIntentView started = service.startAudioIntent(InboundIntentSource.PHONE_EXTENSION_0);
        Files.createDirectories(tempDir.resolve("recordings"));
        Files.writeString(tempDir.resolve("recordings").resolve(started.intentId() + ".wav"), "audio");
        when(transcriber.transcribe(started.intentId())).thenReturn("请修复页面拖拽");

        RecordingCallbackResult result = service.completeAudioRecording(started.intentId());

        assertEquals(RecordingCallbackResult.PROCESSED, result);
        InboundIntentView completed = service.get(started.intentId()).orElseThrow();
        assertEquals(InboundIntentStatus.CODEX_STARTED, completed.status());
        assertEquals(InboundIntentInputStatus.TRANSCRIBED, completed.inputStatus());
        assertEquals("请修复页面拖拽", completed.transcript());
        assertEquals("cs-phone", completed.codexSessionId());
        org.mockito.ArgumentCaptor<CreateCodexSessionRequest> captor = org.mockito.ArgumentCaptor.forClass(CreateCodexSessionRequest.class);
        verify(codexSessionService).create(captor.capture());
        assertEquals(phonePrompt("请修复页面拖拽"), captor.getValue().initialPrompt());
    }

    @Test
    void englishPromptLanguageFormatsTextInboundPrompt() {
        properties.getCodex().setPromptLanguage("en");
        when(codexSessionService.create(any(CreateCodexSessionRequest.class))).thenReturn(session("cs-en"));

        service.submitText(InboundIntentSource.TEXT_API, "check mobile layout");

        org.mockito.ArgumentCaptor<CreateCodexSessionRequest> captor = org.mockito.ArgumentCaptor.forClass(CreateCodexSessionRequest.class);
        verify(codexSessionService).create(captor.capture());
        assertTrue(captor.getValue().initialPrompt().contains("The user started a new request through Phone Agent."));
        assertTrue(captor.getValue().initialPrompt().contains("Source: TEXT_API"));
        assertTrue(captor.getValue().initialPrompt().contains("check mobile layout"));
        assertFalse(captor.getValue().initialPrompt().contains("previous question"));
    }

    @Test
    void phoneRecordingWithEmptyAsrBecomesNoContent() throws Exception {
        InboundIntentView started = service.startAudioIntent(InboundIntentSource.PHONE_EXTENSION_0);
        Files.createDirectories(tempDir.resolve("recordings"));
        Files.writeString(tempDir.resolve("recordings").resolve(started.intentId() + ".wav"), "audio");
        when(transcriber.transcribe(started.intentId())).thenReturn("");

        service.completeAudioRecording(started.intentId());

        InboundIntentView completed = service.get(started.intentId()).orElseThrow();
        assertEquals(InboundIntentStatus.NO_CONTENT, completed.status());
        assertEquals(InboundIntentInputStatus.TRANSCRIBED, completed.inputStatus());
        verify(codexSessionService, never()).create(any());
    }

    @Test
    void duplicatePhoneRecordingCallbackDoesNotCreateTwice() throws Exception {
        when(codexSessionService.create(any(CreateCodexSessionRequest.class))).thenReturn(session("cs-once"));
        InboundIntentView started = service.startAudioIntent(InboundIntentSource.PHONE_EXTENSION_0);
        Files.createDirectories(tempDir.resolve("recordings"));
        Files.writeString(tempDir.resolve("recordings").resolve(started.intentId() + ".wav"), "audio");
        when(transcriber.transcribe(started.intentId())).thenReturn("执行一次");

        assertEquals(RecordingCallbackResult.PROCESSED, service.completeAudioRecording(started.intentId()));
        assertEquals(RecordingCallbackResult.DUPLICATE, service.completeAudioRecording(started.intentId()));

        verify(codexSessionService).create(any(CreateCodexSessionRequest.class));
    }

    @Test
    void concurrentDuplicatePhoneRecordingCallbackIsClaimedOnce() throws Exception {
        when(codexSessionService.create(any(CreateCodexSessionRequest.class))).thenReturn(session("cs-once"));
        InboundIntentView started = service.startAudioIntent(InboundIntentSource.PHONE_EXTENSION_0);
        Files.createDirectories(tempDir.resolve("recordings"));
        Files.writeString(tempDir.resolve("recordings").resolve(started.intentId() + ".wav"), "audio");
        CountDownLatch transcribeStarted = new CountDownLatch(1);
        CountDownLatch releaseTranscriber = new CountDownLatch(1);
        when(transcriber.transcribe(started.intentId())).thenAnswer(invocation -> {
            transcribeStarted.countDown();
            assertTrue(releaseTranscriber.await(5, TimeUnit.SECONDS));
            return "只创建一次";
        });
        ExecutorService callbackExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<RecordingCallbackResult> first = callbackExecutor.submit(() -> service.completeAudioRecording(started.intentId()));
            assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS));
            Future<RecordingCallbackResult> second = callbackExecutor.submit(() -> service.completeAudioRecording(started.intentId()));

            assertEquals(RecordingCallbackResult.DUPLICATE, second.get(5, TimeUnit.SECONDS));
            releaseTranscriber.countDown();
            assertEquals(RecordingCallbackResult.PROCESSED, first.get(5, TimeUnit.SECONDS));
        } finally {
            releaseTranscriber.countDown();
            callbackExecutor.shutdownNow();
            if (!callbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fail("callback executor did not stop");
            }
        }

        InboundIntentView completed = service.get(started.intentId()).orElseThrow();
        assertEquals(InboundIntentStatus.CODEX_STARTED, completed.status());
        verify(codexSessionService).create(any(CreateCodexSessionRequest.class));
    }

    private static CodexSessionView session(String id) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new CodexSessionView(id, id, "/workspace", CodexSessionStatus.RUNNING,
                "phone-agent-codex-" + id, "http://127.0.0.1:49152/", null, null,
                null, null, null, null, null, List.of(), null, null, now, now);
    }

    private static String phonePrompt(String text) {
        return new CodexPromptFormatter().formatInboundInitial(text, "zh-CN");
    }

    private static String textApiPrompt(String text) {
        return new CodexPromptFormatter().formatInboundInitial("来源：TEXT_API，输入类型：TEXT。\n\n" + text, "zh-CN");
    }
}
