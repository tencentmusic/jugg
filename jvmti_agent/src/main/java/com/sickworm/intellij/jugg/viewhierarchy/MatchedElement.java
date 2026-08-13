package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import com.sickworm.intellij.jugg.internal.dragonfly.node.HierarchyNode;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * MatchedElement keeps the normalized node and its optional runtime objects for follow-up actions.
 */
final class MatchedElement {

    final HierarchyNode hierarchyNode;
    final View view;
    final View rootView;
    final ViewNode node;
    final Map<String, String> properties;
    final String text;
    final String resourceId;
    final String contentDesc;
    final String className;
    final ViewNode.Bounds bounds;
    final int centerX;
    final int centerY;
    final String visibility;
    final boolean clickable;
    final boolean enabled;
    final float alpha;
    final ViewNode.Padding padding;

    MatchedElement(
        HierarchyNode hierarchyNode,
        View view,
        View rootView,
        ViewNode node,
        Map<String, String> properties
    ) {
        this.hierarchyNode = hierarchyNode;
        this.view = view;
        this.rootView = rootView;
        this.node = node;
        this.properties = properties == null ? Collections.emptyMap() : properties;
        this.text = node.text;
        this.resourceId = node.id;
        this.contentDesc = node.contentDesc;
        this.className = node.className;
        this.bounds = node.bounds;
        this.centerX = node.bounds.centerX();
        this.centerY = node.bounds.centerY();
        this.visibility = node.visibility;
        this.clickable = node.clickable;
        this.enabled = node.enabled;
        this.alpha = node.alpha;
        this.padding = node.padding;
    }

    Object inspectTarget() {
        return view != null ? view : hierarchyNode;
    }

    boolean hasProperty(String property) {
        return view != null || properties.containsKey(property);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("text", text);
        json.put("resourceId", resourceId);
        json.put("contentDesc", contentDesc);
        json.put("className", className);
        json.put("bounds", bounds.toJson());
        json.put("centerX", centerX);
        json.put("centerY", centerY);
        json.put("visibility", visibility);
        json.put("clickable", clickable);
        return json;
    }

    JSONObject toMatchedElementJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("text", text);
        json.put("className", ViewNode.shortenClassName(className));
        json.put("resourceId", ViewNode.shortenId(resourceId));
        json.put("contentDesc", contentDesc);
        JSONArray boundsArray = new JSONArray();
        boundsArray.put(bounds.left);
        boundsArray.put(bounds.top);
        boundsArray.put(bounds.right);
        boundsArray.put(bounds.bottom);
        json.put("bounds", boundsArray);
        json.put("centerX", centerX);
        json.put("centerY", centerY);
        return json;
    }

    String describe() {
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.isEmpty()) {
            sb.append("text=\"").append(text).append("\"");
        }
        if (resourceId != null && !resourceId.isEmpty()) {
            appendWithComma(sb);
            sb.append("resourceId=\"").append(resourceId).append("\"");
        }
        if (className != null && !className.isEmpty()) {
            appendWithComma(sb);
            sb.append("class=\"").append(className).append("\"");
        }
        appendWithComma(sb);
        sb.append("bounds=[").append(bounds.left).append(',').append(bounds.top)
            .append("][").append(bounds.right).append(',').append(bounds.bottom).append(']');
        return sb.toString();
    }

    private void appendWithComma(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
    }
}
