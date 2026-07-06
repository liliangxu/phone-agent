package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexConsoleStaticUiContractTest {
    private static final Path CONSOLE_DIR = Path.of("src/main/resources/static/console");

    @Test
    void consoleAssetsExposeProductizedI18nCockpitAndBridgeContracts() throws Exception {
        String html = Files.readString(CONSOLE_DIR.resolve("index.html"));
        String script = Files.readString(CONSOLE_DIR.resolve("console.js"));
        String styles = Files.readString(CONSOLE_DIR.resolve("console.css"));

        assertTrue(html.contains("id=\"sessionsTitle\"") && html.contains("id=\"cockpitTitle\"")
                        && html.contains("id=\"cockpitSubtitle\""),
                "Static headings must be addressable so the JS i18n renderer can update them");
        assertTrue(html.contains("class=\"locale-button\"") && html.contains("data-locale=\"zh-CN\"")
                        && html.contains("data-locale=\"en\""),
                "Console must expose an in-page zh-CN/en language switch");
        assertTrue(script.contains("const MESSAGES = {") && script.contains("'zh-CN': {") && script.contains("en: {"),
                "Console JS must contain at least Chinese and English message bundles");
        assertTrue(script.contains("function resolveLocale()") && script.contains("phoneAgent.locale")
                        && script.contains("navigator.languages") && script.contains("setLocale("),
                "Console locale must be browser/localStorage driven and manually switchable");
        assertTrue(script.contains("document.documentElement.lang = state.locale")
                        && script.contains("document.title = t('title')"),
                "Language switching must update browser-visible metadata");

        assertTrue(script.contains("status: {") && script.contains("WAITING_USER: '等待用户'")
                        && script.contains("WAITING_USER: 'Waiting user'")
                        && script.contains("function statusLabel(status)"),
                "Backend session statuses must map to localized user-facing labels");
        assertTrue(script.contains("bridge: {") && script.contains("NONE: '暂无电话提醒'")
                        && script.contains("NONE: 'No phone reminder'")
                        && script.contains("function bridgeText(session)"),
                "Bridge phases must map to localized inline text that does not depend on hover");
        assertTrue(script.contains("title=\"${escapeHtml(session.status || 'IDLE')}\"")
                        && script.contains("${escapeHtml(statusLabel(session.status))}"),
                "Raw backend enum may remain diagnostic title data but must not be the visible status text");

        assertTrue(script.contains("sessionBridgeActions(session)") && script.contains("bridgeActionButton(")
                        && script.contains("if (bridge && bridge.cancellable)")
                        && script.contains("if (bridge && (bridge.renotifyAllowed || RENOTIFY_STATUSES.has(bridge.status)))"),
                "Bridge actions must be rendered only when the current bridge state supports the action");
        assertTrue(!script.contains("bridgeActionButton('取消提醒', bridge, 'cancel', !(bridge && bridge.cancellable))")
                        && !script.contains("bridgeActionButton('再次提醒', bridge, 'renotify'"),
                "Console must not render fixed disabled cancel/renotify buttons on every session card");
        assertTrue(script.contains("'FAILED_RECORDING'") && script.contains("'FAILED_ASR'")
                        && script.contains("'FAILED_REPLY_TO_CODEX'"),
                "Failed phone/ASR/Codex reply bridge states must keep Renotify available");
        assertTrue(script.contains("bridge.cancelFailed") && script.contains("bridge.renotifyFailed")
                        && !script.contains("FAILED_TASK_CREATE bridge failed"),
                "Bridge action failures must be localized and avoid backend detail leakage");

        assertTrue(html.contains("id=\"paneGrid\""),
                "Console must expose a cockpit pane container for native terminals");
        for (int paneCount : new int[]{1, 2, 3, 4, 6}) {
            assertTrue(html.contains("data-pane-count=\"" + paneCount + "\""),
                    "Console must expose a stable " + paneCount + "-pane layout button");
        }
        assertTrue(script.contains("const MAX_PANES = 6")
                        && script.contains("const SUPPORTED_PANE_COUNTS = [1, 2, 3, 4, 6]"),
                "Only explicit 1/2/3/4/6 cockpit layouts should remain supported");
        assertTrue(!script.contains("add-pane-dropzone") && !script.contains("buildAddPaneDropZone")
                        && !script.contains("appendPane(sessionId)"),
                "Console must not reintroduce a + pane add/drop zone");
        assertTrue(script.contains("state.panes[index] = sessionId") && script.contains("state.panes[from] = null")
                        && script.contains("draggable = true"),
                "Dragging a sidebar session must replace the target pane and clear the previous source pane");
        assertTrue(script.contains("existing.dataset.sessionId === session.id")
                        && script.contains("/terminal/ensure")
                        && script.contains("ensurePaneTerminal(index, session.id)"),
                "Polling refresh must preserve ttyd iframes and ensure terminal liveness before mounting");

        assertTrue(html.contains("id=\"ringPhoneButton\"") && html.contains("id=\"ringPhoneStatus\"")
                        && script.contains("fetch('/api/ring-phone'")
                        && script.contains("ringPhoneFailure") && script.contains("ringPhoneSuccess"),
                "Global Ring Phone must remain one global control with localized success/failure text");
        assertTrue(!script.contains("data-ring") && !script.contains("sessionRing"),
                "Ring Phone must not be rendered per session/task");

        assertTrue(styles.contains(".locale-control") && styles.contains(".bridge-inline-status")
                        && styles.contains("flex-wrap: wrap") && styles.contains("@media (max-width: 760px)")
                        && styles.contains(".ring-phone-panel") && styles.contains("grid-template-columns: 1fr"),
                "Styles must include locale controls, inline bridge status, wrapping actions, and mobile ring-phone reflow");
        assertTrue(styles.contains(".sidebar-resizer") && styles.contains("display: none;")
                        && styles.contains(".terminal-pane header") && styles.contains("flex-direction: column"),
                "Mobile CSS must remove the desktop resizer and allow pane headers to wrap");
        assertTrue(styles.contains(".bridge-dot-NONE") && styles.contains(".bridge-dot-IN_PROGRESS")
                        && styles.contains(".bridge-dot-DONE") && styles.contains(".bridge-dot-FAILED")
                        && styles.contains(".bridge-dot-CANCELLED"),
                "Bridge phases must keep stable dot styles");
    }
}
