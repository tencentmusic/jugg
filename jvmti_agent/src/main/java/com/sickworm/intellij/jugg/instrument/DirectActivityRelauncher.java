package com.sickworm.intellij.jugg.instrument;

import android.app.Activity;
import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.sickworm.intellij.jugg.hotfix.LogUtils;
import com.sickworm.intellij.jugg.hotfix.ReflectUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Applies Direct runtime changes on the main thread without restarting the app process. */
public final class DirectActivityRelauncher {

    private static final String TAG = "jugg-jvmti";
    private static final int RESULT_OK = 0;
    private static final int RESULT_RESOURCE_REFRESH_FAILED = 1;
    private static final int RESULT_ACTIVITY_RELAUNCH_FAILED = 2;
    private static final long APPLY_TIMEOUT_SECONDS = 5;

    private DirectActivityRelauncher() {
    }

    public static boolean relaunch() {
        return applyChanges(null, false, true) == RESULT_OK;
    }

    /** Refreshes resources and recreates all live Activities in a single main-thread task. */
    public static int applyChanges(String appDataDir, boolean refreshResources, boolean restartActivity) {
        AtomicInteger result = new AtomicInteger(RESULT_OK);
        CountDownLatch completed = new CountDownLatch(1);
        Runnable action = () -> {
            try {
                if (refreshResources) {
                    ResourceOverlays.refresh(appDataDir);
                }
            } catch (Throwable e) {
                LogUtils.e(TAG, "Direct resource refresh failed", e);
                result.set(RESULT_RESOURCE_REFRESH_FAILED);
                completed.countDown();
                return;
            }
            try {
                if (restartActivity && !relaunchOnMainThread()) {
                    result.set(RESULT_ACTIVITY_RELAUNCH_FAILED);
                }
            } finally {
                completed.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return result.get();
        }
        try {
            if (!new Handler(Looper.getMainLooper()).post(action) ||
                    !completed.await(APPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LogUtils.i(TAG, "Direct runtime apply timed out");
                return RESULT_ACTIVITY_RELAUNCH_FAILED;
            }
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogUtils.i(TAG, "Direct runtime apply interrupted");
            return RESULT_ACTIVITY_RELAUNCH_FAILED;
        }
    }

    private static boolean relaunchOnMainThread() {
        try {
            List<Activity> activities = null;
            try {
                activities = findActivitiesFromRecords();
            } catch (Throwable e) {
                LogUtils.d(TAG, "ActivityThread lookup failed, falling back to windows: " + e);
            }
            if (activities == null || activities.isEmpty()) {
                activities = findActivitiesFromWindows();
            }
            if (activities.isEmpty()) {
                LogUtils.i(TAG, "Direct Activity relaunch failed: no active Activity");
                return false;
            }
            for (Activity activity : activities) {
                activity.recreate();
            }
            LogUtils.i(TAG, "Direct Activity relaunch scheduled for " + activities.size() + " Activities");
            return true;
        } catch (Throwable e) {
            LogUtils.e(TAG, "Direct Activity relaunch failed", e);
            return false;
        }
    }

    private static List<Activity> findActivitiesFromRecords() throws Exception {
        ActivityThread activityThread = ActivityThread.currentActivityThread();
        Map<?, ?> clientRecords = (Map<?, ?>) ReflectUtil.getField(
            activityThread.getClass(), "mActivities", activityThread);
        List<Activity> activities = new ArrayList<>();
        for (Object record : new ArrayList<>(clientRecords.values())) {
            Activity activity = (Activity) ReflectUtil.getField(record.getClass(), "activity", record);
            if (isAvailable(activity)) {
                activities.add(activity);
            }
        }
        return activities;
    }

    private static List<Activity> findActivitiesFromWindows() throws Exception {
        Class<?> windowManagerClass = Class.forName("android.view.WindowManagerGlobal");
        Object windowManager = ReflectUtil.findMethod(windowManagerClass, "getInstance").invoke(null);
        List<?> views = (List<?>) ReflectUtil.getField(
            windowManager.getClass(), "mViews", windowManager);
        LinkedHashSet<Activity> activities = new LinkedHashSet<>();
        for (Object item : views) {
            View view = (View) item;
            Activity activity = findActivity(view.getContext());
            if (isAvailable(activity)) {
                activities.add(activity);
            }
        }
        return new ArrayList<>(activities);
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            Context baseContext = ((ContextWrapper) context).getBaseContext();
            if (baseContext == context) {
                break;
            }
            context = baseContext;
        }
        return null;
    }

    private static boolean isAvailable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
}
