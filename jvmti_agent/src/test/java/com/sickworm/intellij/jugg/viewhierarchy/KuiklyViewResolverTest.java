package com.sickworm.intellij.jugg.viewhierarchy;

import android.text.Layout;
import android.view.View;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests for KuiklyViewResolver.
 *
 * canResolve() checks view.getClass().getSimpleName(), which means Mockito proxies
 * won't match. We use real inner classes named exactly as the Kuikly SDK classes
 * to exercise the name-matching logic.
 *
 * For resolveText() with textLayout field access, we use real instances of these
 * inner classes so the reflection path can find the declared field.
 */
public class KuiklyViewResolverTest {

    // ---- canResolve tests ----

    @Test
    public void canResolve_shouldReturnFalseForNull() {
        Assert.assertFalse(KuiklyViewResolver.canResolve(null));
    }

    @Test
    public void canResolve_shouldReturnFalseForPlainView() {
        View view = Mockito.mock(View.class);
        Assert.assertFalse(KuiklyViewResolver.canResolve(view));
    }

    @Test
    public void canResolve_shouldReturnFalseForTextView() {
        TextView view = Mockito.mock(TextView.class);
        Assert.assertFalse(KuiklyViewResolver.canResolve(view));
    }

    @Test
    public void canResolve_shouldReturnTrueForKRRichTextView() {
        KRRichTextView view = new KRRichTextView();
        Assert.assertTrue(KuiklyViewResolver.canResolve(view));
    }

    @Test
    public void canResolve_shouldReturnTrueForKRGradientRichTextView() {
        KRGradientRichTextView view = new KRGradientRichTextView();
        Assert.assertTrue(KuiklyViewResolver.canResolve(view));
    }

    // ---- resolveText tests ----

    @Test
    public void resolveText_shouldReturnEmptyForNull() {
        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(null);
        Assert.assertEquals("", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldReturnEmptyForUnsupportedView() {
        View view = Mockito.mock(View.class);
        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldExtractTextFromRichTextShadowPath() {
        // Test the primary path: richTextShadow.textProps.text
        KRRichTextView view = new KRRichTextView();
        KRRichTextShadow shadow = new KRRichTextShadow();
        KRTextProps props = new KRTextProps();
        props.text = "Text from textProps";
        shadow.textProps = props;
        view.richTextShadow = shadow;

        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("Text from textProps", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldPreferRichTextShadowOverTextLayout() {
        // When both paths have values, richTextShadow should be preferred
        Layout mockLayout = Mockito.mock(Layout.class);
        Mockito.when(mockLayout.getText()).thenReturn("Text from Layout");

        KRRichTextView view = new KRRichTextView();
        view.textLayout = mockLayout;

        KRRichTextShadow shadow = new KRRichTextShadow();
        KRTextProps props = new KRTextProps();
        props.text = "Text from textProps";
        shadow.textProps = props;
        view.richTextShadow = shadow;

        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("Text from textProps", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldReturnEmptyWhenTextLayoutIsNull() {
        KRRichTextView view = new KRRichTextView();
        view.textLayout = null;

        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldReturnEmptyWhenLayoutTextIsNull() {
        Layout mockLayout = Mockito.mock(Layout.class);
        Mockito.when(mockLayout.getText()).thenReturn(null);

        KRRichTextView view = new KRRichTextView();
        view.textLayout = mockLayout;

        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveText_shouldExtractTextFromGradientRichTextViewWithRichTextShadow() {
        KRGradientRichTextView view = new KRGradientRichTextView();
        KRRichTextShadow shadow = new KRRichTextShadow();
        KRTextProps props = new KRTextProps();
        props.text = "Gradient from textProps";
        shadow.textProps = props;
        view.richTextShadow = shadow;

        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.resolveText(view);
        Assert.assertEquals("Gradient from textProps", result.text);
        Assert.assertFalse(result.hasError());
    }

    // ---- ResolveResult tests ----

    @Test
    public void resolveResult_emptyHasNoError() {
        KuiklyViewResolver.ResolveResult empty = KuiklyViewResolver.ResolveResult.EMPTY;
        Assert.assertEquals("", empty.text);
        Assert.assertFalse(empty.hasError());
    }

    @Test
    public void resolveResult_successHasNoError() {
        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.ResolveResult.success("text");
        Assert.assertEquals("text", result.text);
        Assert.assertFalse(result.hasError());
    }

    @Test
    public void resolveResult_errorHasMessage() {
        KuiklyViewResolver.ResolveResult result = KuiklyViewResolver.ResolveResult.error("something broke");
        Assert.assertEquals("", result.text);
        Assert.assertTrue(result.hasError());
        Assert.assertEquals("something broke", result.errorMessage);
    }

    // ---- Test stubs with exact simple names matching Kuikly SDK ----

    /**
     * Stub class with simple name "KRRichTextView" to match canResolve() name check.
     * Contains the textLayout field and richTextShadow field that KuiklyViewResolver accesses via reflection.
     */
    static class KRRichTextView extends View {
        Layout textLayout;
        KRRichTextShadow richTextShadow;

        KRRichTextView() {
            super(null);
        }
    }

    /**
     * Stub class with simple name "KRGradientRichTextView" to match canResolve() name check.
     * Contains the textLayout field and richTextShadow field that KuiklyViewResolver accesses via reflection.
     */
    static class KRGradientRichTextView extends View {
        Layout textLayout;
        KRRichTextShadow richTextShadow;

        KRGradientRichTextView() {
            super(null);
        }
    }

    /**
     * Stub class for KRRichTextShadow containing textProps field.
     */
    static class KRRichTextShadow {
        KRTextProps textProps;
    }

    /**
     * Stub class for KRTextProps containing text field.
     */
    static class KRTextProps {
        String text;
    }
}
