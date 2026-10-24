package org.fuin.porelo.build.contract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Writes an owner-only executable that starts an adapter's main on the test class path. */
public final class JavaExecutable {

    private JavaExecutable() {}

    /**
     * Writes a launcher, quoting each shell argument independently.
     *
     * @param directory An existing scratch directory belonging to this test.
     * @param main The adapter's main class.
     * @return The executable's absolute path.
     * @throws IOException When the launcher cannot be written.
     */
    public static Path write(final Path directory, final Class<?> main) throws IOException {
        final Path executable = directory.resolve("adapter").toAbsolutePath();
        Files.createFile(
                executable,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.writeString(
                executable,
                "#!/bin/sh\nexec "
                        + quote(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                        + " -cp "
                        + quote(System.getProperty("java.class.path"))
                        + " "
                        + quote(main.getName())
                        + " \"$@\"\n");
        return executable;
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
