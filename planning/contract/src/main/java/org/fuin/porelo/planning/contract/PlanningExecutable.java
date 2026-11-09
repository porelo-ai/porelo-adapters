package org.fuin.porelo.planning.contract;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.fuin.porelo.wire.Json;

final class PlanningExecutable {
    private PlanningExecutable() {}

    static Path write(
            Path root, Class<?> main, Path home, Path temporary, Map<String, String> properties)
            throws Exception {
        final Path working = Files.createDirectory(root.resolve("working"));
        final Path executable = root.resolve("adapter");
        final String classpath =
                Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                        .map(p -> Path.of(p).toAbsolutePath().toString())
                        .collect(Collectors.joining(File.pathSeparator));
        final StringBuilder command =
                new StringBuilder("#!/bin/sh\ncd ")
                        .append(quote(working.toString()))
                        .append(" || exit 1\nexec /usr/bin/env -i PATH=/usr/bin:/bin LANG=C.UTF-8 ")
                        .append(quote("HOME=" + home))
                        .append(' ')
                        .append(quote("TMPDIR=" + temporary))
                        .append(' ')
                        .append(quote("XDG_CONFIG_HOME=" + home.resolve(".config")))
                        .append(' ')
                        .append(quote("XDG_DATA_HOME=" + temporary.resolve("data")))
                        .append(' ')
                        .append(quote("XDG_STATE_HOME=" + temporary.resolve("state")))
                        .append(' ')
                        .append(quote("XDG_CACHE_HOME=" + temporary.resolve("cache")))
                        .append(' ')
                        .append(quote("XDG_RUNTIME_DIR=" + temporary))
                        .append(' ')
                        .append(quote("PORELO_TEST_RUNTIME=" + root.resolve("runtime")))
                        .append(' ')
                        .append(
                                quote(
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString()))
                        .append(" -XX:-UsePerfData")
                        .append(' ')
                        .append(quote("-Duser.home=" + home))
                        .append(' ')
                        .append(quote("-Djava.io.tmpdir=" + temporary));
        properties.forEach(
                (name, value) -> command.append(' ').append(quote("-D" + name + "=" + value)));
        command.append(" -cp ")
                .append(quote(classpath))
                .append(' ')
                .append(AdapterProcess.class.getName())
                .append(' ')
                .append(quote(root.resolve("output").toString()))
                .append(' ')
                .append(quote(root.resolve("pid").toString()))
                .append(' ')
                .append(quote(main.getName()))
                .append(" \"$@\" 2>>")
                .append(quote(root.resolve("output").toString()))
                .append("\n");
        Files.writeString(executable, command.toString());
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        final Path description = root.resolve("description");
        final Process process =
                new ProcessBuilder(executable.toString(), "describe")
                        .redirectOutput(description.toFile())
                        .redirectError(root.resolve("describe-error").toFile())
                        .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS))
                throw new AssertionError("Expected describe within 10 seconds; got a timeout");
            if (process.exitValue() != 0 || Files.size(description) > 65536)
                throw new AssertionError(
                        "Expected describe exit 0 and at most 64 KiB; got exit "
                                + process.exitValue()
                                + " and "
                                + Files.size(description)
                                + " bytes");
            final Object decoded = Json.parse(Files.readString(description));
            if (!(decoded instanceof Map<?, ?> map)
                    || !(map.get("name") instanceof String name)
                    || !name.matches("[a-z][a-z0-9-]{0,62}"))
                throw new AssertionError(
                        "Expected a valid describe name; got an invalid description");
            return Files.move(executable, root.resolve(name));
        } finally {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS))
                throw new AssertionError("Expected describe process to end; got a live process");
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
