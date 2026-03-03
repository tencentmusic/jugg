package com.sickworm.intellij.jugg.viewhierarchy;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * ViewTreeDumper traverses current app windows and exports a JSON-ready View tree.
 */
public class ViewTreeDumper {

    private static final String TAG = "Jugg#ViewTreeDumper";
    private static final int MAX_DEPTH = 60;
    private static final int MAX_NODE_COUNT = 5000;

    private final ComposeTreeExtractor composeTreeExtractor;

    public ViewTreeDumper() {
        this(new NoOpComposeTreeExtractor());
    }

    public ViewTreeDumper(ComposeTreeExtractor composeTreeExtractor) {
        this.composeTreeExtractor = composeTreeExtractor;
    }

    /**
     * Build a JSON object with all window roots and their serialized nodes.
     */
    public JSONObject dumpWindowsJson() throws JSONException {
        JSONArray windowsJson = new JSONArray();
        NodeBudget budget = new NodeBudget(MAX_NODE_COUNT);

        for (WindowInfo window : getAllWindows()) {
            ViewNode rootNode = dumpView(window.rootView, 0, budget);
            windowsJson.put(window.toJson(rootNode));
        }

        JSONObject data = new JSONObject();
        data.put("windows", windowsJson);
        data.put("truncated", budget.truncated);
        return data;
    }

    /**
     * Enumerate all accessible window roots.
     *
     * Strategy:
     * 1) add current activity decor view first when available
     * 2) reflect WindowManagerGlobal.mRoots -> ViewRootImpl.mView
     * 3) fallback to WindowManagerGlobal.mViews
     */
    public List<WindowInfo> getAllWindows() {
        List<WindowInfo> windows = new ArrayList<>();
        IdentityHashMap<View, Boolean> dedup = new IdentityHashMap<>();

        Activity topActivity = getTopResumedActivity();
        if (topActivity != null) {
            Window window = topActivity.getWindow();
            View decorView = window != null ? window.getDecorView() : null;
            if (decorView != null) {
                dedup.put(decorView, Boolean.TRUE);
                windows.add(new WindowInfo("activity", topActivity.getClass().getSimpleName(), decorView));
            }
        }

        List<View> reflectedViews = collectViewsFromWindowManagerGlobal();
        for (View root : reflectedViews) {
            if (root == null || dedup.containsKey(root)) {
                continue;
            }
            dedup.put(root, Boolean.TRUE);
            windows.add(new WindowInfo(resolveWindowType(root), resolveWindowTitle(root), root));
        }

        return windows;
    }

    private ViewNode dumpView(View view, int depth, NodeBudget budget) {
        if (view == null) {
            return null;
        }
        if (!budget.consume()) {
            return buildTruncatedNode(view, "node_limit");
        }
        if (depth > MAX_DEPTH) {
            budget.truncated = true;
            return buildTruncatedNode(view, "depth_limit");
        }

        ViewNode node = buildNode(view);
        List<ComposeNode> composeNodes = composeTreeExtractor.extract(view);
        if (composeNodes != null && !composeNodes.isEmpty()) {
            node.composeNodes.addAll(composeNodes);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                ViewNode child = dumpView(group.getChildAt(i), depth + 1, budget);
                if (child != null) {
                    node.children.add(child);
                }
            }
        }
        return node;
    }

    private ViewNode buildTruncatedNode(View view, String reason) {
        ViewNode node = buildNode(view);
        node.tag = "truncated:" + reason;
        return node;
    }

    private ViewNode buildNode(View view) {
        ViewNode node = new ViewNode();
        node.className = view.getClass().getName();
        node.id = resolveResourceId(view);
        node.idHex = resolveIdHex(view.getId());
        node.text = resolveText(view);
        node.contentDesc = safeToString(view.getContentDescription());
        node.tag = safeToString(view.getTag());
        node.bounds = resolveBounds(view);
        node.visibility = resolveVisibility(view.getVisibility());
        node.alpha = view.getAlpha();
        node.clickable = view.isClickable();
        node.enabled = view.isEnabled();
        node.focused = view.isFocused();
        node.selected = view.isSelected();
        node.padding.left = view.getPaddingLeft();
        node.padding.top = view.getPaddingTop();
        node.padding.right = view.getPaddingRight();
        node.padding.bottom = view.getPaddingBottom();
        return node;
    }

    private ViewNode.Bounds resolveBounds(View view) {
        ViewNode.Bounds bounds = new ViewNode.Bounds();
        int[] location = new int[2];
        try {
            view.getLocationOnScreen(location);
            bounds.left = location[0];
            bounds.top = location[1];
            bounds.right = location[0] + view.getWidth();
            bounds.bottom = location[1] + view.getHeight();
        } catch (Throwable t) {
            LogUtils.e(TAG, "resolveBounds failed", t);
        }
        return bounds;
    }

    private String resolveWindowType(View rootView) {
        try {
            ViewGroup.LayoutParams params = rootView.getLayoutParams();
            if (params instanceof WindowManager.LayoutParams) {
                int type = ((WindowManager.LayoutParams) params).type;
                if (type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || type == WindowManager.LayoutParams.TYPE_APPLICATION
                    || type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                    return "activity";
                }
                if (type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                    || type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                    || type == WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) {
                    return "popup";
                }
                if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    || type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    || type == WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG) {
                    return "system";
                }
                return "dialog";
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "resolveWindowType failed", t);
        }
        return "activity";
    }

    private String resolveWindowTitle(View rootView) {
        try {
            ViewGroup.LayoutParams params = rootView.getLayoutParams();
            if (params instanceof WindowManager.LayoutParams) {
                CharSequence title = ((WindowManager.LayoutParams) params).getTitle();
                if (!TextUtils.isEmpty(title)) {
                    return String.valueOf(title);
                }
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "resolveWindowTitle failed", t);
        }
        Context context = rootView.getContext();
        return context != null ? context.getClass().getSimpleName() : "";
    }

    private String resolveResourceId(View view) {
        int id = view.getId();
        if (id == View.NO_ID) {
            return "";
        }
        try {
            Resources resources = view.getResources();
            return resources != null ? resources.getResourceName(id) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private String resolveIdHex(int id) {
        if (id == View.NO_ID) {
            return "";
        }
        return "0x" + Integer.toHexString(id);
    }

    private String resolveText(View view) {
        if (view instanceof TextView) {
            return safeToString(((TextView) view).getText());
        }
        return "";
    }

    private String resolveVisibility(int visibility) {
        if (visibility == View.VISIBLE) {
            return "visible";
        }
        if (visibility == View.INVISIBLE) {
            return "invisible";
        }
        if (visibility == View.GONE) {
            return "gone";
        }
        return "unknown";
    }

    private String safeToString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Activity getTopResumedActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object activityThread = currentActivityThread.invoke(null);
            if (activityThread == null) {
                return null;
            }
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesObj = activitiesField.get(activityThread);
            if (!(activitiesObj instanceof Map)) {
                return null;
            }
            for (Object record : ((Map<?, ?>) activitiesObj).values()) {
                if (record == null) {
                    continue;
                }
                Field pausedField = record.getClass().getDeclaredField("paused");
                pausedField.setAccessible(true);
                boolean paused = pausedField.getBoolean(record);

                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activityObj = activityField.get(record);
                if (!paused && activityObj instanceof Activity) {
                    return (Activity) activityObj;
                }
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "getTopResumedActivity failed", t);
        }
        return null;
    }

    private List<View> collectViewsFromWindowManagerGlobal() {
        List<View> roots = new ArrayList<>();
        try {
            Class<?> wmGlobalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmGlobalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object wmGlobal = getInstance.invoke(null);
            if (wmGlobal == null) {
                return roots;
            }

            // Preferred path from CodeLocator analysis: mRoots -> ViewRootImpl.mView
            collectRootViewsFromRootsField(wmGlobal, roots);
            if (!roots.isEmpty()) {
                return roots;
            }

            // Fallback path for OEM/ROM compatibility.
            Field mViewsField = wmGlobalClass.getDeclaredField("mViews");
            mViewsField.setAccessible(true);
            Object mViewsObj = mViewsField.get(wmGlobal);
            if (mViewsObj instanceof List) {
                List<?> list = (List<?>) mViewsObj;
                for (Object item : list) {
                    if (item instanceof View) {
                        roots.add((View) item);
                    }
                }
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "collectViewsFromWindowManagerGlobal failed", t);
        }
        return roots;
    }

    private void collectRootViewsFromRootsField(Object wmGlobal, List<View> roots) {
        try {
            Field mRootsField = wmGlobal.getClass().getDeclaredField("mRoots");
            mRootsField.setAccessible(true);
            Object mRootsObj = mRootsField.get(wmGlobal);
            if (mRootsObj instanceof List) {
                for (Object rootImpl : (List<?>) mRootsObj) {
                    View root = extractViewFromViewRootImpl(rootImpl);
                    if (root != null) {
                        roots.add(root);
                    }
                }
            } else if (mRootsObj instanceof Object[]) {
                for (Object rootImpl : (Object[]) mRootsObj) {
                    View root = extractViewFromViewRootImpl(rootImpl);
                    if (root != null) {
                        roots.add(root);
                    }
                }
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "collectRootViewsFromRootsField failed", t);
        }
    }

    private View extractViewFromViewRootImpl(Object viewRootImpl) {
        if (viewRootImpl == null) {
            return null;
        }
        try {
            Field viewField = viewRootImpl.getClass().getDeclaredField("mView");
            viewField.setAccessible(true);
            Object viewObj = viewField.get(viewRootImpl);
            if (viewObj instanceof View) {
                return (View) viewObj;
            }
        } catch (Throwable t) {
            LogUtils.e(TAG, "extractViewFromViewRootImpl failed", t);
        }
        return null;
    }

    private static final class NodeBudget {
        private final int maxNodeCount;
        private int usedCount;
        private boolean truncated;

        private NodeBudget(int maxNodeCount) {
            this.maxNodeCount = maxNodeCount;
        }

        private boolean consume() {
            usedCount++;
            if (usedCount <= maxNodeCount) {
                return true;
            }
            truncated = true;
            return false;
        }
    }
}
