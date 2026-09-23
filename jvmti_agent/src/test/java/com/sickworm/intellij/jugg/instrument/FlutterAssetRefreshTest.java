package com.sickworm.intellij.jugg.instrument;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.AssetManager;
import android.widget.Toast;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Covers which Flutter engines FlutterAssetRefresh updates and how it reports the batch. */
public class FlutterAssetRefreshTest {

    private static final String BUNDLE_PATH = "flutter_assets";

    private final Context context = mock(Context.class);
    private final AssetManager overlayAssetManager = mock(AssetManager.class);
    private final AssetManager staleAssetManager = mock(AssetManager.class);

    @After
    public void tearDown() {
        FlutterEngine.resetForTest();
    }

    @Test
    public void noLiveEngineShouldRefreshNothing() throws Exception {
        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertEquals(Collections.emptyList(), refreshLiveEngines());
            toast.verifyNoInteractions();
        }
    }

    @Test
    public void engineRunningDartShouldBeUpdatedThroughFlutterJni() throws Exception {
        FlutterJNI flutterJNI = new FlutterJNI();
        FlutterEngine engine = new FlutterEngine(flutterJNI, staleAssetManager);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertEquals(Collections.emptyList(), refreshLiveEngines());
            toast.verifyNoInteractions();
        }

        assertEquals(1, flutterJNI.refreshedAssetManagers.size());
        assertSame(overlayAssetManager, flutterJNI.refreshedAssetManagers.get(0));
        assertEquals(BUNDLE_PATH, flutterJNI.refreshedBundlePaths.get(0));
        assertSame(staleAssetManager, engine.dartExecutor().assetManagerForTest());
    }

    @Test
    public void engineThatHasNotStartedDartShouldLaunchWithTheNewAssetManager() throws Exception {
        FlutterJNI flutterJNI = new FlutterJNI();
        FlutterEngine engine = new FlutterEngine(flutterJNI, staleAssetManager);
        engine.dartExecutor().setExecutingDartForTest(false);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertEquals(Collections.emptyList(), refreshLiveEngines());
            toast.verifyNoInteractions();
        }

        // The next Dart launch must hand the overlay AssetManager to native without a JNI update.
        assertSame(overlayAssetManager, engine.dartExecutor().assetManagerForTest());
        assertTrue(flutterJNI.refreshedAssetManagers.isEmpty());
    }

    @Test
    public void everyEngineOfTheBatchShouldBeHandledOnItsOwnPath() throws Exception {
        FlutterJNI running = new FlutterJNI();
        FlutterJNI waiting = new FlutterJNI();
        FlutterEngine runningEngine = new FlutterEngine(running, staleAssetManager);
        FlutterEngine waitingEngine = new FlutterEngine(waiting, staleAssetManager);
        waitingEngine.dartExecutor().setExecutingDartForTest(false);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertEquals(Collections.emptyList(), refreshLiveEngines());
            toast.verifyNoInteractions();
        }

        assertEquals(1, running.refreshedAssetManagers.size());
        assertSame(staleAssetManager, runningEngine.dartExecutor().assetManagerForTest());
        assertTrue(waiting.refreshedAssetManagers.isEmpty());
        assertSame(overlayAssetManager, waitingEngine.dartExecutor().assetManagerForTest());
    }

    @Test
    public void failingEngineShouldNotStopOtherEnginesAndShouldReportOnce() throws Exception {
        FlutterJNI failing = new FlutterJNI();
        failing.failure = new IllegalStateException("engine is gone");
        FlutterJNI healthy = new FlutterJNI();
        new FlutterEngine(failing, staleAssetManager);
        new FlutterEngine(healthy, staleAssetManager);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            Toast toasted = mock(Toast.class);
            toast.when(() -> Toast.makeText(eq(context), anyString(), anyInt())).thenReturn(toasted);

            List<String> failures = refreshLiveEngines();

            assertEquals(1, failures.size());
            assertTrue(failures.get(0), failures.get(0).contains("engine is gone"));
            assertEquals(1, healthy.refreshedAssetManagers.size());
            toast.verify(() -> Toast.makeText(eq(context), anyString(), anyInt()), times(1));
            verify(toasted, times(1)).show();
        }
    }

    @Test
    public void engineWithoutNativeShellShouldBeReportedAsFailure() throws Exception {
        FlutterJNI unattached = new FlutterJNI();
        unattached.setAttachedForTest(false);
        FlutterJNI healthy = new FlutterJNI();
        new FlutterEngine(unattached, staleAssetManager);
        new FlutterEngine(healthy, staleAssetManager);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            Toast toasted = mock(Toast.class);
            toast.when(() -> Toast.makeText(eq(context), anyString(), anyInt())).thenReturn(toasted);

            List<String> failures = refreshLiveEngines();

            assertEquals(1, failures.size());
            assertTrue(failures.get(0), failures.get(0).contains("no native shell"));
            assertEquals(1, healthy.refreshedAssetManagers.size());
            toast.verify(() -> Toast.makeText(eq(context), anyString(), anyInt()), times(1));
        }
    }

    @Test
    public void batchShouldWorkOnOneEngineSnapshot() throws Exception {
        FlutterJNI first = new FlutterJNI();
        FlutterJNI late = new FlutterJNI();
        FlutterEngine[] lateEngine = new FlutterEngine[1];
        new FlutterEngine(first, staleAssetManager);
        // An engine created while the batch runs is not part of the snapshot this batch works on.
        first.onRefresh = () -> lateEngine[0] = new FlutterEngine(late, staleAssetManager);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertEquals(Collections.emptyList(), refreshLiveEngines());
            toast.verifyNoInteractions();
        }

        assertNotNull(lateEngine[0]);
        assertTrue(late.refreshedAssetManagers.isEmpty());
        assertSame(staleAssetManager, lateEngine[0].dartExecutor().assetManagerForTest());
    }

    @Test
    public void classLoaderWithoutFlutterShouldRefreshNothing() throws Exception {
        FlutterJNI flutterJNI = new FlutterJNI();
        new FlutterEngine(flutterJNI, staleAssetManager);

        try (MockedStatic<Toast> toast = mockStatic(Toast.class);
                URLClassLoader withoutFlutter = new URLClassLoader(new URL[0], null)) {
            assertNull(FlutterAssetRefresh.findEngineClass(withoutFlutter));
            toast.verifyNoInteractions();
        }
        assertTrue(flutterJNI.refreshedAssetManagers.isEmpty());
    }

    @Test
    public void nonFlutterApplicationShouldNotReachPackageContextResources() throws Exception {
        Context packageContext = mock(Context.class);
        Context applicationContext = mock(Context.class);
        try (URLClassLoader withoutFlutter = new URLClassLoader(new URL[0], null)) {
            when(applicationContext.getClassLoader()).thenReturn(withoutFlutter);
            try (MockedStatic<ReflectUtil> reflectUtil = currentApplication(applicationContext)) {
                assertFalse(FlutterAssetRefresh.shouldPrepareHostPackageContext(packageContext));

                verify(applicationContext).getClassLoader();
                verifyNoMoreInteractions(packageContext);
            }
        }
    }

    @Test
    public void resourceOnlyPackageContextShouldPassForFlutterApplication() throws Exception {
        Context packageContext = mock(Context.class);
        Context applicationContext = mock(Context.class);
        when(applicationContext.getClassLoader()).thenReturn(classLoader());

        try (URLClassLoader withoutFlutter = new URLClassLoader(new URL[0], null);
                MockedStatic<ReflectUtil> reflectUtil = currentApplication(applicationContext)) {
            when(packageContext.getClassLoader()).thenReturn(withoutFlutter);

            assertTrue(FlutterAssetRefresh.shouldPrepareHostPackageContext(packageContext));
            verifyNoMoreInteractions(packageContext);
        }
    }

    @Test
    public void unreadableEngineRegistryShouldWarnWithoutNotifying() {
        try (MockedStatic<LogUtils> logUtils = mockStatic(LogUtils.class);
                MockedStatic<Toast> toast = mockStatic(Toast.class)) {
            assertNull(FlutterAssetRefresh.snapshotEngines(String.class));
            logUtils.verify(() -> LogUtils.w(eq(InstrumentationHooks.TAG), anyString()), times(1));
            toast.verifyNoInteractions();
        }
    }

    @Test
    public void overlayPathsShouldBeAppendedLastForRawAssetPriority() {
        // AssetManager2::OpenNonAsset walks the apk assets backwards, so the overlay has to be last.
        assertEquals(Arrays.asList("/base.apk", "/split.apk", "/overlay/base.apk"),
                FlutterAssetRefresh.assetPathsForOverlay(
                        Arrays.asList("/base.apk", "/split.apk"),
                        Collections.singletonList("/overlay/base.apk")));
        // An overlay that is already part of the list must not be duplicated or moved.
        assertEquals(Arrays.asList("/base.apk", "/overlay/base.apk"),
                FlutterAssetRefresh.assetPathsForOverlay(
                        Arrays.asList("/base.apk", "/overlay/base.apk"),
                        Collections.singletonList("/overlay/base.apk")));
        assertEquals(Collections.singletonList("/base.apk"),
                FlutterAssetRefresh.assetPathsForOverlay(
                        Collections.singletonList("/base.apk"), Collections.emptyList()));
    }

    /** Mirrors what the hook reaches: class lookup, one engine snapshot, then the batch. */
    private List<String> refreshLiveEngines() throws Exception {
        Class<?> engineClass = FlutterAssetRefresh.findEngineClass(classLoader());
        assertNotNull(engineClass);
        return FlutterAssetRefresh.refreshEngines(
                context, FlutterAssetRefresh.snapshotEngines(engineClass), overlayAssetManager, BUNDLE_PATH);
    }

    /** Mirrors the app class loader the agent passes in, which can resolve the Flutter classes. */
    private static ClassLoader classLoader() {
        return FlutterAssetRefreshTest.class.getClassLoader();
    }

    private static MockedStatic<ReflectUtil> currentApplication(Context applicationContext) {
        MockedStatic<ReflectUtil> reflectUtil = mockStatic(ReflectUtil.class, CALLS_REAL_METHODS);
        reflectUtil.when(() -> ReflectUtil.getActivityThread(null, null))
                .thenReturn(new FakeActivityThread(applicationContext));
        return reflectUtil;
    }

    private static final class FakeActivityThread {
        private final Context applicationContext;

        private FakeActivityThread(Context applicationContext) {
            this.applicationContext = applicationContext;
        }

        private Context currentApplication() {
            return applicationContext;
        }
    }
}
