package org.fuin.porelo.planning.contract;

import java.nio.file.Path;

class FilePlanningContractTest extends PlanningContract {
    FilePlanningContractTest() {
        super(ReferencePlanning.class);
    }

    @Override
    protected Fixture fixture(Path root) {
        return new FileFixture();
    }
}
