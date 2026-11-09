package org.fuin.porelo.planning.storiesgit;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.fuin.porelo.planning.api.PlanningRefused;

record Settings(
        String repository,
        String storiesDirectory,
        String issueRepositoryPattern,
        String linkPattern) {
    static Settings read(Map<String, Object> values, String token) {
        final Object kind = values.getOrDefault("credentialKind", "token");
        if (!(kind instanceof String))
            throw Refusals.invalid("Expected credentialKind as a string; got another JSON type");
        if (!kind.equals("token") && !kind.equals("github-app"))
            throw PlanningRefused.notSupported("credentialKind");
        final String repository = text(values, "repository");
        final String stories =
                values.containsKey("storiesDirectory")
                        ? text(values, "storiesDirectory")
                        : "stories";
        final String clones = text(values, "issueRepositoryPattern");
        final String links = text(values, "linkPattern");
        for (final String value : new String[] {repository, stories, clones, links}) {
            if (!token.isEmpty() && value.contains(token))
                throw Refusals.invalid(
                        "Expected credential-free settings; got a credential in settings");
        }
        try {
            SafeFiles.relative(stories);
        } catch (PlanningRefused refused) {
            throw Refusals.invalid(
                    "Expected storiesDirectory as a contained relative path; got an unsafe path");
        }
        url(repository, "repository");
        if (!clones.contains("{repository}"))
            throw Refusals.invalid(
                    "Expected issueRepositoryPattern containing {repository}; got no placeholder");
        url(clones.replace("{repository}", "example"), "issueRepositoryPattern");
        if (!links.contains("{repository}") || !links.contains("{path}"))
            throw Refusals.invalid(
                    "Expected linkPattern containing {repository} and {path}; got missing"
                            + " placeholders");
        url(
                links.replace("{repository}", "example").replace("{path}", "example.md"),
                "linkPattern");
        final Settings settings = new Settings(repository, stories, clones, links);
        try {
            settings.planningKey();
        } catch (PlanningRefused refused) {
            throw Refusals.invalid(
                    "Expected repository URL ending in a simple repository key; got an unsafe"
                            + " name");
        }
        return settings;
    }

    private static String text(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String value) || value.isBlank())
            throw Refusals.invalid(
                    "Expected nonblank string setting "
                            + key
                            + "; got missing, blank or another JSON type");
        return value;
    }

    private static void url(String value, String key) {
        try {
            final URI uri = new URI(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPath() == null
                    || uri.getPath().isBlank()) throw new URISyntaxException("", "Unsupported URL");
        } catch (URISyntaxException ex) {
            throw Refusals.invalid(
                    "Expected "
                            + key
                            + " as an HTTP(S) URL without credentials, query or fragment; got an"
                            + " invalid URL");
        }
    }

    String planningKey() {
        final String path = URI.create(repository).getPath().replaceFirst("/+$", "");
        return repositoryKey(path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", ""));
    }

    String cloneUrl(String key) {
        return issueRepositoryPattern.replace("{repository}", repositoryKey(key));
    }

    String link(String key, String path) {
        return linkPattern.replace("{repository}", repositoryKey(key)).replace("{path}", path);
    }

    static String repositoryKey(String key) {
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || key.contains(".."))
            throw Refusals.failed("Expected a simple repository key; got " + key);
        return key;
    }

    static String id(String id) {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw Refusals.failed("Expected a simple story or issue id; got " + id);
        return id;
    }
}
