package com.sickworm.intellij.jugg.viewhierarchy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewNode is the normalized JSON-ready node model for a single Android View.
 */
public class ViewNode {

    public String className = "";
    public String id = "";
    public String idHex = "";
    public String text = "";
    public String contentDesc = "";
    public String tag = "";
    public Bounds bounds = new Bounds();
    public String visibility = "visible";
    public float alpha = 1.0f;
    public boolean clickable = false;
    public boolean enabled = true;
    public boolean focused = false;
    public boolean selected = false;
    public Padding padding = new Padding();
    public final List<ViewNode> children = new ArrayList<>();
    public final List<ComposeNode> composeNodes = new ArrayList<>();

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("className", className);
        json.put("id", id);
        json.put("idHex", idHex);
        json.put("text", text);
        json.put("contentDesc", contentDesc);
        json.put("tag", tag);
        json.put("bounds", bounds.toJson());
        json.put("visibility", visibility);
        json.put("alpha", alpha);
        json.put("clickable", clickable);
        json.put("enabled", enabled);
        json.put("focused", focused);
        json.put("selected", selected);
        json.put("padding", padding.toJson());

        JSONArray childArray = new JSONArray();
        for (ViewNode child : children) {
            childArray.put(child.toJson());
        }
        json.put("children", childArray);

        JSONArray composeArray = new JSONArray();
        for (ComposeNode composeNode : composeNodes) {
            composeArray.put(composeNode.toJson());
        }
        json.put("composeNodes", composeArray);

        return json;
    }

    public static class Bounds {
        public int left;
        public int top;
        public int right;
        public int bottom;

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

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("left", left);
            json.put("top", top);
            json.put("right", right);
            json.put("bottom", bottom);
            return json;
        }
    }
}
