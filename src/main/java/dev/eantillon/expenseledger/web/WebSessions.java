package dev.eantillon.expenseledger.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class WebSessions {

    private static final String COOKIE_NAME = "expense_session";
    private static final Duration LIFETIME = Duration.ofHours(12);

    private final byte[] accessTokenHash;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    WebSessions(String accessToken) {
        accessTokenHash = sha256(accessToken);
    }

    boolean tokenMatches(String candidate) {
        return candidate != null
                && MessageDigest.isEqual(accessTokenHash, sha256(candidate));
    }

    Session create(Headers responseHeaders) {
        String id = randomToken();
        Session session = new Session(id, randomToken(), Instant.now().plus(LIFETIME));
        sessions.put(id, session);
        responseHeaders.add(
                "Set-Cookie",
                COOKIE_NAME + "=" + id + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                        + LIFETIME.toSeconds());
        return session;
    }

    Optional<Session> current(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return Optional.empty();
        }
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && COOKIE_NAME.equals(pair[0])) {
                Session session = sessions.get(pair[1]);
                if (session != null && session.expiresAt().isAfter(Instant.now())) {
                    return Optional.of(session);
                }
                sessions.remove(pair[1]);
            }
        }
        return Optional.empty();
    }

    void destroy(HttpExchange exchange) {
        current(exchange).ifPresent(session -> sessions.remove(session.id()));
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                COOKIE_NAME + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    boolean validCsrf(Session session, String provided) {
        return provided != null && MessageDigest.isEqual(
                session.csrf().getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Session(String id, String csrf, Instant expiresAt) {
    }
}
