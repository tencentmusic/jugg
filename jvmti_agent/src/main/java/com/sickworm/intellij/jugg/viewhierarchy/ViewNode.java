package com.sickworm.intellij.jugg.viewhierarchy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewNode is the normalized JSON-ready node model for a single Android View.
 * toJson() omits default/empty fields and uses compact array format for bounds/padding
 * to reduce payload size.
 */
public class ViewNode {

    public String className = "";
    public String id = "";
    public String text = "";
    public String contentDesc = "";
    public String tag = "";
    public Bounds bounds = new Bounds();
    public String visibility = "visible";
    public float alpha = 1.0f;
    public boolean clickable = false;
    public boolean enabled = true;
    public Padding padding = new Padding();
    public final List<ViewNode> children = new ArrayList<>();
    public final List<ComposeNode> composeNodes = new ArrayList<>();

    /**
     * Shorten resource id by stripping the package prefix before the slash.
     * e.g. "com.tencent.ibg.joox:id/btn_play" -> "btn_play"
     */
    static String shortenId(String id) {
        if (id == null || id.isEmpty()) {
            return id;
        }
        int slashIndex = id.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < id.length() - 1) {
            return id.substring(slashIndex + 1);
        }
        return id;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("className", shortenClassName(className));

        String shortId = shortenId(id);
        if (shortId != null && !shortId.isEmpty()) {
            json.put("id", shortId);
        }
        if (!text.isEmpty()) {
            json.put("text", text);
        }
        if (!contentDesc.isEmpty()) {
            json.put("contentDesc", contentDesc);
        }
        if (!tag.isEmpty()) {
            json.put("tag", tag);
        }

        json.put("bounds", bounds.toJsonArray());

        if (!"visible".equals(visibility)) {
            json.put("visibility", visibility);
        }
        if (Float.compare(alpha, 1.0f) != 0) {
            json.put("alpha", alpha);
        }
        if (clickable) {
            json.put("clickable", true);
        }
        if (!enabled) {
            json.put("enabled", false);
        }
        if (!padding.isAllZero()) {
            json.put("padding", padding.toJsonArray());
        }

        if (!children.isEmpty()) {
            JSONArray childArray = new JSONArray();
            for (ViewNode child : children) {
                childArray.put(child.toJson());
            }
            json.put("children", childArray);
        }

        if (!composeNodes.isEmpty()) {
            JSONArray composeArray = new JSONArray();
            for (ComposeNode composeNode : composeNodes) {
                composeArray.put(composeNode.toJson());
            }
            json.put("composeNodes", composeArray);
        }

        return json;
    }

    /**
     * Shorten className to simple class name by stripping the package prefix.
     * e.g. "com.tencent.mtt.hippy.views.text.HippyTextView" -> "HippyTextView"
     */
    static String shortenClassName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1) {
            return name.substring(lastDot + 1);
        }
        return name;
    }

    public static class Bounds {
        public int left;
        public int top;
        public int right;
        public int bottom;

        /**
         * Serialize as compact array [left, top, right, bottom].
         */
        JSONArray toJsonArray() {
            JSONArray arr = new JSONArray();
            arr.put(left);
            arr.put(top);
            arr.put(right);
            arr.put(bottom);
            return arr;
        }

        /**
         * Serialize as object for contexts requiring named fields (e.g. MatchedElement).
         */
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("left", left);
            json.put("top", top);
            json.put("right", right);
            json.put("bottom", bottom);
            return json;
        }

        int centerX() {
            return (left + right) / 2;
        }

        int centerY() {
            return (top + bottom) / 2;
        }
    }

    public static class Padding {
        public int left;
        public int top;
        public int right;
        public int bottom;

        boolean isAllZero() {
            return left == 0 && top == 0 && right == 0 && bottom == 0;
        }

        /**
         * Serialize as compact array [left, top, right, bottom].
         */
        JSONArray toJsonArray() {
            JSONArray arr = new JSONArray();
            arr.put(left);
            arr.put(top);
            arr.put(right);
            arr.put(bottom);
            return arr;
        }
    }
}
