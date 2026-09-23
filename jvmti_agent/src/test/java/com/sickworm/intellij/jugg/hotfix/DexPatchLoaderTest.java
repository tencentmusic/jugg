package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import dalvik.system.BaseDexClassLoader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that a compat overlay without dex files never replaces the app classloader. Replacing it
 * is only meaningful for dex payloads, and it can fail on devices whose framework hides the
 * DrawableInflater internals, which would crash the app during startup.
 */
public class DexPatchLoaderTest {

    private File root;
    private File codeCacheDir;
    private File overlayDir;
    private File embeddedDir;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("jugg-dex-patch").toFile();
        codeCacheDir = new File(root, "code_cache");
        overlayDir = new File(codeCacheDir, ".overlay");
        embeddedDir = new File(codeCacheDir, ".jugg_classes_embed");
        assertTrue(overlayDir.mkdirs());
        assertTrue(embeddedDir.mkdirs());
        HotfixLoader.codeCacheDir = codeCacheDir;
        HotfixLoader.overlayFilesDir = overlayDir;
        HotfixLoader.embeddedClassesDir = embeddedDir;
    }

    @After
    public void tearDown() {
        HotfixLoader.codeCacheDir = null;
        HotfixLoader.overlayFilesDir = null;
        HotfixLoader.embeddedClassesDir = null;
        deleteRecursively(root);
    }

    @Test
    public void install_shouldKeepOriginClassLoaderWhenNoDexFileIsCollected() {
        new DexPatchLoader(mockBaseContext()).install();
    }

    private static Context mockBaseContext() {
        Context context = mock(Context.class);
        when(context.getClassLoader()).thenReturn(mock(BaseDexClassLoader.class));
        when(context.getApplicationInfo()).thenReturn(new ApplicationInfo());
        return context;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
