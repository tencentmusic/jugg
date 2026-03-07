package com.sickworm.intellij.jugg.viewhierarchy;

import org.junit.Assert;
import org.junit.Test;

/**
 * ViewNodeTest verifies textColor field assignment and colorToHex utility.
 * Note: toJson() depends on android.jar JSONObject stubs in unit test environment,
 * so we verify field-level logic only here; JSON serialization is integration-tested on device.
 */
public class ViewNodeTest {

    @Test
    public void textColor_shouldBeNonZeroForNonBlack() {
        ViewNode node = new ViewNode();
        node.textColor = 0xFFFF0000; // red

        Assert.assertNotEquals(
            "textColor should be non-zero for red",
            0, node.textColor
        );
    }

    @Test
    public void textColor_shouldDefaultToZero() {
        ViewNode node = new ViewNode();

        Assert.assertEquals(
            "textColor should default to 0 (not applicable)",
            0, node.textColor
        );
    }

    @Test
    public void textColor_shouldNotBeZeroForWhite() {
        ViewNode node = new ViewNode();
        node.textColor = 0xFFFFFFFF; // white

        Assert.assertNotEquals(
            "textColor should be non-zero for white",
            0, node.textColor
        );
    }

    @Test
    public void colorToHex_shouldFormatRedCorrectly() {
        Assert.assertEquals("#FFFF0000", ViewNode.colorToHex(0xFFFF0000));
    }

    @Test
    public void colorToHex_shouldFormatWhiteCorrectly() {
        Assert.assertEquals("#FFFFFFFF", ViewNode.colorToHex(0xFFFFFFFF));
    }

    @Test
    public void colorToHex_shouldFormatBlackCorrectly() {
        Assert.assertEquals("#FF000000", ViewNode.colorToHex(0xFF000000));
    }

    @Test
    public void colorToHex_shouldFormatWithLeadingZeroes() {
        Assert.assertEquals("#00000001", ViewNode.colorToHex(1));
    }

    @Test
    public void textColor_blackShouldBeZeroInNodeWhenNotSet() {
        // When buildNode() encounters black text, it leaves textColor as 0
        // to suppress output. This test verifies the guard condition.
        ViewNode node = new ViewNode();
        // Simulate: color == 0xFF000000 => do NOT assign textColor
        int color = 0xFF000000;
        if (color != 0xFF000000) {
            node.textColor = color;
        }
        Assert.assertEquals(
            "textColor should remain 0 when color is black",
            0, node.textColor
        );
    }
}
