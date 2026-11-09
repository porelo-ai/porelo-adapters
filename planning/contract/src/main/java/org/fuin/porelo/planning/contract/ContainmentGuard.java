package org.fuin.porelo.planning.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Watches outside the adapter directory so a write followed by an unlink cannot hide traversal. */
final class ContainmentGuard implements AutoCloseable {
    private final Path root;
    private final Path working;
    private final WatchService writes;
    private final Map<Path, byte[]> originals = new LinkedHashMap<>();
    private final Set<String> owned;

    ContainmentGuard(ContractSession session, boolean issue) throws Exception {
        root = session.root;
        working = root.resolve("working");
        for (final Path directory : List.of(root, working)) {
            for (final String name : List.of("canary", "canary.md")) {
                final Path decoy = directory.resolve(name);
                session.fixture.decoy(decoy, issue, session.board);
                originals.put(decoy, Files.readAllBytes(decoy));
            }
        }
        try (var paths = Files.list(root)) {
            owned =
                    paths.map(p -> p.getFileName().toString())
                            .collect(java.util.stream.Collectors.toSet());
        }
        writes = root.getFileSystem().newWatchService();
        for (final Path directory : List.of(root, working))
            directory.register(
                    writes,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
    }

    void verify() throws Exception {
        final List<String> unexpected = new ArrayList<>();
        var key = writes.poll();
        while (key != null) {
            for (final var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW)
                    unexpected.add("write watcher overflow");
                else {
                    final Path directory = (Path) key.watchable();
                    final Path path = directory.resolve((Path) event.context());
                    if (directory.equals(working)
                            || originals.containsKey(path)
                            || !owned.contains(path.getFileName().toString()))
                        unexpected.add("write outside adapter directory");
                }
            }
            assertThat(key.reset()).as("containment write watcher stays active").isTrue();
            key = writes.poll();
        }
        assertThat(unexpected)
                .as("unsafe ids create or modify no files outside the adapter directory")
                .isEmpty();
        for (final var entry : originals.entrySet())
            assertThat(
                            java.util.Arrays.equals(
                                    Files.readAllBytes(entry.getKey()), entry.getValue()))
                    .as("readable traversal decoys remain unchanged")
                    .isTrue();
    }

    @Override
    public void close() throws Exception {
        writes.close();
    }
}
