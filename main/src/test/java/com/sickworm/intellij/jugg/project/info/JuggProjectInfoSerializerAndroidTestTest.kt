package com.sickworm.intellij.jugg.project.info

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.mock.StdLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `deserialize legacy variant preserves module minSdk and signing config`() {
        val original = projectInfoWithoutAgpR8(mapOf("app" to ModuleInfo.virtualModule.copy(
            name = "app",
            buildVariant = "compatDebug",
            minSdkVersion = "21",
            variants = listOf(Variant("compatDebug", "debug", "21")),
        )))
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("modules")[0].asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .getAsJsonArray("variants")[0].asJsonObject.remove("minSdkVersion")

        val restored = JuggProjectInfoSerialize.deserialize(
            ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java),
            isSkipVersionCheck = true,
        ).modules.getValue("app")

        assertEquals("21", restored.minSdkVersion)
        assertEquals("compatDebug", restored.buildVariant)
        assertEquals(listOf(Variant("compatDebug", "debug")), restored.variants)
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
    fun `serialize and deserialize preserves external build metadata`() {
        val externalBuildInfos = listOf(
            ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = listOf(flutterInputDir(File("/project/flutter"))),
                taskPath = ":app:compileFlutterBuildDebug",
                assetsOutputDir = File("/project/app/build/flutter/intermediates"),
                nativeOutput = File("/project/app/build/flutter/native.jar"),
            ),
            ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = listOf(cppInputDir(File("/project/native/src/main/cpp"))),
                taskPath = ":native:mergeDebugNativeLibs",
                assetsOutputDir = null,
                nativeOutput = File("/project/native/build/intermediates/merged_native_libs/debug/out/lib"),
            ),
        )
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                externalBuildInfos = externalBuildInfos,
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(externalBuildInfos, restored.modules["app"]?.externalBuildInfos)
    }

    @Test
    fun `rejects project info that stores external build inputs as plain paths`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                externalBuildInfos = listOf(externalBuildInfo()),
            ))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        val info = json.getAsJsonArray("modules")[0]
            .asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .getAsJsonArray("externalBuildInfos")[0]
            .asJsonObject
        // Snapshots written before the rule model described every input root by one plain path.
        info.add("inputDirs", JsonParser.parseString(
            """["/project/flutter", "/project/shared-package"]"""
        ).asJsonArray)

        val error = assertThrows(JsonSyntaxException::class.java) {
            ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)
        }

        assertTrue(
            "unexpected error: $error",
            generateSequence(error as Throwable) { it.cause }
                .any { it.message == EXTERNAL_BUILD_INPUT_SCHEMA_ERROR },
        )
    }

    @Test
    fun `serialize and deserialize keeps every rule set of one directory`() {
        val info = ExternalBuildInfo(
            type = ExternalBuildType.Cpp,
            inputDirs = listOf(
                ExternalBuildInputDir(File("/project/DTMP"), setOf(ExternalBuildInputFilterRule.CppHeader)),
                ExternalBuildInputDir(
                    File("/project/DTMP"),
                    setOf(ExternalBuildInputFilterRule.CppHeader, ExternalBuildInputFilterRule.CppSource),
                ),
                ExternalBuildInputDir(File("/project/DTMP/native"), setOf(ExternalBuildInputFilterRule.NativeDirectory)),
            ),
            taskPath = ":app:mergeDebugNativeLibs",
            assetsOutputDir = null,
            nativeOutput = File("/project/DTMP/build/merged"),
        )
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                externalBuildInfos = listOf(info),
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(info.inputDirs, restored.modules["app"]?.externalBuildInfos?.single()?.inputDirs)
    }

    @Test
    fun `serialize and deserialize preserves external build inputs`() {
        val info = externalBuildInfo()
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                externalBuildInfos = listOf(info),
            ))
        )

        val restored = JuggProjectInfoSerialize.deserialize(
            JuggProjectInfoSerialize.serialize(original),
            isSkipVersionCheck = true,
        )

        assertEquals(info, restored.modules["app"]?.externalBuildInfos?.single())
    }

    private fun externalBuildInfo() = ExternalBuildInfo(
        type = ExternalBuildType.Flutter,
        inputDirs = listOf(
            flutterInputDir(File("/project/flutter")),
            flutterInputDir(File("/project/shared-package")),
        ),
        taskPath = ":flutter:copyJniLibsflutterBuildDebug",
        assetsOutputDir = File("/project/flutter/build/intermediates/flutter/debug"),
        nativeOutput = File("/project/flutter/build/generated/jniLibs/copyJniLibsflutterBuildDebug"),
        configFiles = listOf(File("/project/flutter/pubspec.yaml")),
        excludedDirs = listOf(File("/project/flutter/.dart_tool")),
    )

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
    fun `targeted load preserves invalid project info file`() {
        val dataFile = Files.createTempFile("jugg_project_info_invalid_", ".json").toFile()
        try {
            dataFile.writeText("invalid json")

            val restored = ProjectInfoSerializer(
                dataFile,
                StdLogger("JuggProjectInfoSerializerAndroidTestTest"),
            ).loadPreservingFile(isSkipVersionCheck = true)

            assertNull(restored)
            assertTrue(dataFile.exists())
            assertEquals("invalid json", dataFile.readText())
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
    fun `deserialize old project info without R package name yields null`() {
        val library = LibraryDependency("com.example:external:1.0", File("/gradle/caches/external/res"), 0L, 1L)
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                name = "app",
                libraryDependencies = listOf(library),
            ))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("dependencyList")[0].asJsonObject.remove("rPackageName")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertNull(restored.modules["app"]?.libraryDependencies?.single()?.rPackageName)
    }

    @Test
    fun `project info file round-trip preserves R package name`() {
        val dataFile = Files.createTempFile("jugg_project_info_r_package_", ".json").toFile()
        val logger = StdLogger("JuggProjectInfoSerializerAndroidTestTest")
        try {
            ProjectInfoSerializer(dataFile, logger).save(JuggProjectInfo(
                modules = mapOf("app" to ModuleInfo.virtualModule.copy(
                    name = "app",
                    libraryDependencies = listOf(
                        LibraryDependency(
                            "com.example:external:1.0",
                            File("/gradle/caches/external/res"),
                            0L,
                            1L,
                            "com.example.external",
                        )
                    ),
                )),
                agpR8Classpath = null,
            ))

            val restored = ProjectInfoSerializer(dataFile, logger).load(isSkipVersionCheck = true)

            assertEquals(
                "com.example.external",
                restored?.modules?.get("app")?.libraryDependencies?.single()?.rPackageName,
            )
        } finally {
            dataFile.delete()
        }
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
    fun `deserialize old project info without external builds defaults to empty list`() {
        val original = projectInfoWithoutAgpR8(
            modules = mapOf("app" to ModuleInfo.virtualModule.copy(name = "app"))
        )
        val json = JsonParser.parseString(
            ProjectInfoSerializer.gson.toJson(JuggProjectInfoSerialize.serialize(original))
        ).asJsonObject
        json.getAsJsonArray("modules")[0]
            .asJsonObject
            .getAsJsonObject("moduleInfoExceptLibraries")
            .remove("externalBuildInfos")
        val serialized = ProjectInfoSerializer.gson.fromJson(json, JuggProjectInfoSerialize::class.java)

        val restored = JuggProjectInfoSerialize.deserialize(serialized, isSkipVersionCheck = true)

        assertEquals(emptyList<ExternalBuildInfo>(), restored.modules["app"]?.externalBuildInfos)
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
    private fun flutterInputDir(directory: File) =
        ExternalBuildInputDir(directory, setOf(ExternalBuildInputFilterRule.Dart))

    private fun cppInputDir(directory: File) = ExternalBuildInputDir(
        directory,
        setOf(ExternalBuildInputFilterRule.CppSource, ExternalBuildInputFilterRule.CppHeader),
    )

}
