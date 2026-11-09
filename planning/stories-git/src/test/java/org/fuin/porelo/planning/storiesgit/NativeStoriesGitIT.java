package org.fuin.porelo.planning.storiesgit;

import java.nio.file.Path;
import java.util.Map;

class NativeStoriesGitIT extends StoriesGitContract {
    NativeStoriesGitIT() {
        super(NativePlanningLauncher.class);
    }

    @Override
    protected Map<String, String> adapterProperties() {
        return Map.of(
                "stories.git.executable",
                Path.of("target/porelo-planning-stories-git").toAbsolutePath().toString());
    }
}
