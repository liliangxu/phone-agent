package io.github.liliangxu.phoneagent.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSourceDocumentationContractTest {
    private static final String[] README_ASSETS = {
            "docs/assets/readme-cover.png",
            "docs/assets/what-it-does.png",
            "docs/assets/console-desktop.png",
            "docs/assets/architecture.svg",
            "docs/assets/blf-workflow.svg"
    };

    private static final String[] ENGLISH_PUBLIC_DOCS = {
            "README.md",
            "CONTRIBUTING.md",
            "docs/ARCHITECTURE.md",
            "docs/SECURITY.md",
            "docs/TROUBLESHOOTING.md",
            "docs/DEPLOYMENT.md"
    };

    @Test
    void productDocumentationDescribesLocalOpenSourceShapeWithoutLicenseClaim() throws Exception {
        assertContains(read("LICENSE"), "MIT License");
        assertContains(read("LICENSE"), "Copyright (c) 2026 liliangxu");

        assertPublicDocPair("README.md", "README.zh-CN.md", "[中文](README.zh-CN.md)", "[English](README.md)");
        assertPublicDocPair("CONTRIBUTING.md", "CONTRIBUTING.zh-CN.md", "[中文](CONTRIBUTING.zh-CN.md)", "[English](CONTRIBUTING.md)");
        assertPublicDocPair("docs/ARCHITECTURE.md", "docs/ARCHITECTURE.zh-CN.md", "[中文](ARCHITECTURE.zh-CN.md)", "[English](ARCHITECTURE.md)");
        assertPublicDocPair("docs/SECURITY.md", "docs/SECURITY.zh-CN.md", "[中文](SECURITY.zh-CN.md)", "[English](SECURITY.md)");
        assertPublicDocPair("docs/TROUBLESHOOTING.md", "docs/TROUBLESHOOTING.zh-CN.md", "[中文](TROUBLESHOOTING.zh-CN.md)", "[English](TROUBLESHOOTING.md)");
        assertPublicDocPair("docs/DEPLOYMENT.md", "docs/DEPLOYMENT.zh-CN.md", "[中文](DEPLOYMENT.zh-CN.md)", "[English](DEPLOYMENT.md)");

        String readme = read("README.md");
        assertContains(readme, "local phone-to-Codex bridge");
        assertContains(readme, "not a hosted multi-tenant service");
        assertContains(readme, "http://127.0.0.1:8080/console/");
        assertContains(readme, "Chinese and English Console UI text");
        assertContains(readme, "## Install");
        assertContains(readme, "## Configure");
        assertContains(readme, "## Quick Start");
        assertContains(readme, "## Phone and Asterisk Hardware Setup");
        assertContains(readme, "## Developer Notes and Testing");
        assertContains(readme, "## Basic Workflow");
        assertContains(readme, "## Browser Cockpit");
        assertContains(readme, "mlan/asterisk:latest");
        assertContains(readme, "Eventlist BLF URI: `phone-agent-slots`");
        assertContains(readme, "The UI chrome is localized");
        assertContains(readme, "scripts/phone-agent-dev.sh start");
        assertContains(readme, "scripts/phone-agent-dev.sh status");
        assertContains(readme, "scripts/phone-agent-dev.sh stop");
        assertContains(readme, "scripts/phone-agent-dev.sh logs spring");
        assertContains(readme, "scripts/phone-agent-dev.sh logs asterisk");
        assertContains(readme, "scripts/phone-agent-dev.sh logs all -f");
        assertContains(readme, "The variables in `.env.example` use the `PHONE_AGENT_*` names");
        assertContains(readme, "Software-only mode is useful for Console/API smoke");
        assertContains(readme, "## License");
        assertContains(readme, "Phone Agent is licensed under the [MIT License](LICENSE).");
        assertFalse(readme.contains("中文简介"), "Default README must not mix a Chinese body into the English entrypoint");
        assertFalse(readme.contains("本机电话与 Codex 的桥接工具"),
                "Default README must link to the Chinese version instead of embedding it");

        String readmeZh = read("README.zh-CN.md");
        assertContains(readmeZh, "本机电话与 Codex 的桥接工具");
        assertContains(readmeZh, "Console 页面提示语支持中文和英文");
        assertContains(readmeZh, "inbound 初始请求和 phone reply 回写提示词支持中文和英文模板");
        assertContains(readmeZh, "## 安装");
        assertContains(readmeZh, "## 配置");
        assertContains(readmeZh, "## 快速开始");
        assertContains(readmeZh, "## 电话和 Asterisk 硬件接入");
        assertContains(readmeZh, "## 开发说明与测试");
        assertContains(readmeZh, "## 基本工作流");
        assertContains(readmeZh, "## Browser Cockpit");
        assertContains(readmeZh, "mlan/asterisk:latest");
        assertContains(readmeZh, "Eventlist BLF URI：`phone-agent-slots`");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh start");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh status");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh stop");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh logs spring");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh logs asterisk");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh logs all -f");
        assertContains(readmeZh, "## 许可证");
        assertContains(readmeZh, "Phone Agent 使用 [MIT License](LICENSE)。");

        String env = read(".env.example");
        assertContains(env, "PHONE_AGENT_SPRING_BIND_ADDRESS=127.0.0.1");
        assertContains(env, "PHONE_AGENT_MYSQL_USER=root");
        assertContains(env, "PHONE_AGENT_FFMPEG_COMMAND=ffmpeg");
        assertContains(env, "PHONE_AGENT_WHISPER_COMMAND=whisper");
        assertContains(env, "PHONE_AGENT_WHISPER_MODEL_PATH=models/whisper/ggml-small.bin");
        assertContains(env, "PHONE_AGENT_CODEX_PROMPT_LANGUAGE=zh-CN");
        assertContains(env, "PHONE_AGENT_AMI_SECRET=change-me");
        assertFalse(env.lines().anyMatch("SPRING_BIND_ADDRESS=127.0.0.1"::equals),
                "Configuration samples must use the script's PHONE_AGENT_* variable names");
        assertFalse(env.lines().anyMatch(line -> line.startsWith("PHONE_AGENT_MYSQL_USERNAME=")),
                "Configuration samples must use PHONE_AGENT_MYSQL_USER to match the dev script");
        assertFalse(env.lines().anyMatch(line -> line.startsWith("PHONE_AGENT_ASR_MODEL_PATH=")),
                "Configuration samples must use PHONE_AGENT_WHISPER_MODEL_PATH to match the dev script");
        assertFalse(env.matches("(?s).*/Users/[^/]+/.*"),
                "Configuration samples must not depend on a contributor machine path");

        String security = read("docs/SECURITY.md");
        assertContains(security, "writable terminal sessions");
        assertContains(security, "server.address=127.0.0.1");
        assertContains(security, "PHONE_AGENT_SPRING_BIND_ADDRESS=0.0.0.0");
        assertContains(security, "not a production remote-access model");
        assertFalse(security.contains("默认绑定"), "English security doc must not embed Chinese body content");
        assertContains(read("docs/SECURITY.zh-CN.md"), "默认绑定");

        String contributing = read("CONTRIBUTING.md");
        assertContains(contributing, "fd-v5");
        assertContains(contributing, "rendered evidence");
        assertContains(contributing, "Hardware-only phone flows");
        assertContains(contributing, "Public-facing documentation must keep language files separate");
        assertFalse(contributing.contains("真实浏览器或目标运行环境"),
                "English contributing doc must not embed Chinese body content");
        assertContains(read("CONTRIBUTING.zh-CN.md"), "真实浏览器或目标运行环境");

        String architecture = read("docs/ARCHITECTURE.md");
        assertContains(architecture, "phone reply prompt");
        assertFalse(architecture.contains("核心数据流"), "English architecture doc must not embed Chinese body content");
        assertContains(read("docs/ARCHITECTURE.zh-CN.md"), "核心数据流");

        String troubleshooting = read("docs/TROUBLESHOOTING.md");
        assertContains(troubleshooting, "390px and 430px");
        assertFalse(troubleshooting.contains("常见问题"), "English troubleshooting doc must not embed Chinese body content");
        assertContains(read("docs/TROUBLESHOOTING.zh-CN.md"), "移动端 UI 异常");

        String deployment = read("docs/DEPLOYMENT.md");
        assertContains(deployment, "Phone Agent Local Deployment");
        assertContains(deployment, "JDK 25, available through `JAVA_HOME` or `PATH`");
        assertContains(deployment, "Commands in `.env.local` may be command names on `PATH` or absolute executable paths");
        assertContains(deployment, "scripts/phone-agent-dev.sh start");
        assertContains(deployment, "scripts/phone-agent-dev.sh status");
        assertContains(deployment, "scripts/phone-agent-dev.sh logs all -f");
        assertContains(deployment, "A user-prepared MySQL service and database");
        assertContains(deployment, "Spring startup runs Flyway migrations");
        assertContains(deployment, "Optional manual database debugging");
        assertNotContains(deployment, "database-level readiness and Flyway history checks from the dev script");
        assertContains(deployment, "mlan/asterisk:latest");
        assertContains(read("ops/asterisk-mvp/README.md"), "The Compose file pulls `mlan/asterisk:latest`");
        assertContains(deployment, "Codex Phone Bridge Smoke Test");
        assertContains(read("docs/DEPLOYMENT.zh-CN.md"), "Phone Agent 本地部署");
    }

    @Test
    void publicDocsUseFullPhoneStartStatusMainPathAndExternalMysql() throws Exception {
        String readme = read("README.md");
        String readmeZh = read("README.zh-CN.md");
        String deployment = read("docs/DEPLOYMENT.md");
        String deploymentZh = read("docs/DEPLOYMENT.zh-CN.md");
        String troubleshooting = read("docs/TROUBLESHOOTING.md");
        String troubleshootingZh = read("docs/TROUBLESHOOTING.zh-CN.md");
        String envExample = read(".env.example");

        for (String doc : new String[] {readme, readmeZh, deployment, deploymentZh, troubleshooting, troubleshootingZh}) {
            assertContains(doc, "scripts/phone-agent-dev.sh status");
            assertNotContains(doc, "scripts/phone-agent-dev.sh init --phone");
            assertNotContains(doc, "scripts/phone-agent-dev.sh doctor --phone");
            assertNotContains(doc, "scripts/phone-agent-dev.sh doctor --software-only");
            assertNotContains(doc, "scripts/phone-agent-dev.sh start --phone");
            assertNotContains(doc, "scripts/phone-agent-dev.sh start --software-only");
            assertNotContains(doc, "scripts/phone-agent-dev.sh init --software-only");
            assertNotContains(doc, "PHONE_AGENT_MYSQL_CONTAINER");
            assertNotContains(doc, "docker exec mysql mysql");
            assertNotContains(doc, "database-level checks from the script");
            assertNotContains(doc, "database-level readiness and Flyway history checks from the dev script");
            assertNotContains(doc, "can inspect and optionally create a local development MySQL container");
            assertNotContains(doc, "创建本地开发 MySQL 容器");
        }
        for (String doc : new String[] {readme, deployment, troubleshooting}) {
            assertContains(doc, "MySQL TCP reachability");
            assertContains(doc, "phone configuration");
            assertContains(doc, "Database, authentication, and Flyway readiness are verified by Spring startup health and logs");
            assertNotContains(doc, "Java, Spring, MySQL, Flyway");
            assertNotContains(doc, "reports Java, Spring, MySQL, Flyway");
        }
        for (String doc : new String[] {readmeZh, deploymentZh, troubleshootingZh}) {
            assertContains(doc, "MySQL TCP 可达性");
            assertContains(doc, "电话配置");
            assertContains(doc, "数据库、认证和 Flyway 就绪状态由 Spring 启动");
            assertNotContains(doc, "Java、Spring、MySQL、Flyway");
            assertNotContains(doc, "报告 Java、Spring、MySQL、Flyway");
            assertNotContains(doc, "Database、authentication");
            assertNotContains(doc, "Database authentication");
            assertNotContains(doc, "Flyway readiness");
            assertNotContains(doc, "Asterisk config");
            assertNotContains(doc, "callback");
            assertNotContains(doc, "legacy doctor/software-only");
        }
        assertContains(envExample, "PHONE_AGENT_MYSQL_HOST");
        assertContains(envExample, "PHONE_AGENT_MYSQL_DATABASE");
        assertNotContains(envExample, "PHONE_AGENT_MYSQL_CONTAINER");
        assertNotContains(envExample, "PHONE_AGENT_MYSQL_IMAGE");

        assertContains(readme, "The open-source main path is the full phone workflow");
        assertContains(readme, "scripts/phone-agent-dev.sh start");
        assertContains(readme, "scripts/phone-agent-dev.sh stop");
        assertContains(readme, "scripts/phone-agent-dev.sh logs all -f");
        assertContains(readme, "Prepare MySQL yourself");
        assertContains(readme, "PHONE_AGENT_MYSQL_HOST");
        assertContains(readme, "Spring Boot runs Flyway schema migrations during startup");
        assertContains(readme, "The script checks the MySQL TCP endpoint only");
        assertContains(readme, "database, authentication, and Flyway readiness are verified by Spring startup health and logs");
        assertContains(readme, "Software-only mode is useful for Console/API smoke");
        assertContains(readme, "It is not a full Phone Agent proof");

        assertContains(readmeZh, "开源主路径是完整电话工作流");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh start");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh stop");
        assertContains(readmeZh, "scripts/phone-agent-dev.sh logs all -f");
        assertContains(readmeZh, "自行准备 MySQL");
        assertContains(readmeZh, "PHONE_AGENT_MYSQL_HOST");
        assertContains(readmeZh, "Spring Boot 启动时运行 Flyway schema migration");
        assertContains(readmeZh, "脚本只检查 MySQL TCP 端点");
        assertContains(readmeZh, "数据库、认证和 Flyway 就绪状态由 Spring 启动健康状态和日志验证");
        assertContains(readmeZh, "生成的 Asterisk 配置");
        assertContains(readmeZh, "回调连通性");
        assertContains(readmeZh, "纯软件模式适合 Console/API 冒烟");
        assertContains(readmeZh, "它不是完整 Phone Agent 证明");

        assertContains(deployment, "A user-prepared MySQL service and database");
        assertContains(deployment, "nc -z -w 1 \"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}\"");
        assertContains(deployment, "Optional manual database debugging");
        assertContains(deployment, "If you choose to run MySQL in Docker, treat it as your own user-provided MySQL service");
        assertContains(deployment, "The Phone Agent dev script does not create that container or database");
        assertContains(deployment, "Spring startup runs Flyway migrations");
        assertContains(deployment, "Legacy doctor/software-only commands may exist for maintainers, but they are not the public deployment check");

        assertContains(deploymentZh, "由用户自行准备的 MySQL 服务和数据库");
        assertContains(deploymentZh, "nc -z -w 1 \"${PHONE_AGENT_MYSQL_HOST:-127.0.0.1}\"");
        assertContains(deploymentZh, "可选进行人工数据库排查");
        assertContains(deploymentZh, "如果你选择用 Docker 运行 MySQL，它仍然是你自行提供的 MySQL 服务");
        assertContains(deploymentZh, "Phone Agent 开发脚本不会创建该容器或数据库");
        assertContains(deploymentZh, "Spring 启动时会运行 Flyway migration");
        assertContains(deploymentZh, "doctor 和 software-only 等兼容保留命令可供维护者使用，但不是公开部署检查");

        assertTrue(troubleshooting.indexOf("scripts/phone-agent-dev.sh status") < troubleshooting.indexOf("## Spring Boot Does Not Start"),
                "Troubleshooting must start with status before topic-specific sections");
        assertTrue(troubleshootingZh.indexOf("scripts/phone-agent-dev.sh status") < troubleshootingZh.indexOf("## Spring Boot 无法启动"),
                "Chinese troubleshooting must start with status before topic-specific sections");
        assertContains(troubleshooting, "Flyway migrations run during Spring startup");
        assertContains(troubleshooting, "The dev script checks MySQL TCP reachability only");
        assertContains(troubleshooting, "Optional manual database debugging");
        assertContains(troubleshootingZh, "Flyway migration 在 Spring 启动时执行");
        assertContains(troubleshootingZh, "开发脚本只检查 MySQL TCP 可达性");
        assertContains(troubleshootingZh, "可选进行人工数据库排查");
    }

    @Test
    void readmeVisualAssetsAreReferencedAndConstrained() throws Exception {
        String readme = read("README.md");
        String readmeZh = read("README.zh-CN.md");

        // TC-014 protects the README visual contract independently from rendered-state evidence.
        for (String asset : README_ASSETS) {
            assertContains(readme, asset);
            assertContains(readmeZh, asset);
            assertTrue(Files.exists(Path.of(asset)), "README asset must exist before release: " + asset);
            assertTrue(Files.size(Path.of(asset)) > 0, "README asset must be non-empty: " + asset);
        }

        for (String doc : new String[] {readme, readmeZh}) {
            assertNotContains(doc, "/tmp/");
            assertNotContains(doc, "$CODEX_HOME");
            assertNotContains(doc, System.getProperty("user.home"));
        }

        assertMaintainableSvgAsset(
                "docs/assets/architecture.svg",
                "Phone Agent Architecture",
                "Browser Console",
                "Spring Boot",
                "Asterisk",
                "SIP Phone",
                "ASR",
                "Codex CLI",
                "MySQL"
        );
        assertMaintainableSvgAsset(
                "docs/assets/blf-workflow.svg",
                "BLF Reminder Workflow",
                "Codex waits",
                "Ring Phone",
                "Allocate slot",
                "Set BLF red",
                "User answers",
                "Recording",
                "Session resumes"
        );
    }

    @Test
    void englishPublicDocumentsDoNotEmbedChineseBodyText() throws Exception {
        for (String path : ENGLISH_PUBLIC_DOCS) {
            assertNoHanCharactersOutsideLanguageSwitches(path);
        }
    }

    @Test
    void localOnlyConfigurationDoesNotLeakIntoCommittedProjectFiles() throws Exception {
        String gitignore = read(".gitignore");
        assertContains(gitignore, ".env.*");
        assertContains(gitignore, "!.env.example");
        assertFalse(Files.exists(Path.of("gradle.properties")),
                "Contributor-local Gradle Java paths must live outside the committed project");

        for (String path : ENGLISH_PUBLIC_DOCS) {
            assertFalse(read(path).matches("(?s).*/Users/[^/]+/.*"),
                    "Public documentation must not depend on a contributor machine path: " + path);
        }
        assertFalse(read("README.zh-CN.md").matches("(?s).*/Users/[^/]+/.*"),
                "Chinese README must not depend on a contributor machine path");
        assertFalse(read("docs/DEPLOYMENT.zh-CN.md").matches("(?s).*/Users/[^/]+/.*"),
                "Chinese deployment doc must not depend on a contributor machine path");
    }

    @Test
    void phoneConfigurationDocumentationCoversSupportedScenarioAndOperationalContract() throws Exception {
        String env = read(".env.example");
        assertContains(env, "PHONE_AGENT_SIP_EXTENSION=1001");
        assertContains(env, "PHONE_AGENT_SIP_AUTH_ID=1001");
        assertContains(env, "PHONE_AGENT_SIP_PASSWORD=1001");
        assertContains(env, "PHONE_AGENT_RING_TARGET=PJSIP/1001");
        assertContains(env, "PHONE_AGENT_BLF_EVENTLIST_URI=phone-agent-slots");
        assertContains(env, "PHONE_AGENT_BLF_EXTENSIONS=601,602,603,604,605,606,607,608");
        assertContains(env, "PHONE_AGENT_ASTERISK_EXTERNAL_SIGNALING_ADDRESS=192.168.10.1");
        assertContains(env, "PHONE_AGENT_ASTERISK_EXTERNAL_MEDIA_ADDRESS=192.168.10.1");

        String readme = read("README.md");
        assertContains(readme, "## Supported Scenario");
        assertContains(readme, "## Supported Phone Setup");
        assertContains(readme, "GXP1630, but it is not the only supported device");
        assertContains(readme, "The full BLF red-light experience requires both configured SIP registration and Eventlist BLF/dialog subscription");
        assertContains(readme, "does not include phone-brand UI tutorials");
        assertContains(readme, "The open-source main path is the full phone workflow");
        assertContains(readme, "Software-only checks are developer/testing aids");
        assertContains(readme, "run `scripts/phone-agent-dev.sh start`");
        assertContains(readme, "does not run a database migration");
        assertContains(readme, "does not hot-remap active tasks at runtime");

        String readmeZh = read("README.zh-CN.md");
        assertContains(readmeZh, "## 支持场景");
        assertContains(readmeZh, "## 支持的电话接入");
        assertContains(readmeZh, "GXP1630，但它不是唯一支持设备");
        assertContains(readmeZh, "完整 BLF 红灯体验要求配置后的 SIP 注册和 Eventlist BLF/dialog 订阅都成立");
        assertContains(readmeZh, "不提供各电话品牌 UI 配置教程");
        assertContains(readmeZh, "开源主路径是完整电话工作流");
        assertContains(readmeZh, "纯软件检查是开发和测试辅助路径");
        assertContains(readmeZh, "运行 `scripts/phone-agent-dev.sh start`");
        assertContains(readmeZh, "不会触发数据库迁移");
        assertContains(readmeZh, "不会在运行中热重映射 active task");

        String deployment = read("docs/DEPLOYMENT.md");
        assertContains(deployment, "## Default Phone Configuration");
        assertContains(deployment, "| `PHONE_AGENT_SIP_EXTENSION` | `1001` |");
        assertContains(deployment, "| `PHONE_AGENT_BLF_EXTENSIONS` | `601,602,603,604,605,606,607,608` |");
        assertContains(deployment, "| SIP server | Asterisk host IP");
        assertContains(deployment, "| Eventlist BLF URI | `PHONE_AGENT_BLF_EVENTLIST_URI`");
        assertContains(deployment, "Example `1001->1002`");
        assertContains(deployment, "Example `601-608->801-808`");
        assertContains(deployment, "Example `801-804`");
        assertContains(deployment, "docker compose config");
        assertContains(deployment, "pjsip show contacts");
        assertContains(deployment, "pjsip show subscriptions inbound");
        assertContains(deployment, "core show hints");
        assertContains(deployment, "Changing SIP or BLF configuration requires exporting the new values and running `scripts/phone-agent-dev.sh start`");
        assertContains(deployment, "no database migration");
        assertContains(deployment, "does not hot-remap active tasks at runtime");

        String deploymentZh = read("docs/DEPLOYMENT.zh-CN.md");
        assertContains(deploymentZh, "## 默认电话配置");
        assertContains(deploymentZh, "| `PHONE_AGENT_SIP_EXTENSION` | `1001` |");
        assertContains(deploymentZh, "| `PHONE_AGENT_BLF_EXTENSIONS` | `601,602,603,604,605,606,607,608` |");
        assertContains(deploymentZh, "| SIP 服务器 | Asterisk 主机 IP");
        assertContains(deploymentZh, "| Eventlist BLF URI | `PHONE_AGENT_BLF_EVENTLIST_URI`");
        assertContains(deploymentZh, "示例 `1001->1002`");
        assertContains(deploymentZh, "示例 `601-608->801-808`");
        assertContains(deploymentZh, "示例 `801-804`");
        assertContains(deploymentZh, "docker compose config");
        assertContains(deploymentZh, "pjsip show contacts");
        assertContains(deploymentZh, "pjsip show subscriptions inbound");
        assertContains(deploymentZh, "core show hints");
        assertContains(deploymentZh, "需要导出新值并运行 `scripts/phone-agent-dev.sh start`");
        assertContains(deploymentZh, "不会为本地历史数据执行数据库迁移");
        assertContains(deploymentZh, "不会在运行中热重映射 active task");

        String troubleshooting = read("docs/TROUBLESHOOTING.md");
        assertContains(troubleshooting, "Software-only troubleshooting is a developer/testing aid");
        assertContains(troubleshooting, "Full phone troubleshooting uses the values exported from `.env.local`");
        assertContains(troubleshooting, "## Phone Registration or BLF Is Down");
        assertContains(troubleshooting, "Look for the contact for `PHONE_AGENT_SIP_EXTENSION`");
        assertContains(troubleshooting, "The subscription should reference `PHONE_AGENT_BLF_EVENTLIST_URI`");
        assertContains(troubleshooting, "For `PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804`, only `801-804` should be required");
        assertContains(troubleshooting, "## Generated Asterisk Config Drift");
        assertContains(troubleshooting, "There is no runtime hot remap of active tasks");
        assertContains(troubleshooting, "no database migration rewrites historical local development data");

        String troubleshootingZh = read("docs/TROUBLESHOOTING.zh-CN.md");
        assertContains(troubleshootingZh, "纯软件排障是开发和测试辅助路径");
        assertContains(troubleshootingZh, "完整电话排障使用 `.env.local` 导出的值");
        assertContains(troubleshootingZh, "## 电话注册或 BLF 异常");
        assertContains(troubleshootingZh, "应查找 `PHONE_AGENT_SIP_EXTENSION` 对应的 `1002` 联系记录");
        assertContains(troubleshootingZh, "订阅应引用 `PHONE_AGENT_BLF_EVENTLIST_URI`");
        assertContains(troubleshootingZh, "如果 `PHONE_AGENT_BLF_EXTENSIONS=801,802,803,804`，只要求 `801-804`");
        assertContains(troubleshootingZh, "## 生成的 Asterisk 配置漂移");
        assertContains(troubleshootingZh, "不会在运行中热重映射 active task");
        assertContains(troubleshootingZh, "不会通过数据库迁移改写本地历史开发数据");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "Expected documentation to contain: " + expected);
    }

    private static void assertNotContains(String text, String unexpected) {
        assertFalse(text.contains(unexpected), "Expected documentation not to contain: " + unexpected);
    }

    private static void assertNoHanCharactersOutsideLanguageSwitches(String path) throws Exception {
        String checked = read(path)
                .replace("[中文]", "")
                .replace("| 中文", "");
        assertFalse(checked.matches("(?s).*\\p{IsHan}.*"),
                "English public document must not embed Chinese body text: " + path);
    }

    private static void assertMaintainableSvgAsset(String path, String... requiredText) throws Exception {
        String svg = read(path);
        assertContains(svg, "<svg");
        assertContains(svg, "viewBox=");
        assertContains(svg, "width=");
        assertContains(svg, "height=");
        for (String text : requiredText) {
            assertContains(svg, text);
        }
        assertNotContains(svg, "href=\"http");
        assertNotContains(svg, "href='http");
        assertNotContains(svg, "/tmp/");
        assertNotContains(svg, "$CODEX_HOME");
        assertNotContains(svg, System.getProperty("user.home"));
    }

    private static void assertPublicDocPair(
            String englishPath,
            String chinesePath,
            String englishSwitch,
            String chineseSwitch
    ) throws Exception {
        assertTrue(Files.exists(Path.of(englishPath)), "Missing English public document: " + englishPath);
        assertTrue(Files.exists(Path.of(chinesePath)), "Missing Chinese public document: " + chinesePath);
        assertContains(read(englishPath), englishSwitch);
        assertContains(read(chinesePath), chineseSwitch);
    }
}
