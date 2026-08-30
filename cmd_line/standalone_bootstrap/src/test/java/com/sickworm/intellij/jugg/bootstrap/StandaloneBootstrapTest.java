package com.sickworm.intellij.jugg.bootstrap;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void stopProjectOnlyStopsMatchingStandaloneDaemons() throws Exception {
        Path temp = Files.createTempDirectory("jugg-bootstrap-stop");
        Path root = Files.createDirectories(temp.resolve("root"));
        Path foreignRoot = Files.createDirectories(temp.resolve("foreign-root"));
        Path targetProject = Files.createDirectories(temp.resolve("target-project"));
        Path otherProject = Files.createDirectories(temp.resolve("other-project"));
        Path classes = compileFakeStandaloneBootstrap(temp);
        List<Process> processes = new ArrayList<>();
        try {
            Process splitArgument = startFakeStandaloneDaemon(classes, root, targetProject, false, temp.resolve("split.ready"));
            Process equalsArgument = startFakeStandaloneDaemon(classes, root, targetProject, true, temp.resolve("equals.ready"));
            Process otherProjectProcess = startFakeStandaloneDaemon(classes, root, otherProject, false, temp.resolve("other.ready"));
            Process foreignRootProcess = startFakeStandaloneDaemon(classes, foreignRoot, targetProject, false, temp.resolve("foreign.ready"));
            Process unrelatedProcess = startFakeUnrelatedProcess(classes, root, targetProject, temp.resolve("unrelated.ready"));
            processes.add(splitArgument);
            processes.add(equalsArgument);
            processes.add(otherProjectProcess);
            processes.add(foreignRootProcess);
            processes.add(unrelatedProcess);

            List<Long> stopped = StandaloneBootstrap.stopProjectDaemons(root.toFile(), targetProject.toFile(), 2_000L);

            assertEquals(
                    List.of(splitArgument.pid(), equalsArgument.pid()),
                    stopped.stream().sorted().collect(Collectors.toList())
            );
            assertTrue(splitArgument.waitFor(2, TimeUnit.SECONDS));
            assertTrue(equalsArgument.waitFor(2, TimeUnit.SECONDS));
            assertFalse(otherProjectProcess.waitFor(200, TimeUnit.MILLISECONDS));
            assertFalse(foreignRootProcess.waitFor(200, TimeUnit.MILLISECONDS));
            assertFalse(unrelatedProcess.waitFor(200, TimeUnit.MILLISECONDS));
        } finally {
            processes.forEach(Process::destroyForcibly);
        }
    }

    @Test
    public void stopProjectForcesDaemonThatBlocksNormalShutdown() throws Exception {
        Path temp = Files.createTempDirectory("jugg-bootstrap-force-stop");
        Path root = Files.createDirectories(temp.resolve("root"));
        Path project = Files.createDirectories(temp.resolve("project"));
        Path classes = compileFakeStandaloneBootstrap(temp);
        Path shutdownStarted = temp.resolve("shutdown.started");
        Process process = startFakeJavaProcess(
                classes,
                root,
                project,
                false,
                temp.resolve("blocking.ready"),
                "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap",
                shutdownStarted
        );
        try {
            boolean supportsNormalTermination = process.toHandle().supportsNormalTermination();
            assertEquals(List.of(process.pid()), StandaloneBootstrap.stopProjectDaemons(
                    root.toFile(), project.toFile(), 200L));
            assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            assertEquals("Unexpected shutdown hook behavior", supportsNormalTermination, shutdownStarted.toFile().isFile());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void stopProjectIsIdempotentWhenNoDaemonMatches() throws Exception {
        Path temp = Files.createTempDirectory("jugg-bootstrap-stop-empty");

        assertTrue(StandaloneBootstrap.stopProjectDaemons(
                temp.resolve("root").toFile(), temp.resolve("project").toFile(), 100L).isEmpty());
    }

    @Test
    public void stopProjectCommandDoesNotRequireAnInstalledRuntimeManifest() throws Exception {
        Path temp = Files.createTempDirectory("jugg-bootstrap-stop-without-manifest");
        String previousRoot = System.getProperty("jugg.root.dir");
        try {
            System.setProperty("jugg.root.dir", temp.resolve("root").toString());
            StandaloneBootstrap.main(new String[]{"--stop-project", temp.resolve("project").toString()});
        } finally {
            if (previousRoot == null) System.clearProperty("jugg.root.dir");
            else System.setProperty("jugg.root.dir", previousRoot);
        }
    }

    private Path compileFakeStandaloneBootstrap(Path temp) throws Exception {
        Path source = temp.resolve("src/com/sickworm/intellij/jugg/bootstrap/StandaloneBootstrap.java");
        Path unrelatedSource = temp.resolve("src/example/UnrelatedJavaProcess.java");
        Path classes = Files.createDirectories(temp.resolve("classes"));
        Files.createDirectories(source.getParent());
        Files.createDirectories(unrelatedSource.getParent());
        Files.writeString(source,
                "package com.sickworm.intellij.jugg.bootstrap;\n" +
                "public final class StandaloneBootstrap {\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty(\"fake.ready\")), \"ready\");\n" +
                "        String shutdownFile = System.getProperty(\"fake.shutdown\");\n" +
                "        if (shutdownFile != null) {\n" +
                "            Runtime.getRuntime().addShutdownHook(new Thread(() -> {\n" +
                "                try {\n" +
                "                    java.nio.file.Files.writeString(java.nio.file.Path.of(shutdownFile), \"started\");\n" +
                "                    Thread.sleep(Long.MAX_VALUE);\n" +
                "                } catch (Exception ignored) {}\n" +
                "            }));\n" +
                "        }\n" +
                "        Thread.sleep(Long.MAX_VALUE);\n" +
                "    }\n" +
                "}\n");
        Files.writeString(unrelatedSource,
                "package example;\n" +
                "public final class UnrelatedJavaProcess {\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty(\"fake.ready\")), \"ready\");\n" +
                "        Thread.sleep(Long.MAX_VALUE);\n" +
                "    }\n" +
                "}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString(), unrelatedSource.toString()));
        return classes;
    }

    private Process startFakeStandaloneDaemon(
            Path classes,
            Path root,
            Path project,
            boolean equalsArgument,
            Path readyFile
    ) throws Exception {
        return startFakeJavaProcess(
                classes,
                root,
                project,
                equalsArgument,
                readyFile,
                "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap",
                null
        );
    }

    private Process startFakeUnrelatedProcess(Path classes, Path root, Path project, Path readyFile) throws Exception {
        return startFakeJavaProcess(classes, root, project, false, readyFile, "example.UnrelatedJavaProcess", null);
    }

    private Process startFakeJavaProcess(
            Path classes,
            Path root,
            Path project,
            boolean equalsArgument,
            Path readyFile,
            String mainClass,
            Path shutdownFile
    ) throws Exception {
        File java = new File(System.getProperty("java.home"), "bin/java");
        List<String> command = new ArrayList<>(List.of(
                java.getPath(),
                "-Djugg.root.dir=" + root,
                "-Dfake.ready=" + readyFile,
                "-cp",
                classes.toString(),
                mainClass
        ));
        if (shutdownFile != null) command.add(3, "-Dfake.shutdown=" + shutdownFile);
        if (equalsArgument) {
            command.add("--project-dir=" + project);
        } else {
            command.add("--project-dir");
            command.add(project.toString());
        }
        Process process = new ProcessBuilder(command).start();
        for (int attempt = 0; attempt < 100 && !readyFile.toFile().isFile(); attempt++) {
            Thread.sleep(20L);
        }
        assertTrue("Fake standalone daemon did not start", readyFile.toFile().isFile());
        return process;
    }

    private String manifest(String buildId) {
        return "{\"releaseBuildId\":\"" + buildId + "\",\"jarFileNames\":[\"runtime.jar\"]}";
    }
}
