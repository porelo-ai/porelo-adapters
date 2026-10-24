package org.fuin.porelo.build.github;

import java.nio.file.Path;
import org.fuin.porelo.build.contract.AdapterContract;

/** Exercises GitHub's adapter as an installed executable, including its token boundaries. */
class GitHubContractTest extends AdapterContract {

    GitHubContractTest() {
        super(GitHubReader.class, "github", Path.of("src/test/resources/recordings"));
    }
}
