package com.sickworm.intellij.jugg.viewhierarchy;

import android.util.DisplayMetrics;
import android.view.View;
import android.widget.TextView;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * LayoutVerifier performs assertion-based UI property verification on live View objects.
 * Supports both single-element property assertions (assert) and two-element relation checks (relation).
 * All pixel values are converted to dp/sp using DisplayMetrics when unit="dp"/"sp".
 */
public class LayoutVerifier {

    private static final String TAG = "Jugg#LayoutVerifier";

    private final ElementFinder elementFinder;
    private final DisplayMetrics displayMetrics;

    public LayoutVerifier(ElementFinder elementFinder, DisplayMetrics displayMetrics) {
        this.elementFinder = elementFinder;
        this.displayMetrics = displayMetrics;
    }

    /**
     * Execute a verify request described by params JSON.
     * Returns PASS/FAIL/ERROR result object.
     */
    public JSONObject verify(JSONObject params) {
        try {
            JSONObject targetSelector = params.optJSONObject("target");
            JSONObject assertParams = params.optJSONObject("assert");
            JSONObject relationParams = params.optJSONObject("relation");

            if (targetSelector == null) {
                return errorResult("target is required", null);
            }
            if (assertParams == null && relationParams == null) {
                return errorResult("assert or relation is required", null);
            }

            List<MatchedElement> targets = findElements(targetSelector, true);
            if (targets.isEmpty()) {
                return notFoundResult("target", targetSelector);
            }
            MatchedElement target = targets.get(0);

            if (assertParams != null) {
                return executeAssert(target, assertParams);
            }

            // relation mode requires target2
            JSONObject target2Selector = params.optJSONObject("target2");
            if (target2Selector == null) {
                return errorResult("target2 is required for relation checks", null);
            }
            List<MatchedElement> targets2 = findElements(target2Selector, true);
            if (targets2.isEmpty()) {
                return notFoundResult("target2", target2Selector);
            }
            return executeRelation(target, targets2.get(0), relationParams);

        } catch (Throwable t) {
            LogUtils.e(TAG, "verify failed", t);
            return errorResult("verify failed: " + t.getMessage(), null);
        }
    }

    private List<MatchedElement> findElements(JSONObject selector, boolean topWindowOnly) {
        String text = optString(selector, "text");
        String resourceId = optString(selector, "resourceId");
        String contentDesc = optString(selector, "contentDesc");
        String className = optString(selector, "className");
        return elementFinder.find(text, resourceId, contentDesc, className, topWindowOnly);
    }

    private JSONObject executeAssert(MatchedElement target, JSONObject assertParams) throws JSONException {
        String property = assertParams.optString("property", "");
        String op = assertParams.optString("op", "eq");
        String unit = optString(assertParams, "unit");

        switch (property) {
            case "exists":
                return buildPassResult("exists", true, "exists", unit);
            case "visibility":
                return assertText(target.visibility, op, assertParams.optString("value", "visible"), "visibility");
            case "clickable":
                return assertBoolean(target.clickable, assertParams.optBoolean("value", true), "clickable");
            case "enabled":
                return assertBoolean(target.view.isEnabled(), assertParams.optBoolean("value", true), "enabled");
            case "text":
                return assertText(target.text, op, assertParams.optString("value", ""), "text");
            case "alpha":
                return assertDouble(target.view.getAlpha(), op, assertParams.optDouble("value", 1.0), "alpha", null);
            case "textColor":
                return assertTextColor(target.view, op, assertParams.optString("value", ""));
            case "textSizeSp":
                return assertTextSizeSp(target.view, op, assertParams.optDouble("value", 0), unit);
            case "bounds.width":
                return assertInt(boundsWidth(target), op, assertParams.optInt("value", 0), "bounds.width", unit);
            case "bounds.height":
                return assertInt(boundsHeight(target), op, assertParams.optInt("value", 0), "bounds.height", unit);
            case "bounds.left":
                return assertInt(target.bounds.left, op, assertParams.optInt("value", 0), "bounds.left", unit);
            case "bounds.top":
                return assertInt(target.bounds.top, op, assertParams.optInt("value", 0), "bounds.top", unit);
            case "bounds.right":
                return assertInt(target.bounds.right, op, assertParams.optInt("value", 0), "bounds.right", unit);
            case "bounds.bottom":
                return assertInt(target.bounds.bottom, op, assertParams.optInt("value", 0), "bounds.bottom", unit);
            case "padding.left":
                return assertInt(target.view.getPaddingLeft(), op, assertParams.optInt("value", 0), "padding.left", unit);
            case "padding.top":
                return assertInt(target.view.getPaddingTop(), op, assertParams.optInt("value", 0), "padding.top", unit);
            case "padding.right":
                return assertInt(target.view.getPaddingRight(), op, assertParams.optInt("value", 0), "padding.right", unit);
            case "padding.bottom":
                return assertInt(target.view.getPaddingBottom(), op, assertParams.optInt("value", 0), "padding.bottom", unit);
            default:
                return errorResult("unsupported property: " + property, null);
        }
    }

    private JSONObject executeRelation(MatchedElement target, MatchedElement target2, JSONObject relationParams)
            throws JSONException {
        String type = relationParams.optString("type", "");
        String direction = optString(relationParams, "direction");
        int expected = relationParams.optInt("expected", 0);
        int tolerance = relationParams.optInt("tolerance", 0);
        String unit = optString(relationParams, "unit");

        switch (type) {
            case "spacing":
                return checkSpacing(target, target2, direction, expected, tolerance, unit);
            case "alignment":
                return checkAlignment(target, target2, direction);
            case "overlap":
                return checkOverlap(target, target2);
            case "containment":
                return checkContainment(target, target2);
            case "order":
                return checkOrder(target, target2, direction);
            default:
                return errorResult("unsupported relation type: " + type, null);
        }
    }

    // ---- Assertion helpers ----

    private JSONObject assertInt(int actualPx, String op, int expectedValue, String property, String unit)
            throws JSONException {
        int actual = toDpIfNeeded(actualPx, unit);
        String unitLabel = unit != null ? unit : "px";
        boolean pass;
        switch (op) {
            case "gte":
                pass = actual >= expectedValue;
                break;
            case "lte":
                pass = actual <= expectedValue;
                break;
            case "gt":
                pass = actual > expectedValue;
                break;
            case "lt":
                pass = actual < expectedValue;
                break;
            default: // "eq"
                pass = actual == expectedValue;
                break;
        }
        String message = property + " of element = " + actual + unitLabel
            + " (expected: " + op + " " + expectedValue + unitLabel + ")";
        if (pass) {
            return buildPassResult(message, actual, expectedValue, unit);
        }
        return buildFailResult(message, actual, expectedValue, unit);
    }

    private JSONObject assertDouble(double actual, String op, double expected, String property, String unit)
            throws JSONException {
        boolean pass;
        switch (op) {
            case "gte":
                pass = actual >= expected;
                break;
            case "lte":
                pass = actual <= expected;
                break;
            default:
                pass = Math.abs(actual - expected) < 0.001;
                break;
        }
        String message = property + " = " + actual + " (expected: " + op + " " + expected + ")";
        return pass ? buildPassResult(message, actual, expected, unit) : buildFailResult(message, actual, expected, unit);
    }

    private JSONObject assertBoolean(boolean actual, boolean expected, String property) throws JSONException {
        boolean pass = actual == expected;
        String message = property + " = " + actual + " (expected: " + expected + ")";
        return pass ? buildPassResult(message, actual, expected, null) : buildFailResult(message, actual, expected, null);
    }

    private JSONObject assertText(String actual, String op, String expected, String property) throws JSONException {
        boolean pass;
        switch (op) {
            case "contains":
                pass = actual != null && actual.contains(expected);
                break;
            case "matches":
                pass = actual != null && actual.matches(expected);
                break;
            default:
                pass = expected.equals(actual);
                break;
        }
        String message = property + " = \"" + actual + "\" (expected: " + op + " \"" + expected + "\")";
        return pass ? buildPassResult(message, actual, expected, null) : buildFailResult(message, actual, expected, null);
    }

    private JSONObject assertTextColor(View view, String op, String expected) throws JSONException {
        if (!(view instanceof TextView)) {
            return errorResult("textColor assertion requires a TextView; got " + view.getClass().getSimpleName(), null);
        }
        int color = ((TextView) view).getCurrentTextColor();
        String actualHex = ViewNode.colorToHex(color);
        boolean pass = "eq".equals(op) ? actualHex.equalsIgnoreCase(expected) : actualHex.equalsIgnoreCase(expected);
        String message = "textColor = " + actualHex + " (expected: " + expected + ")";
        return pass ? buildPassResult(message, actualHex, expected, null) : buildFailResult(message, actualHex, expected, null);
    }

    private JSONObject assertTextSizeSp(View view, String op, double expected, String unit) throws JSONException {
        if (!(view instanceof TextView)) {
            return errorResult("textSizeSp assertion requires a TextView; got " + view.getClass().getSimpleName(), null);
        }
        float textSizePx = ((TextView) view).getTextSize();
        double actualSp = textSizePx / displayMetrics.scaledDensity;
        boolean pass;
        switch (op) {
            case "gte":
                pass = actualSp >= expected;
                break;
            case "lte":
                pass = actualSp <= expected;
                break;
            default:
                pass = Math.abs(actualSp - expected) < 0.5;
                break;
        }
        String message = "textSizeSp = " + String.format("%.1f", actualSp) + "sp (expected: " + op + " " + expected + "sp)";
        return pass ? buildPassResult(message, actualSp, expected, "sp") : buildFailResult(message, actualSp, expected, "sp");
    }

    // ---- Relation helpers ----

    private JSONObject checkSpacing(MatchedElement a, MatchedElement b, String direction,
                                    int expected, int tolerance, String unit) throws JSONException {
        int spacingPx;
        if ("vertical".equals(direction)) {
            // vertical spacing: gap between bottom of a and top of b (or vice versa)
            int topOfLower = Math.max(a.bounds.top, b.bounds.top);
            int bottomOfUpper = Math.min(a.bounds.bottom, b.bounds.bottom);
            spacingPx = topOfLower - bottomOfUpper;
        } else {
            // horizontal spacing: gap between right of left-element and left of right-element
            int leftOfRight = Math.max(a.bounds.left, b.bounds.left);
            int rightOfLeft = Math.min(a.bounds.right, b.bounds.right);
            spacingPx = leftOfRight - rightOfLeft;
        }
        int actual = toDpIfNeeded(spacingPx, unit);
        String unitLabel = unit != null ? unit : "px";
        boolean pass = Math.abs(actual - expected) <= tolerance;
        String message = "spacing (" + direction + ") = " + actual + unitLabel
            + " (expected: " + expected + unitLabel + " ±" + tolerance + unitLabel + ")";
        return pass ? buildPassResult(message, actual, expected, unit) : buildFailResult(message, actual, expected, unit);
    }

    private JSONObject checkAlignment(MatchedElement a, MatchedElement b, String direction) throws JSONException {
        boolean pass;
        String desc;
        if ("vertical".equals(direction)) {
            // vertically aligned: same horizontal center
            int centerA = (a.bounds.left + a.bounds.right) / 2;
            int centerB = (b.bounds.left + b.bounds.right) / 2;
            pass = Math.abs(centerA - centerB) <= 2;
            desc = "horizontal centers: " + centerA + " vs " + centerB;
        } else {
            // horizontally aligned: same vertical center
            int centerA = (a.bounds.top + a.bounds.bottom) / 2;
            int centerB = (b.bounds.top + b.bounds.bottom) / 2;
            pass = Math.abs(centerA - centerB) <= 2;
            desc = "vertical centers: " + centerA + " vs " + centerB;
        }
        String message = "alignment (" + direction + "): " + desc;
        return pass ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    private JSONObject checkOverlap(MatchedElement a, MatchedElement b) throws JSONException {
        boolean overlaps = a.bounds.left < b.bounds.right
            && a.bounds.right > b.bounds.left
            && a.bounds.top < b.bounds.bottom
            && a.bounds.bottom > b.bounds.top;
        String message = "overlap check: " + (overlaps ? "elements overlap" : "no overlap");
        // PASS when no overlap (expected non-overlapping)
        return !overlaps ? buildPassResult(message, false, false, null) : buildFailResult(message, true, false, null);
    }

    private JSONObject checkContainment(MatchedElement target, MatchedElement container) throws JSONException {
        boolean contained = target.bounds.left >= container.bounds.left
            && target.bounds.top >= container.bounds.top
            && target.bounds.right <= container.bounds.right
            && target.bounds.bottom <= container.bounds.bottom;
        String message = "containment: target " + (contained ? "is inside" : "is NOT inside") + " container";
        return contained ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    private JSONObject checkOrder(MatchedElement a, MatchedElement b, String direction) throws JSONException {
        boolean inOrder;
        String desc;
        if ("vertical".equals(direction)) {
            inOrder = a.bounds.top < b.bounds.top;
            desc = "vertical order: a.top=" + a.bounds.top + " b.top=" + b.bounds.top;
        } else {
            inOrder = a.bounds.left < b.bounds.left;
            desc = "horizontal order: a.left=" + a.bounds.left + " b.left=" + b.bounds.left;
        }
        String message = "order (" + direction + "): " + desc;
        return inOrder ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    // ---- Result builders ----

    private JSONObject buildPassResult(String message, Object actual, Object expected, String unit)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("result", "PASS");
        result.put("message", message);
        if (actual != null) {
            result.put("actual", actual);
        }
        if (expected != null) {
            result.put("expected", expected);
        }
        if (unit != null) {
            result.put("unit", unit);
        }
        return wrapOk(result);
    }

    private JSONObject buildFailResult(String message, Object actual, Object expected, String unit)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("result", "FAIL");
        result.put("message", message);
        if (actual != null) {
            result.put("actual", actual);
        }
        if (expected != null) {
            result.put("expected", expected);
        }
        if (unit != null) {
            result.put("unit", unit);
        }
        return wrapOk(result);
    }

    private JSONObject notFoundResult(String targetName, JSONObject selector) {
        try {
            // Return clickable candidates as hints
            List<MatchedElement> candidates = elementFinder.findClickableCandidates(5, true);
            JSONObject data = new JSONObject();
            data.put("result", "ERROR");
            data.put("message", targetName + " not found: " + describeSelector(selector));
            JSONArray candidatesArray = new JSONArray();
            for (MatchedElement c : candidates) {
                candidatesArray.put(c.toMatchedElementJson());
            }
            data.put("candidates", candidatesArray);
            return wrapOk(data);
        } catch (Throwable t) {
            return errorResult(targetName + " not found", null);
        }
    }

    private JSONObject errorResult(String message, JSONObject extra) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "error");
            response.put("message", message);
            JSONObject data = new JSONObject();
            data.put("result", "ERROR");
            data.put("message", message);
            if (extra != null) {
                response.put("extra", extra);
            }
            response.put("data", data);
        } catch (Throwable ignore) {
        }
        return response;
    }

    private JSONObject wrapOk(JSONObject data) {
        JSONObject response = new JSONObject();
        try {
            response.put("status", "ok");
            response.put("data", data);
        } catch (Throwable ignore) {
        }
        return response;
    }

    // ---- Utilities ----

    private int boundsWidth(MatchedElement e) {
        return e.bounds.right - e.bounds.left;
    }

    private int boundsHeight(MatchedElement e) {
        return e.bounds.bottom - e.bounds.top;
    }

    private int toDpIfNeeded(int px, String unit) {
        if ("dp".equals(unit) && displayMetrics.density > 0) {
            return Math.round(px / displayMetrics.density);
        }
        return px;
    }

    private String describeSelector(JSONObject selector) {
        if (selector == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        String resourceId = optString(selector, "resourceId");
        String text = optString(selector, "text");
        String contentDesc = optString(selector, "contentDesc");
        if (resourceId != null) {
            sb.append("resourceId=").append(resourceId);
        }
        if (text != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("text=").append(text);
        }
        if (contentDesc != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("contentDesc=").append(contentDesc);
        }
        return sb.toString();
    }

    private String optString(JSONObject params, String key) {
        Object value = params.opt(key);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? null : str;
    }
}
