package org.fuin.porelo.build.forgejo;

import java.nio.file.Path;
import org.fuin.porelo.build.contract.AdapterContract;

/** Exercises Forgejo through the installed Build2 protocol. */
class ForgejoContractTest extends AdapterContract {
    ForgejoContractTest() {
        super(ForgejoReader.class, "forgejo", Path.of("src/test/resources/recordings"), "token");
    }
}
