package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.wire.Json;

final class FileBoard {
    private FileBoard() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path directory) throws IOException {
        final Object parsed = Json.parse(Files.readString(directory.resolve("board.json")));
        if (!(parsed instanceof Map<?, ?>))
            throw new IOException("Expected a board object; got another JSON value");
        return (Map<String, Object>) parsed;
    }

    static void write(Path directory, Map<String, Object> document) throws IOException {
        document.put("revision", UUID.randomUUID().toString());
        final Path pending = Files.createTempFile(directory, "board-", ".json");
        try {
            Files.writeString(pending, Json.write(document));
            Files.move(
                    pending,
                    directory.resolve("board.json"),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> stories(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("stories");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> issues(Map<String, Object> document, String story) {
        return (List<Map<String, Object>>) ((Map<?, ?>) document.get("issues")).get(story);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> issues(Map<String, Object> document) {
        final List<Map<String, Object>> result = new ArrayList<>();
        ((Map<?, ?>) document.get("issues"))
                .values()
                .forEach(entries -> result.addAll((List<Map<String, Object>>) entries));
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> agents(Map<String, Object> issue) {
        return (List<Map<String, Object>>) issue.get("subAgents");
    }
}
