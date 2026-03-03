package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MatchedElement captures one selector match and keeps the original View reference
 * for follow-up tap execution.
 */
final class MatchedElement {

    final View view;
    final WindowInfo window;
    final String text;
    final String resourceId;
    final String contentDesc;
    final String className;
    final ViewNode.Bounds bounds;
    final int centerX;
    final int centerY;
    final String visibility;
    final boolean clickable;

    MatchedElement(
        View view,
        WindowInfo window,
        String text,
        String resourceId,
        String contentDesc,
        String className,
        ViewNode.Bounds bounds,
        String visibility,
        boolean clickable
    ) {
        this.view = view;
        this.window = window;
        this.text = text;
        this.resourceId = resourceId;
        this.contentDesc = contentDesc;
        this.className = className;
        this.bounds = bounds;
        this.centerX = bounds.centerX();
        this.centerY = bounds.centerY();
        this.visibility = visibility;
        this.clickable = clickable;
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
