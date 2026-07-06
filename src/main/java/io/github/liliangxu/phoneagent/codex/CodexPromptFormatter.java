package io.github.liliangxu.phoneagent.codex;

import org.springframework.stereotype.Component;

/**
 * Formats text that Phone Agent sends to Codex on behalf of a user. Direct TUI
 * input bypasses this class; only phone/inbound mediated prompts receive
 * source context and the phone-readable response rule.
 */
@Component
public class CodexPromptFormatter {
    static final String ZH_RESPONSE_FORMAT_INSTRUCTION =
            """
            后续回复用户时，请先用一句话概括当前问题/事项，再给出正文。
            回复需要适合电话语音播报：避免依赖屏幕上下文；不要把 URL、文件路径、命令、代码块或表格作为主要表达方式；如必须提到链接、命令或路径，请用简短自然语言说明用途。\
            """;
    static final String EN_RESPONSE_FORMAT_INSTRUCTION =
            """
            For future replies, start with one sentence that summarizes the current issue or request, then provide the main answer.
            The reply must work well when read over a phone call: avoid relying on screen context; do not make URLs, file paths, commands, code blocks, or tables the main form of expression; if you must mention a link, command, or path, first explain its purpose in short natural language.\
            """;

    /**
     * Formats a new inbound request. Blank input remains blank so upstream
     * callers can preserve their NO_CONTENT branches.
     */
    public String formatInboundInitial(String userText, String languageTag) {
        String cleaned = clean(userText);
        if (cleaned.isEmpty()) {
            return "";
        }
        return switch (language(languageTag)) {
            case ZH -> """
                    用户通过 Phone Agent 发起一个新的请求。

                    用户内容如下：

                    %s

                    %s\
                    """.formatted(cleaned, ZH_RESPONSE_FORMAT_INSTRUCTION);
            case EN -> """
                    The user started a new request through Phone Agent.

                    User content:

                    %s

                    %s\
                    """.formatted(cleaned, EN_RESPONSE_FORMAT_INSTRUCTION);
        };
    }

    /**
     * Formats a reply captured from a phone bridge. The scene prefix is
     * intentionally different from a new inbound request so short replies such
     * as "continue" are interpreted as the continuation of the previous turn.
     */
    public String formatPhoneReply(String replyText, String languageTag) {
        String cleaned = clean(replyText);
        if (cleaned.isEmpty()) {
            return "";
        }
        return switch (language(languageTag)) {
            case ZH -> """
                    用户通过电话语音回复了你上一轮的问题或请求。请把下面内容理解为上一轮对话的继续，而不是一个全新的任务。

                    用户电话回复如下：

                    %s

                    %s\
                    """.formatted(cleaned, ZH_RESPONSE_FORMAT_INSTRUCTION);
            case EN -> """
                    The user replied by phone voice to your previous question or request. Treat the following content as a continuation of the previous turn, not as a brand-new task.

                    User phone reply:

                    %s

                    %s\
                    """.formatted(cleaned, EN_RESPONSE_FORMAT_INSTRUCTION);
        };
    }

    /**
     * Compatibility wrapper for existing inbound callers. New code should use a
     * scenario-specific method so phone replies keep their continuation context.
     */
    public String formatUserInput(String userText) {
        return formatInboundInitial(userText, "zh-CN");
    }

    private static String clean(String text) {
        return text == null ? "" : text.strip();
    }

    private static PromptLanguage language(String languageTag) {
        String normalized = clean(languageTag).toLowerCase();
        if (normalized.startsWith("zh")) {
            return PromptLanguage.ZH;
        }
        return PromptLanguage.EN;
    }

    private enum PromptLanguage {
        ZH,
        EN
    }
}
