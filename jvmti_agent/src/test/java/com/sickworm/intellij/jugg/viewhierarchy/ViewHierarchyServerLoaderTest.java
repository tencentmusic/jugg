package com.sickworm.intellij.jugg.viewhierarchy;

import android.content.Context;

import com.sickworm.intellij.jugg.viewhierarchy.ViewHierarchyServerLoader;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class ViewHierarchyServerLoaderTest {

    @After
    public void tearDown() {
        ViewHierarchyServerLoader.resetForTest();
    }

    @Test
    public void init_shouldRetryAfterStartThrows() {
        RecordingStarter starter = new RecordingStarter(true, 2);
        ViewHierarchyServerLoader.setServerStarterForTest(starter);
        Context context = Mockito.mock(Context.class);

        ViewHierarchyServerLoader.init(context);
        ViewHierarchyServerLoader.init(context);

        Assert.assertEquals(2, starter.calls);
    }

    @Test
    public void init_shouldStartOnlyOnceAfterSuccess() {
        RecordingStarter starter = new RecordingStarter(false, 1);
        ViewHierarchyServerLoader.setServerStarterForTest(starter);
        Context context = Mockito.mock(Context.class);

        ViewHierarchyServerLoader.init(context);
        ViewHierarchyServerLoader.init(context);

        Assert.assertEquals(1, starter.calls);
    }

    @Test
    public void init_shouldReturnWhenContextIsNull() {
        RecordingStarter starter = new RecordingStarter(false, 1);
        ViewHierarchyServerLoader.setServerStarterForTest(starter);

        ViewHierarchyServerLoader.init(null);

        Assert.assertEquals(0, starter.calls);
    }

    private static final class RecordingStarter implements ViewHierarchyServerLoader.ServerStarter {
        private final boolean throwOnFirstCall;
        private final int successCallIndex;
        private int calls;

        private RecordingStarter(boolean throwOnFirstCall, int successCallIndex) {
            this.throwOnFirstCall = throwOnFirstCall;
            this.successCallIndex = successCallIndex;
        }

        @Override
        public boolean start(Context context) {
            calls += 1;
            if (throwOnFirstCall && calls == 1) {
                throw new RuntimeException("boom");
            }
            return calls >= successCallIndex;
        }
    }
}
