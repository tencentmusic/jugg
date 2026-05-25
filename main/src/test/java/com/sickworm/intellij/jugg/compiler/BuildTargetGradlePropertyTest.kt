package com.sickworm.intellij.jugg.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTargetGradlePropertyTest {

    @Test
    fun `fromGradlePropertyValue maps ANDROID_TEST`() {
        assertEquals(BuildTarget.ANDROID_TEST, BuildTarget.fromGradlePropertyValue("ANDROID_TEST"))
        assertTrue(BuildTarget.fromGradlePropertyValue("ANDROID_TEST").includeAndroidTestSourceSet)
    }

    @Test
    fun `fromGradlePropertyValue defaults to APP`() {
        assertEquals(BuildTarget.APP, BuildTarget.fromGradlePropertyValue(null))
        assertEquals(BuildTarget.APP, BuildTarget.fromGradlePropertyValue(""))
        assertEquals(BuildTarget.APP, BuildTarget.fromGradlePropertyValue("APP"))
        assertFalse(BuildTarget.fromGradlePropertyValue(null).includeAndroidTestSourceSet)
    }
}
