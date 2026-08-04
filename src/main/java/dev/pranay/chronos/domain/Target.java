package dev.pranay.chronos.domain;

import java.util.Map;

/**
 * Where the webhook goes.
 *
 * <p>Every field here is attacker-controlled. {@code url} is the SSRF surface (§6.2) and
 * {@code headers} is the one people forget (§6.3) — a user who sets {@code X-Webhook-Signature}
 * can shadow the HMAC we attach, and Java's {@code HttpClient} throws on restricted headers
 * like {@code Host} and {@code Content-Length}. Both are validated at creation time, and
 * {@code url} is re-validated at delivery time because DNS can change in between.
 *
 * <p>{@code payload} is capped at creation (§2.1) — Mongo's document limit is 16MB and a
 * dead-lettered job snapshots the whole thing, so an oversized payload costs storage twice.
 */
public record Target(
        String url,
        String method,
        Map<String, String> headers,
        Map<String, Object> payload,
        int timeoutMs
) {

    public Target {
        if (method == null || method.isBlank()) {
            method = "POST";
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
