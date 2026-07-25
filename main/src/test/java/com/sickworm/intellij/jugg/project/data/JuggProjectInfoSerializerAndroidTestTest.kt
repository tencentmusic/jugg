package com.sickworm.intellij.jugg.project.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class JuggProjectInfoSerializerAndroidTestTest {

    private fun composeInfo() = ComposeResourceInfo(
        generatorClasspath = listOf(File("/gradle/compose-gradle-plugin-1.7.3.jar"), File("/gradle/kotlin-stdlib-2.1.0.jar")),
        packageName = "com.example.generated.resources",
        publicResClass = true,
        resourceDirectories = listOf(
            ComposeResourceDirectory("commonMain", File("/project/shared/src/commonMain/composeResources")),
            ComposeResourceDirectory("androidMain", File("/project/shared/src/androidMain/customComposeResources")),
        ),
        assetRelativePath = "composeResources/com.example.generated.resources",
        resClassName = "AppRes",
        generateResourceContentHash = true,
        usesLegacyGenerator = true,
        supportStatus = ComposeResourceSupportStatus.Unsupported,
        unsupportedReason = "Unsupported Kotlin 2.0.21; Jugg Compose resources require Kotlin 2.1.x.",
    )

    private fun androidTestModule(appPkg: String = "com.example.app") =
        ModuleInfo.virtualModule.copy(
            name = "app.androidTest",
            moduleType = ModuleInfo.Type.Library,
            moduleRootDir = File("/project/app"),
            projectRootDir = File("/project"),
            applicationId = "$appPkg.test",
            instrumentationTargetPackage = appPkg,
            buildVariant = "debugAndroidTest",
        )

    @Test
    fun `serialize and deserialize androidTest module preserves instrumentationTargetPackage`() {
        val original = JuggProjectInfo(
            modules = mapOf("app.androidTest" to androidTestModule())
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals(
            "com.example.app",
            restored.modules["app.androidTest"]?.instrumentationTargetPackage
        )
    }

    @Test
    fun `deserialize module with missing instrumentationTargetPackage field yields null`() {
        // Verifies that a module without instrumentationTargetPackage (null) is preserved
        // through the serialize() -> deserialize() in-memory round-trip.
        // Guards against accidentally overwriting null during the copy() chains in serialize/deserialize.
        val original = JuggProjectInfo(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.modules["app"]?.instrumentationTargetPackage)
    }

    @Test
    fun `serialize preserves instrumentationTargetPackage distinct from applicationId`() {
        val original = JuggProjectInfo(
            modules = mapOf("app.androidTest" to androidTestModule())
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        val module = restored.modules["app.androidTest"]!!
        // instrumentationTargetPackage is the app package, applicationId is the test package — they must differ
        assertEquals("com.example.app", module.instrumentationTargetPackage)
        assertEquals("com.example.app.test", module.applicationId)
        assertNotEquals(module.instrumentationTargetPackage, module.applicationId)
    }

    @Test
    fun `serialize and deserialize preserves Compose resource info`() {
        val original = JuggProjectInfo(
            modules = mapOf("shared" to ModuleInfo.virtualModule.copy(
                name = "shared",
                composeResourceInfo = composeInfo(),
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(composeInfo(), restored.modules["shared"]?.composeResourceInfo)
    }
}
