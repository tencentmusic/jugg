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

    @Test
    public void assetManagerExitShouldSkipOverlayFixInCompatMode() throws Exception {
        String source = read("src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java");
        int methodStart = source.indexOf("public static AssetManager createAssetManagerExit");
        String method = source.substring(
                methodStart,
                source.indexOf("private static final ThreadLocal<Boolean>", methodStart));

        assertTrue(method.contains("if (isEnableHotfix())"));
        assertTrue(method.contains("return assetManager;"));
    }

    @Test
    public void flutterEngineAssetManagerShouldComeFromThePackageContextHook() throws Exception {
        String instrumenter = read("src/main/cpp/instrumenter.cc");
        String hooks = read("src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java");

        // FlutterEngine captures the AssetManager of the package context it creates, and Flutter is
        // an app class that the startup agent cannot hook, so the overlay is added at this boundary.
        assertTrue(instrumenter.contains("\"android/app/ContextImpl\""));
        assertTrue(instrumenter.contains("\"createPackageContext\""));
        assertTrue(instrumenter.contains("\"(Ljava/lang/String;I)Landroid/content/Context;\""));
        assertTrue(instrumenter.contains("\"handleCreatePackageContextExit\""));
        assertTrue(instrumenter.contains("&contextImpl"));
        assertFalse(instrumenter.contains("io/flutter/embedding/engine/FlutterJNI"));
        assertTrue(hooks.contains("public static Context handleCreatePackageContextExit(Context context)"));
    }

    @Test
    public void packageContextHookShouldSkipFlutterRefreshInCompatMode() throws Exception {
        String source = read("src/main/java/com/sickworm/intellij/jugg/instrument/InstrumentationHooks.java");
        int methodStart = source.indexOf("public static Context handleCreatePackageContextExit");
        String method = source.substring(
                methodStart,
                source.indexOf("public static void sendMessageEnter", methodStart));

        assertTrue(method.contains("isEnableHotfix()"));
        assertTrue(method.indexOf("isEnableHotfix()")
                < method.indexOf("FlutterAssetRefresh.prepareHostPackageContext(context)"));
        assertTrue(method.indexOf("FlutterAssetRefresh.shouldPrepareHostPackageContext(context)")
                < method.indexOf("FlutterAssetRefresh.prepareHostPackageContext(context)"));
    }

    @Test
    public void setupScriptShouldNotCreateLegacyDexPathFixFlag() throws Exception {
        String source = read("src/main/script/jugg_agent_setup.sh");

        assertFalse(source.contains(".need_fix_dex_path_list"));
        assertFalse(source.contains("compareVersion"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
