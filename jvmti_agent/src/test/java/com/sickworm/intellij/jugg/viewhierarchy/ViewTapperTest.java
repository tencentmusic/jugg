package com.sickworm.intellij.jugg.viewhierarchy;

import android.view.MotionEvent;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;

public class ViewTapperTest {

    @Test
    public void tap_shouldDispatchToRootForComposeNode() {
        View root = Mockito.mock(View.class);
        Mockito.when(root.dispatchTouchEvent(ArgumentMatchers.any())).thenReturn(true);
        stubLocation(root, 0, 0);

        MatchedElement element = composeElement(root);
        MotionEvent down = Mockito.mock(MotionEvent.class);
        MotionEvent up = Mockito.mock(MotionEvent.class);
        try (MockedStatic<MotionEvent> events = mockMotionEvents(down, up)) {
            Assert.assertTrue(new ViewTapper().tap(element));
        }

        Mockito.verify(root).dispatchTouchEvent(down);
        Mockito.verify(root).dispatchTouchEvent(up);
    }

    @Test
    public void longPress_shouldScheduleUpWithoutBlockingMainThread() {
        View root = Mockito.mock(View.class);
        Mockito.when(root.dispatchTouchEvent(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(root.postDelayed(ArgumentMatchers.any(Runnable.class), ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();
                return true;
            });
        stubLocation(root, 0, 0);

        MotionEvent down = Mockito.mock(MotionEvent.class);
        MotionEvent up = Mockito.mock(MotionEvent.class);
        try (MockedStatic<MotionEvent> events = mockMotionEvents(down, up)) {
            Assert.assertTrue(new ViewTapper().longPress(composeElement(root), 500));
        }

        Mockito.verify(root).postDelayed(ArgumentMatchers.any(Runnable.class), Mockito.eq(500L));
        Mockito.verify(root).dispatchTouchEvent(down);
        Mockito.verify(root).dispatchTouchEvent(up);
    }

    private static MatchedElement composeElement(View root) {
        ViewNode node = new ViewNode();
        node.className = "Text";
        node.id = "_vir_id_1";
        node.text = "Compose target";
        node.bounds.left = 10;
        node.bounds.top = 20;
        node.bounds.right = 110;
        node.bounds.bottom = 60;
        return new MatchedElement(null, null, root, node, Collections.emptyMap());
    }

    private static MockedStatic<MotionEvent> mockMotionEvents(MotionEvent down, MotionEvent up) {
        MockedStatic<MotionEvent> events = Mockito.mockStatic(MotionEvent.class);
        events.when(() -> MotionEvent.obtain(
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyLong(),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.anyFloat(),
            ArgumentMatchers.anyFloat(),
            ArgumentMatchers.anyInt()
        )).thenReturn(down, up);
        return events;
    }

    private static void stubLocation(View view, int left, int top) {
        Mockito.doAnswer(invocation -> {
            int[] location = invocation.getArgument(0);
            location[0] = left;
            location[1] = top;
            return null;
        }).when(view).getLocationOnScreen(ArgumentMatchers.any(int[].class));
    }
}
