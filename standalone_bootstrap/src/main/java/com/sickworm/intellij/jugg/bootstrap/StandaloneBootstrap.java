package com.sickworm.intellij.jugg.bootstrap;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Loads exactly the ordered standalone runtime snapshot selected by its active manifest. */
public final class StandaloneBootstrap {
    private static final Gson GSON = new Gson();

    private StandaloneBootstrap() {}

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("jugg.root.dir", new File(System.getProperty("user.home"), ".jugg").getPath()));
        File hotUpdate = new File(root, "hot_update");
        if (args.length == 1 && "--rollback".equals(args[0])) {
            rollback(hotUpdate);
            return;
        }
        Manifest manifest = read(new File(hotUpdate, "standalone_load_manifest.json"), Manifest.class);
        if (manifest == null || manifest.jarFileNames == null || manifest.jarFileNames.isEmpty()) {
            throw new IllegalStateException("Jugg standalone is not installed");
        }
        if (args.length == 1 && "--verify".equals(args[0])) {
            verify(new File(hotUpdate, "jars"), manifest);
            System.out.println("Jugg standalone verified: " + manifest.releaseBuildId);
            return;
        }
        try {
            launch(new File(hotUpdate, "jars"), manifest, args);
        } catch (Throwable error) {
            ActivationState state = read(new File(hotUpdate, "standalone_activation_state.json"), ActivationState.class);
            if (state != null && manifest.releaseBuildId.equals(state.lastKnownGoodBuildId)) throw rethrow(error);
            if (state != null && manifest.releaseBuildId.equals(state.failedBuildId)) throw rethrow(error);
            write(new File(hotUpdate, "standalone_activation_state.json"), new ActivationState(
                    state == null ? null : state.lastKnownGoodBuildId, manifest.releaseBuildId));
            rollback(hotUpdate);
            Manifest previous = read(new File(hotUpdate, "standalone_load_manifest.json"), Manifest.class);
            launch(new File(hotUpdate, "jars"), previous, args);
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

    private static void rollback(File hotUpdateDir) throws Exception {
        File active = new File(hotUpdateDir, "standalone_load_manifest.json");
        File previous = new File(hotUpdateDir, "standalone_previous_load_manifest.json");
        Manifest previousManifest = read(previous, Manifest.class);
        if (previousManifest == null || previousManifest.jarFileNames == null || previousManifest.jarFileNames.isEmpty()) {
            throw new IllegalStateException("No previous standalone runtime is available");
        }
        File temp = new File(hotUpdateDir, active.getName() + ".rollback.tmp");
        Files.copy(previous.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        replaceAtomically(temp, active);
        System.out.println("Jugg standalone rolled back to " + previousManifest.releaseBuildId);
    }

    private static <T> T read(File file, Class<T> type) throws Exception {
        if (!file.isFile()) return null;
        return GSON.fromJson(Files.readString(file.toPath()), type);
    }

    private static void write(File file, Object value) throws Exception {
        file.getParentFile().mkdirs();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(temp.toPath(), GSON.toJson(value));
        replaceAtomically(temp, file);
    }

    private static void replaceAtomically(File source, File target) throws Exception {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Exception rethrow(Throwable error) {
        return error instanceof Exception ? (Exception) error : new RuntimeException(error);
    }

    private static final class Manifest {
        String releaseBuildId;
        List<String> jarFileNames;
        Map<String, String> jarSha256;
    }

    private static final class ActivationState {
        String lastKnownGoodBuildId;
        String failedBuildId;

        ActivationState(String lastKnownGoodBuildId, String failedBuildId) {
            this.lastKnownGoodBuildId = lastKnownGoodBuildId;
            this.failedBuildId = failedBuildId;
        }
    }
}
