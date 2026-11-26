package com.intellij.openapi.util;

import com.intellij.openapi.Disposable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DisposerTest {

    @Test
    public void newDisposableTracksDisposal() {
        Disposable disposable = Disposer.newDisposable();

        assertFalse(Disposer.isDisposed(disposable));
        Disposer.dispose(disposable);

        assertTrue(Disposer.isDisposed(disposable));
    }

    @Test
    public void disposingChildBeforeParentDoesNotDisposeChildTwice() throws Exception {
        RecordingDisposable parent = new RecordingDisposable("parent");
        RecordingDisposable child = new RecordingDisposable("child");
        Disposer.register(parent, child);

        Disposer.dispose(child);
        Disposer.dispose(parent);

        assertEquals(1, child.disposeCount);
        assertEquals(1, parent.disposeCount);
    }

    @Test
    public void equalChildrenAreTrackedByIdentity() throws Exception {
        RecordingDisposable parent = new RecordingDisposable("parent");
        EqualDisposable first = new EqualDisposable();
        EqualDisposable second = new EqualDisposable();
        Disposer.register(parent, first);
        Disposer.register(parent, second);

        Disposer.dispose(parent);

        assertEquals(1, first.disposeCount);
        assertEquals(1, second.disposeCount);
    }

    @Test
    public void childFailureDoesNotSkipSiblingOrParentDisposal() throws Exception {
        List<String> disposalOrder = new ArrayList<>();
        RecordingDisposable parent = new RecordingDisposable("parent", disposalOrder, false);
        RecordingDisposable failingChild = new RecordingDisposable("failing", disposalOrder, true);
        RecordingDisposable sibling = new RecordingDisposable("sibling", disposalOrder, false);
        Disposer.register(parent, failingChild);
        Disposer.register(parent, sibling);

        try {
            Disposer.dispose(parent);
            fail("Expected child disposal failure");
        } catch (IllegalStateException expected) {
            assertEquals("failing", expected.getMessage());
        }

        assertEquals(1, failingChild.disposeCount);
        assertEquals(1, sibling.disposeCount);
        assertEquals(1, parent.disposeCount);
    }

    @Test
    public void registeringToDisposedParentDoesNotDisposeChild() {
        RecordingDisposable parent = new RecordingDisposable("parent");
        RecordingDisposable child = new RecordingDisposable("child");
        Disposer.dispose(parent);

        try {
            Disposer.register(parent, child);
            fail("Expected disposed parent rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().startsWith("Parent has already been disposed:"));
        }

        assertEquals(0, child.disposeCount);
    }

    @Test
    public void childrenAreDisposedInReverseRegistrationOrder() {
        List<String> disposalOrder = new ArrayList<>();
        RecordingDisposable parent = new RecordingDisposable("parent", disposalOrder, false);
        Disposer.register(parent, new RecordingDisposable("first", disposalOrder, false));
        Disposer.register(parent, new RecordingDisposable("second", disposalOrder, false));

        Disposer.dispose(parent);

        assertEquals(List.of("second", "first", "parent"), disposalOrder);
    }

    private static class RecordingDisposable implements Disposable {
        private final String name;
        private final List<String> disposalOrder;
        private final boolean fail;
        protected int disposeCount;

        private RecordingDisposable(String name) {
            this(name, new ArrayList<>(), false);
        }

        private RecordingDisposable(String name, List<String> disposalOrder, boolean fail) {
            this.name = name;
            this.disposalOrder = disposalOrder;
            this.fail = fail;
        }

        @Override
        public void dispose() {
            disposeCount++;
            disposalOrder.add(name);
            if (fail) {
                throw new IllegalStateException(name);
            }
        }
    }

    private static final class EqualDisposable extends RecordingDisposable {
        private EqualDisposable() {
            super("equal");
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EqualDisposable;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
