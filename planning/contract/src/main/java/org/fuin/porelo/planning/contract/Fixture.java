package org.fuin.porelo.planning.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns a real store and its fault controls, without supplying any of the suite's answer rules. */
public interface Fixture extends AutoCloseable {
    /** Independently observable store controls; missing controls cause named JUnit assumptions. */
    enum Ability {
        /** Can commit a change independently of the running adapter. */
        changeBehindTheBack,
        /** Can reject a fresh token while accepting the original token. */
        refuseCredential,
        /** Can refuse publication without refusing preceding reads. */
        refusePush,
        /** Can hold store traffic until fixture shutdown. */
        stall,
        /** Can redirect authenticated traffic to another host. */
        redirect,
        /** Stores are real Git remotes with observable commit ids. */
        gitStore,
        /** Can select the credential kind through adapter settings. */
        credentialKind,
        /** Can replace the issue file with a symbolic link. */
        issueSymlink,
        /** Can remove a listed brief. */
        missingBrief,
        /** Can replace a listed brief with a symbolic link. */
        symlinkBrief,
        /** Can list a brief outside its issue's .sub directory. */
        outsideBrief,
        /** Can add an unlisted brief file beside listed briefs. */
        unlistedBrief
    }

    /** Path attacks that the fixture places in its store, never in the adapter's implementation. */
    enum Damage {
        /** A symbolic link in place of the issue file. */
        issueSymlink,
        /** A listed file absent from the store. */
        missingBrief,
        /** A listed brief that is a symbolic link. */
        symlinkBrief,
        /** A listed brief outside .sub. */
        outsideBrief,
        /** An extra file absent from the proposal list. */
        unlistedBrief
    }

    /** Seeds exactly this board; directory is the caller-created durable adapter directory. */
    void seed(Board board, Path directory) throws Exception;

    /** Returns the opaque, credential-free settings for this store. */
    Map<String, Object> settings();

    /** Returns the current call's accepted token, kept only in memory. */
    String token();

    /** Returns the independently available controls. */
    Set<Ability> abilities();

    /**
     * Returns authoritative store fingerprints, including every remote, without consulting the
     * adapter.
     */
    Map<String, String> snapshot() throws Exception;

    /** Changes one issue independently, preserving the adapter's last read cache. */
    default void changeIssue(String issue, String state) throws Exception {
        throw unavailable(Ability.changeBehindTheBack);
    }

    /** Changes one story independently, preserving the adapter's last read cache. */
    default void changeStory(String story, String state) throws Exception {
        throw unavailable(Ability.changeBehindTheBack);
    }

    /** Returns settings requesting the named credential kind, including an unsupported kind. */
    default Map<String, Object> settingsForCredentialKind(String kind) {
        throw unavailable(Ability.credentialKind);
    }

    /** Refuses pushes to the repository key, such as two, after allowing reads. */
    default void refusePush(String repository) throws Exception {
        throw unavailable(Ability.refusePush);
    }

    /** Refuses the second publication in a two-repository batch, regardless of push order. */
    default void refuseSecondPush() throws Exception {
        throw unavailable(Ability.refusePush);
    }

    /** Verifies that both repository publications reached the second-push failure control. */
    default void verifySecondPushRefused() throws Exception {
        throw unavailable(Ability.refusePush);
    }

    /** Stalls all remote traffic, leaving existing local read snapshots available. */
    default void stall() throws Exception {
        throw unavailable(Ability.stall);
    }

    /** Redirects remote traffic to a different host, recording all requests at that host. */
    default void redirect() throws Exception {
        throw unavailable(Ability.redirect);
    }

    /** Verifies that the redirect target was reached and received no Authorization header. */
    default void verifyRedirect() throws Exception {
        throw unavailable(Ability.redirect);
    }

    /** Returns an independently measured read/write counter, for refusals before store access. */
    default long accesses() throws Exception {
        throw unavailable(Ability.credentialKind);
    }

    /**
     * Introduces an unsafe file for I1 and returns the filename the refusal must identify. Symlink
     * controls write a valid readable document to outside before linking to it, so following a
     * dangling link cannot pass as containment. An outside brief must also exist.
     */
    default String damage(Damage damage, Path outside) throws Exception {
        throw new UnsupportedOperationException(
                "Expected fixture ability " + damage + "; got unsupported control");
    }

    /**
     * Returns observed child environments when the store can capture short-lived credential
     * helpers.
     */
    default List<Map<String, String>> childEnvironments() {
        return List.of();
    }

    /** Checks asynchronous fixture failures after the adapter has stopped. */
    default void verify() throws Exception {}

    /** Stops only processes and listeners owned by this fixture. */
    @Override
    default void close() throws IOException {}

    private static UnsupportedOperationException unavailable(Ability ability) {
        return new UnsupportedOperationException(
                "Expected fixture ability " + ability + "; got unsupported control");
    }
}
