package org.fuin.porelo.build.github.review;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A path inside a repository as the profile's repository-relative URI: no scheme, authority,
 * absolute path, backslash, drive prefix, query, fragment, empty or dot component, and every byte
 * outside the unreserved set percent-encoded, so decoding it once gives the path back.
 */
public final class RepositoryPath {

    /** At most this many UTF-8 bytes decoded. */
    public static final int DECODED_LIMIT = 1024;

    /** At most this many ASCII bytes encoded. */
    public static final int ENCODED_LIMIT = 3072;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private RepositoryPath() {}

    /**
     * Encodes a path a forge gave.
     *
     * @param path The path, components separated by {@code /}.
     * @return Its URI.
     * @throws IllegalArgumentException If the profile refuses it.
     */
    public static String uri(String path) {
        check(path);
        final StringBuilder out = new StringBuilder();
        for (final byte b : path.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 0xFF;
            if (c == '/' || unreserved(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        if (out.length() > ENCODED_LIMIT) {
            throw new IllegalArgumentException("A path over " + ENCODED_LIMIT + " encoded bytes");
        }
        return out.toString();
    }

    /**
     * Decodes a document's URI to the path it names, refusing what the profile refuses.
     *
     * @param uri The URI.
     * @return The path.
     * @throws IllegalArgumentException If the profile refuses it.
     */
    public static String path(String uri) {
        if (uri.length() > ENCODED_LIMIT) {
            throw new IllegalArgumentException("A URI over " + ENCODED_LIMIT + " bytes");
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < uri.length(); i++) {
            final char c = uri.charAt(i);
            if (c == '%') {
                if (i + 2 >= uri.length()) {
                    throw new IllegalArgumentException("A truncated percent escape");
                }
                final int value = Integer.parseInt(uri.substring(i + 1, i + 3), 16);
                if (value == '/' || value == '\\' || value == '%') {
                    throw new IllegalArgumentException("An encoded separator or percent");
                }
                bytes.write(value);
                i += 2;
            } else if (c == '/' || (c < 0x80 && unreserved(c))) {
                bytes.write(c);
            } else {
                throw new IllegalArgumentException("A character a URI may not hold unencoded");
            }
        }
        final String path = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        check(path);
        if (!uri(path).equals(uri)) {
            throw new IllegalArgumentException("Not the one encoding of its path");
        }
        return path;
    }

    private static void check(String path) {
        if (path.isEmpty()
                || path.getBytes(StandardCharsets.UTF_8).length > DECODED_LIMIT
                || path.startsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf('�') >= 0
                || (path.length() > 1 && path.charAt(1) == ':')
                || ReviewText.refusedCharacter(path) != null
                || path.indexOf('\n') >= 0
                || path.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("A path the profile refuses");
        }
        for (final String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("An empty or dot component");
            }
        }
    }

    private static boolean unreserved(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '.'
                || c == '_'
                || c == '~';
    }
}
