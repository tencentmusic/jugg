package com.sickworm.intellij.jugg.project.data

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JuggProjectInfoSerializerAndroidTestTest {

    private fun projectInfoWithoutAgpR8(modules: Map<String, ModuleInfo>) =
        JuggProjectInfo(modules, agpR8Classpath = null)

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
        val original = projectInfoWithoutAgpR8(
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
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val serialized = JuggProjectInfoSerialize.serialize(original)
        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.modules["app"]?.instrumentationTargetPackage)
    }

    @Test
    fun `deserialize module with missing runtime module dependencies yields null`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                runtimeModuleDependencies = listOf(ModuleDependency("library1")),
            ))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("modules")[0]
            .asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .remove("runtimeModuleDependencies")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.modules["app"]?.runtimeModuleDependencies)
    }

    @Test
    fun `serialize preserves instrumentationTargetPackage distinct from applicationId`() {
        val original = projectInfoWithoutAgpR8(
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
        val original = projectInfoWithoutAgpR8(
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

    @Test
    fun `serialize and deserialize preserves Kotlin common source directories`() {
        val commonSourceDirs = listOf(
            File("/project/shared/src/commonMain/kotlin"),
            File("/project/shared/src/sharedMain/kotlin"),
        )
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("shared" to ModuleInfo.virtualModule.copy(
                name = "shared",
                kotlinCommonSourceDirs = commonSourceDirs,
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(commonSourceDirs, restored.modules["shared"]?.kotlinCommonSourceDirs)
    }

    @Test
    fun `serialize and deserialize preserves Kotlin compiler plugin options`() {
        val pluginOptions = listOf("plugin:dev.zacsweers.moshix.compiler:enabled=true")
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                kotlinPluginOptions = pluginOptions,
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(pluginOptions, restored.modules["app"]?.kotlinPluginOptions)
    }

    @Test
    fun `serialize and deserialize preserves AGP R8 classpath`() {
        val r8Classpath = File("/gradle/caches/builder-9.2.0.jar")
        val original = JuggProjectInfo(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app")),
            agpR8Classpath = r8Classpath,
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(r8Classpath, restored.agpR8Classpath)
    }

    @Test
    fun `project info file round-trip preserves AGP R8 classpath`() {
        val dataFile = Files.createTempFile("jugg_project_info_", ".json").toFile()
        val logger = StdLogger("JuggProjectInfoSerializerAndroidTestTest")
        val r8Classpath = File("/gradle/caches/builder-9.2.0.jar")
        try {
            ProjectInfoSerializer(dataFile, logger).save(JuggProjectInfo(
                modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app")),
                agpR8Classpath = r8Classpath,
            ))

            val restored = ProjectInfoSerializer(dataFile, logger).load(isSkipVersionCheck = true)

            assertEquals(r8Classpath, restored?.agpR8Classpath)
        } finally {
            dataFile.delete()
        }
    }

    @Test
    fun `deserialize old project info without AGP R8 classpath yields null`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonObject("juggProjectInfoExceptModules").remove("agpR8Classpath")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.agpR8Classpath)
    }

    @Test
    fun `deserialize old project info without Kotlin common source directories defaults to empty list`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("shared" to ModuleInfo.virtualModule.copy(name = "shared"))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("modules")[0]
            .asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .remove("kotlinCommonSourceDirs")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals(emptyList<File>(), restored.modules["shared"]?.kotlinCommonSourceDirs)
    }

    @Test
    fun `deserialize old project info without Kotlin compiler plugin options defaults to empty list`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("modules")[0]
            .asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .remove("kotlinPluginOptions")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals(emptyList<String>(), restored.modules["app"]?.kotlinPluginOptions)
    }
}
