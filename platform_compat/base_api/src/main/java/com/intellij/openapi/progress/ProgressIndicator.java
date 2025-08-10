package com.intellij.openapi.progress;

public interface ProgressIndicator {
    void start();

    void stop();

    boolean isRunning();

    void cancel();

    boolean isCanceled();

    void setText(String var1);

    String getText();

    void setText2(String var1);

    String getText2();

    double getFraction();

    void setFraction(double var1);

    void pushState();

    void popState();

    boolean isModal();

    void setModalityProgress(ProgressIndicator var1);

    boolean isIndeterminate();

    void setIndeterminate(boolean var1);

    boolean isPopupWasShown();

    boolean isShowing();
}
