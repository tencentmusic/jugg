package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

/**
 * Tests for LayoutHtmlConverter: verifies that the JSON view hierarchy is correctly converted
 * to compact HTML with virtual-node pruning, HTML-escaped text, and properly formatted bounds.
 */
class LayoutHtmlConverterTest {

    private val converter = LayoutHtmlConverter()

    // --- Window structure ---

    @Test
    fun testWindowTitleAppearsAsComment() {
        val json = """
            {"windows":[{"title":"MainActivity","root":{"className":"FrameLayout","bounds":[0,0,360,640]}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("HTML should contain window title as comment", html.contains("<!-- Window: MainActivity -->"))
    }

    @Test
    fun testMultipleWindowsRenderedSeparately() {
        val json = """
            {"windows":[
              {"title":"WindowA","root":{"className":"FrameLayout","bounds":[0,0,360,640]}},
              {"title":"WindowB","root":{"className":"LinearLayout","bounds":[0,0,360,100]}}
            ]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue(html.contains("<!-- Window: WindowA -->"))
        Assert.assertTrue(html.contains("<!-- Window: WindowB -->"))
    }

    // --- Node rendering ---

    @Test
    fun testNodeClassNameRenderedAsTag() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"Button","bounds":[0,0,100,50],"clickable":true}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("Element should use className as tag prefix", html.contains("Button"))
    }

    @Test
    fun testNodeIdRenderedAsAttribute() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"Button","id":"btn_ok","bounds":[0,0,100,50],"clickable":true}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("id attribute should appear", html.contains("btn_ok"))
    }

    @Test
    fun testNodeTextRenderedAsContent() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"TextView","bounds":[0,0,200,50],"text":"Hello World"}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("text content should appear", html.contains("Hello World"))
    }

    @Test
    fun testBoundsRenderedAsDataAttribute() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"View","bounds":[10,20,110,70]}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("bounds should appear in output", html.contains("10,20,110,70"))
    }

    @Test
    fun testClickableAttributeRendered() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"Button","bounds":[0,0,100,50],"clickable":true}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("clickable attribute should appear", html.contains("clickable"))
    }

    @Test
    fun testNonClickableNodeHasNoClickableAttribute() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"TextView","bounds":[0,0,100,50],"text":"label"}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertFalse("non-clickable node should NOT have clickable attribute", html.contains("clickable"))
    }

    @Test
    fun testContentDescRenderedAsAttribute() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"ImageView","bounds":[0,0,50,50],"contentDesc":"profile photo"}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("contentDesc should appear", html.contains("profile photo"))
    }

    // --- HTML escaping ---

    @Test
    fun testTextWithHtmlSpecialCharsIsEscaped() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"TextView","bounds":[0,0,200,50],"text":"<b>Hello & 'World'</b>"}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertFalse("raw < should be escaped", html.contains("<b>Hello"))
        Assert.assertTrue("&lt; should appear", html.contains("&lt;"))
        Assert.assertTrue("&amp; should appear", html.contains("&amp;"))
    }

    // --- Virtual node pruning ---

    @Test
    fun testVirtualNodeWithNoSemanticsAndTransparentIsPruned() {
        // A purely structural FrameLayout with no id, no text, no contentDesc, alpha=0, no clickable
        val json = """
            {"windows":[{"title":"W","root":{
              "className":"FrameLayout",
              "bounds":[0,0,360,640],
              "alpha":0.0,
              "children":[{
                "className":"TextView",
                "bounds":[10,10,200,50],
                "text":"visible child"
              }]
            }}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        // The transparent FrameLayout should be pruned; its children should still appear
        Assert.assertTrue("child text should still appear", html.contains("visible child"))
    }

    @Test
    fun testVirtualIdNodeWithNoSemanticsIsPruned() {
        // Virtual ID nodes (_vir_id_N) with no text, no contentDesc, no clickable
        val json = """
            {"windows":[{"title":"W","root":{
              "className":"FrameLayout",
              "id":"_vir_id_0",
              "bounds":[0,0,360,640],
              "children":[{
                "className":"Button",
                "id":"btn_action",
                "bounds":[0,0,100,50],
                "text":"Click me",
                "clickable":true
              }]
            }}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        // _vir_id_0 node should not appear as a wrapper; real Button should appear
        Assert.assertFalse("_vir_id_0 should not appear in HTML", html.contains("_vir_id_0"))
        Assert.assertTrue("real child button should appear", html.contains("btn_action"))
        Assert.assertTrue("button text should appear", html.contains("Click me"))
    }

    @Test
    fun testRealIdNodeIsNotPruned() {
        val json = """
            {"windows":[{"title":"W","root":{
              "className":"FrameLayout",
              "id":"container",
              "bounds":[0,0,360,640],
              "children":[{
                "className":"TextView",
                "bounds":[0,0,200,50],
                "text":"child"
              }]
            }}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("real id node should be present", html.contains("container"))
    }

    @Test
    fun testClickableNodeIsNotPruned() {
        // Even without id/text, a clickable node must be kept
        val json = """
            {"windows":[{"title":"W","root":{
              "className":"FrameLayout",
              "bounds":[0,0,360,640],
              "clickable":true
            }}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("clickable node must not be pruned", html.contains("FrameLayout"))
    }

    // --- Nested children ---

    @Test
    fun testNestedChildrenRenderedWithIndentation() {
        val json = """
            {"windows":[{"title":"W","root":{
              "className":"LinearLayout",
              "id":"root_ll",
              "bounds":[0,0,360,640],
              "children":[{
                "className":"TextView",
                "bounds":[0,0,200,50],
                "text":"Item 1"
              },{
                "className":"TextView",
                "bounds":[0,60,200,110],
                "text":"Item 2"
              }]
            }}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue(html.contains("Item 1"))
        Assert.assertTrue(html.contains("Item 2"))
        Assert.assertTrue("parent id should appear", html.contains("root_ll"))
    }

    // --- Output must be valid minimal HTML structure ---

    @Test
    fun testOutputStartsWithHtmlDoctype() {
        val json = """{"windows":[{"title":"W","root":{"className":"View","bounds":[0,0,100,100]}}]}"""
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("output should start with <html>", html.trimStart().startsWith("<html>"))
    }

    @Test
    fun testOutputContainsBodyTag() {
        val json = """{"windows":[{"title":"W","root":{"className":"View","bounds":[0,0,100,100]}}]}"""
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("output should contain <body>", html.contains("<body>"))
        Assert.assertTrue("output should contain </body>", html.contains("</body>"))
    }

    // --- Disabled state ---

    @Test
    fun testDisabledNodeHasDisabledAttribute() {
        val json = """
            {"windows":[{"title":"W","root":{"className":"Button","bounds":[0,0,100,50],"enabled":false,"text":"Disabled"}}]}
        """.trimIndent()
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("disabled attribute should appear", html.contains("disabled"))
    }

    // --- Empty windows ---

    @Test
    fun testEmptyWindowsProducesMinimalHtml() {
        val json = """{"windows":[]}"""
        val html = converter.convert(JsonParser.parseString(json).asJsonObject)
        Assert.assertTrue("should still produce valid HTML structure", html.contains("<html>"))
    }
}
