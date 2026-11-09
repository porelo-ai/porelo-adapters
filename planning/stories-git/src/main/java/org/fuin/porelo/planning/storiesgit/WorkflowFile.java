package org.fuin.porelo.planning.storiesgit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planning.api.PlanPhase;
import org.fuin.porelo.planning.api.PlanState;
import org.fuin.porelo.planning.api.Workflow;

/** A deliberately bounded YAML subset: ordered mappings, scalar fields and string sequences. */
final class WorkflowFile {
    private WorkflowFile() {}

    static Workflow parse(String text) {
        final List<Map<String, String>> states = new ArrayList<>();
        final List<Map<String, String>> phases = new ArrayList<>();
        final Set<String> sections = new HashSet<>();
        String section = "";
        Map<String, String> current = new LinkedHashMap<>();
        boolean phaseStates = false;
        for (final String raw : text.split("\\R")) {
            final String line = uncomment(raw).stripTrailing();
            if (line.isBlank()) continue;
            final String trimmed = line.stripLeading();
            final int indent = line.length() - trimmed.length();
            if (indent == 0) {
                if (!Set.of("states:", "phases:", "phases: []").contains(line))
                    throw malformed("states and phases mappings", "another top-level field");
                section = line.startsWith("states:") ? "states" : "phases";
                if (!sections.add(section))
                    throw malformed("one " + section + " section", "a duplicate section");
                current = new LinkedHashMap<>();
                phaseStates = false;
            } else if (indent == 2 && trimmed.startsWith("- ") && !section.isEmpty()) {
                current = new LinkedHashMap<>();
                (section.equals("states") ? states : phases).add(current);
                final String entry = trimmed.substring(2).strip();
                if (entry.startsWith("{") && entry.endsWith("}")) {
                    for (final String field : split(entry.substring(1, entry.length() - 1)))
                        field(current, field);
                } else field(current, entry);
                phaseStates = false;
            } else if (indent == 4 && !current.isEmpty()) {
                field(current, trimmed);
                phaseStates = section.equals("phases") && trimmed.equals("states:");
            } else if (indent == 6 && phaseStates && trimmed.startsWith("- ")) {
                current.put(
                        "states",
                        current.get("states") + "," + quote(scalar(trimmed.substring(2))));
            } else
                throw malformed(
                        "a two-space YAML list entry or four-space field",
                        "unsupported indentation or syntax");
        }
        if (!sections.contains("states") || states.isEmpty())
            throw malformed("nonempty states", "missing or empty states");
        final List<PlanState> ordered = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        for (final Map<String, String> entry : states) {
            final String name = required(entry, "name");
            if (!names.add(name)) throw malformed("unique state names", "duplicate state " + name);
            final String closed = entry.get("closed");
            if (!"true".equals(closed) && !"false".equals(closed))
                throw malformed(
                        "closed: true or false for " + name, "missing or non-boolean closed");
            ordered.add(
                    new PlanState(
                            name,
                            "true".equals(closed)
                                    ? PlanState.Kind.CLOSED
                                    : PlanState.Kind.WAITING));
        }
        final List<PlanPhase> groups = new ArrayList<>();
        final Set<String> phaseNames = new HashSet<>();
        for (final Map<String, String> entry : phases) {
            final String name = required(entry, "name");
            if (!phaseNames.add(name))
                throw malformed("unique phase names", "duplicate phase " + name);
            final String value = entry.get("states");
            if (value == null) throw malformed("states for phase " + name, "a missing sequence");
            final String content;
            if (value.startsWith("[") && value.endsWith("]"))
                content = value.substring(1, value.length() - 1);
            else if (value.startsWith(",")) content = value.substring(1);
            else if (value.isEmpty()) content = "";
            else throw malformed("a states sequence for phase " + name, "a scalar");
            final List<String> members =
                    content.isBlank()
                            ? List.of()
                            : split(content).stream().map(WorkflowFile::scalar).toList();
            if (!names.containsAll(members)
                    || members.stream().distinct().count() != members.size())
                throw malformed(
                        "distinct workflow states in phase " + name, "unknown or duplicate states");
            groups.add(new PlanPhase(name, members));
        }
        return new Workflow(ordered, groups);
    }

    private static void field(Map<String, String> current, String text) {
        final int colon = text.indexOf(':');
        if (colon < 1) throw malformed("a YAML field", "a field without a colon");
        final String key = text.substring(0, colon).strip();
        if (!key.matches("[A-Za-z][A-Za-z0-9_-]*")
                || current.putIfAbsent(key, text.substring(colon + 1).strip()) != null)
            throw malformed("a unique field name", "an invalid or duplicate field");
    }

    private static String required(Map<String, String> entry, String key) {
        final String value = entry.get(key);
        if (value == null) throw malformed("field " + key, "a missing field");
        return scalar(value);
    }

    private static String scalar(String text) {
        final String value = text.strip();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            try {
                final Object parsed = org.fuin.porelo.wire.Json.parse(value);
                if (parsed instanceof String string && !string.isBlank()) return string;
            } catch (org.fuin.porelo.wire.JsonException ex) {
                throw malformed("a quoted string", "invalid JSON quoting");
            }
            throw malformed("a nonblank string", "another value");
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length() > 2)
            return value.substring(1, value.length() - 1).replace("''", "'");
        if (value.isBlank()
                || value.matches(".*[\\[\\]{}&*!|>\\\"'].*")
                || Set.of("null", "~", "true", "false").contains(value))
            throw malformed("a plain or quoted nonblank string", "an unsupported scalar");
        return value;
    }

    private static String quote(String value) {
        return org.fuin.porelo.wire.Json.write(value);
    }

    private static List<String> split(String text) {
        final List<String> parts = new ArrayList<>();
        char quote = 0;
        int brackets = 0;
        int start = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            final char ch = text.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quote == '"' && ch == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') quote = ch;
            else if (ch == '[') brackets++;
            else if (ch == ']') brackets--;
            else if (ch == ',' && brackets == 0) {
                parts.add(text.substring(start, index).strip());
                start = index + 1;
            }
        }
        if (quote != 0 || brackets != 0)
            throw malformed("balanced YAML strings and sequences", "unbalanced delimiters");
        parts.add(text.substring(start).strip());
        return parts;
    }

    private static String uncomment(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            final char ch = line.charAt(index);
            if (ch == '\t') throw malformed("space indentation", "a tab");
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && quote == '"') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '#' && (index == 0 || Character.isWhitespace(line.charAt(index - 1))))
                return line.substring(0, index);
        }
        return line;
    }

    private static org.fuin.porelo.planning.api.PlanningRefused malformed(
            String asked, String got) {
        return Refusals.failed("Expected workflow.yml " + asked + "; got " + got);
    }
}
