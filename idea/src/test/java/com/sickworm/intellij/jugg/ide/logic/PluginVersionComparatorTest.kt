package com.sickworm.intellij.jugg.ide.logic

import org.junit.Test
import org.junit.Assert.*

class PluginVersionComparatorTest {

    @Test
    fun testVersionCompare() {
        // Test basic version number comparison
        assertTrue("3.1.0 should be less than 3.2.0", 
            PluginVersionComparator.compare("3.1.0", "3.2.0") < 0)
        assertTrue("3.2.0 should be greater than 3.1.0", 
            PluginVersionComparator.compare("3.2.0", "3.1.0") > 0)
        assertEquals("3.1.0 should equal 3.1.0", 
            0, PluginVersionComparator.compare("3.1.0", "3.1.0"))
        
        // Test version numbers with different lengths
        assertTrue("3.1 should be less than 3.1.1", 
            PluginVersionComparator.compare("3.1", "3.1.1") < 0)
        assertTrue("3.1.1 should be greater than 3.1", 
            PluginVersionComparator.compare("3.1.1", "3.1") > 0)
        
        // Test rc versions
        assertTrue("3.1.0-rc1 should be less than 3.1.0", 
            PluginVersionComparator.compare("3.1.0-rc1", "3.1.0") < 0)
        assertTrue("3.1.0-rc1 should be less than 3.1.0-rc2", 
            PluginVersionComparator.compare("3.1.0-rc1", "3.1.0-rc2") < 0)
        
        // Test SNAPSHOT versions
        assertEquals("SNAPSHOT versions should be treated as same as non-SNAPSHOT", 
            0, PluginVersionComparator.compare("3.1.0-SNAPSHOT", "3.1.0"))
        assertTrue("3.1.0-rc1-SNAPSHOT should be less than 3.1.0", 
            PluginVersionComparator.compare("3.1.0-rc1-SNAPSHOT", "3.1.0") < 0)
        
        // Test feature versions
        assertTrue("3.1.0-feature-abc should be less than 3.1.0-rc1", 
            PluginVersionComparator.compare("3.1.0-feature-abc", "3.1.0-rc1") < 0)
        assertTrue("3.1.0-feature-abc should be less than 3.1.0", 
            PluginVersionComparator.compare("3.1.0-feature-abc", "3.1.0") < 0)
        
        // Test complex version numbers
        assertTrue("4.0.0-rc3-feature-abc-SNAPSHOT should be less than 4.0.0", 
            PluginVersionComparator.compare("4.0.0-rc3-feature-abc-SNAPSHOT", "4.0.0") < 0)
        
        // Test different major version numbers
        assertTrue("3.14.0 should be less than 4.0.0", 
            PluginVersionComparator.compare("3.14.0", "4.0.0") < 0)
        assertTrue("3.1.20 should be greater than 3.1.0", 
            PluginVersionComparator.compare("3.1.20", "3.1.0") > 0)
    }
}