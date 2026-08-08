package com.sickworm.intellij.jugg.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class StandaloneBootstrapTest {
    @Test
    public void rollbackDoesNotLoadActiveRuntime() throws Exception {
        Path root = Files.createTempDirectory("jugg-bootstrap-rollback");
        Path hotUpdate = Files.createDirectories(root.resolve("hot_update"));
        Files.writeString(hotUpdate.resolve("standalone_load_manifest.json"), manifest("broken-build"));
        Files.writeString(hotUpdate.resolve("standalone_previous_load_manifest.json"), manifest("good-build"));
        String previousRoot = System.getProperty("jugg.root.dir");
        try {
            System.setProperty("jugg.root.dir", root.toString());
            StandaloneBootstrap.main(new String[] {"--rollback"});
        } finally {
            if (previousRoot == null) System.clearProperty("jugg.root.dir");
            else System.setProperty("jugg.root.dir", previousRoot);
        }
        assertTrue(Files.readString(hotUpdate.resolve("standalone_load_manifest.json")).contains("good-build"));
    }

    private String manifest(String buildId) {
        return "{\"releaseBuildId\":\"" + buildId + "\",\"jarFileNames\":[\"runtime.jar\"]}";
    }
}
