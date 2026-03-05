package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

public class ElementFinderTest {

    @Test
    public void find_shouldIgnoreGoneNodeWhenSelectorsMatch() {
        TextView visible = mockTextView("Item", View.VISIBLE, true, 100, 40, 10, 20);
        TextView gone = mockTextView("Item", View.GONE, false, 100, 40, 30, 40);
        ViewGroup root = mockRoot(visible, gone);

        ElementFinder finder = new ElementFinder(new FixedWindowsDumper(new WindowInfo("activity", "Main", root)));
        List<MatchedElement> matches = finder.find("Item", null, null, null);

        Assert.assertEquals(1, matches.size());
        Assert.assertEquals("visible", matches.get(0).visibility);
    }

    @Test
    public void find_shouldIgnoreZeroSizedNode() {
        TextView zeroSized = mockTextView("Item", View.VISIBLE, true, 0, 0, 10, 20);
        ViewGroup root = mockRoot(zeroSized);

        ElementFinder finder = new ElementFinder(new FixedWindowsDumper(new WindowInfo("activity", "Main", root)));
        List<MatchedElement> matches = finder.find("Item", null, null, null);

        Assert.assertTrue(matches.isEmpty());
    }

    @Test
    public void find_shouldKeepMultipleWhenAllCandidatesAreActionable() {
        TextView first = mockTextView("Item", View.VISIBLE, true, 120, 48, 10, 20);
        TextView second = mockTextView("Item", View.VISIBLE, true, 120, 48, 200, 220);
        ViewGroup root = mockRoot(first, second);

        ElementFinder finder = new ElementFinder(new FixedWindowsDumper(new WindowInfo("activity", "Main", root)));
        List<MatchedElement> matches = finder.find("Item", null, null, null);

        Assert.assertEquals(2, matches.size());
    }

    @Test
    public void find_shouldUseFirstWindowAsTopWhenTopWindowOnlyIsEnabled() {
        TextView topText = mockTextView("TopWindowTarget", View.VISIBLE, true, 120, 48, 10, 20);
        TextView backgroundText = mockTextView("BackgroundTarget", View.VISIBLE, true, 120, 48, 200, 220);

        ViewGroup topWindowRoot = mockRoot(topText);
        ViewGroup backgroundWindowRoot = mockRoot(backgroundText);

        ElementFinder finder = new ElementFinder(
            new FixedWindowsDumper(
                new WindowInfo("activity", "Top", topWindowRoot),
                new WindowInfo("activity", "Background", backgroundWindowRoot)
            )
        );

        List<MatchedElement> topMatches = finder.find("TopWindowTarget", null, null, null, true);
        List<MatchedElement> backgroundMatches = finder.find("BackgroundTarget", null, null, null, true);

        Assert.assertEquals(1, topMatches.size());
        Assert.assertTrue(backgroundMatches.isEmpty());
    }

    private ViewGroup mockRoot(View... children) {
        ViewGroup root = Mockito.mock(ViewGroup.class);
        Mockito.when(root.getVisibility()).thenReturn(View.VISIBLE);
        Mockito.when(root.isShown()).thenReturn(true);
        Mockito.when(root.getWidth()).thenReturn(1080);
        Mockito.when(root.getHeight()).thenReturn(1920);
        Mockito.when(root.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            Mockito.when(root.getChildAt(i)).thenReturn(children[i]);
        }
        stubLocation(root, 0, 0);
        return root;
    }

    private TextView mockTextView(
        String text,
        int visibility,
        boolean shown,
        int width,
        int height,
        int left,
        int top
    ) {
        TextView view = Mockito.mock(TextView.class);
        Mockito.when(view.getText()).thenReturn(text);
        Mockito.when(view.getVisibility()).thenReturn(visibility);
        Mockito.when(view.isShown()).thenReturn(shown);
        Mockito.when(view.getWidth()).thenReturn(width);
        Mockito.when(view.getHeight()).thenReturn(height);
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

    private static final class FixedWindowsDumper extends ViewTreeDumper {
        private final List<WindowInfo> windows;

        private FixedWindowsDumper(WindowInfo... windows) {
            this.windows = Arrays.asList(windows);
        }

        @Override
        public List<WindowInfo> getAllWindows() {
            return windows;
        }
    }
}
