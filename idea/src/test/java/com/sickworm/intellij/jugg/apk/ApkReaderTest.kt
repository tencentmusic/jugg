package com.sickworm.intellij.jugg.apk

import org.junit.Test
import kotlin.test.assertEquals

class BuildToolsVersionComparatorTest {

    @Test
    fun testVersionCompare() {
        val dirs = mutableListOf("30.0.0", "30.0.2")
        val maxVersion = dirs.maxBy { BuildToolsVersionComparator(it) }
        assertEquals("30.0.2", maxVersion)
    }

    @Test
    fun testVersionCompare2() {
        val dirs = mutableListOf("30.0.0", "30.0.2", "android-8.1.0", "android-4.4.2")
        val maxVersion = dirs.maxBy { BuildToolsVersionComparator(it) }
        assertEquals("30.0.2", maxVersion)
    }

    @Test
    fun testVersionCompare3() {
        val dirs = mutableListOf("29.0.0", "28.0.0", "30.0.0", "30.0.2", "android-8.1.0", "android-4.4.2")
        val maxVersion = dirs.maxBy { BuildToolsVersionComparator(it) }
        assertEquals("30.0.2", maxVersion)
    }

    @Test
    fun testVersionCompare4() {
        val dirs = mutableListOf("32.0.0", "32.0.0_rc1", "32.0.0_rc2", "29.0.0", "28.0.0", "30.0.0", "30.0.2", "android-8.1.0", "android-4.4.2")
        val maxVersion = dirs.maxBy { BuildToolsVersionComparator(it) }
        assertEquals("32.0.0_rc2", maxVersion)
    }

    @Test
    fun testVersionCompare5() {
        val dirs = mutableListOf("34.0.0", "32.0.0", "32.0.0_rc2", "29.0.0", "28.0.0", "30.0.0", "30.0.2", "android-8.1.0", "android-4.4.2")
        val maxVersion = dirs.maxBy { BuildToolsVersionComparator(it) }
        assertEquals("34.0.0", maxVersion)
    }

}