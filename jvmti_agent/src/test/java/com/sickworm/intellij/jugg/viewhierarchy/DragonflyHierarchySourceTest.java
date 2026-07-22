package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import top.kokomi.dragonfly.node.HierarchyNode;

public class DragonflyHierarchySourceTest {

    @Test
    public void capture_shouldExplainMissingKotlinDependency() {
        try {
            new DragonflyHierarchySource().capture(true);
            Assert.fail("Expected unsupported layout error");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("本工程没有 kotlin 依赖，不支持此功能", exception.getMessage());
        }
    }

    @Test
    public void dump_shouldSearchAllWindowsWhenRootLayoutIsSpecified() throws Exception {
        HierarchyNode target = node(
            "Text",
            properties("id", "com.example:id/target", "text", "Dialog content")
        );
        boolean[] capturedTopWindowOnly = {true};
        DragonflyHierarchySource source = new DragonflyHierarchySource() {
            @Override
            public Snapshot capture(boolean topWindowOnly) {
                capturedTopWindowOnly[0] = topWindowOnly;
                return buildSnapshot(Arrays.asList(
                    window(node("FirstRoot", Collections.emptyMap())),
                    window(node("SecondRoot", Collections.emptyMap(), target))
                ));
            }
        };

        JSONObject dump = source.dumpWindowsJson("target", false, true);

        Assert.assertFalse(capturedTopWindowOnly[0]);
        Assert.assertEquals("target", dump.getString("rootLayout"));
        Assert.assertEquals("Dialog content", dump.getJSONArray("windows")
            .getJSONObject(0).getJSONObject("root").getString("text"));
    }

    @Test
    public void snapshot_shouldDriveDumpAndSelectorFromSameNodes() throws Exception {
        HierarchyNode text = node(
            "Text",
            properties("text", "Compose target", "bounds", "10,20,110,60")
        );
        HierarchyNode window = window(node("Root", properties("bounds", "0,0,200,300"), text));
        DragonflyHierarchySource.Snapshot snapshot = new DragonflyHierarchySource()
            .buildSnapshot(Collections.singletonList(window));

        JSONObject dump = snapshot.toJson(null, false);
        List<MatchedElement> matches = new ElementFinder(snapshot)
            .find("Compose target", null, null, null, true);

        String dumpId = findNodeByText(dump, "Compose target").getString("id");
        Assert.assertEquals(1, matches.size());
        Assert.assertEquals(dumpId, matches.get(0).resourceId);
        Assert.assertEquals("[10,20,110,60]", matches.get(0).toMatchedElementJson()
            .getJSONArray("bounds").toString());
    }

    @Test
    public void snapshot_shouldKeepVirtualIdWhenGoneNodesAreFiltered() throws Exception {
        HierarchyNode visible = node(
            "Text",
            properties("text", "Visible", "bounds", "0,20,100,40")
        );
        HierarchyNode root = node(
            "Root",
            properties("bounds", "0,0,100,100"),
            node("Gone", properties("visibility", "gone", "bounds", "0,0,10,10")),
            visible
        );
        DragonflyHierarchySource.Snapshot snapshot = new DragonflyHierarchySource()
            .buildSnapshot(Collections.singletonList(window(root)));

        String includedId = findNodeByText(snapshot.toJson(null, false), "Visible").getString("id");
        String filteredId = findNodeByText(snapshot.toJson(null, true), "Visible").getString("id");

        Assert.assertEquals(includedId, filteredId);
    }

    @Test
    public void snapshot_shouldGenerateSameVirtualIdsForSameTraversal() throws Exception {
        HierarchyNode window = window(node(
            "Root",
            properties("bounds", "0,0,100,100"),
            node("Text", properties("text", "Stable", "bounds", "0,0,50,20"))
        ));
        DragonflyHierarchySource source = new DragonflyHierarchySource();

        JSONObject first = source.buildSnapshot(Collections.singletonList(window)).toJson(null, false);
        JSONObject second = source.buildSnapshot(Collections.singletonList(window)).toJson(null, false);

        Assert.assertEquals(
            findNodeByText(first, "Stable").getString("id"),
            findNodeByText(second, "Stable").getString("id")
        );
    }

    @Test
    public void snapshot_shouldKeepExistingJsonShapeAndRootLayout() throws Exception {
        HierarchyNode target = node(
            "Text",
            properties(
                "id", "com.example:id/target",
                "text", "Content",
                "bounds", "10,20,110,60",
                "fontSize", "16.sp",
                "textColor", "FF123456"
            )
        );
        HierarchyNode root = node(
            "com.example.RootView",
            properties(
                "id", "com.example:id/root",
                "bounds", "0,0,200,300",
                "padding", "1,2,3,4",
                "alpha", "0.5"
            ),
            target
        );
        DragonflyHierarchySource.Snapshot snapshot = new DragonflyHierarchySource()
            .buildSnapshot(Collections.singletonList(window(root)));

        JSONObject full = snapshot.toJson(null, false);
        JSONObject rootJson = full.getJSONArray("windows").getJSONObject(0).getJSONObject("root");
        JSONObject subtree = snapshot.toJson("target", false);

        Assert.assertEquals("RootView", rootJson.getString("className"));
        Assert.assertEquals("root", rootJson.getString("id"));
        Assert.assertEquals(0.5, rootJson.getDouble("alpha"), 0.0);
        Assert.assertEquals("[1,2,3,4]", rootJson.getJSONArray("padding").toString());
        Assert.assertEquals("#FF123456", rootJson.getJSONArray("children")
            .getJSONObject(0).getString("textColor"));
        Assert.assertTrue(full.has("deviceInfo"));
        Assert.assertEquals("subtree", subtree.getJSONArray("windows")
            .getJSONObject(0).getString("windowType"));
        Assert.assertEquals("target", subtree.getString("rootLayout"));
    }

    @Test
    public void snapshot_shouldKeepAndroidViewFields() throws Exception {
        View view = Mockito.mock(View.class);
        Mockito.when(view.getContentDescription()).thenReturn("profile image");
        Mockito.when(view.getTag()).thenReturn("avatar");
        Mockito.when(view.isClickable()).thenReturn(true);
        Mockito.when(view.isEnabled()).thenReturn(false);

        top.kokomi.dragonfly.node.android.ViewNode hierarchyNode = Mockito.mock(
            top.kokomi.dragonfly.node.android.ViewNode.class
        );
        Mockito.when(hierarchyNode.getName()).thenReturn("android.widget.ImageView");
        Mockito.when(hierarchyNode.properties()).thenReturn(properties("bounds", "0,0,40,40"));
        Mockito.when(hierarchyNode.getChildren()).thenReturn(Collections.emptyList());
        Mockito.when(hierarchyNode.getView()).thenReturn(view);

        JSONObject json = new DragonflyHierarchySource().buildSnapshot(
            Collections.singletonList(window(hierarchyNode))
        ).toJson(null, false).getJSONArray("windows").getJSONObject(0).getJSONObject("root");

        Assert.assertEquals("profile image", json.getString("contentDesc"));
        Assert.assertEquals("avatar", json.getString("tag"));
        Assert.assertTrue(json.getBoolean("clickable"));
        Assert.assertFalse(json.getBoolean("enabled"));
    }

    @Test
    public void finder_shouldSeparateActionableAndInspectableNodes() {
        HierarchyNode hidden = node(
            "Text",
            properties("text", "Hidden", "visibility", "invisible", "bounds", "0,0,100,40")
        );
        ElementFinder finder = new ElementFinder(new DragonflyHierarchySource().buildSnapshot(
            Collections.singletonList(window(node("Root", properties("bounds", "0,0,100,100"), hidden)))
        ));

        Assert.assertTrue(finder.find("Hidden", null, null, null, true).isEmpty());
        Assert.assertEquals(1, finder.findInspectable("Hidden", null, null, null, true).size());
    }

    @Test
    public void snapshot_shouldKeepDepthTruncationContract() throws Exception {
        HierarchyNode root = node("Leaf", properties("text", "Beyond limit", "bounds", "0,0,1,1"));
        for (int depth = 0; depth < 62; depth++) {
            root = node("Level" + depth, properties("bounds", "0,0,1,1"), root);
        }

        DragonflyHierarchySource.Snapshot snapshot = new DragonflyHierarchySource().buildSnapshot(
            Collections.singletonList(window(root))
        );
        JSONObject dump = snapshot.toJson(null, false);

        Assert.assertTrue(dump.getBoolean("truncated"));
        Assert.assertTrue(dump.toString().contains("truncated:depth_limit"));
        Assert.assertTrue(new ElementFinder(snapshot)
            .find("Beyond limit", null, null, null, true).isEmpty());
    }

    private static HierarchyNode window(HierarchyNode root) {
        return node("MainActivity", Collections.emptyMap(), root);
    }

    private static HierarchyNode node(String name, Map<String, String> properties, HierarchyNode... children) {
        HierarchyNode node = Mockito.mock(HierarchyNode.class);
        Mockito.when(node.getName()).thenReturn(name);
        Mockito.when(node.properties()).thenReturn(properties);
        Mockito.when(node.getChildren()).thenReturn(Arrays.asList(children));
        return node;
    }

    private static Map<String, String> properties(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private static JSONObject findNodeByText(JSONObject dump, String text) throws Exception {
        JSONArray windows = dump.getJSONArray("windows");
        for (int index = 0; index < windows.length(); index++) {
            JSONObject found = findInNode(windows.getJSONObject(index).getJSONObject("root"), text);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError("Node not found: " + text);
    }

    private static JSONObject findInNode(JSONObject node, String text) throws Exception {
        if (text.equals(node.optString("text"))) {
            return node;
        }
        JSONArray children = node.optJSONArray("children");
        if (children == null) {
            return null;
        }
        for (int index = 0; index < children.length(); index++) {
            JSONObject found = findInNode(children.getJSONObject(index), text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

}
