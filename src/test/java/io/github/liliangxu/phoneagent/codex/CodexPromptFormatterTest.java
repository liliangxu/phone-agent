package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexPromptFormatterTest {
    private final CodexPromptFormatter formatter = new CodexPromptFormatter();

    @Test
    void blankInputStaysBlank() {
        assertEquals("", formatter.formatUserInput(null));
        assertEquals("", formatter.formatUserInput("  \n "));
        assertEquals("", formatter.formatInboundInitial("  ", "en"));
        assertEquals("", formatter.formatPhoneReply(null, "zh-CN"));
    }

    @Test
    void formatsChineseInboundInitialWithoutPhoneReplyPrefix() {
        String prompt = formatter.formatInboundInitial("  继续执行测试  ", "zh-CN");

        assertTrue(prompt.contains("用户通过 Phone Agent 发起一个新的请求"));
        assertTrue(prompt.contains("继续执行测试"));
        assertTrue(prompt.contains("后续回复用户时，请先用一句话概括当前问题/事项"));
        assertTrue(prompt.contains("回复需要适合电话语音播报"));
        assertTrue(prompt.contains("不要把 URL、文件路径、命令、代码块或表格作为主要表达方式"));
        assertTrue(prompt.contains("请用简短自然语言说明用途"));
        assertFalse(prompt.contains("用户通过电话"));
        assertFalse(prompt.contains("AGENTS.md"));
    }

    @Test
    void formatsEnglishInboundInitial() {
        String prompt = formatter.formatInboundInitial(" continue testing ", "en-US");

        assertTrue(prompt.contains("The user started a new request through Phone Agent."));
        assertTrue(prompt.contains("continue testing"));
        assertTrue(prompt.contains("start with one sentence"));
        assertTrue(prompt.contains("phone call"));
        assertFalse(prompt.contains("用户通过电话"));
    }

    @Test
    void formatsChinesePhoneReplyWithContinuationContext() {
        String prompt = formatter.formatPhoneReply("  可以，继续  ", "zh-Hans");

        assertTrue(prompt.contains("用户通过电话语音回复了你上一轮的问题或请求"));
        assertTrue(prompt.contains("不是一个全新的任务"));
        assertTrue(prompt.contains("可以，继续"));
        assertTrue(prompt.contains("回复需要适合电话语音播报"));
    }

    @Test
    void formatsEnglishPhoneReplyWithContinuationContext() {
        String prompt = formatter.formatPhoneReply(" yes, continue ", "fr");

        assertTrue(prompt.contains("The user replied by phone voice to your previous question or request."));
        assertTrue(prompt.contains("not as a brand-new task"));
        assertTrue(prompt.contains("yes, continue"));
        assertTrue(prompt.contains("phone call"));
        assertFalse(prompt.contains("用户电话回复"));
    }
}
