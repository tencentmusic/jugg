package com.sickworm.intellij.jugg.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class StandaloneBootstrapTest {
    @Test
    public void startupFailureKeepsActiveManifest() throws Exception {
        Path root = Files.createTempDirectory("jugg-bootstrap-failure");
        Path hotUpdate = Files.createDirectories(root.resolve("hot_update"));
        Files.writeString(hotUpdate.resolve("standalone_load_manifest.json"), manifest("broken-build"));
        String previousRoot = System.getProperty("jugg.root.dir");
        try {
            System.setProperty("jugg.root.dir", root.toString());
            StandaloneBootstrap.main(new String[0]);
            fail("Expected startup failure");
        } catch (IllegalStateException expected) {
        } finally {
            if (previousRoot == null) System.clearProperty("jugg.root.dir");
            else System.setProperty("jugg.root.dir", previousRoot);
        }
        assertTrue(Files.readString(hotUpdate.resolve("standalone_load_manifest.json")).contains("broken-build"));
    }

    private String manifest(String buildId) {
        return "{\"releaseBuildId\":\"" + buildId + "\",\"jarFileNames\":[\"runtime.jar\"]}";
    }
}
