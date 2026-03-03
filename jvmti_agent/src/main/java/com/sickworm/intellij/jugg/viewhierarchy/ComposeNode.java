package com.sickworm.intellij.jugg.viewhierarchy;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ComposeNode is a protocol placeholder for future Compose semantics export.
 *
 * This version is intentionally minimal because Compose traversal is out of scope
 * for the current release.
 */
public class ComposeNode {

    public String semanticsId = "";
    public String role = "";
    public String text = "";

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("semanticsId", semanticsId);
        json.put("role", role);
        json.put("text", text);
        return json;
    }
}
