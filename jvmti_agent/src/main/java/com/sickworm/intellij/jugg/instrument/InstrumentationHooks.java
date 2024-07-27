package com.sickworm.intellij.jugg.instrument;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import com.sickworm.intellij.jugg.hotfix.HotfixLoader;
import com.sickworm.intellij.jugg.hotfix.LogUtils;

/** @noinspection unused*/
public class InstrumentationHooks {

    public static final String TAG = "jugg-jvmti";

    public static void handleAttachBaseContextEntry(ContextWrapper contextWrapper, Context base)
        throws Exception {
        if (!(contextWrapper instanceof Application)) {
            return;
        }
        LogUtils.d(TAG, "handleAttachBaseContextEntry contextWrapper: " + contextWrapper);
        try {
            boolean isNeedFix = DexPathListFixer.isNeedFix(base);
            LogUtils.d(TAG, "handleAttachBaseContextEntry isNeedFix: " + isNeedFix);
            if (isNeedFix) {
                HotfixLoader.install(base);
                LogUtils.d(TAG, "handleAttachBaseContextEntry fix finished");
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "handleAttachBaseContextEntry", e);
            throw e;
        }
    }

    @SuppressLint("StaticFieldLeak")
    private static Context base;

    public static void handleNewApplicationEntry(Instrumentation instrumentation, ClassLoader classLoader, String className, Context base) {
        try {
            boolean isNeedFix = DexPathListFixer.isNeedFix(base);
            LogUtils.d(TAG, "handleAttachBaseContextEntry isNeedFix: " + isNeedFix);
            if (isNeedFix) {
                HotfixLoader.install(base);
                InstrumentationHooks.base = base;
                LogUtils.d(TAG, "handleAttachBaseContextEntry fix finished");
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "handleAttachBaseContextEntry", e);
            throw new RuntimeException(e);
        }
    }

    public static Application handleInstantiateApplicationExit(Application application)
        throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return (Application) base.getClassLoader().loadClass(application.getClass().getName()).newInstance();
    }
}