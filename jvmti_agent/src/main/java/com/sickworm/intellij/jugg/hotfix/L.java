package com.sickworm.intellij.jugg.hotfix;

import android.util.Log;

class L {

    static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    static void e(String tag, String msg) {
        Log.i(tag, msg);
    }

    static void e(String tag, String msg, Throwable throwable) {
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
