package com.sickworm.intellij.jugg.bootstrap;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Loads exactly the ordered standalone runtime snapshot selected by its active manifest. */
public final class StandaloneBootstrap {
    private static final Gson GSON = new Gson();
    private static final String MAIN_CLASS = "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap";
    private static final String PROJECT_DIR_ARGUMENT = "--project-dir";
    private static final String ROOT_ARGUMENT = "-Djugg.root.dir=";
    private static final long STOP_TIMEOUT_MILLIS = 5_000L;

    private StandaloneBootstrap() {}

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("jugg.root.dir", new File(System.getProperty("user.home"), ".jugg").getPath()));
        String stopProject = stopProjectArgument(args);
        if (stopProject != null) {
            File stopProjectDir = new File(stopProject).getCanonicalFile();
            List<Long> stopped = stopProjectDaemons(root, stopProjectDir, STOP_TIMEOUT_MILLIS);
            if (stopped.isEmpty()) {
                System.out.println("No Jugg standalone Runtime is running for " + stopProjectDir + ".");
            } else {
                System.out.println("Stopped Jugg standalone Runtime for " + stopProjectDir +
                        " (PID: " + stopped.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ").");
            }
            return;
        }
        File hotUpdate = new File(root, "hot_update");
        Manifest manifest = read(new File(hotUpdate, "standalone_load_manifest.json"), Manifest.class);
        if (manifest == null || manifest.jarFileNames == null || manifest.jarFileNames.isEmpty()) {
            throw new IllegalStateException("Jugg standalone is not installed");
        }
        if (args.length == 1 && "--verify".equals(args[0])) {
            verify(new File(hotUpdate, "jars"), manifest);
            System.out.println("Jugg standalone verified: " + manifest.releaseBuildId);
            return;
        }
        launch(new File(hotUpdate, "jars"), manifest, args);
    }

    /** Stops standalone daemon processes that were launched for the exact target project. */
    static List<Long> stopProjectDaemons(File root, File projectDir, long timeoutMillis) throws Exception {
        long currentPid = ProcessHandle.current().pid();
        String rootKey = pathKey(root);
        String projectKey = pathKey(projectDir);
        List<ProcessHandle> daemons;
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            daemons = processes
                    .filter(process -> process.pid() != currentPid)
                    .filter(process -> isStandaloneProjectDaemon(process, rootKey, projectKey))
                    .sorted(Comparator.comparingLong(ProcessHandle::pid))
                    .collect(Collectors.toList());
        }
        if (daemons.isEmpty()) return List.of();

        List<ProcessHandle> graceful = daemons.stream()
                .filter(ProcessHandle::supportsNormalTermination)
                .collect(Collectors.toList());
        graceful.forEach(ProcessHandle::destroy);
        waitForExit(graceful, timeoutMillis);
        List<ProcessHandle> survivors = daemons.stream().filter(ProcessHandle::isAlive).collect(Collectors.toList());
        survivors.forEach(ProcessHandle::destroyForcibly);
        waitForExit(survivors, timeoutMillis);
        List<Long> alive = survivors.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).collect(Collectors.toList());
        if (!alive.isEmpty()) throw new IllegalStateException("Failed to stop standalone Runtime processes: " + alive);
        return daemons.stream().map(ProcessHandle::pid).collect(Collectors.toList());
    }

    private static String stopProjectArgument(String[] args) {
        if (args.length == 2 && "--stop-project".equals(args[0]) && !args[1].isBlank()) return args[1];
        if (args.length == 1 && args[0].startsWith("--stop-project=")) {
            String projectDir = args[0].substring("--stop-project=".length());
            if (!projectDir.isBlank()) return projectDir;
        }
        for (String argument : args) {
            if ("--stop-project".equals(argument) || argument.startsWith("--stop-project=")) {
                throw new IllegalArgumentException("--stop-project requires exactly one project path");
            }
        }
        return null;
    }

    private static boolean isStandaloneProjectDaemon(ProcessHandle process, String rootKey, String projectKey) {
        String[] arguments = process.info().arguments().orElse(new String[0]);
        if (!contains(arguments, MAIN_CLASS) || !rootKey.equals(processRootKey(arguments))) return false;
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (PROJECT_DIR_ARGUMENT.equals(argument) && index + 1 < arguments.length && projectKey.equals(pathKey(arguments[index + 1]))) {
                return true;
            }
            if (argument.startsWith(PROJECT_DIR_ARGUMENT + "=") &&
                    projectKey.equals(pathKey(argument.substring((PROJECT_DIR_ARGUMENT + "=").length())))) {
                return true;
            }
        }
        return false;
    }

    private static String processRootKey(String[] arguments) {
        for (String argument : arguments) {
            if (argument.startsWith(ROOT_ARGUMENT)) return pathKey(argument.substring(ROOT_ARGUMENT.length()));
        }
        return pathKey(new File(System.getProperty("user.home"), ".jugg"));
    }

    private static boolean contains(String[] arguments, String expected) {
        for (String argument : arguments) {
            if (expected.equals(argument)) return true;
        }
        return false;
    }

    private static String pathKey(String path) {
        return pathKey(new File(path));
    }

    private static String pathKey(File path) {
        String canonical = canonicalPath(path);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("mac") || osName.contains("win") ? canonical.toLowerCase(Locale.ROOT) : canonical;
    }

    private static String canonicalPath(File path) {
        try {
            return path.getCanonicalPath();
        } catch (Exception ignored) {
            return path.getAbsolutePath();
        }
    }

    private static void waitForExit(List<ProcessHandle> processes, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }

    private static void launch(File storageDir, Manifest manifest, String[] args) throws Exception {
        URL[] urls = runtimeUrls(storageDir, manifest);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> entry = Class.forName("com.sickworm.intellij.jugg.cmdline.standalone.JuggDaemonKt", true, loader);
            Method main = entry.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) args);
            } catch (InvocationTargetException error) {
                throw rethrow(error.getCause());
            }
        }
    }

    private static void verify(File storageDir, Manifest manifest) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(runtimeUrls(storageDir, manifest), ClassLoader.getPlatformClassLoader())) {
            Class.forName("com.sickworm.intellij.jugg.cmdline.standalone.JuggDaemonKt", true, loader);
        }
    }

    private static URL[] runtimeUrls(File storageDir, Manifest manifest) throws Exception {
        URL[] urls = new URL[manifest.jarFileNames.size()];
        for (int i = 0; i < urls.length; i++) {
            File jar = new File(storageDir, manifest.jarFileNames.get(i));
            if (!jar.isFile()) throw new IllegalStateException("Standalone runtime JAR is missing: " + jar.getName());
            urls[i] = jar.toURI().toURL();
        }
        return urls;
    }

    private static <T> T read(File file, Class<T> type) throws Exception {
        if (!file.isFile()) return null;
        return GSON.fromJson(Files.readString(file.toPath()), type);
    }

    private static Exception rethrow(Throwable error) {
        return error instanceof Exception ? (Exception) error : new RuntimeException(error);
    }

    private static final class Manifest {
        String releaseBuildId;
        List<String> jarFileNames;
        Map<String, String> jarSha256;
    }

}
