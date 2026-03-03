package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * WindowInfo keeps one root window entry for traversal and JSON export.
 */
public class WindowInfo {

    public final String windowType;
    public final String title;
    public final View rootView;

    public WindowInfo(String windowType, String title, View rootView) {
        this.windowType = windowType;
        this.title = title;
        this.rootView = rootView;
    }

    public JSONObject toJson(ViewNode rootNode) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("windowType", windowType);
        json.put("title", title);
        json.put("root", rootNode != null ? rootNode.toJson() : new JSONObject());
        return json;
    }
}
