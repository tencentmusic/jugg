package com.sickworm.intellij.jugg.viewhierarchy;

import android.util.DisplayMetrics;
import android.widget.TextView;

import com.sickworm.intellij.jugg.hotfix.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * LayoutVerifier performs assertion-based UI property verification on one Dragonfly snapshot.
 * Supports both single-element property assertions (assert) and two-element relation checks (relation).
 * All pixel values are always converted to dp using DisplayMetrics.density.
 * textSizeSp is always in sp using scaledDensity.
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

        if (assertParams.has("tolerance")) {
            return errorResult(
                "assert does not support 'tolerance'. " +
                "To verify approximate values, use two separate asserts with 'gte' and 'lte'. " +
                "For layout gap checks with tolerance, use relation.type=spacing with tolerance.",
                null
            );
        }

        switch (property) {
            case "exists":
                return buildPassResult("exists", true, "exists", "dp");
            case "visibility":
                if (!target.hasProperty("visibility")) {
                    return unavailable(property, target);
                }
                return assertText(target.visibility, op, assertParams.optString("value", "visible"), "visibility");
            case "clickable":
                if (!target.hasProperty("clickable")) {
                    return unavailable(property, target);
                }
                return assertBoolean(target.clickable, assertParams.optBoolean("value", true), "clickable");
            case "enabled":
                if (!target.hasProperty("enabled")) {
                    return unavailable(property, target);
                }
                return assertBoolean(target.enabled, assertParams.optBoolean("value", true), "enabled");
            case "text":
                return assertText(target.text, op, assertParams.optString("value", ""), "text");
            case "alpha":
                if (!target.hasProperty("alpha")) {
                    return unavailable(property, target);
                }
                return assertDouble(target.alpha, op, assertParams.optDouble("value", 1.0), "alpha", null);
            case "textColor":
                return assertTextColor(target, op, assertParams.optString("value", ""));
            case "textSizeSp":
                return assertTextSizeSp(target, op, assertParams.optDouble("value", 0));
            case "bounds.width":
                return assertInt(boundsWidth(target), op, assertParams.optInt("value", 0), "bounds.width");
            case "bounds.height":
                return assertInt(boundsHeight(target), op, assertParams.optInt("value", 0), "bounds.height");
            case "bounds.left":
                return assertInt(target.bounds.left, op, assertParams.optInt("value", 0), "bounds.left");
            case "bounds.top":
                return assertInt(target.bounds.top, op, assertParams.optInt("value", 0), "bounds.top");
            case "bounds.right":
                return assertInt(target.bounds.right, op, assertParams.optInt("value", 0), "bounds.right");
            case "bounds.bottom":
                return assertInt(target.bounds.bottom, op, assertParams.optInt("value", 0), "bounds.bottom");
            case "padding.left":
                return assertPadding(target, op, assertParams, property, target.padding.left);
            case "padding.top":
                return assertPadding(target, op, assertParams, property, target.padding.top);
            case "padding.right":
                return assertPadding(target, op, assertParams, property, target.padding.right);
            case "padding.bottom":
                return assertPadding(target, op, assertParams, property, target.padding.bottom);
            case "backgroundColor": {
                if (target.view == null) {
                    return unavailable(property, target);
                }
                android.graphics.drawable.Drawable bg = target.view.getBackground();
                if (bg instanceof android.graphics.drawable.ColorDrawable) {
                    int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                    String actualHex = ViewNode.colorToHex(color).toUpperCase();
                    String normalizedExpected = assertParams.optString("value", "").toUpperCase();
                    return assertText(actualHex, op, normalizedExpected, "backgroundColor");
                }
                // Non-ColorDrawable backgrounds cannot be expressed as a single color
                return errorResult(
                    "backgroundColor is not a solid color (drawable type: " + (bg != null ? bg.getClass().getSimpleName() : "null") + "). " +
                    "Use screenshot comparison for gradient/shape backgrounds.",
                    null
                );
            }
            default:
                return errorResult("unsupported property: " + property, null);
        }
    }

    private JSONObject executeRelation(MatchedElement target, MatchedElement target2, JSONObject relationParams)
            throws JSONException {
        String type = relationParams.optString("type", "");
        String axis = resolveAxis(type, relationParams);
        String op = optString(relationParams, "op");
        if (op == null || op.isEmpty()) {
            op = "eq";
        }
        int expected = relationParams.optInt("expected", 0);
        int tolerance = relationParams.optInt("tolerance", 0);
        boolean hasTolerance = relationParams.has("tolerance");

        switch (type) {
            case "spacing":
                if (axis == null) {
                    return errorResult("unsupported axis for spacing: " + optString(relationParams, "axis")
                        + ". Use axis=x or axis=y.", null);
                }
                if (hasTolerance && relationParams.has("op")) {
                    return errorResult("type=spacing check 'op' and 'tolerance' are mutually exclusive", null);
                }
                return checkSpacing(target, target2, axis, expected, op, tolerance, hasTolerance);
            case "alignment":
                if (axis == null) {
                    return errorResult("unsupported axis for alignment: " + optString(relationParams, "axis")
                        + ". Use axis=x or axis=y.", null);
                }
                return checkAlignment(target, target2, axis);
            case "overlap":
                boolean expectOverlap = relationParams.optBoolean("expectOverlap", false);
                return checkOverlap(target, target2, expectOverlap);
            case "containment":
                return checkContainment(target, target2);
            case "order":
                if (axis == null) {
                    return errorResult("unsupported axis for order: " + optString(relationParams, "axis")
                        + ". Use axis=x or axis=y.", null);
                }
                return checkOrder(target, target2, axis);
            default:
                return errorResult("unsupported relation type: " + type, null);
        }
    }

    // ---- Assertion helpers ----

    private JSONObject assertInt(int actualPx, String op, int expectedValue, String property)
            throws JSONException {
        int actual = toDp(actualPx);
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
            case "neq":
                pass = actual != expectedValue;
                break;
            default: // "eq"
                pass = actual == expectedValue;
                break;
        }
        String message = property + " of element = " + actual + "dp"
            + " (expected: " + op + " " + expectedValue + "dp)";
        if (pass) {
            return buildPassResult(message, actual, expectedValue, "dp");
        }
        return buildFailResult(message, actual, expectedValue, "dp");
    }

    private JSONObject assertDouble(double actual, String op, double expected, String property, String unit)
            throws JSONException {
        boolean pass;
        switch (op) {
            case "gte":
                pass = actual >= expected - 0.001;
                break;
            case "lte":
                pass = actual <= expected + 0.001;
                break;
            case "gt":
                pass = actual > expected + 0.001;
                break;
            case "lt":
                pass = actual < expected - 0.001;
                break;
            case "neq":
                pass = Math.abs(actual - expected) >= 0.001;
                break;
            default: // eq
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
        if ("matches".equals(op)) {
            java.util.regex.Pattern compiled;
            try {
                compiled = java.util.regex.Pattern.compile(expected);
            } catch (java.util.regex.PatternSyntaxException e) {
                return errorResult("invalid regex pattern for " + property + ": \"" + expected + "\" (" + e.getMessage() + ")", null);
            }
            // Use find() so the pattern can match a substring, not the whole string.
            boolean pass = actual != null && compiled.matcher(actual).find();
            String message = property + " = \"" + actual + "\" (expected: matches \"" + expected + "\")";
            return pass ? buildPassResult(message, actual, expected, null) : buildFailResult(message, actual, expected, null);
        }
        boolean pass;
        switch (op) {
            case "contains":
                pass = actual != null && actual.contains(expected);
                break;
            case "neq":
                pass = !expected.equals(actual);
                break;
            default:
                pass = expected.equals(actual);
                break;
        }
        String message = property + " = \"" + actual + "\" (expected: " + op + " \"" + expected + "\")";
        return pass ? buildPassResult(message, actual, expected, null) : buildFailResult(message, actual, expected, null);
    }

    private JSONObject assertTextColor(MatchedElement target, String op, String expected) throws JSONException {
        int color;
        if (target.view instanceof TextView) {
            color = ((TextView) target.view).getCurrentTextColor();
        } else if (target.view != null) {
            return errorResult(
                "textColor assertion requires a TextView; got " + target.view.getClass().getSimpleName(),
                null
            );
        } else if (target.properties.containsKey("textColor")) {
            String actualHex = normalizeColor(target.properties.get("textColor"));
            if (actualHex == null) {
                return unavailable("textColor", target);
            }
            String normalizedExpected = expected != null ? expected.toUpperCase(Locale.ROOT) : "";
            return assertText(actualHex, op, normalizedExpected, "textColor");
        } else {
            return unavailable("textColor", target);
        }
        String actualHex = ViewNode.colorToHex(color).toUpperCase(Locale.ROOT);
        String normalizedExpected = expected != null ? expected.toUpperCase(Locale.ROOT) : "";
        return assertText(actualHex, op, normalizedExpected, "textColor");
    }

    private String normalizeColor(String value) {
        if (value == null || "Unspecified".equals(value)) {
            return null;
        }
        String hex = value.trim().replace("#", "");
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        if (hex.length() != 8) {
            return null;
        }
        try {
            Long.parseLong(hex, 16);
            return "#" + hex.toUpperCase(Locale.ROOT);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JSONObject assertTextSizeSp(MatchedElement target, String op, double expected) throws JSONException {
        double actualSp;
        if (target.view instanceof TextView) {
            float textSizePx = ((TextView) target.view).getTextSize();
            actualSp = textSizePx / displayMetrics.scaledDensity;
        } else if (target.view != null) {
            return errorResult(
                "textSizeSp assertion requires a TextView; got " + target.view.getClass().getSimpleName(),
                null
            );
        } else {
            Double composeTextSize = parseTextUnit(target.properties.get("fontSize"));
            if (composeTextSize == null) {
                return unavailable("textSizeSp", target);
            }
            actualSp = composeTextSize;
        }
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

    private JSONObject assertPadding(
        MatchedElement target,
        String op,
        JSONObject assertParams,
        String property,
        int actual
    ) throws JSONException {
        if (!target.hasProperty("padding")) {
            return unavailable(property, target);
        }
        return assertInt(actual, op, assertParams.optInt("value", 0), property);
    }

    private Double parseTextUnit(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("sp")) {
            return null;
        }
        try {
            return Double.parseDouble(normalized.substring(0, normalized.length() - 2).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JSONObject unavailable(String property, MatchedElement target) {
        return errorResult(
            "property " + property + " unavailable for node " + target.describe(),
            null
        );
    }

    // ---- Relation helpers ----

    private JSONObject checkSpacing(MatchedElement a, MatchedElement b, String axis,
                                    int expected, String op, int tolerance, boolean hasTolerance) throws JSONException {
        int spacingPx;
        if ("y".equals(axis)) {
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
        int actual = toDp(spacingPx);
        SpacingEval eval = evaluateSpacing(actual, expected, op, tolerance, hasTolerance);
        if (eval.error != null) {
            return errorResult(eval.error, null);
        }
        boolean pass = eval.pass;
        String boundsInfo = " [target:[" + a.bounds.left + "," + a.bounds.top + "," + a.bounds.right + "," + a.bounds.bottom + "]"
            + ", target2:[" + b.bounds.left + "," + b.bounds.top + "," + b.bounds.right + "," + b.bounds.bottom + "]]";
        String message;
        if (hasTolerance) {
            message = "spacing (axis=" + axis + ") = " + actual + "dp"
                + " (expected: " + expected + "dp ±" + tolerance + "dp)" + boundsInfo;
        } else {
            message = "spacing (axis=" + axis + ") = " + actual + "dp"
                + " (expected: " + op + " " + expected + "dp)" + boundsInfo;
        }
        return pass ? buildPassResult(message, actual, expected, "dp") : buildFailResult(message, actual, expected, "dp");
    }

    private JSONObject checkAlignment(MatchedElement a, MatchedElement b, String axis) throws JSONException {
        boolean pass;
        String desc;
        if ("x".equals(axis)) {
            // vertically aligned: same horizontal center
            int centerA = (a.bounds.left + a.bounds.right) / 2;
            int centerB = (b.bounds.left + b.bounds.right) / 2;
            pass = Math.abs(centerA - centerB) <= 2;
            desc = "axis=x -> X-center check: " + centerA + " vs " + centerB;
        } else {
            // horizontally aligned: same vertical center
            int centerA = (a.bounds.top + a.bounds.bottom) / 2;
            int centerB = (b.bounds.top + b.bounds.bottom) / 2;
            pass = Math.abs(centerA - centerB) <= 2;
            desc = "axis=y -> Y-center check: " + centerA + " vs " + centerB;
        }
        String message = "alignment (" + desc + ")";
        return pass ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    private JSONObject checkOverlap(MatchedElement a, MatchedElement b, boolean expectOverlap) throws JSONException {
        boolean overlaps = a.bounds.left < b.bounds.right
            && a.bounds.right > b.bounds.left
            && a.bounds.top < b.bounds.bottom
            && a.bounds.bottom > b.bounds.top;
        boolean pass = expectOverlap ? overlaps : !overlaps;
        String message = "overlap (expectOverlap=" + expectOverlap + "): "
            + (overlaps ? "elements overlap" : "no overlap");
        return pass ? buildPassResult(message, overlaps, expectOverlap, null)
                    : buildFailResult(message, overlaps, expectOverlap, null);
    }

    private JSONObject checkContainment(MatchedElement target, MatchedElement container) throws JSONException {
        boolean contained = target.bounds.left >= container.bounds.left
            && target.bounds.top >= container.bounds.top
            && target.bounds.right <= container.bounds.right
            && target.bounds.bottom <= container.bounds.bottom;
        String message = "containment: target(child) " + (contained ? "is inside" : "is NOT inside") + " target2(parent)";
        return contained ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    private JSONObject checkOrder(MatchedElement a, MatchedElement b, String axis) throws JSONException {
        boolean inOrder;
        String desc;
        if ("y".equals(axis)) {
            inOrder = a.bounds.top < b.bounds.top;
            desc = "vertical order: a.top=" + a.bounds.top + " b.top=" + b.bounds.top;
        } else {
            inOrder = a.bounds.left < b.bounds.left;
            desc = "horizontal order: a.left=" + a.bounds.left + " b.left=" + b.bounds.left;
        }
        String message = "order (axis=" + axis + "): " + desc;
        return inOrder ? buildPassResult(message, null, null, null) : buildFailResult(message, null, null, null);
    }

    private String resolveAxis(String type, JSONObject relationParams) {
        String axis = optString(relationParams, "axis");
        if (axis != null && !axis.isEmpty()) {
            String normalizedAxis = axis.toLowerCase();
            if ("x".equals(normalizedAxis) || "y".equals(normalizedAxis)) {
                return normalizedAxis;
            }
            return null;
        }
        String direction = optString(relationParams, "direction");
        if (direction != null) {
            if ("alignment".equals(type)) {
                return "vertical".equals(direction) ? "x" : "y";
            }
            if ("spacing".equals(type) || "order".equals(type)) {
                return "vertical".equals(direction) ? "y" : "x";
            }
        }
        // Preserve historical defaults when direction is omitted.
        if ("alignment".equals(type)) {
            return "y";
        }
        if ("spacing".equals(type) || "order".equals(type)) {
            return "x";
        }
        return null;
    }

    private SpacingEval evaluateSpacing(int actual, int expected, String op, int tolerance, boolean hasTolerance) {
        if (hasTolerance) {
            return new SpacingEval(Math.abs(actual - expected) <= tolerance, null);
        }
        switch (op) {
            case "eq":
                return new SpacingEval(actual == expected, null);
            case "neq":
                return new SpacingEval(actual != expected, null);
            case "gte":
                return new SpacingEval(actual >= expected, null);
            case "lte":
                return new SpacingEval(actual <= expected, null);
            case "gt":
                return new SpacingEval(actual > expected, null);
            case "lt":
                return new SpacingEval(actual < expected, null);
            default:
                return new SpacingEval(false, "unsupported op for spacing: " + op);
        }
    }

    private static final class SpacingEval {
        private final boolean pass;
        private final String error;

        private SpacingEval(boolean pass, String error) {
            this.pass = pass;
            this.error = error;
        }
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

    private int toDp(int px) {
        if (displayMetrics.density > 0) {
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
