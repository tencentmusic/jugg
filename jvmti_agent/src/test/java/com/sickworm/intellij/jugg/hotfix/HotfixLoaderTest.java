package com.sickworm.intellij.jugg.hotfix;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HotfixLoaderTest {

    @Test
    public void isRuntimeSupported_shouldRejectApiBelow26() {
        assertFalse(HotfixLoader.isRuntimeSupported(25));
    }

    @Test
    public void isRuntimeSupported_shouldAcceptApi26() {
        assertTrue(HotfixLoader.isRuntimeSupported(26));
    }
}
