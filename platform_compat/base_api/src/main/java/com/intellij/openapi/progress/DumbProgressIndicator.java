package com.intellij.openapi.progress;


public class DumbProgressIndicator implements ProgressIndicator {
    public static final DumbProgressIndicator INSTANCE = new DumbProgressIndicator();

    public DumbProgressIndicator() {
    }

    public void start() {
    }

    public void stop() {
    }

    public boolean isRunning() {
        return true;
    }

    public final void cancel() {
    }

    public final boolean isCanceled() {
        return false;
    }

    public final void checkCanceled() {
    }

    public void setText(String text) {
    }

    public String getText() {
        return null;
    }

    public void setText2(String text) {
    }

    public String getText2() {
        return null;
    }

    public double getFraction() {
        return (double)0.0F;
    }

    public void setFraction(double fraction) {
    }

    public void pushState() {
    }

    public void popState() {
    }

    public boolean isModal() {
        return false;
    }

    public void setModalityProgress(ProgressIndicator modalityProgress) {
    }

    public boolean isIndeterminate() {
        return false;
    }

    public void setIndeterminate(boolean indeterminate) {
    }

    public boolean isPopupWasShown() {
        return false;
    }

    public boolean isShowing() {
        return false;
    }
}

