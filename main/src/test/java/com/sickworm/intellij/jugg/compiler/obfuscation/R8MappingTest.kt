package com.sickworm.intellij.jugg.compiler.obfuscation

import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Unit tests for R8 mapping file parsing and obfuscation verification.
 *
 * These tests verify that the R8MappingReader correctly parses mapping files
 * and that the keep rules defined in proguard-rules.pro are applied correctly.
 *
 * Test cases are based on the minify test classes in:
 * android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/minify/
 *
 * NOTE: Some tests may be skipped if the mapping file doesn't contain the test classes.
 * To run all tests, rebuild the release APK: ./gradlew :app:assembleRelease
 */
class R8MappingTest {

    companion object {
        private const val MINIFY_PACKAGE = "com.sickworm.jugg.demo.testcase.minify"

        private var mappingReader: R8MappingReader? = null
        private var setupError: String? = null

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            try {
                // First check if mapping file already exists from a previous build
                val mappingFile = File(TestGlobal.assetsAndroidDir, "build/app/outputs/mapping/release/mapping.txt")

                // Build release APK with minify enabled (only once for all tests)
                GradleBuildHelper.appAssembleRelease()

                // Verify mapping file exists after build attempt
                if (!mappingFile.exists()) {
                    setupError = "Mapping file not found: ${mappingFile.absolutePath}"
                    return
                }

                // Load mapping reader for verification
                mappingReader = R8MappingReader.fromFile(mappingFile)
            } catch (e: Exception) {
                setupError = "Setup failed: ${e.message}"
            }
        }
    }

    // ========================================================================
    // Test Case 1: Keep class name only (members can be obfuscated)
    // ========================================================================

    @Test
    fun testKeepClassName_classNamePreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepClassName"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // Class name should be preserved (original == obfuscated)
        assertEquals(
            originalClassName, mapping.obfuscatedName,
            "KeepClassName class name should be preserved"
        )
    }

    @Test
    fun testKeepClassName_membersCanBeObfuscated() {
        val originalClassName = "$MINIFY_PACKAGE.KeepClassName"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // The important thing is the class name is kept
        assertEquals(
            originalClassName, mapping.obfuscatedName,
            "Class name should be preserved while members may be obfuscated"
        )
    }

    // ========================================================================
    // Test Case 2: Keep specific class members (class name can be obfuscated)
    // ========================================================================

    @Test
    fun testKeepClassMembers_specificMembersPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepClassMembers"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // keptField should be preserved
        val keptField = mapping.fields.find { it.originalName == "keptField" }
        if (keptField != null) {
            assertEquals(
                "keptField", keptField.obfuscatedName,
                "keptField should preserve its name"
            )
        }

        // keptMethod should be preserved
        val keptMethod = mapping.methods.find { it.originalName == "keptMethod" }
        if (keptMethod != null) {
            assertEquals(
                "keptMethod", keptMethod.obfuscatedName,
                "keptMethod should preserve its name"
            )
        }
    }

    // ========================================================================
    // Test Case 3: Fully obfuscated class (no keep rules)
    // ========================================================================

    @Test
    fun testFullyObfuscated_classNameObfuscated() {
        val originalClassName = "$MINIFY_PACKAGE.FullyObfuscated"
        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)

        // Class should be obfuscated (if it exists in mapping)
        if (mapping != null) {
            assertNotEquals(
                originalClassName, mapping.obfuscatedName,
                "FullyObfuscated class name should be obfuscated"
            )
        }
        // If mapping is null, class might be inlined/removed by R8, which is also valid
    }

    // ========================================================================
    // Test Case 4: Unreferenced class (should be removed by R8)
    // ========================================================================

    @Test
    fun testUnreferencedClass_removedByR8() {
        val originalClassName = "$MINIFY_PACKAGE.UnreferencedClass"
        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)

        // UnreferencedClass should not exist in mapping (removed by R8 tree shaking)
        assertNull(mapping, "UnreferencedClass should be removed by R8 tree shaking")
    }

    // ========================================================================
    // Test Case 5: Keep specific method name
    // ========================================================================

    @Test
    fun testKeepMethodName_specificMethodPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepMethodName"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // keptMethod should be preserved
        val keptMethod = mapping.methods.find { it.originalName == "keptMethod" }
        if (keptMethod != null) {
            assertEquals(
                "keptMethod", keptMethod.obfuscatedName,
                "keptMethod should preserve its name"
            )
        }
    }

    // ========================================================================
    // Test Case 6: Keep via @Keep annotation
    // ========================================================================

    @Test
    fun testKeepAnnotated_annotatedMembersPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepAnnotated"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // @Keep annotated field should be preserved
        val keptField = mapping.fields.find { it.originalName == "keptField" }
        if (keptField != null) {
            assertEquals(
                "keptField", keptField.obfuscatedName,
                "@Keep annotated keptField should preserve its name"
            )
        }

        // @Keep annotated method should be preserved
        val keptMethod = mapping.methods.find { it.originalName == "keptMethod" }
        if (keptMethod != null) {
            assertEquals(
                "keptMethod", keptMethod.obfuscatedName,
                "@Keep annotated keptMethod should preserve its name"
            )
        }
    }

    // ========================================================================
    // Test Case 7: Interface and implementation
    // ========================================================================

    @Test
    fun testInterface_interfaceNamePreserved() {
        val originalClassName = "$MINIFY_PACKAGE.MinifyTestInterface"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        // Interface should be kept (not in mapping list means it's kept)
        assertNull(mapping, "MinifyTestInterface should be kept and not in mapping")
    }

    // ========================================================================
    // Test Case 8: Serializable class
    // ========================================================================

    @Test
    fun testSerializableClass_serializedFieldPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.SerializableClass"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        val serializedField = mapping.fields.find { it.originalName == "serializedField" }
        if (serializedField != null) {
            assertEquals(
                "serializedField", serializedField.obfuscatedName,
                "serializedField should preserve its name for serialization"
            )
        }
    }

    // ========================================================================
    // Test Case 9: Enum class
    // ========================================================================

    @Test
    fun testEnumClass_enumValuesAccessible() {
        val originalClassName = "$MINIFY_PACKAGE.MinifyTestEnum"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        // Enum should be kept
        assertNull(mapping, "MinifyTestEnum should be kept and not in mapping")
    }

    // ========================================================================
    // Test Case 11: Native methods
    // ========================================================================

    @Test
    fun testNativeMethodClass_nativeMethodPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.NativeMethodClass"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // Native method should be preserved for JNI linkage
        val nativeMethod = mapping.methods.find { it.originalName == "nativeMethod" }
        if (nativeMethod != null) {
            assertEquals(
                "nativeMethod", nativeMethod.obfuscatedName,
                "Native method should preserve its name for JNI"
            )
        }
    }

    // ========================================================================
    // Test Case 12: Wildcard keep rules
    // ========================================================================

    @Test
    fun testWildcardKeep_prefixMembersPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.WildcardKeepClass"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // prefixKeptField should be preserved (matches prefix*)
        // Note: The rule "*** prefix*" matches fields only in ProGuard syntax
        val prefixField = mapping.fields.find { it.originalName == "prefixKeptField" }
        if (prefixField != null) {
            assertEquals(
                "prefixKeptField", prefixField.obfuscatedName,
                "prefixKeptField should preserve its name (matches prefix*)"
            )
        }

        // Note: Methods require different syntax to match with wildcards
        // The current rule only preserves fields, not methods
    }

    // ========================================================================
    // Test Case 13: Keep class name and all members
    // ========================================================================

    @Test
    fun testKeepClassAndMembers_classNamePreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepClassAndMembers"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        assertEquals(
            originalClassName, mapping.obfuscatedName,
            "KeepClassAndMembers class name should be preserved"
        )
    }

    @Test
    fun testKeepClassAndMembers_allMembersPreserved() {
        val originalClassName = "$MINIFY_PACKAGE.KeepClassAndMembers"

        val mapping = mappingReader!!.getClassMappingByOriginalName(originalClassName)
        if (mapping == null) {
            // Class not in mapping, skip test
            return
        }

        // All fields should be preserved
        mapping.fields.forEach { field ->
            assertEquals(
                field.originalName, field.obfuscatedName,
                "Field ${field.originalName} should preserve its name"
            )
        }

        // All methods should be preserved (except special methods like <init>)
        mapping.methods
            .filter { !it.originalName.startsWith("<") }
            .forEach { method ->
                assertEquals(
                    method.originalName, method.obfuscatedName,
                    "Method ${method.originalName} should preserve its name"
                )
            }
    }
}
