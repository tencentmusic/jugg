package com.sickworm.intellij.jugg.viewhierarchy;

import android.app.Activity;
import android.content.res.Resources;
import android.util.DisplayMetrics;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import top.kokomi.dragonfly.Dragonfly;
import top.kokomi.dragonfly.extractor.ViewExtractorKt;
import top.kokomi.dragonfly.node.HierarchyNode;
import top.kokomi.dragonfly.node.android.WindowNode;

/**
 * Captures one Dragonfly hierarchy snapshot for dump, selector, action, inspect, and verify flows.
 */
public class DragonflyHierarchySource {

    private static final String TAG = "DragonflyHierarchySource";
    private static final int MAX_DEPTH = 60;
    private static final int MAX_NODE_COUNT = 5000;
    private static final int MAX_WINDOW_COUNT = 32;
    private static final String VIRTUAL_ID_PREFIX = "_vir_id_";

    private boolean composeEnabled;

    /**
     * Capture the current top window or all windows in top-to-bottom order.
     */
    public Snapshot capture(boolean topWindowOnly) {
        ensureKotlinRuntimeAvailable();
        List<WindowNode> windows = extractWindows(topWindowOnly);
        if (!composeEnabled && containsComposeView(windows) && Dragonfly.enableComposeExtract()) {
            composeEnabled = true;
            windows = extractWindows(topWindowOnly);
        }
        return buildSnapshot(windows);
    }

    /**
     * Preserve the existing layout dump contract while using a fresh Dragonfly snapshot.
     */
    public JSONObject dumpWindowsJson(String rootId, boolean excludeGone, boolean topWindowOnly)
        throws JSONException {
        boolean captureTopWindowOnly = topWindowOnly && (rootId == null || rootId.isEmpty());
        return capture(captureTopWindowOnly).toJson(rootId, excludeGone);
    }

    /**
     * Normalize already extracted Dragonfly windows into one immutable request snapshot.
     */
    Snapshot buildSnapshot(List<? extends HierarchyNode> windows) {
        NodeBudget budget = new NodeBudget(MAX_NODE_COUNT);
        List<CapturedWindow> capturedWindows = new ArrayList<>();
        List<MatchedElement> elements = new ArrayList<>();
        Set<String> virtualIds = new HashSet<>();

        for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
            HierarchyNode window = windows.get(windowIndex);
            HierarchyNode root = firstChild(window);
            View rootView = androidView(root);
            CapturedNode rootNode = captureNode(
                root,
                rootView,
                "w" + windowIndex + "/0",
                0,
                budget,
                elements,
                virtualIds
            );
            capturedWindows.add(new CapturedWindow(
                resolveWindowType(rootView),
                safeString(window == null ? null : window.getName()),
                rootView,
                rootNode
            ));
        }
        return new Snapshot(capturedWindows, elements, budget.truncated);
    }

    private List<WindowNode> extractWindows(boolean topWindowOnly) {
        try {
            List<WindowNode> windows = extractDragonflyWindows(topWindowOnly);
            if (!windows.isEmpty()) {
                return windows;
            }
        } catch (Throwable throwable) {
            LogUtils.w(TAG, "Dragonfly window enumeration failed, using reflected roots: " + throwable);
        }
        return extractFallbackWindows(topWindowOnly);
    }

    private List<WindowNode> extractDragonflyWindows(boolean topWindowOnly) {
        if (topWindowOnly) {
            WindowNode window = Dragonfly.extractTopWindow();
            return window == null ? Collections.emptyList() : Collections.singletonList(window);
        }
        List<WindowNode> windows = new ArrayList<>();
        for (int index = -1; index >= -MAX_WINDOW_COUNT; index--) {
            WindowNode window = Dragonfly.extractWindow(index);
            if (window == null) {
                break;
            }
            windows.add(window);
        }
        return windows;
    }

    private List<WindowNode> extractFallbackWindows(boolean topWindowOnly) {
        List<View> roots = getFallbackRootViews();
        if (topWindowOnly && roots.size() > 1) {
            roots = Collections.singletonList(roots.get(0));
        }
        List<WindowNode> windows = new ArrayList<>();
        for (View root : roots) {
            try {
                WindowNode window = ViewExtractorKt.extractWindow(root);
                if (window != null) {
                    windows.add(window);
                }
            } catch (Throwable throwable) {
                LogUtils.w(TAG, "Reflected window extraction failed: " + throwable);
            }
        }
        return windows;
    }

    /**
     * Enumerate roots with the legacy ActivityThread and WindowManagerGlobal best-effort paths.
     */
    private List<View> getFallbackRootViews() {
        List<View> roots = new ArrayList<>();
        IdentityHashMap<View, Boolean> dedup = new IdentityHashMap<>();
        Activity activity = getTopResumedActivity();
        if (activity != null) {
            Window window = activity.getWindow();
            View decorView = window == null ? null : window.getDecorView();
            addRoot(roots, dedup, decorView);
        }
        for (View root : collectViewsFromWindowManagerGlobal()) {
            addRoot(roots, dedup, root);
        }
        return roots;
    }

    private void addRoot(List<View> roots, IdentityHashMap<View, Boolean> dedup, View root) {
        if (root != null && !dedup.containsKey(root)) {
            dedup.put(root, Boolean.TRUE);
            roots.add(root);
        }
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
            Object activities = activitiesField.get(activityThread);
            if (!(activities instanceof Map)) {
                return null;
            }
            for (Object record : ((Map<?, ?>) activities).values()) {
                if (record == null) {
                    continue;
                }
                Field pausedField = record.getClass().getDeclaredField("paused");
                pausedField.setAccessible(true);
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object candidate = activityField.get(record);
                if (!pausedField.getBoolean(record) && candidate instanceof Activity) {
                    return (Activity) candidate;
                }
            }
        } catch (Throwable throwable) {
            LogUtils.w(TAG, "Activity root enumeration failed: " + throwable);
        }
        return null;
    }

    private List<View> collectViewsFromWindowManagerGlobal() {
        List<View> roots = new ArrayList<>();
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            if (global == null) {
                return roots;
            }
            collectRootViews(global, roots);
            if (roots.isEmpty()) {
                Field viewsField = globalClass.getDeclaredField("mViews");
                viewsField.setAccessible(true);
                Object views = viewsField.get(global);
                if (views instanceof List) {
                    for (Object view : (List<?>) views) {
                        if (view instanceof View) {
                            roots.add((View) view);
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            LogUtils.w(TAG, "WindowManagerGlobal enumeration failed: " + throwable);
        }
        return roots;
    }

    private void collectRootViews(Object global, List<View> roots) {
        try {
            Field rootsField = global.getClass().getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            Object rootObjects = rootsField.get(global);
            if (rootObjects instanceof List) {
                for (Object rootObject : (List<?>) rootObjects) {
                    addViewRoot(rootObject, roots);
                }
            } else if (rootObjects instanceof Object[]) {
                for (Object rootObject : (Object[]) rootObjects) {
                    addViewRoot(rootObject, roots);
                }
            }
        } catch (Throwable throwable) {
            LogUtils.w(TAG, "WindowManagerGlobal.mRoots enumeration failed: " + throwable);
        }
    }

    private void addViewRoot(Object rootObject, List<View> roots) {
        if (rootObject == null) {
            return;
        }
        try {
            Field viewField = rootObject.getClass().getDeclaredField("mView");
            viewField.setAccessible(true);
            Object view = viewField.get(rootObject);
            if (view instanceof View) {
                roots.add((View) view);
            }
        } catch (Throwable throwable) {
            LogUtils.w(TAG, "ViewRootImpl extraction failed: " + throwable);
        }
    }

    private void ensureKotlinRuntimeAvailable() {
        try {
            Class.forName("kotlin.LazyThreadSafetyMode", false, getClass().getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Kotlin runtime is unavailable; this feature is not supported", exception);
        }
    }

    /**
     * Capture one node while preserving its Dragonfly object, optional Android View, and stable path.
     */
    private CapturedNode captureNode(
        HierarchyNode hierarchyNode,
        View rootView,
        String path,
        int depth,
        NodeBudget budget,
        List<MatchedElement> elements,
        Set<String> virtualIds
    ) {
        if (hierarchyNode == null) {
            return null;
        }

        Map<String, String> properties = safeProperties(hierarchyNode);
        boolean overNodeLimit = !budget.consume();
        boolean overDepthLimit = depth > MAX_DEPTH;
        if (overDepthLimit) {
            budget.truncated = true;
        }

        ViewNode node = buildNode(hierarchyNode, properties, path, virtualIds);
        if (overNodeLimit || overDepthLimit) {
            node.tag = "truncated:" + (overNodeLimit ? "node_limit" : "depth_limit");
        }
        MatchedElement element = new MatchedElement(
            hierarchyNode,
            androidView(hierarchyNode),
            rootView,
            node,
            properties
        );
        elements.add(element);

        List<CapturedNode> children = new ArrayList<>();
        if (!overNodeLimit && !overDepthLimit) {
            List<HierarchyNode> hierarchyChildren = safeChildren(hierarchyNode);
            for (int index = 0; index < hierarchyChildren.size(); index++) {
                CapturedNode child = captureNode(
                    hierarchyChildren.get(index),
                    rootView,
                    path + "/" + index,
                    depth + 1,
                    budget,
                    elements,
                    virtualIds
                );
                if (child != null) {
                    children.add(child);
                }
            }
        }
        return new CapturedNode(element, children);
    }

    private ViewNode buildNode(
        HierarchyNode hierarchyNode,
        Map<String, String> properties,
        String path,
        Set<String> virtualIds
    ) {
        ViewNode node = new ViewNode();
        node.className = safeString(hierarchyNode.getName());
        node.id = resolveNodeId(properties, path, node.className, virtualIds);
        node.text = safeString(properties.get("text"));
        node.contentDesc = firstValue(properties, "contentDesc", "contentDescription");
        node.tag = safeString(properties.get("tag"));
        node.visibility = valueOrDefault(properties.get("visibility"), "visible");
        node.alpha = parseFloat(properties.get("alpha"), 1.0f);
        node.clickable = parseBoolean(properties.get("clickable"), false);
        node.enabled = parseBoolean(properties.get("enabled"), true);
        parseBounds(properties.get("bounds"), node.bounds);
        parsePadding(properties.get("padding"), node.padding);
        node.textColor = parseColor(properties.get("textColor"));
        appendAndroidViewProperties(hierarchyNode, node);
        return node;
    }

    private void appendAndroidViewProperties(HierarchyNode hierarchyNode, ViewNode node) {
        View view = androidView(hierarchyNode);
        if (view == null) {
            return;
        }

        node.contentDesc = safeString(view.getContentDescription());
        node.tag = safeString(view.getTag());
        node.clickable = view.isClickable();
        node.enabled = view.isEnabled();
        if (node.text.isEmpty() && view instanceof TextView) {
            node.text = safeString(((TextView) view).getText());
        } else if (node.text.isEmpty() && KuiklyViewResolver.canResolve(view)) {
            KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
            node.text = result.text;
            node.message = result.errorMessage;
        }
    }

    private String resolveNodeId(
        Map<String, String> properties,
        String path,
        String className,
        Set<String> virtualIds
    ) {
        String id = properties.get("id");
        if (id != null && !id.isEmpty()) {
            return id;
        }

        String fingerprint = path + '|' + className + '|'
            + safeString(properties.get("sourceFile")) + '|'
            + safeString(properties.get("lineNumber"));
        for (int salt = 0; ; salt++) {
            int hash = (fingerprint + '|' + salt).hashCode() & Integer.MAX_VALUE;
            String virtualId = VIRTUAL_ID_PREFIX + hash;
            if (virtualIds.add(virtualId)) {
                return virtualId;
            }
        }
    }

    private boolean containsComposeView(List<? extends HierarchyNode> windows) {
        Class<?> composeViewClass;
        try {
            composeViewClass = Class.forName("androidx.compose.ui.platform.AbstractComposeView");
        } catch (Throwable ignored) {
            return false;
        }
        for (HierarchyNode window : windows) {
            if (containsViewType(window, composeViewClass)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsViewType(HierarchyNode node, Class<?> viewClass) {
        View view = androidView(node);
        if (viewClass.isInstance(view)) {
            return true;
        }
        for (HierarchyNode child : safeChildren(node)) {
            if (containsViewType(child, viewClass)) {
                return true;
            }
        }
        return false;
    }

    private View androidView(HierarchyNode node) {
        if (!(node instanceof top.kokomi.dragonfly.node.android.ViewNode)) {
            return null;
        }
        return ((top.kokomi.dragonfly.node.android.ViewNode) node).getView();
    }

    private HierarchyNode firstChild(HierarchyNode node) {
        List<HierarchyNode> children = safeChildren(node);
        return children.isEmpty() ? null : children.get(0);
    }

    private List<HierarchyNode> safeChildren(HierarchyNode node) {
        if (node == null || node.getChildren() == null) {
            return Collections.emptyList();
        }
        return node.getChildren();
    }

    private Map<String, String> safeProperties(HierarchyNode node) {
        if (node == null || node.properties() == null) {
            return Collections.emptyMap();
        }
        return node.properties();
    }

    private String resolveWindowType(View view) {
        try {
            ViewGroup.LayoutParams params = view == null ? null : view.getLayoutParams();
            if (params instanceof WindowManager.LayoutParams) {
                return windowType(((WindowManager.LayoutParams) params).type);
            }
        } catch (Throwable ignored) {
            return "activity";
        }
        return "activity";
    }

    private String windowType(int type) {
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

    private void parseBounds(String value, ViewNode.Bounds bounds) {
        int[] rect = parseRect(value);
        if (rect == null) {
            return;
        }
        bounds.left = rect[0];
        bounds.top = rect[1];
        bounds.right = rect[2];
        bounds.bottom = rect[3];
    }

    private void parsePadding(String value, ViewNode.Padding padding) {
        int[] rect = parseRect(value);
        if (rect == null) {
            return;
        }
        padding.left = rect[0];
        padding.top = rect[1];
        padding.right = rect[2];
        padding.bottom = rect[3];
    }

    private int[] parseRect(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()),
                Integer.parseInt(parts[3].trim())
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int parseColor(String value) {
        if (value == null || "Unspecified".equals(value)) {
            return 0;
        }
        String hex = value.replace("#", "").replace("0x", "");
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        if (hex.length() != 8) {
            return 0;
        }
        try {
            int color = (int) Long.parseLong(hex, 16);
            return color == 0xFF000000 ? 0 : color;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private float parseFloat(String value, float defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean parseBoolean(String value, boolean defaultValue) {
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private String firstValue(Map<String, String> properties, String first, String second) {
        String value = properties.get(first);
        return safeString(value == null ? properties.get(second) : value);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isEmpty() ? defaultValue : value.toLowerCase(Locale.ROOT);
    }

    private static String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void appendDeviceInfo(JSONObject data) throws JSONException {
        float density = 1.0f;
        float scaledDensity = 1.0f;
        try {
            DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
            if (metrics != null) {
                density = metrics.density;
                scaledDensity = metrics.scaledDensity;
            }
        } catch (Throwable ignored) {
            // Keep safe defaults for host-side tests and unusual runtime environments.
        }
        JSONObject deviceInfo = new JSONObject();
        deviceInfo.put("density", density);
        deviceInfo.put("scaledDensity", scaledDensity);
        data.put("deviceInfo", deviceInfo);
    }

    /**
     * Immutable hierarchy capture shared by every operation in one server request.
     */
    static final class Snapshot {
        private final List<CapturedWindow> windows;
        private final List<MatchedElement> elements;
        private final boolean truncated;

        private Snapshot(
            List<CapturedWindow> windows,
            List<MatchedElement> elements,
            boolean truncated
        ) {
            this.windows = windows;
            this.elements = elements;
            this.truncated = truncated;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList(), false);
        }

        JSONObject toJson(String rootId, boolean excludeGone) throws JSONException {
            if (rootId != null && !rootId.isEmpty()) {
                CapturedNode target = findNodeById(rootId);
                if (target != null) {
                    return buildSubtreeJson(target, rootId, excludeGone);
                }
            }
            return buildFullJson(excludeGone);
        }

        List<MatchedElement> find(
            String text,
            String resourceId,
            String contentDesc,
            String className,
            boolean requireActionable
        ) {
            List<MatchedElement> result = new ArrayList<>();
            for (MatchedElement element : elements) {
                if (matches(element, text, resourceId, contentDesc, className)
                    && (!requireActionable || isActionable(element))) {
                    result.add(element);
                }
            }
            return result;
        }

        List<MatchedElement> findClickableCandidates(int limit) {
            List<MatchedElement> result = new ArrayList<>();
            for (MatchedElement element : elements) {
                if (element.clickable && isActionable(element)) {
                    result.add(element);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
            return result;
        }

        List<View> rootViews() {
            List<View> roots = new ArrayList<>();
            for (CapturedWindow window : windows) {
                if (window.rootView != null) {
                    roots.add(window.rootView);
                }
            }
            return roots;
        }

        private JSONObject buildFullJson(boolean excludeGone) throws JSONException {
            JSONArray windowsJson = new JSONArray();
            for (CapturedWindow window : windows) {
                windowsJson.put(window.toJson(excludeGone));
            }
            JSONObject data = new JSONObject();
            data.put("windows", windowsJson);
            data.put("truncated", truncated);
            appendDeviceInfo(data);
            return data;
        }

        private JSONObject buildSubtreeJson(
            CapturedNode target,
            String rootId,
            boolean excludeGone
        ) throws JSONException {
            ViewNode root = target.toViewNode(excludeGone);
            JSONObject window = new JSONObject();
            window.put("windowType", "subtree");
            window.put("title", rootId);
            window.put("root", root == null ? new JSONObject() : root.toJson());

            JSONObject data = new JSONObject();
            data.put("windows", new JSONArray().put(window));
            data.put("truncated", truncated);
            data.put("rootLayout", rootId);
            appendDeviceInfo(data);
            return data;
        }

        private CapturedNode findNodeById(String id) {
            for (CapturedWindow window : windows) {
                CapturedNode found = window.root == null ? null : window.root.findById(id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private boolean matches(
            MatchedElement element,
            String text,
            String resourceId,
            String contentDesc,
            String className
        ) {
            return exact(text, element.text)
                && resourceIdMatches(resourceId, element.resourceId)
                && exact(contentDesc, element.contentDesc)
                && classNameMatches(className, element.className);
        }

        private boolean isActionable(MatchedElement element) {
            if (!"visible".equals(element.visibility)
                || element.bounds.right <= element.bounds.left
                || element.bounds.bottom <= element.bounds.top) {
                return false;
            }
            if (element.view == null) {
                return true;
            }
            return element.view.isShown()
                && element.view.getWidth() > 0
                && element.view.getHeight() > 0;
        }

        private boolean exact(String selector, String value) {
            return selector == null || selector.isEmpty() || selector.equals(value);
        }

        private boolean resourceIdMatches(String selector, String value) {
            if (selector == null || selector.isEmpty()) {
                return true;
            }
            String selectorShort = ViewNode.shortenId(selector);
            String valueShort = ViewNode.shortenId(value);
            return selector.equals(value)
                || (selectorShort != null && selectorShort.equals(valueShort));
        }

        private boolean classNameMatches(String selector, String value) {
            if (selector == null || selector.isEmpty()) {
                return true;
            }
            if (selector.equals(value)) {
                return true;
            }
            String simpleName = ViewNode.shortenClassName(value);
            return simpleName != null && simpleName.contains(selector);
        }
    }

    private static final class CapturedWindow {
        private final String type;
        private final String title;
        private final View rootView;
        private final CapturedNode root;

        private CapturedWindow(String type, String title, View rootView, CapturedNode root) {
            this.type = type;
            this.title = title;
            this.rootView = rootView;
            this.root = root;
        }

        private JSONObject toJson(boolean excludeGone) throws JSONException {
            ViewNode rootNode = root == null ? null : root.toViewNode(excludeGone);
            JSONObject json = new JSONObject();
            json.put("windowType", type);
            json.put("title", title);
            json.put("root", rootNode == null ? new JSONObject() : rootNode.toJson());
            return json;
        }
    }

    private static final class CapturedNode {
        private final MatchedElement element;
        private final List<CapturedNode> children;

        private CapturedNode(MatchedElement element, List<CapturedNode> children) {
            this.element = element;
            this.children = children;
        }

        private CapturedNode findById(String id) {
            if (idMatches(element.resourceId, id)) {
                return this;
            }
            for (CapturedNode child : children) {
                CapturedNode found = child.findById(id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private ViewNode toViewNode(boolean excludeGone) {
            if (excludeGone && "gone".equals(element.visibility)) {
                return null;
            }
            ViewNode copy = copyNode(element.node);
            for (CapturedNode child : children) {
                ViewNode childNode = child.toViewNode(excludeGone);
                if (childNode != null) {
                    copy.children.add(childNode);
                }
            }
            return copy;
        }

        private static boolean idMatches(String value, String selector) {
            if (value == null || selector == null) {
                return false;
            }
            return value.equals(selector)
                || safeString(ViewNode.shortenId(value)).equals(ViewNode.shortenId(selector));
        }

        private static ViewNode copyNode(ViewNode source) {
            ViewNode copy = new ViewNode();
            copy.className = source.className;
            copy.id = source.id;
            copy.text = source.text;
            copy.contentDesc = source.contentDesc;
            copy.tag = source.tag;
            copy.bounds.left = source.bounds.left;
            copy.bounds.top = source.bounds.top;
            copy.bounds.right = source.bounds.right;
            copy.bounds.bottom = source.bounds.bottom;
            copy.visibility = source.visibility;
            copy.alpha = source.alpha;
            copy.clickable = source.clickable;
            copy.enabled = source.enabled;
            copy.padding.left = source.padding.left;
            copy.padding.top = source.padding.top;
            copy.padding.right = source.padding.right;
            copy.padding.bottom = source.padding.bottom;
            copy.textColor = source.textColor;
            copy.message = source.message;
            return copy;
        }
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
