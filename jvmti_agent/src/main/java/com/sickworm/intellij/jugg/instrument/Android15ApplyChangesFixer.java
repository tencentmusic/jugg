package com.sickworm.intellij.jugg.instrument;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static com.sickworm.intellij.jugg.instrument.InstrumentationHooks.TAG;

/**
 * Apply changes has compat issue with Android 15 before Android Studio Meerkat.
 * Here we fix it by calling scheduleApplicationInfoChanged to trigger resource update.
 *
 * @noinspection unused
 */
public class Android15ApplyChangesFixer {

    private static final int ATTACH_AGENT = 155;

    private static boolean isNeedUpdateResourceByJugg = false;
    private static boolean isNeedRestartActivityByJugg = false;

    public static void sendMessageEnter(ActivityThread activityThread, int what, Object obj, int arg1, int arg2, boolean async) {
        if (what == ATTACH_AGENT) {
            // apply changes coming
            if (isNeedFix()) {
                isNeedUpdateResourceByJugg = true;
            }
        }
    }

    public static void sendMessageExit() {
        if (!isNeedUpdateResourceByJugg) {
            return;
        }

        isNeedUpdateResourceByJugg = false;
        try {
            // call scheduleApplicationInfoChanged to trigger resource update
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            ApplicationInfo applicationInfo = new ApplicationInfo(getApplicationInfo(activityThread));
            ((IApplicationThread.Stub) activityThread.getApplicationThread()).scheduleApplicationInfoChanged(applicationInfo);
            isNeedRestartActivityByJugg = true;
            LogUtils.i(TAG, "sendMessageExit scheduleApplicationInfoChanged");
        } catch (Throwable e) {
            LogUtils.e(TAG, "sendMessageExit failed", e);
        }
    }

    public static void restartActivityIfNeeded() {
        if (!isNeedRestartActivityByJugg) {
            return;
        }
        isNeedRestartActivityByJugg = false;

        boolean isNeedRestartActivity = isNeedRestartInApplyChanges();
        LogUtils.i(TAG, "restartActivityIfNeeded isNeedRestartInApplyChanges: " + isNeedRestartActivity);
        if (!isNeedRestartActivity) {
            return;
        }

        try {
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            Map<?, ?> clientRecords = (Map<?, ?>) ReflectUtil.getField(activityThread.getClass(), "mActivities", activityThread);
            for (Object record : clientRecords.values()) {
                Activity activity = (Activity) ReflectUtil.getField(record.getClass(), "activity", record);
                activity.recreate();
            }
            LogUtils.i(TAG, "restartActivity success");
        } catch (Throwable e) {
            LogUtils.e(TAG, "restartActivity failed", e);
        }
    }

    private static ApplicationInfo getApplicationInfo(ActivityThread activityThread) {
        String packageName = ActivityThread.currentPackageName();
        LoadedApk loadedApk = activityThread.peekPackageInfo(packageName, true);
        return loadedApk.getApplicationInfo();
    }

    private static boolean isNeedFix() {

        boolean isUpperAndroid15 = Build.VERSION.SDK_INT >= 35;
        boolean isBelowAndroidStudioMeerkat = false;

        try {
            @SuppressLint("PrivateApi")
            Class<?> applyChangesHook = Class.forName("com.android.tools.deploy.instrument.InstrumentationHooks");
            Method method = applyChangesHook.getMethod("restartActivity");
        } catch (ClassNotFoundException e) {
            // this should not happen
            LogUtils.e(TAG, "isNeedFix detect failed " + e);
            isBelowAndroidStudioMeerkat = true;
        } catch (NoSuchMethodException e) {
            // InstrumentationHooks don't have restartActivity, is lower than Android Studio Meerkat, need fix
            isBelowAndroidStudioMeerkat = true;
        }

        boolean isNeedFix = isUpperAndroid15 && isBelowAndroidStudioMeerkat;
        LogUtils.i(TAG, "isNeedFixApplyChangesForAndroid15 " + isNeedFix +
                ", isUpperAndroid15: " + isUpperAndroid15 +
                ", isBelowAndroidStudioMeerkat: " + isBelowAndroidStudioMeerkat
        );
        return isNeedFix;
    }

    private static boolean isNeedRestartInApplyChanges() {
        try {
            @SuppressLint("PrivateApi")
            Class<?> applyChangesHook = Class.forName("com.android.tools.deploy.instrument.InstrumentationHooks");
            Field field = applyChangesHook.getDeclaredField("mRestart");
            field.setAccessible(true);
            return field.get(null) == Boolean.TRUE;
        } catch (Exception e) {
            // this should not happen
            LogUtils.e(TAG, "isNeedRestartInApplyChanges detect failed " + e);
            return true;
        }
    }
}
