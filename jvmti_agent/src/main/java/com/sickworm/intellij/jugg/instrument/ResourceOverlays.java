/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sickworm.intellij.jugg.instrument;

import android.annotation.TargetApi;
import android.content.pm.ApplicationInfo;
import android.content.res.ApkAssets;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.StrictMode;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads exploded APK resources for Direct sandbox deployments on Android 11+.
 * Adapted from Android Studio's ResourceOverlays; only the marked app's resources
 * receive the loader.
 */
@TargetApi(Build.VERSION_CODES.R)
public final class ResourceOverlays {
    private static final String TAG = "jugg-resource-overlay";
    private static File appDataDir;
    private static final Set<String> hostApkPaths = new HashSet<>();
    private static ResourcesLoader resourcesLoader;

    private ResourceOverlays() {
    }

    public static synchronized void initialize(String dataDir) {
        File directory = new File(dataDir);
        if (new File(directory, "code_cache/" + BuildConfig.DIRECT_RESOURCE_OVERLAY_FLAG_FILE).isFile()) {
            appDataDir = directory;
        }
    }

    public static synchronized void prepare(ApplicationInfo info) throws IOException {
        if (appDataDir == null || info == null || info.dataDir == null ||
                !appDataDir.getCanonicalFile().equals(new File(info.dataDir).getCanonicalFile())) {
            return;
        }
        hostApkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            hostApkPaths.addAll(Arrays.asList(info.splitSourceDirs));
        }
    }

    /** Adds the shared loader only to Resources that contain the host APK. */
    public static synchronized void addResourceOverlays(Resources resources) throws Exception {
        if (appDataDir == null || resources == null || hostApkPaths.isEmpty() || !isHostResources(resources)) {
            return;
        }
        if (resourcesLoader == null && !updateLoader()) {
            return;
        }
        resources.addLoaders(resourcesLoader);
        LogUtils.d(TAG, "Attached Direct resource overlays to host Resources");
    }

    /** Refreshes committed overlays in all live Resources that belong to the host APK. */
    public static synchronized void refresh(String dataDir) throws Exception {
        initialize(dataDir);
        if (appDataDir == null) {
            throw new IOException("Direct resource overlay is not enabled");
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object application = ReflectUtil.findMethod(activityThreadClass, "currentApplication").invoke(null);
        if (application == null) {
            throw new IOException("Application is unavailable");
        }
        ApplicationInfo info = (ApplicationInfo) ReflectUtil.findMethod(application, "getApplicationInfo")
                .invoke(application);
        prepare(info);
        if (!updateLoader()) {
            throw new IOException("No Direct resource overlays found");
        }

        Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
        Object resourcesManager = ReflectUtil.findMethod(resourcesManagerClass, "getInstance").invoke(null);
        @SuppressWarnings("unchecked")
        ArrayList<WeakReference<Resources>> references = (ArrayList<WeakReference<Resources>>)
                ReflectUtil.findField(resourcesManager, "mResourceReferences").get(resourcesManager);
        int attachedCount = 0;
        for (WeakReference<Resources> reference : references) {
            Resources resources = reference.get();
            if (resources != null && isHostResources(resources)) {
                resources.addLoaders(resourcesLoader);
                attachedCount++;
            }
        }
        LogUtils.i(TAG, "Refreshed Direct resource overlays for " + attachedCount + " Resources");
    }

    private static boolean isHostResources(Resources resources) throws Exception {
        ApkAssets[] assets = (ApkAssets[]) ReflectUtil.findMethod(resources.getAssets(), "getApkAssets")
                .invoke(resources.getAssets());
        for (ApkAssets asset : assets) {
            if (hostApkPaths.contains(asset.getAssetPath())) {
                return true;
            }
        }
        return false;
    }

    private static boolean updateLoader() throws IOException {
        File overlayDir = new File(appDataDir, "code_cache/.overlay");
        File[] apkDirs = overlayDir.listFiles(file -> file.isDirectory() && file.getName().endsWith(".apk"));
        if (apkDirs == null || apkDirs.length == 0) {
            return false;
        }
        for (File apkDir : apkDirs) {
            if (new File(apkDir, BuildConfig.ENABLE_COMPAT_DEPLOY_FLAG_FILE).exists()) {
                return false;
            }
        }
        Arrays.sort(apkDirs, (left, right) -> {
            if (left.equals(right)) return 0;
            if (left.getName().equals("base.apk")) return -1;
            if (right.getName().equals("base.apk")) return 1;
            return left.getName().compareTo(right.getName());
        });
        List<ResourcesProvider> providers = loadProviders(apkDirs);
        if (providers.isEmpty()) {
            return false;
        }
        if (resourcesLoader == null) {
            resourcesLoader = new ResourcesLoader();
        }
        resourcesLoader.setProviders(providers);
        LogUtils.i(TAG, "Loaded Direct resource overlays: " + providers.size());
        return true;
    }

    private static List<ResourcesProvider> loadProviders(File[] apkDirs) throws IOException {
        List<ResourcesProvider> providers = new ArrayList<>();
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            for (File apkDir : apkDirs) {
                if (new File(apkDir, "res").exists() || new File(apkDir, "resources.arsc").exists() ||
                        new File(apkDir, "assets").exists()) {
                    providers.add(ResourcesProvider.loadFromDirectory(apkDir.getAbsolutePath(), null));
                }
            }
            return providers;
        } catch (IOException e) {
            for (ResourcesProvider provider : providers) {
                provider.close();
            }
            throw e;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
    }
}
