package org.fuin.porelo.planning.storiesgit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planning.api.Story;
import org.fuin.porelo.planning.api.Workflow;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;

record StoryFile(Story story, String priority, List<Reference> issues) {
    record Reference(String repository, String id) {
        static Reference parse(String value) {
            final String[] parts = value.split("#", -1);
            if (parts.length != 2)
                throw Refusals.failed("Expected repository#issue reference; got " + value);
            return new Reference(Settings.repositoryKey(parts[0]), Settings.id(parts[1]));
        }
    }

    static StoryFile parse(String markdown, String link, Workflow workflow) {
        final String section = Markdown.section(markdown, "Data");
        final List<String> lines = section.lines().toList();
        final StringBuilder json = new StringBuilder();
        boolean inside = false;
        boolean complete = false;
        for (final String line : lines) {
            if (line.equals("```json") && !inside) inside = true;
            else if (inside && line.equals("```")) {
                complete = true;
                break;
            } else if (inside) json.append(line).append('\n');
        }
        if (!complete)
            throw Refusals.failed(
                    "Expected a complete Data JSON block in story; got no complete block");
        final Map<?, ?> data;
        try {
            if (!(Json.parse(json.toString()) instanceof Map<?, ?> object))
                throw Refusals.failed("Expected a story Data JSON object; got another JSON type");
            data = object;
        } catch (JsonException ex) {
            throw Refusals.failed("Expected valid story Data JSON; got malformed JSON");
        }
        final String id = Settings.id(text(data, "id"));
        final String state = text(data, "state");
        final Story story =
                new Story(
                        id,
                        text(data, "title"),
                        state,
                        link,
                        description(text(data, "gain"), text(data, "test")));
        if (!(data.get("issues") instanceof List<?> references))
            throw Refusals.failed(
                    "Expected issues array in story " + id + "; got missing or another JSON type");
        final List<Reference> issues = new ArrayList<>();
        for (final Object reference : references) {
            if (!(reference instanceof String value))
                throw Refusals.failed(
                        "Expected string issue reference in story "
                                + id
                                + "; got another JSON type");
            final Reference parsed = Reference.parse(value);
            if (issues.contains(parsed))
                throw Refusals.failed(
                        "Expected unique issue references in story "
                                + id
                                + "; got "
                                + value
                                + " twice");
            issues.add(parsed);
        }
        checkState(workflow, id, state, !issues.isEmpty());
        return new StoryFile(story, text(data, "priority"), List.copyOf(issues));
    }

    static String description(String gain, String test) {
        if (test.isEmpty()) return gain;
        if (gain.isEmpty()) return test;
        return gain + "\n\n" + test;
    }

    static void checkState(Workflow workflow, String id, String state, boolean allowEmpty) {
        if (allowEmpty && state.isEmpty()) return;
        if (workflow.states().stream().noneMatch(s -> s.name().equals(state)))
            throw Refusals.failed(
                    "Expected a workflow state for " + id + "; got unknown state " + state);
    }

    private static String text(Map<?, ?> object, String field) {
        if (!(object.get(field) instanceof String value))
            throw Refusals.failed(
                    "Expected story field "
                            + field
                            + " as a string; got missing or another JSON type");
        return value;
    }
}
