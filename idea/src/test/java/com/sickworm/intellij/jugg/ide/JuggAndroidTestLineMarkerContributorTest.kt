package com.sickworm.intellij.jugg.ide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JuggAndroidTestLineMarkerContributorTest {

    @Test
    fun `app androidTest path is supported`() {
        assertTrue(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/app/src/androidTest/java/com/example/FooTest.kt"
            )
        )
    }

    @Test
    fun `unit test path is not supported`() {
        assertFalse(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/app/src/test/java/com/example/FooTest.kt"
            )
        )
    }

    @Test
    fun `library androidTest path is not supported in phase three`() {
        assertFalse(
            JuggAndroidTestLineMarkerContributor.isSupportedAndroidTestPath(
                "/project/library1/src/androidTest/java/com/example/FooTest.kt"
            )
        )
    }
}
