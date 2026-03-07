package com.sickworm.intellij.jugg.viewhierarchy;

import android.util.DisplayMetrics;
import android.view.View;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link LayoutVerifier} using FixedElementFinder (dependency injection) and
 * Mockito-mocked Views, mirroring the FixedWindowsDumper pattern from ElementFinderTest.
 *
 * <p>Gap annotations document cases that cannot be covered by dumpFile mode and require live queries.
 */
public class LayoutVerifierTest {

    // ---- Test: exists assertion when element found → PASS ----

    @Test
    public void verify_shouldPassForExistsWhenElementFound() throws JSONException {
        TextView view = mockTextView("Hello", View.VISIBLE, true, 200, 60, 50, 100);
        LayoutVerifier verifier = buildSingleElementVerifier("tv_hello", "Hello", view,
            50, 100, 250, 160, 3.0f, 3.0f);

        JSONObject params = new JSONObject();
        params.put("target", selectorById("tv_hello"));
        params.put("assert", assertProperty("exists"));

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        Assert.assertEquals("PASS", response.getJSONObject("data").getString("result"));
    }

    // ---- Test: target not found → ERROR with candidates list ----

    @Test
    public void verify_shouldReturnErrorWithCandidatesWhenNotFound() throws JSONException {
        // Verifier has no elements; any selector → not found
        LayoutVerifier verifier = new LayoutVerifier(
            new FixedElementFinder(Collections.emptyMap()),
            makeDisplayMetrics(3.0f, 3.0f)
        );

        JSONObject params = new JSONObject();
        params.put("target", selectorById("nonexistent"));
        params.put("assert", assertProperty("exists"));

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        JSONObject data = response.getJSONObject("data");
        Assert.assertEquals("ERROR", data.getString("result"));
        Assert.assertTrue(data.getString("message").contains("not found"));
    }

    // ---- Test: text eq assertion → PASS ----

    @Test
    public void verify_shouldPassForTextEq() throws JSONException {
        // LayoutVerifier reads target.text (MatchedElement.text), not view.getText()
        TextView view = mockTextView("Submit", View.VISIBLE, true, 200, 60, 0, 0);
        LayoutVerifier verifier = buildSingleElementVerifier("btn_submit", "Submit", view,
            0, 0, 200, 60, 3.0f, 3.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "text");
        assertObj.put("value", "Submit");

        JSONObject params = new JSONObject();
        params.put("target", selectorById("btn_submit"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        JSONObject data = response.getJSONObject("data");
        Assert.assertEquals("PASS", data.getString("result"));
        Assert.assertEquals("Submit", data.getString("actual"));
    }

    // ---- Test: textSizeSp assertion (live-only capability, not available in dumpFile mode) ----
    // GAP NOTE: dumpFile JSON does not carry textSizePx/scaledDensity; textSizeSp requires live query.

    @Test
    public void verify_shouldPassForTextSizeSp() throws JSONException {
        // scaledDensity=2.0, textSizePx=32 → sp=16
        TextView view = mockTextView("Hello", View.VISIBLE, true, 200, 60, 0, 0);
        Mockito.when(view.getTextSize()).thenReturn(32f);
        LayoutVerifier verifier = buildSingleElementVerifier("tv", "Hello", view,
            0, 0, 200, 60, 3.0f, 2.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "textSizeSp");
        assertObj.put("value", 16.0);

        JSONObject params = new JSONObject();
        params.put("target", selectorById("tv"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        Assert.assertEquals("PASS", response.getJSONObject("data").getString("result"));
    }

    // ---- Test: textSizeSp on a non-TextView → ERROR (errorResult wraps with status=error) ----

    @Test
    public void verify_shouldReturnErrorForTextSizeSpOnNonTextView() throws JSONException {
        View plainView = mockPlainView(View.VISIBLE, true, 100, 50, 0, 0);
        LayoutVerifier verifier = buildSinglePlainViewVerifier("plain_view", plainView,
            0, 0, 100, 50, 3.0f, 3.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "textSizeSp");
        assertObj.put("value", 14.0);

        JSONObject params = new JSONObject();
        params.put("target", selectorById("plain_view"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        // errorResult() sets status="error"; data.result="ERROR" message describes the cause
        Assert.assertEquals("error", response.getString("status"));
        Assert.assertEquals("ERROR", response.getJSONObject("data").getString("result"));
        Assert.assertTrue(response.getJSONObject("data").getString("message").contains("TextView"));
    }

    // ---- Test: textColor on TextView → PASS ----
    // GAP NOTE: dumpFile only stores textColor if non-zero (see ViewNode.toJson). Live always reads getCurrentTextColor().

    @Test
    public void verify_shouldPassForTextColorOnTextView() throws JSONException {
        // 0xFFFF0000 = opaque red
        TextView view = mockTextView("Red", View.VISIBLE, true, 200, 60, 0, 0);
        Mockito.when(view.getCurrentTextColor()).thenReturn(0xFFFF0000);
        LayoutVerifier verifier = buildSingleElementVerifier("tv_red", "Red", view,
            0, 0, 200, 60, 3.0f, 3.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "textColor");
        assertObj.put("value", "#FFFF0000");

        JSONObject params = new JSONObject();
        params.put("target", selectorById("tv_red"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        Assert.assertEquals("PASS", response.getJSONObject("data").getString("result"));
    }

    // ---- Test: textColor on non-TextView → ERROR ----

    @Test
    public void verify_shouldReturnErrorForTextColorOnNonTextView() throws JSONException {
        View plainView = mockPlainView(View.VISIBLE, true, 100, 50, 0, 0);
        LayoutVerifier verifier = buildSinglePlainViewVerifier("plain_view", plainView,
            0, 0, 100, 50, 3.0f, 3.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "textColor");
        assertObj.put("value", "#FF000000");

        JSONObject params = new JSONObject();
        params.put("target", selectorById("plain_view"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("error", response.getString("status"));
        Assert.assertEquals("ERROR", response.getJSONObject("data").getString("result"));
        Assert.assertTrue(response.getJSONObject("data").getString("message").contains("TextView"));
    }

    // ---- Test: alpha with op=gte → PASS ----

    @Test
    public void verify_shouldPassForAlphaGte() throws JSONException {
        TextView view = mockTextView("Alpha", View.VISIBLE, true, 100, 50, 0, 0);
        Mockito.when(view.getAlpha()).thenReturn(0.8f);
        LayoutVerifier verifier = buildSingleElementVerifier("tv_alpha", "Alpha", view,
            0, 0, 100, 50, 1.0f, 1.0f);

        JSONObject assertObj = new JSONObject();
        assertObj.put("property", "alpha");
        assertObj.put("op", "gte");
        assertObj.put("value", 0.5);

        JSONObject params = new JSONObject();
        params.put("target", selectorById("tv_alpha"));
        params.put("assert", assertObj);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        Assert.assertEquals("PASS", response.getJSONObject("data").getString("result"));
    }

    // ---- Test: spacing relation end-to-end ----

    @Test
    public void verify_shouldPassForSpacingRelation() throws JSONException {
        // Two views: A bounds=[0,0,300,100], B bounds=[0,116,300,200] → spacing=116-100=16px
        View viewA = mockPlainView(View.VISIBLE, true, 300, 100, 0, 0);
        View viewB = mockPlainView(View.VISIBLE, true, 300, 84, 0, 116);

        Map<String, MatchedElement> elementMap = new HashMap<>();
        elementMap.put("view_a", makeElement("view_a", "", viewA, makeBounds(0, 0, 300, 100)));
        elementMap.put("view_b", makeElement("view_b", "", viewB, makeBounds(0, 116, 300, 200)));
        LayoutVerifier verifier = new LayoutVerifier(
            new FixedElementFinder(elementMap),
            makeDisplayMetrics(1.0f, 1.0f)
        );

        JSONObject params = new JSONObject();
        params.put("target", selectorById("view_a"));
        params.put("target2", selectorById("view_b"));
        JSONObject relation = new JSONObject();
        relation.put("type", "spacing");
        relation.put("direction", "vertical");
        relation.put("expected", 16);
        relation.put("tolerance", 0);
        params.put("relation", relation);

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("ok", response.getString("status"));
        Assert.assertEquals("PASS", response.getJSONObject("data").getString("result"));
    }

    // ---- Test: missing assert and relation → ERROR ----

    @Test
    public void verify_shouldReturnErrorWhenAssertAndRelationBothNull() throws JSONException {
        TextView view = mockTextView("Hello", View.VISIBLE, true, 200, 60, 0, 0);
        LayoutVerifier verifier = buildSingleElementVerifier("tv", "Hello", view,
            0, 0, 200, 60, 3.0f, 3.0f);

        JSONObject params = new JSONObject();
        params.put("target", selectorById("tv"));
        // No assert or relation → errorResult("assert or relation is required", null)

        JSONObject response = verifier.verify(params);
        Assert.assertEquals("error", response.getString("status"));
        Assert.assertTrue(response.getString("message").contains("assert or relation"));
    }

    // ================================================================
    // Helpers
    // ================================================================

    private JSONObject selectorById(String id) throws JSONException {
        JSONObject sel = new JSONObject();
        sel.put("resourceId", id);
        return sel;
    }

    private JSONObject assertProperty(String property) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("property", property);
        return obj;
    }

    /**
     * Build LayoutVerifier backed by a single TextView with explicit text and bounds.
     */
    private LayoutVerifier buildSingleElementVerifier(String resourceId, String text, TextView view,
                                                       int left, int top, int right, int bottom,
                                                       float density, float scaledDensity) {
        Map<String, MatchedElement> map = new HashMap<>();
        map.put(resourceId, makeElement(resourceId, text, view, makeBounds(left, top, right, bottom)));
        return new LayoutVerifier(new FixedElementFinder(map), makeDisplayMetrics(density, scaledDensity));
    }

    /**
     * Build LayoutVerifier backed by a single plain View (non-TextView).
     */
    private LayoutVerifier buildSinglePlainViewVerifier(String resourceId, View view,
                                                         int left, int top, int right, int bottom,
                                                         float density, float scaledDensity) {
        Map<String, MatchedElement> map = new HashMap<>();
        map.put(resourceId, makeElement(resourceId, "", view, makeBounds(left, top, right, bottom)));
        return new LayoutVerifier(new FixedElementFinder(map), makeDisplayMetrics(density, scaledDensity));
    }

    private MatchedElement makeElement(String resourceId, String text, View view, ViewNode.Bounds bounds) {
        return new MatchedElement(
            view,
            new WindowInfo("activity", "Main", null),
            text,
            "com.example:id/" + resourceId,
            "",
            view.getClass().getSimpleName(),
            bounds,
            "visible",
            false
        );
    }

    private ViewNode.Bounds makeBounds(int left, int top, int right, int bottom) {
        ViewNode.Bounds b = new ViewNode.Bounds();
        b.left = left;
        b.top = top;
        b.right = right;
        b.bottom = bottom;
        return b;
    }

    private DisplayMetrics makeDisplayMetrics(float density, float scaledDensity) {
        DisplayMetrics dm = new DisplayMetrics();
        dm.density = density;
        dm.scaledDensity = scaledDensity;
        return dm;
    }

    private TextView mockTextView(String text, int visibility, boolean shown, int width, int height, int left, int top) {
        TextView view = Mockito.mock(TextView.class);
        Mockito.when(view.getText()).thenReturn(text);
        Mockito.when(view.getVisibility()).thenReturn(visibility);
        Mockito.when(view.isShown()).thenReturn(shown);
        Mockito.when(view.getWidth()).thenReturn(width);
        Mockito.when(view.getHeight()).thenReturn(height);
        Mockito.when(view.isEnabled()).thenReturn(true);
        Mockito.when(view.getAlpha()).thenReturn(1.0f);
        Mockito.when(view.getCurrentTextColor()).thenReturn(0xFF000000);
        Mockito.when(view.getTextSize()).thenReturn(42f);
        stubLocation(view, left, top);
        return view;
    }

    private View mockPlainView(int visibility, boolean shown, int width, int height, int left, int top) {
        View view = Mockito.mock(View.class);
        Mockito.when(view.getVisibility()).thenReturn(visibility);
        Mockito.when(view.isShown()).thenReturn(shown);
        Mockito.when(view.getWidth()).thenReturn(width);
        Mockito.when(view.getHeight()).thenReturn(height);
        Mockito.when(view.isEnabled()).thenReturn(true);
        Mockito.when(view.getAlpha()).thenReturn(1.0f);
        stubLocation(view, left, top);
        return view;
    }

    private void stubLocation(View view, int left, int top) {
        Mockito.doAnswer(invocation -> {
            int[] location = invocation.getArgument(0);
            location[0] = left;
            location[1] = top;
            return null;
        }).when(view).getLocationOnScreen(ArgumentMatchers.any(int[].class));
    }

    // ---- FixedElementFinder: maps resourceId → MatchedElement ----

    /**
     * Lookup-by-resourceId element finder for deterministic test isolation.
     * Returns the element whose resourceId (short form after '/') matches the selector resourceId.
     * Falls back to empty list when not found.
     */
    private static final class FixedElementFinder extends ElementFinder {

        private final Map<String, MatchedElement> byResourceId;

        FixedElementFinder(Map<String, MatchedElement> byResourceId) {
            super(new EmptyViewTreeDumper());
            this.byResourceId = byResourceId;
        }

        @Override
        public List<MatchedElement> find(
            String text, String resourceId, String contentDesc, String className, boolean topWindowOnly
        ) {
            if (resourceId != null) {
                // Strip package prefix to get short id
                String shortId = resourceId.contains("/")
                    ? resourceId.substring(resourceId.lastIndexOf('/') + 1)
                    : resourceId;
                MatchedElement element = byResourceId.get(shortId);
                if (element != null) {
                    return Collections.singletonList(element);
                }
            }
            return Collections.emptyList();
        }

        @Override
        public List<MatchedElement> findClickableCandidates(int limit, boolean topWindowOnly) {
            return Collections.emptyList();
        }

        @Override
        public List<MatchedElement> findClickableCandidates(int limit) {
            return Collections.emptyList();
        }
    }

    // ---- Minimal ViewTreeDumper stub (no actual windows) ----

    private static final class EmptyViewTreeDumper extends ViewTreeDumper {
        @Override
        public List<WindowInfo> getAllWindows() {
            return Collections.emptyList();
        }
    }
}
