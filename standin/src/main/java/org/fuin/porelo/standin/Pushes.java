package org.fuin.porelo.standin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** The pushes waiting at the stand-in's gate, by name, in the order they were added. */
final class Pushes {

    private final Map<String, Push> byName = new ConcurrentHashMap<>();

    private final List<String> order = new ArrayList<>();

    /**
     * Adds a push.
     *
     * @param push The push.
     */
    synchronized void add(Push push) {
        if (byName.putIfAbsent(push.name, push) == null) {
            order.add(push.name);
        }
    }

    /**
     * Returns the push of a pull request, made on its first read and moved to its head on each
     * later one. Its level and class are the stand-in's fixed values.
     *
     * @param name Its name.
     * @param number The pull request.
     * @param subject Its title.
     * @param at When it changed last.
     * @param head Its head.
     * @param base Its merge base.
     * @return The push.
     */
    synchronized Push github(
            String name, long number, String subject, String at, String head, String base) {
        final Push existing = byName.get(name);
        if (existing != null) {
            existing.commit = head;
            existing.base = base;
            return existing;
        }
        final Push push =
                new Push(
                        name,
                        subject,
                        at,
                        number,
                        "E1",
                        "C1",
                        Map.of(),
                        1,
                        null,
                        null,
                        "GitHub pull request " + number,
                        head,
                        base);
        add(push);
        return push;
    }

    /**
     * Returns a push by name.
     *
     * @param name Its name.
     * @return The push, or {@code null}.
     */
    @Nullable Push get(String name) {
        return byName.get(name);
    }

    /**
     * Returns every push, in the order they were added.
     *
     * @return The pushes.
     */
    synchronized List<Push> all() {
        return order.stream().map(byName::get).toList();
    }
}
