package io.github.liliangxu.phoneagent.sip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/**
 * Implements the SIP HTTP-Digest response check used by Grandstream REGISTER requests.
 * This is intentionally narrow: it supports MD5 with optional qop=auth, which is enough
 * for the GXP1630 registration handshake while keeping the MVP registrar auditable.
 */
final class DigestAuth {
    private DigestAuth() {
    }

    static boolean verify(String method, String password, Map<String, String> auth) {
        String username = auth.get("username");
        String realm = auth.get("realm");
        String nonce = auth.get("nonce");
        String uri = auth.get("uri");
        String response = auth.get("response");
        if (isBlank(username) || isBlank(realm) || isBlank(nonce) || isBlank(uri) || isBlank(response)) {
            return false;
        }

        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String qop = auth.get("qop");
        String expected;
        if (!isBlank(qop)) {
            String nc = auth.get("nc");
            String cnonce = auth.get("cnonce");
            if (isBlank(nc) || isBlank(cnonce)) {
                return false;
            }
            expected = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            expected = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        return expected.equalsIgnoreCase(response);
    }

    static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest is unavailable", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
