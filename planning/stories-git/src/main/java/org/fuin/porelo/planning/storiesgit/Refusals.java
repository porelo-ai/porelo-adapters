package org.fuin.porelo.planning.storiesgit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningRefused;
import org.fuin.porelo.planning.api.PlanningRefused.Reason;

final class Refusals {
    private Refusals() {}

    static PlanningRefused failed(String detail) {
        return new PlanningRefused(Reason.FAILED, Map.of("message", detail));
    }

    static PlanningRefused invalid(String detail) {
        return new PlanningRefused(Reason.INVALID_SETTINGS, Map.of("detail", detail));
    }

    static PlanningRefused store(Reason reason, String detail) {
        return new PlanningRefused(reason, Map.of("detail", detail, "retryAfter", 0));
    }

    static PlanningRefused safe(PlanningRefused refusal, String token) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        refusal.parameters()
                .forEach(
                        (key, value) ->
                                fields.put(
                                        key,
                                        value instanceof String text ? safe(text, token) : value));
        return new PlanningRefused(refusal.reason(), fields);
    }

    private static String safe(String text, String token) {
        final String redacted = token.isEmpty() ? text : text.replace(token, "[credential]");
        final StringBuilder bounded = new StringBuilder();
        redacted.codePoints()
                .limit(512)
                .forEach(c -> bounded.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return bounded.toString();
    }
}
