package com.sickworm.intellij.jugg.bootstrap;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Loads exactly the ordered standalone runtime snapshot selected by its active manifest. */
public final class StandaloneBootstrap {
    private static final Gson GSON = new Gson();

    private StandaloneBootstrap() {}

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("jugg.root.dir", new File(System.getProperty("user.home"), ".jugg").getPath()));
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
