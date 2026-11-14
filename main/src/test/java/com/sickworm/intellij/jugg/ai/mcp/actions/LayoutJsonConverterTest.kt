package com.sickworm.intellij.jugg.ai.mcp.actions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

class LayoutJsonConverterTest {
    private val converter = LayoutJsonConverter()

    @Test
    fun testInteractionDropsHiddenSubtreesAndVisualFieldsButKeepsClassNamesAndWarnings() {
        val source = parse("""{"warnings":["Capture warning"],"windows":[{"root":{
            "className":"Root","id":"_vir_id_1","padding":[1,2,3,4],"textColor":"#FF000000",
            "children":[{"id":"hidden","visibility":"invisible","children":[{"text":"Do not promote"}]},
            {"id":"gone","visibility":"gone","children":[{"text":"Gone descendant"}]},
            {"className":"TextView","id":"_vir_id_2","text":"Visible","alpha":0,
            "padding":[1,0,0,0],"textColor":"#FFFFFFFF"}]}},
            {"root":{"id":"hidden_root","visibility":"invisible","children":[{"text":"Hidden window"}]}},
            {"root":{"id":"gone_root","visibility":"gone"}}]}""")
        val original = source.deepCopy()
        val output = converter.convert(source, "interaction")
        val windows = output.getAsJsonArray("windows")
        Assert.assertEquals(1, windows.size())
        val root = windows[0].asJsonObject.getAsJsonObject("root")
        Assert.assertEquals("_vid_1", root["id"].asString)
        Assert.assertFalse(root.has("padding"))
        Assert.assertFalse(root.has("textColor"))
        val children = root.getAsJsonArray("children")
        Assert.assertEquals(1, children.size())
        val visible = children[0].asJsonObject
        Assert.assertEquals("TextView", visible["className"].asString)
        Assert.assertEquals("_vid_2", visible["id"].asString)
        Assert.assertEquals(0, visible["alpha"].asInt)
        Assert.assertFalse(visible.has("padding"))
        Assert.assertFalse(visible.has("textColor"))
        Assert.assertEquals(source["warnings"], output["warnings"])
        Assert.assertEquals(original, source)
        val ui = converter.convert(source, "ui")
        Assert.assertEquals(3, ui.getAsJsonArray("windows").size())
        Assert.assertTrue(ui.toString().contains("Do not promote"))
        Assert.assertTrue(ui.toString().contains("padding"))
        Assert.assertTrue(ui.toString().contains("textColor"))
    }

    @Test
    fun testChildrenBracketsUseOwnLinesWhileOffscreenDetailsStayInline() {
        val source = parse("""{"windows":[{"root":{"className":"Root","children":[
            {"className":"Page","children":[{"jugg_details":"View is off-screen; descendants omitted in interaction mode."}]},
            {"className":"TextView","text":"Keep\\ntext"}]}}]}""")
        val expected = """{"windows":[{"root":
{"className":"Root","children":
[
{"className":"Page","children":[{"jugg_details":"View is off-screen; descendants omitted in interaction mode."}]},
{"className":"TextView","text":"Keep\\ntext"}
]}}]}"""
        Assert.assertEquals(expected, converter.serialize(source))
        Assert.assertEquals(source, parse(converter.serialize(source)))
    }

    @Test
    fun testInteractionKeepsFirstOffscreenNodeAndReplacesOnlyItsDescendants() {
        for (bounds in listOf("[-100,0,0,100]", "[100,0,200,100]", "[0,-100,100,0]", "[0,100,100,200]")) {
            val source = parse("""{"truncated":false,"windows":[{"root":{"className":"FrameLayout",
                "bounds":[0,0,100,100],"children":[{"className":"Page","id":"_vir_id_page",
                "bounds":$bounds,"alpha":0.5,"children":[{"className":"TextView",
                "id":"label","text":"Offscreen","bounds":$bounds}]}]}}]}""")
            val original = source.deepCopy()
            val output = converter.convert(source, "interaction")
            val page = output.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
                .getAsJsonArray("children")[0].asJsonObject
            Assert.assertEquals("Page", page["className"].asString)
            Assert.assertEquals("_vid_page", page["id"].asString)
            Assert.assertEquals(0.5, page["alpha"].asDouble, 0.0)
            val children = page.getAsJsonArray("children")
            Assert.assertEquals(1, children.size())
            Assert.assertEquals(setOf("jugg_details"), children[0].asJsonObject.keySet())
            Assert.assertEquals("View is off-screen; descendants omitted in interaction mode.",
                children[0].asJsonObject["jugg_details"].asString)
            Assert.assertFalse(output["truncated"].asBoolean)
            Assert.assertEquals(original, source)
            val expectedUi = original.apply { addProperty("mode", "ui") }
            expectedUi.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
                .getAsJsonArray("children")[0].asJsonObject.addProperty("id", "_vid_page")
            Assert.assertEquals(expectedUi, converter.convert(source, "ui"))
        }
    }

    @Test
    fun testOffscreenFoldingPreservesVisibleUnknownAndDiagnosticDescendants() {
        val source = parse("""{"warnings":["Missing tooling"],"windows":[{"root":{
            "bounds":[0,0,100,100],"children":[
            {"id":"visible_child","bounds":[100,0,200,100],"children":[
              {"text":"Visible","bounds":[90,0,110,20]}]},
            {"id":"unknown_child","bounds":[100,0,200,100],"children":[{"text":"Unknown"}]},
            {"id":"diagnostic_child","bounds":[100,0,200,100],"children":[
              {"message":"Missing tooling","bounds":[110,0,120,20]}]},
            {"id":"partially_visible","bounds":[90,0,110,20],"children":[
              {"text":"Partial","bounds":[90,0,110,20]}]},
            {"id":"zero_area","bounds":[100,0,100,100],"children":[
              {"text":"Zero parent","bounds":[110,0,120,20]}]},
            {"className":"View","id":"_vir_id_leaf","bounds":[100,0,120,20]}]}}]}""")
        val output = converter.convert(source, "interaction")
        val children = output.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
            .getAsJsonArray("children")
        Assert.assertEquals(6, children.size())
        for (index in 0..4) {
            Assert.assertEquals(source.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
                .getAsJsonArray("children")[index], children[index])
        }
        Assert.assertFalse(children[5].asJsonObject.has("children"))
        Assert.assertEquals(source["warnings"], output["warnings"])
    }

    @Test
    fun testFoldingUsesEachWindowViewportAndSkipsSubtreesOrUnknownViewports() {
        val source = parse("""{"windows":[
            {"root":{"bounds":[0,0,100,100],"children":[{"id":"page","bounds":[100,0,200,100],
              "children":[{"text":"Label","bounds":[110,0,120,20]}]}]}},
            {"root":{"bounds":[100,0,200,100],"children":[{"id":"page","bounds":[100,0,200,100],
              "children":[{"text":"Label","bounds":[110,0,120,20]}]}]}}]}""")
        val output = converter.convert(source, "interaction")
        Assert.assertTrue(output.toString().contains("jugg_details"))
        Assert.assertEquals(source.getAsJsonArray("windows")[1], output.getAsJsonArray("windows")[1])
        for (viewport in listOf("null", "[0,0,0,100]", "[0,0,100]", "[0,0,\"invalid\",100]")) {
            val unknown = source.deepCopy()
            unknown.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
                .add("bounds", JsonParser.parseString(viewport))
            Assert.assertFalse(converter.convert(unknown, "interaction").toString().contains("jugg_details"))
        }
        val subtree = source.deepCopy().apply { addProperty("rootLayout", "panel") }
        Assert.assertFalse(converter.convert(subtree, "interaction").toString().contains("jugg_details"))
        source.getAsJsonArray("windows")[0].asJsonObject.addProperty("windowType", "subtree")
        Assert.assertFalse(converter.convert(source, "interaction").toString().contains("jugg_details"))
    }

    @Test
    fun testNodeLinesRoundTripWithoutSplittingFieldsOrText() {
        val source = parse("""{"windows":[{"title":"窗口","root":{"className":"FrameLayout",
            "bounds":[0,0,360,800],"children":[{"className":"TextView",
            "text":"登录🙂\n\"children\":[{测试}]","padding":[1,2,3,4]},
            {"className":"View","children":[{"className":"Button","text":"确认"}]}]}},
            {"title":"popup","root":{"className":"View"}}],"warnings":["保持\\n诊断"],"truncated":true}""")
        val text = converter.serialize(source)
        Assert.assertEquals(source, parse(text))
        val nodeLines = text.lines().filter { it.startsWith("{\"className\":") }
        Assert.assertEquals(5, nodeLines.size)
        Assert.assertTrue(nodeLines[0].contains("\"bounds\":[0,0,360,800]"))
        Assert.assertTrue(nodeLines[1].contains("\"padding\":[1,2,3,4]"))
        Assert.assertTrue(nodeLines[1].contains("登录🙂"))
        Assert.assertTrue(text.lines().all { it.length == it.trimStart().length })
    }

    @Test
    fun testInteractionPromotesChildrenAndKeepsEverySemanticNodeInOrder() {
        val source = parse("""{"windows":[{"root":{"className":"FrameLayout","id":"_vir_id_root",
            "children":[{"className":"CustomWrapper","id":"_vir_id_wrapper","children":[
              {"className":"TextView","id":"_vir_id_text","text":"<登录> & \"继续\"","alpha":0},
              {"className":"View","id":"real_id"},
              {"className":"ImageView","contentDesc":"头像"},
              {"className":"View","id":"_vir_id_click","clickable":true},
              {"className":"ComposeView","id":"_vir_id_error","message":"Missing <tooling>"},
              {"className":"ImageView","bounds":[0,0,40,40]}]}]}}]}""")
        val output = converter.convert(source, "interaction")
        val root = output.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
        Assert.assertEquals("_vid_root", root["id"].asString)
        val children = root.getAsJsonArray("children")
        Assert.assertEquals(5, children.size())
        Assert.assertEquals("_vid_text", children[0].asJsonObject["id"].asString)
        Assert.assertEquals("<登录> & \"继续\"", children[0].asJsonObject["text"].asString)
        Assert.assertEquals(0, children[0].asJsonObject["alpha"].asInt)
        Assert.assertEquals("real_id", children[1].asJsonObject["id"].asString)
        Assert.assertEquals("头像", children[2].asJsonObject["contentDesc"].asString)
        Assert.assertTrue(children[3].asJsonObject["clickable"].asBoolean)
        Assert.assertEquals("Missing <tooling>", children[4].asJsonObject["message"].asString)
        Assert.assertEquals(1, source.getAsJsonArray("windows")[0].asJsonObject
            .getAsJsonObject("root").getAsJsonArray("children").size())
    }

    @Test
    fun testUiPreservesCapturedFieldsAndLeavesSourceUnchanged() {
        val source = parse("""{"truncated":true,"warnings":["partial"],"rootLayout":"panel",
            "deviceInfo":{"density":3,"scaledDensity":3.5},
            "windows":[{"windowType":"subtree","title":"panel","root":{"className":"View",
            "id":"_vir_id_1","bounds":[1,2,3,4],"children":[{"className":"ImageView",
            "tag":"truncated:node_limit","visibility":"invisible","alpha":0.5,
            "enabled":false,"padding":[1,2,3,4],"textColor":"#FFFF0000"}]}}]}""")
        val expected = source.deepCopy().apply { addProperty("mode", "ui") }
        expected.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root").addProperty("id", "_vid_1")
        Assert.assertEquals(expected, converter.convert(source, "ui"))
        Assert.assertFalse(source.has("mode"))
    }

    @Test
    fun testPruningPreservesMetadataAndRemovesEmptyChildren() {
        val source = parse("""{"truncated":false,"warnings":["partial"],"rootLayout":"content",
            "deviceInfo":{"density":2},"windows":[
            {"windowType":"popup","title":"Dialog","root":{"className":"View","children":[
            {"className":"View","id":"_vir_id_1"}]}},
            {"windowType":"activity","title":"Main","root":{"className":"View"}}]}""")
        val output = converter.convert(source, "interaction")
        Assert.assertFalse(output["truncated"].asBoolean)
        Assert.assertEquals(source["warnings"], output["warnings"])
        Assert.assertEquals(source["deviceInfo"], output["deviceInfo"])
        Assert.assertEquals("content", output["rootLayout"].asString)
        val windows = output.getAsJsonArray("windows")
        Assert.assertEquals(2, windows.size())
        Assert.assertEquals("popup", windows[0].asJsonObject["windowType"].asString)
        Assert.assertFalse(windows[0].asJsonObject.getAsJsonObject("root").has("children"))
        Assert.assertEquals(source.getAsJsonArray("windows")[1], windows[1])
    }

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
}
