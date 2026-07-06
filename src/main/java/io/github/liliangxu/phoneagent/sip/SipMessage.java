package io.github.liliangxu.phoneagent.sip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small parser for SIP messages used by the MVP registrar.
 * It preserves repeated headers such as Via and accepts folded-free SIP headers,
 * which is sufficient for Grandstream REGISTER packets observed in this project.
 */
final class SipMessage {
    private final String startLine;
    private final Map<String, List<String>> headers;

    private SipMessage(String startLine, Map<String, List<String>> headers) {
        this.startLine = startLine;
        this.headers = headers;
    }

    static SipMessage parse(String raw) {
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        String startLine = lines.length == 0 ? "" : lines[0];
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = normalizeHeaderName(line.substring(0, colon));
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return new SipMessage(startLine, headers);
    }

    static Map<String, String> parseDigestAuthorization(String header) {
        if (header == null || !header.regionMatches(true, 0, "Digest", 0, "Digest".length())) {
            return Collections.emptyMap();
        }
        String body = header.substring("Digest".length()).trim();
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : splitDigestParts(body)) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }

    String method() {
        int space = startLine.indexOf(' ');
        return space < 0 ? startLine : startLine.substring(0, space);
    }

    String startLine() {
        return startLine;
    }

    String header(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    String headerOrDefault(String name, String defaultValue) {
        String value = header(name);
        return value == null ? defaultValue : value;
    }

    List<String> headers(String name) {
        List<String> values = headers.get(normalizeHeaderName(name));
        return values == null ? List.of() : values;
    }

    private static List<String> splitDigestParts(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static String normalizeHeaderName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
