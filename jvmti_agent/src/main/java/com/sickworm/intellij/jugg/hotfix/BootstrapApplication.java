package com.sickworm.intellij.jugg.hotfix;


import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

/**
 * Refer from Lightning :)
 *
 * Being responsible for incremental dex and resources patching, the whole process should be no-perception to developers.
 * then replace itself with the raw application attach raw application to runtime.
 *
 * This class should use as few other classes as possible before the class loader is patched
 * because any class loaded before it cannot be incrementally deployed.
 *
 * Notice that current class applied a liberal amount of reflection on Android internals.
 * So, It may process failed on some devices.
 *
 * Unfortunately, if this does not work, we don't have a fallback mechanism: as soon as we
 * build the APK with this class as the Application, we are committed to going through with it.
 *
 * @author ethanfeng
 * @noinspection JavadocBlankLines, unchecked, JavaReflectionMemberAccess, unchecked, DataFlowIssue, DataFlowIssue, deprecation
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public class BootstrapApplication extends Application {

    private static final String TAG = HotfixLoader.TAG + "#BootstrapApplication";
    public static final String META_DATA_LABEL_RAW_APPLICATION = BuildConfig.META_DATA_LABEL_RAW_APPLICATION;

    private Application rawApplication = null;

    public BootstrapApplication() {
        LogUtils.i(TAG, "BootstrapApplication instance created: @" + Integer.toHexString(hashCode()) );
    }

    @Override
    protected void attachBaseContext(Context base) {
        HotfixLoader.init(base);
        boolean isNeedEnableHotfix = HotfixLoader.isNeedEnableHotfix();
        LogUtils.e(TAG, "attachBaseContext start, isNeedEnableHotfix " + isNeedEnableHotfix);
        if (isNeedEnableHotfix) {
            HotfixLoader.install(base);
        }

        super.attachBaseContext(base);
        generateRawApplication(base);
        LogUtils.i(TAG, "attachBaseContext done");
    }

    @Override public void onCreate() {
        LogUtils.i(TAG, "onCreate start");
        if (rawApplication != null) {
            replaceApplication();
        }

        super.onCreate();

        if (rawApplication != null) {
            moveActivityLifecycleCallbacks();
            rawApplication.onCreate();
        }

        LogUtils.i(TAG, "onCreate done");
    }

    /**
     * Some ways to register ActivityLifecycleCallbacks by Provider (such as LeakCanary) will be registered in BootstrapApplication,
     * causing the original project's Application to fail to register successfully. Here we need to migrate it
     */
    private void moveActivityLifecycleCallbacks() {
        LogUtils.i(TAG, "moveActivityLifecycleCallbacks");
        try {
            Class<Application> clz = Application.class;
            Field field = clz.getDeclaredField("mActivityLifecycleCallbacks");
            field.setAccessible(true);
            ArrayList<ActivityLifecycleCallbacks> lifecycleCallbacks =  (ArrayList<ActivityLifecycleCallbacks>)field.get(this);
            LogUtils.i(TAG, "moveActivityLifecycleCallbacks lifecycleCallbacks:" + lifecycleCallbacks);
            if (lifecycleCallbacks != null && !lifecycleCallbacks.isEmpty()) {
                for (ActivityLifecycleCallbacks callback : lifecycleCallbacks) {
                    rawApplication.registerActivityLifecycleCallbacks(callback);
                }
                lifecycleCallbacks.clear();
            }
        } catch (Throwable e) {
            LogUtils.e(TAG, "moveActivityLifecycleCallbacks error", e);
        }
    }

    /**
     * Create a new instance of raw application and then call "attachBaseContext()" on it.
     */
    private void generateRawApplication(Context base) {

        String rawApplicationName = null;

        try {
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            if (applicationInfo != null && applicationInfo.metaData != null) {
                rawApplicationName = applicationInfo.metaData.getString(META_DATA_LABEL_RAW_APPLICATION);
                LogUtils.i(TAG, "generateRawApplication: rawApplicationName : " + rawApplicationName);

                if (TextUtils.isEmpty(rawApplicationName) || rawApplicationName.equals("null") || BootstrapApplication.class.getName().equals(rawApplicationName)) {
                    LogUtils.i(TAG, "generateRawApplication: no raw application, exit generate");
                    return;
                }
                Class<?> clazz = getClassLoader().loadClass(rawApplicationName);
                rawApplication = (Application) clazz.newInstance();
                Method attachMethod = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                attachMethod.setAccessible(true);
                attachMethod.invoke(rawApplication, base);
                LogUtils.i(TAG, "generateRawApplication: rawApplication : " + rawApplication);
            }
        } catch (Throwable e) {
            LogUtils.e(TAG, "generateRawApplication: error while create instance for rawApplicationName : " + rawApplicationName, e);
            throw new IllegalStateException(e);
        }
    }

    private void replaceApplication() {
        try {
            // Find the ActivityThread instance for the current thread
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object currentActivityThread = getActivityThread(this);

            LogUtils.d(TAG, "replaceApplication: currentActivityThread = " + currentActivityThread);

            if (currentActivityThread == null) throw new IllegalStateException("Failed to get current ActivityThread.");

            // Find the mInitialApplication field of the ActivityThread to the real application
            Field mInitialApplication = activityThread.getDeclaredField("mInitialApplication");
            mInitialApplication.setAccessible(true);
            Application initialApplication = (Application) mInitialApplication.get(currentActivityThread);
            LogUtils.d(TAG, "replaceApplication: initialApplication = " + initialApplication);
            if (initialApplication == this) {
                mInitialApplication.set(currentActivityThread, rawApplication);
                LogUtils.e(TAG, "replaceApplication: replace initial application with raw application: " + rawApplication);
                LogUtils.e(TAG, "replaceApplication: replace \"mInitialApplication\" inside ActivityThread done.");
            }

            // Replace all instance of the stub application in ActivityThread#mAllApplications with the
            // real one
            Field mAllApplications = activityThread.getDeclaredField("mAllApplications");
            mAllApplications.setAccessible(true);
            List<Application> allApplications = (List<Application>) mAllApplications.get(currentActivityThread);
            for (int i = 0; i < allApplications.size(); i++) {
                LogUtils.e(TAG, "replaceApplication: mAllApplications: " + allApplications.get(i));
                if (allApplications.get(i) == this) {
                    allApplications.set(i, rawApplication);
                }
            }
            LogUtils.d(TAG, "replaceApplication: replace \"mAllApplications\" inside ActivityThread done.");


            // API version 8 has PackageInfo, 10 has LoadedApk. 9, I don't know.
            Class<?> loadedApkClass;
            try {
                loadedApkClass = Class.forName("android.app.LoadedApk");
            } catch (ClassNotFoundException e) {
                loadedApkClass = Class.forName("android.app.ActivityThread$PackageInfo");
            }

            LogUtils.d(TAG, "replaceApplication: loadedApkClass = " + loadedApkClass);

            Field mApplication = loadedApkClass.getDeclaredField("mApplication");
            mApplication.setAccessible(true);
            Field mResDir = loadedApkClass.getDeclaredField("mResDir");
            mResDir.setAccessible(true);

            // 10 doesn't have this field, 14 does. Fortunately, there are not many Honeycomb devices
            // floating around.
            Field mLoadedApk = null;
            try {
                mLoadedApk = Application.class.getDeclaredField("mLoadedApk");
            } catch (NoSuchFieldException e) {
                // According to testing, it's okay to ignore this.
            }

            // Enumerate all LoadedApk (or PackageInfo) fields in ActivityThread#mPackages and
            // ActivityThread#mResourcePackages and do two things:
            //   - Replace the Application instance in its mApplication field with the real one
            //   - Replace mResDir to point to the external resource file instead of the .apk. This is
            //     used as the asset path for new Resources objects.
            //   - Set Application#mLoadedApk to the found LoadedApk instance
            for (String fieldName : new String[]{"mPackages", "mResourcePackages"}) {
                Field field = activityThread.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(currentActivityThread);
                LogUtils.d(TAG, "replaceApplication: replacing field \"" + fieldName + "\"");
                for (Map.Entry<String, WeakReference<?>> entry : ((Map<String, WeakReference<?>>) value).entrySet()) {

                    Object loadedApk = entry.getValue().get();
                    if (loadedApk == null) {
                        continue;
                    }

                    if (mApplication.get(loadedApk) == this) {
                        mApplication.set(loadedApk, rawApplication);
                        if (mLoadedApk != null) {
                            mLoadedApk.set(rawApplication, loadedApk);
                        }
                    }
                }
            }
            LogUtils.d(TAG, "replaceApplication: Enumerate all LoadedApk (or PackageInfo) fields in ActivityThread#mPackages and" +
                    " ActivityThread#mResourcePackages done.");

        } catch (Throwable e) {
            LogUtils.e(TAG, "replaceApplication: error = ", e);
            throw new IllegalStateException(e);
        }
    }

    private static Object getActivityThread(Context context) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method m = activityThread.getMethod("currentActivityThread");
            m.setAccessible(true);
            Object currentActivityThread = m.invoke(null);
            if (currentActivityThread == null && context != null) {
                // In older versions of Android (prior to frameworks/base 66a017b63461a22842)
                // the currentActivityThread was built on thread locals, so we'll need to try
                // even harder
                Field mLoadedApk = context.getClass().getField("mLoadedApk");
                mLoadedApk.setAccessible(true);
                Object apk = mLoadedApk.get(context);
                Field mActivityThreadField = apk.getClass().getDeclaredField("mActivityThread");
                mActivityThreadField.setAccessible(true);
                currentActivityThread = mActivityThreadField.get(apk);
            }
            return currentActivityThread;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
