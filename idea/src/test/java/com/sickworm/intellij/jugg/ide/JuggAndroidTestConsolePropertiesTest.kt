package com.sickworm.intellij.jugg.ide

import com.intellij.execution.testframework.JavaTestLocator
import org.junit.Assert.assertSame
import org.junit.Test

class JuggAndroidTestConsolePropertiesTest {

    @Test
    fun `test locator uses Java test locator protocols`() {
        assertSame(JavaTestLocator.INSTANCE, JuggAndroidTestConsoleProperties.testLocator())
    }
}
