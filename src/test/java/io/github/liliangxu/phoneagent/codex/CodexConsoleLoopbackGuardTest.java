package io.github.liliangxu.phoneagent.codex;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexConsoleLoopbackGuardTest {
    @Test
    void consoleRequestsRequireLoopbackRemoteAndLocalAddress() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/codex-sessions");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void consoleRequestsRequireLoopbackLocalAddressWhenServerBindsAllInterfaces() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/console/");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("192.168.1.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void consoleRequestsRejectDockerHostHeaderEvenWhenNatLooksLocal() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/console/");
        request.addHeader("Host", "host.docker.internal:8080");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void consoleRequestsAllowLoopbackHostHeader() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/console/");
        request.addHeader("Host", "localhost:8080");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void internalAsteriskCallbacksMayArriveFromDockerNetwork() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/asterisk/slots/1/start");
        request.addHeader("Host", "host.docker.internal:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("192.168.1.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void ringPhoneApiRequiresLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ring-phone");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void ringPhoneApiAllowsLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ring-phone");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void phoneBridgeActionsRequireLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/codex-phone-bridges/br-1/cancel");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void taskApiRequiresLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void internalAdminActionsRequireLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/admin/blf/sync");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void defaultConsolePageAlsoRequiresLoopback() throws Exception {
        CodexConsoleLoopbackGuard guard = new CodexConsoleLoopbackGuard();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader("Host", "127.0.0.1:8080");
        request.setRemoteAddr("192.168.1.10");
        request.setLocalAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        guard.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }
}
