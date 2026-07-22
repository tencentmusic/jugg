package com.sickworm.intellij.jugg.viewhierarchy;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import java.util.List;

/**
 * ViewTapper executes click actions on UI thread by View performClick first,
 * then fallback to dispatching touch events on the root window.
 */
public class ViewTapper {

    private static final String TAG = "Jugg#ViewTapper";

    /**
     * Tap a matched element. Returns true when click dispatch is accepted.
     */
    public boolean tap(MatchedElement element) {
        if (element == null) {
            return false;
        }

        try {
            if (element.view != null && element.view.isClickable() && element.view.performClick()) {
                return true;
            }
            return dispatchTapToRoot(element.rootView, element.centerX, element.centerY);
        } catch (Throwable t) {
            LogUtils.e(TAG, "tap failed", t);
            return false;
        }
    }

    /**
     * Long press a matched element. Returns true when long press dispatch is accepted.
     */
    public boolean longPress(MatchedElement element, long durationMs) {
        if (element == null) {
            return false;
        }
        long holdDuration = Math.max(50L, durationMs);
        try {
            if (element.view != null && element.view.isLongClickable() && element.view.performLongClick()) {
                return true;
            }
            return dispatchLongPressToRoot(element.rootView, element.centerX, element.centerY, holdDuration);
        } catch (Throwable t) {
            LogUtils.e(TAG, "longPress failed", t);
            return false;
        }
    }

    /**
     * Tap by absolute screen coordinates. Root views are expected in top-to-bottom order.
     */
    public boolean tapCoordinate(List<View> rootViews, int x, int y) {
        if (rootViews == null || rootViews.isEmpty()) {
            return false;
        }

        for (View root : rootViews) {
            if (root == null) {
                continue;
            }
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            int left = location[0];
            int top = location[1];
            int right = left + root.getWidth();
            int bottom = top + root.getHeight();
            if (x >= left && x <= right && y >= top && y <= bottom) {
                return dispatchTapToRoot(root, x, y);
            }
        }

        return dispatchTapToRoot(rootViews.get(0), x, y);
    }

    private boolean dispatchTapToRoot(View rootView, int screenX, int screenY) {
        if (rootView == null) {
            return false;
        }

        int[] location = new int[2];
        rootView.getLocationOnScreen(location);
        float localX = screenX - location[0];
        float localY = screenY - location[1];

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 16, MotionEvent.ACTION_UP, localX, localY, 0);

        try {
            boolean downHandled = rootView.dispatchTouchEvent(down);
            boolean upHandled = rootView.dispatchTouchEvent(up);
            return downHandled || upHandled;
        } catch (Throwable t) {
            LogUtils.e(TAG, "dispatchTapToRoot failed", t);
            return false;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private boolean dispatchLongPressToRoot(View rootView, int screenX, int screenY, long durationMs) {
        if (rootView == null) {
            return false;
        }

        int[] location = new int[2];
        rootView.getLocationOnScreen(location);
        float localX = screenX - location[0];
        float localY = screenY - location[1];

        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, localX, localY, 0);
        try {
            boolean downHandled = rootView.dispatchTouchEvent(down);
            boolean scheduled = rootView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    MotionEvent up = MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        localX,
                        localY,
                        0
                    );
                    try {
                        rootView.dispatchTouchEvent(up);
                    } finally {
                        up.recycle();
                    }
                }
            }, durationMs);
            return downHandled || scheduled;
        } catch (Throwable t) {
            LogUtils.e(TAG, "dispatchLongPressToRoot failed", t);
            return false;
        } finally {
            down.recycle();
        }
    }
}
