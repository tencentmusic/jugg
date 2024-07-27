package com.sickworm.intellij.jugg.hotfix;

import android.util.Log;
import java.io.File;

public class LogUtils {

    private static final boolean logDebug = new File("/data/local/tmp/jugg/log_debug").exists();

    public static void d(String tag, String msg) {
        if (logDebug) {
            Log.d(tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void e(String tag, String msg, Throwable throwable) {
        Log.i(tag, msg + " : " + (callStack(throwable)));
    }

    private static String callStack(Throwable throwable) {
        try {
            String log = "\n" + throwable + "\n";
            for (StackTraceElement s : throwable.getStackTrace()) {
                log += s.toString() + "\n";
            }
            return log;
        } catch (Exception e) {
            return "<callStackException>";
        }
    }
}
