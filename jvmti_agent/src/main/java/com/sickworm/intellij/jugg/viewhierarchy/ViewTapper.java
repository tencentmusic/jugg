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
        if (element == null || element.view == null) {
            return false;
        }

        try {
            if (element.view.isClickable() && element.view.performClick()) {
                return true;
            }
            return dispatchTapToRoot(element.window.rootView, element.centerX, element.centerY);
        } catch (Throwable t) {
            LogUtils.e(TAG, "tap failed", t);
            return false;
        }
    }

    /**
     * Tap by absolute screen coordinates. Windows are checked in reverse order
     * to favor top-most overlays.
     */
    public boolean tapCoordinate(List<WindowInfo> windows, int x, int y) {
        if (windows == null || windows.isEmpty()) {
            return false;
        }

        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowInfo window = windows.get(i);
            View root = window.rootView;
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

        return dispatchTapToRoot(windows.get(0).rootView, x, y);
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
}
