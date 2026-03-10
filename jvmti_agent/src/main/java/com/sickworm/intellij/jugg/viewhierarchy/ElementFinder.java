package com.sickworm.intellij.jugg.viewhierarchy;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ElementFinder performs exact-match selector lookup on current View hierarchy.
 */
public class ElementFinder {

    private final ViewTreeDumper dumper;

    public ElementFinder(ViewTreeDumper dumper) {
        this.dumper = dumper;
    }

    /**
     * Find elements with AND logic: all non-empty selectors must match.
     */
    public List<MatchedElement> find(
        String text,
        String resourceId,
        String contentDesc,
        String className
    ) {
        return find(text, resourceId, contentDesc, className, false);
    }

    /**
     * Find elements with AND logic: all non-empty selectors must match.
     * When topWindowOnly is true, only searches in the topmost window.
     */
    public List<MatchedElement> find(
        String text,
        String resourceId,
        String contentDesc,
        String className,
        boolean topWindowOnly
    ) {
        List<MatchedElement> result = new ArrayList<>();
        List<WindowInfo> windows = resolveWindows(topWindowOnly);

        // Traverse in reverse so overlay/popup windows are preferred over base activity.
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowInfo window = windows.get(i);
            findInView(window.rootView, window, text, resourceId, contentDesc, className, result);
        }
        return result;
    }

    /**
     * Return clickable candidates for debugging when a selector has no match.
     */
    public List<MatchedElement> findClickableCandidates(int limit) {
        return findClickableCandidates(limit, false);
    }

    /**
     * Return clickable candidates for debugging when a selector has no match.
     * When topWindowOnly is true, only searches in the topmost window.
     */
    public List<MatchedElement> findClickableCandidates(int limit, boolean topWindowOnly) {
        List<MatchedElement> result = new ArrayList<>();
        List<WindowInfo> windows = resolveWindows(topWindowOnly);
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowInfo window = windows.get(i);
            collectClickable(window.rootView, window, result, limit);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private List<WindowInfo> resolveWindows(boolean topWindowOnly) {
        if (topWindowOnly) {
            WindowInfo top = dumper.getTopWindow();
            return top != null ? Collections.singletonList(top) : Collections.emptyList();
        }
        return dumper.getAllWindows();
    }

    private void findInView(
        View view,
        WindowInfo window,
        String text,
        String resourceId,
        String contentDesc,
        String className,
        List<MatchedElement> result
    ) {
        if (view == null) {
            return;
        }
        ViewNode.Bounds bounds = resolveBounds(view);
        if (isActionableNode(view, bounds)) {
            String nodeText = resolveText(view);
            String nodeResourceId = resolveResourceId(view);
            String nodeContentDesc = safeToString(view.getContentDescription());
            String nodeClassName = view.getClass().getName();

            boolean matches = isMatch(text, nodeText)
                && isResourceIdMatch(resourceId, nodeResourceId)
                && isMatch(contentDesc, nodeContentDesc)
                && isClassNameMatch(className, nodeClassName);
            if (matches) {
                result.add(
                    new MatchedElement(
                        view,
                        window,
                        nodeText,
                        nodeResourceId,
                        nodeContentDesc,
                        nodeClassName,
                        bounds,
                        resolveVisibility(view.getVisibility()),
                        view.isClickable()
                    )
                );
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                findInView(group.getChildAt(i), window, text, resourceId, contentDesc, className, result);
            }
        }
    }

    private void collectClickable(View view, WindowInfo window, List<MatchedElement> out, int limit) {
        if (view == null || out.size() >= limit) {
            return;
        }
        if (view.isClickable()) {
            out.add(
                new MatchedElement(
                    view,
                    window,
                    resolveText(view),
                    resolveResourceId(view),
                    safeToString(view.getContentDescription()),
                    view.getClass().getName(),
                    resolveBounds(view),
                    resolveVisibility(view.getVisibility()),
                    true
                )
            );
            if (out.size() >= limit) {
                return;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                collectClickable(group.getChildAt(i), window, out, limit);
                if (out.size() >= limit) {
                    return;
                }
            }
        }
    }

    private boolean isMatch(String selector, String value) {
        if (selector == null || selector.isEmpty()) {
            return true;
        }
        return selector.equals(value);
    }

    /**
     * Match className with support for simple name containment.
     * e.g. selector "TextView" matches "android.widget.AppCompatTextView" because the simple name
     * "AppCompatTextView" contains "TextView".
     */
    private boolean isClassNameMatch(String selector, String value) {
        if (selector == null || selector.isEmpty()) {
            return true;
        }
        if (selector.equals(value)) {
            return true;
        }
        // Compare against the simple class name (part after the last '.')
        String simpleValue = value != null ? value.substring(value.lastIndexOf('.') + 1) : "";
        return simpleValue.contains(selector);
    }

    private boolean isResourceIdMatch(String selector, String value) {
        if (selector == null || selector.isEmpty()) {
            return true;
        }
        String selectorShort = ViewNode.shortenId(selector);
        String valueShort = ViewNode.shortenId(value);
        if (selectorShort != null && selectorShort.equals(valueShort)) {
            return true;
        }
        return selector.equals(value);
    }

    private boolean isActionableNode(View view, ViewNode.Bounds bounds) {
        return view.getVisibility() == View.VISIBLE
            && view.isShown()
            && view.getWidth() > 0
            && view.getHeight() > 0
            && bounds != null
            && bounds.right > bounds.left
            && bounds.bottom > bounds.top;
    }

    private ViewNode.Bounds resolveBounds(View view) {
        ViewNode.Bounds bounds = new ViewNode.Bounds();
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        bounds.left = location[0];
        bounds.top = location[1];
        bounds.right = location[0] + view.getWidth();
        bounds.bottom = location[1] + view.getHeight();
        return bounds;
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

    private String resolveText(View view) {
        if (view instanceof TextView) {
            return safeToString(((TextView) view).getText());
        }
        if (KuiklyViewResolver.canResolve(view)) {
            KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
            return result.text;
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
}
