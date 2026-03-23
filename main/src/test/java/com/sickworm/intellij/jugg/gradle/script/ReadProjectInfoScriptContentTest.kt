package com.sickworm.intellij.jugg.gradle.script

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadProjectInfoScriptContentTest {

    @Test
    fun generatedScript_shouldAvoidFragileCompatibilityApis() {
        val scriptText = javaClass.getResource("/gradle/readProjectInfo.gradle.kts")?.readText()
        assertNotNull(scriptText)

        assertFalse(scriptText.contains("groovy.util.XmlParser"))
        assertFalse(scriptText.contains("groovy.xml.QName"))
        assertFalse(scriptText.contains("XmlNodePrinter"))
        assertFalse(scriptText.contains("SelfResolvingDependency"))
        assertFalse(scriptText.contains("capitalize("))
        assertFalse(scriptText.contains("toUpperCase("))
        // Classes that use Reflector() in their instance methods must keep their companion object.
        // On Kotlin 1.5 (Gradle 7), removing the companion changes bytecode generation for the class,
        // causing the Kotlin backend to fail resolving outer instance references when constructing
        // sibling inner classes (Reflector), resulting in NoSuchMethodError at runtime.
        listOf("GradleApplicationInjector", "GradleProjectInfoReaderManager", "Reflector").forEach { className ->
            val classIdx = scriptText.indexOf("class $className")
            assertTrue(classIdx >= 0, "class $className not found in script")
            val classBody = scriptText.substring(classIdx)
            val classEnd = classBody.indexOf("\n}\n\n")
            val classBlock = if (classEnd >= 0) classBody.substring(0, classEnd) else classBody
            assertTrue(
                classBlock.contains("companion object"),
                "$className must keep its companion object in the script to avoid NoSuchMethodError in Kotlin 1.5 (Gradle 7)"
            )
        }
        // Reflector companion must NOT construct Reflector(): triggers getOuterExpression crash in Kotlin 1.5
        val reflectorIdx = scriptText.indexOf("class Reflector(")
        val companionIdx = scriptText.indexOf("companion object", reflectorIdx)
        val companionEndIdx = scriptText.indexOf("\n    }\n}", companionIdx)
        val companionBlock = if (companionEndIdx >= 0) scriptText.substring(companionIdx, companionEndIdx) else ""
        assertFalse(
            companionBlock.contains("return Reflector("),
            "Reflector companion must not construct Reflector(): causes getOuterExpression crash in Kotlin 1.5 (Gradle 7)"
        )
        assertFalse(scriptText.contains("project.buildDir"))
        assertFalse(scriptText.contains("gradle.buildFinished("))
        assertFalse(scriptText.contains("firstChar.toInt()"))
        assertFalse(scriptText.contains(".code"), "Char.code is Kotlin 1.5+ API, not supported in Gradle 7")
        assertFalse(scriptText.lineSequence().any { it.startsWith("const val ") || it.startsWith("private const val ") })
        // private top-level functions are not allowed in Kotlin 1.5 (Gradle 7)
        assertFalse(
            scriptText.lineSequence().any { it.startsWith("private fun ") },
            "private top-level functions not allowed in Kotlin 1.5 (Gradle 7)"
        )
        // top-level extension properties used in constructor default args crash Kotlin 1.5 backend
        assertFalse(
            scriptText.contains("val File.toCrc32"),
            "extension property in constructor default arg causes ConstructorContext.getOuterExpression crash in Kotlin 1.5 (Gradle 7)"
        )
        // top-level extension functions in constructor default args also crash Kotlin 1.5 backend
        assertFalse(
            scriptText.contains("fun File.toCrc32"),
            "extension function in constructor default arg causes generateConstructors crash in Kotlin 1.5 (Gradle 7)"
        )
        // top-level function calls in primary constructor default args crash Kotlin 1.5 script codegen
        assertFalse(
            scriptText.contains("val crc32: Long = computeCrc32"),
            "top-level function call in primary constructor default arg crashes Kotlin 1.5 (Gradle 7) script codegen"
        )
        // top-level val references in primary constructor default args also crash Kotlin 1.5 script codegen
        assertFalse(
            scriptText.contains("val version: Int = VERSION"),
            "top-level val reference in primary constructor default arg crashes Kotlin 1.5 (Gradle 7) script codegen"
        )
    }

    @Test
    fun sourceFiles_shouldKeepReadableCompanionEntries() {
        val injectorText = readSource("src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleApplicationInjector.kt")
        assertTrue(injectorText.contains("companion object"))
        assertTrue(injectorText.contains("const val PARAM_ENABLE = \"jugg.inject.application.enable\""))

        val managerText = readSource("src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt")
        assertTrue(managerText.contains("companion object"))
        assertTrue(managerText.contains("const val PARAM_DIFF_MODE = \"jugg.diffMode\""))
        assertTrue(managerText.contains("const val PARAM_INC_DEPLOY_TIMES = \"jugg.incDeployTimes\""))

        val modelText = readSource("src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt")
        assertTrue(modelText.contains("companion object"))
        assertTrue(modelText.contains("const val DEFAULT_BUILD_VARIANT = \"debug\""))
        assertTrue(modelText.contains("val virtualModule = ModuleInfo("))
        assertTrue(modelText.contains("private fun File.listFilesRecursively()"))
        assertTrue(modelText.contains("private fun <T, R : Any> Iterable<T>.firstNotNullOfOrNull("))
        assertTrue(modelText.contains("private fun computeCrc32(file: File)"))
        assertFalse(modelText.contains("private fun File.listFilesRecursivelyCompat()"))
        assertFalse(modelText.contains("private fun <T, R : Any> Iterable<T>.firstNotNullOfOrNullCompat("))
        assertFalse(modelText.contains("private val File.toCrc32Compat: Long"))
        assertTrue(modelText.contains("val EMPTY = SigningConfig("))

        val manifestInfoText = readSource("src/main/java/com/sickworm/intellij/jugg/compiler/manifest/XmlAndroidManifestInfo.kt")
        assertFalse(manifestInfoText.contains("private set"))

        val pathManagerText = readSource("src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt")
        assertTrue(pathManagerText.contains("companion object"))
        assertTrue(pathManagerText.contains("const val RSYNC_PUSH_CONFIG_DIR_ARGUMENTS"))
        assertTrue(pathManagerText.contains("const val RSYNC_FETCH_DIFF_DIR_ARGUMENTS"))
    }

    @Test
    fun reflectorSource_shouldReuseSharedCamelCompatHelper() {
        val reflectorText = readSource("src/main/java/com/sickworm/intellij/jugg/gradle/script/Reflector.kt")
        assertTrue(reflectorText.contains("get\${propertyName.camelCompat}"))
        assertFalse(reflectorText.contains("private fun Char.uppercaseChar()"))
        assertFalse(reflectorText.contains("toUpperCase("))
        // companion object must NOT construct Reflector(): companion is static context, but in .kts files
        // Reflector is a non-static inner class of the script. Constructing Reflector() from static
        // context triggers getOuterExpression crash in Kotlin 1.5 (Gradle 7).
        val companionStart = reflectorText.indexOf("companion object")
        assertTrue(companionStart >= 0, "companion object not found in Reflector.kt")
        val companionBlock = reflectorText.substring(companionStart)
        val companionEnd = companionBlock.indexOf("\n    }\n}")
        val companionOnly = if (companionEnd >= 0) companionBlock.substring(0, companionEnd) else companionBlock
        assertFalse(
            companionOnly.contains("return Reflector("),
            "companion object must not construct Reflector(): causes getOuterExpression crash in Kotlin 1.5 (Gradle 7)"
        )
        // newInstance/newInstanceP must be accessible as top-level wrappers
        assertTrue(reflectorText.contains("fun reflectorNewInstance("))
        assertTrue(reflectorText.contains("fun reflectorNewInstanceP("))
    }

    @Test
    fun gradleProjectInfoReader_shouldKeepFileCollectionIterationOrder() {
        val readerText = readSource("src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt")
        assertTrue(readerText.contains("dependency.files.toList()"))
        assertFalse(readerText.contains("dependency.files.files.toList()"))
    }

    private fun readSource(relativePath: String): String {
        return java.io.File(System.getProperty("user.dir"), relativePath).readText()
    }
}
