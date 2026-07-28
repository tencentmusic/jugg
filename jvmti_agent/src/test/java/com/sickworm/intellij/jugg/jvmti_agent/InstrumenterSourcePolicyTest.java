package com.sickworm.intellij.jugg.jvmti_agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public class InstrumenterSourcePolicyTest {

    @Test
    public void hookTransformsShouldNotFailWholeAgent() throws Exception {
        String source = read("src/main/cpp/instrumenter.cc");

        assertFalse(source.contains("success &= ApplyTransforms"));
        assertTrue(source.contains("ApplyTransforms(jvmti, jni, kNoCache"));
    }

    @Test
    public void optionalTransformLogsShouldOnlyPrintResults() throws Exception {
        String source = read("src/main/cpp/instrumenter.cc");

        assertFalse(source.contains("Apply optional hook transform start"));
        assertFalse(source.contains("Optional hook transform retransform start"));
        assertTrue(source.contains("Optional hook transform class not found"));
        assertTrue(source.contains("Optional hook transform retransform failed"));
        assertTrue(source.contains("Optional hook transform retransform success"));
    }

    @Test
    public void webViewProviderHookShouldNotBeInstalled() throws Exception {
        String source = read("src/main/cpp/instrumenter.cc");

        assertFalse(source.contains("android/webkit/WebViewFactory"));
        assertFalse(source.contains("webViewFactoryGetProviderEnter"));
    }

    @Test
    public void compatModeShouldNotBeCachedBeforeHotfixLoaderInitialization() throws Exception {
        String source = read("src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java");

        assertTrue(source.contains("if (HotfixLoader.overlayFilesDir == null)"));
    }

    @Test
    public void newAssetManagerExitShouldSkipOverlayFixInCompatMode() throws Exception {
        String source = read("src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java");
        int methodStart = source.indexOf("public static AssetManager createAssetManagerNewExit");
        String method = source.substring(
                methodStart,
                source.indexOf("private static boolean isNeedFixThisAssetManager", methodStart));

        assertTrue(method.contains("if (isEnableHotfix())"));
        assertTrue(method.contains("return assetManager;"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
