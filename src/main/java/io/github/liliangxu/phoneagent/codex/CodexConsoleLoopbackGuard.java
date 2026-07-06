package io.github.liliangxu.phoneagent.codex;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Enforces the local-only security boundary for the writable Codex terminal
 * console. The Spring dev server may bind to a host-reachable address so the
 * Asterisk Docker container can call internal callback endpoints, but console
 * routes remain restricted to loopback request paths.
 */
@Component
public class CodexConsoleLoopbackGuard extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (protects(request.getRequestURI()) && !isLoopbackConsoleRequest(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"CODEX_CONSOLE_LOOPBACK_ONLY\",\"errorMessage\":\"Codex Console is only available on loopback\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean protects(String uri) {
        return uri != null && (uri.equals("/") || uri.equals("/console") || uri.startsWith("/console/")
                || uri.startsWith("/api/codex-sessions") || uri.startsWith("/api/inbound-intents")
                || uri.startsWith("/api/ring-phone") || uri.startsWith("/api/codex-phone-bridges")
                || uri.startsWith("/api/tasks") || uri.startsWith("/internal/admin"));
    }

    /**
     * Docker Desktop may present host.docker.internal traffic as local-looking
     * socket addresses after NAT. Protected console routes therefore also
     * require a loopback Host header, while /internal Asterisk callbacks remain
     * reachable from the container.
     */
    private static boolean isLoopbackConsoleRequest(HttpServletRequest request) {
        return isLoopback(request.getRemoteAddr())
                && isLoopback(request.getLocalAddr())
                && isLoopbackHostHeader(request.getHeader("Host"));
    }

    private static boolean isLoopbackHostHeader(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.strip();
        if (normalized.startsWith("[")) {
            int end = normalized.indexOf(']');
            normalized = end >= 0 ? normalized.substring(1, end) : normalized;
        } else {
            int colon = normalized.indexOf(':');
            if (colon >= 0) {
                normalized = normalized.substring(0, colon);
            }
        }
        return isLoopback(normalized);
    }

    private static boolean isLoopback(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
