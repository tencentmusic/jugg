package com.sickworm.intellij.jugg.project.runtime

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Verifies deterministic CLI run-configuration inference and JSON persistence. */
class CliRunConfigurationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `default inference prefers app module and current custom variant`() {
        val projectDir = temporaryFolder.newFolder("app_project")
        val projectInfo = projectInfo(applicationModule(projectDir, "feature", "debug"), applicationModule(projectDir, "app", "qaDebug"))

        val configuration = CliRunConfigurationGenerator.generate(projectInfo, generatedAt = 100L)

        assertEquals("app", configuration.moduleName)
        assertEquals("qaDebug", configuration.variant)
        assertEquals("./gradlew :app:assembleQaDebug", configuration.compileCommand)
        assertEquals("app/build/outputs/apk/qa/debug/*.apk", configuration.outputApkName)
        assertEquals("gradle-project-info", configuration.generatedBy)
        assertEquals(BuildTarget.APP, configuration.buildTarget)
    }

    @Test
    fun `default inference uses stable ordering when app module is absent`() {
        val projectDir = temporaryFolder.newFolder("multi_app_project")
        val projectInfo = projectInfo(applicationModule(projectDir, "zeta", "release"), applicationModule(projectDir, "mobile", "debug"))

        val first = CliRunConfigurationGenerator.generate(projectInfo, generatedAt = 100L)
        val second = CliRunConfigurationGenerator.generate(projectInfo, generatedAt = 200L)

        assertEquals("mobile", first.moduleName)
        assertEquals("debug", first.variant)
        assertEquals(first.id, second.id)
        assertNotEquals(first.generatedAt, second.generatedAt)
    }

    @Test
    fun `default inference uses configured build directory`() {
        val projectDir = temporaryFolder.newFolder("custom_build_dir_project")
        val app = applicationModule(projectDir, "app", "debug").copy(
            buildPathInfo = ModuleBuildPathInfo(projectDir, File(projectDir, "app"), "debug", buildDirRelativePath = "build/app"),
        )

        val configuration = CliRunConfigurationGenerator.generate(projectInfo(app), generatedAt = 100L)

        assertEquals("build/app/outputs/apk/debug/*.apk", configuration.outputApkName)
    }

    @Test
    fun `module inference creates stable profile for active variant`() {
        val projectDir = temporaryFolder.newFolder("module_variant_project")
        val paid = applicationModule(projectDir, "paid", "release")

        val first = CliRunConfigurationGenerator.generateForModule(paid, generatedAt = 100L)
        val second = CliRunConfigurationGenerator.generateForModule(paid, generatedAt = 200L)

        assertEquals("paid", first.moduleName)
        assertEquals("release", first.variant)
        assertEquals("./gradlew :paid:assembleRelease", first.compileCommand)
        assertEquals("paid/build/outputs/apk/release/*.apk", first.outputApkName)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `verified module identity reuses module profile id`() {
        val projectDir = temporaryFolder.newFolder("module_identity_project")
        val includedApp = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = File(projectDir, "SMCommon/app"),
            buildVariant = "prodStaging",
            buildPathInfo = ModuleBuildPathInfo(
                projectDir,
                File(projectDir, "SMCommon/app"),
                "prodStaging",
                buildDirRelativePath = "",
            ),
        )

        val moduleConfiguration = CliRunConfigurationGenerator.generateForModule(includedApp, generatedAt = 100L)
        val identityConfiguration = CliRunConfigurationGenerator.generateForModuleIdentity(
            modulePath = ":SMCommon:app",
            moduleName = "SMCommon.app",
            variant = "prodStaging",
            outputApkName = "SMCommon/app/build/outputs/apk/prod/staging/*.apk",
            generatedAt = 200L,
        )

        assertEquals(moduleConfiguration.id, identityConfiguration.id)
        assertEquals("./gradlew :SMCommon:app:assembleProdStaging", identityConfiguration.compileCommand)
        assertEquals("SMCommon.app", identityConfiguration.moduleName)
    }

    @Test
    fun `build identity follows known variant suffix on the leaf module task`() {
        val projectDir = temporaryFolder.newFolder("variant_suffix_identity")
        val app = applicationModule(projectDir, "app", "debug").copy(
            variants = listOf(Variant("debug", null), Variant("release", null)),
        )
        val musicAppDir = File(projectDir, "app/musicApp")
        val musicApp = ModuleInfo.virtualModule.copy(
            name = "app.musicApp",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = musicAppDir,
            buildVariant = "debug",
            variants = listOf(Variant("debug", null), Variant("release", null)),
            buildPathInfo = ModuleBuildPathInfo(projectDir, musicAppDir, "debug", buildDirRelativePath = ""),
        )
        val projectInfo = projectInfo(app, musicApp)
        val customDebug = "gradlew.bat :app:musicApp:deployDebug --stacktrace"

        assertEquals("app.musicApp" to "debug", CliRunConfigurationGenerator.resolveBuildIdentity(projectInfo, customDebug))
        assertNull(CliRunConfigurationGenerator.encodedBuildVariant(customDebug, app))
        assertTrue(CliRunConfigurationGenerator.matchesBuildIdentity(customDebug, musicApp, "debug"))
        assertFalse(CliRunConfigurationGenerator.targetsDifferentBuildVariant(customDebug, musicApp, "debug"))
        assertTrue(
            CliRunConfigurationGenerator.targetsDifferentBuildVariant(
                "./gradlew :app:musicApp:assembleRelease",
                musicApp,
                "debug",
            ),
        )
        assertFalse(
            CliRunConfigurationGenerator.targetsDifferentBuildVariant(
                "./gradlew :app:musicApp:customTask",
                musicApp,
                "debug",
            ),
        )
    }

    @Test
    fun `build identity prefers the longest known variant suffix`() {
        val projectDir = temporaryFolder.newFolder("longest_variant_suffix")
        val app = applicationModule(projectDir, "app", "debug").copy(
            variants = listOf(Variant("debug", null), Variant("paidDebug", null)),
        )

        assertEquals(
            "paidDebug",
            CliRunConfigurationGenerator.encodedBuildVariant("./gradlew :app:assemblePaidDebug", app),
        )
        assertFalse(CliRunConfigurationGenerator.matchesBuildIdentity("./gradlew :app:assemblePaidDebug", app, "debug"))
        assertTrue(CliRunConfigurationGenerator.targetsDifferentBuildVariant("./gradlew :app:assemblePaidDebug", app, "debug"))
    }

    @Test
    fun `generated profile identity rejects custom commands`() {
        val projectDir = temporaryFolder.newFolder("generated_profile_identity")
        val app = applicationModule(projectDir, "app", "release").copy(
            variants = listOf(Variant("debug", null), Variant("release", null)),
        )

        assertEquals(
            "debug",
            CliRunConfigurationGenerator.findGeneratedConfiguration("./gradlew :app:assembleDebug", app)?.variant,
        )
        assertNull(CliRunConfigurationGenerator.findGeneratedConfiguration("./gradlew :app:uploadDebug", app))
        assertNull(CliRunConfigurationGenerator.findGeneratedConfiguration("./gradlew :app:assembleDebug --offline", app))
    }

    @Test
    fun `recent successful build has priority over project info fallback`() {
        val projectDir = temporaryFolder.newFolder("recent_build_project")
        val projectInfo = projectInfo(applicationModule(projectDir, "app", "debug"))
        val recent = configuration(id = "014f6d67-28c7-4a25-bd8d-7bd467cb9a84", moduleName = "paid", variant = "paidRelease")

        val actual = CliRunConfigurationGenerator.generate(projectInfo, recentSuccessfulBuild = recent, generatedAt = 300L)

        assertEquals(recent, actual)
    }

    @Test
    fun `configuration collection and current pointer round trip`() {
        val projectDir = temporaryFolder.newFolder("store_project")
        val pathManager = JuggPathManager(projectDir)
        val store = CliRunConfigurationStore(pathManager)
        val first = configuration(id = "014f6d67-28c7-4a25-bd8d-7bd467cb9a84", moduleName = "app", variant = "debug")
        val second = configuration(id = "2d257e92-3d76-4c41-a792-93ac08a6d73f", moduleName = "demo", variant = "release")

        store.save(first)
        store.save(second)
        store.select(second.id)

        assertEquals(listOf(first, second).sortedBy { it.id }, store.loadAll().sortedBy { it.id })
        assertEquals(second, store.loadCurrent())
        assertTrue(pathManager.runConfigurationsDir.resolve("${first.id}.json").isFile)
        assertTrue(pathManager.currentRunConfigurationFile.isFile)
    }

    @Test
    fun `safe description never exposes remote password`() {
        val configuration = configuration(id = "014f6d67-28c7-4a25-bd8d-7bd467cb9a84", moduleName = "app", variant = "debug")
        val pathManager = JuggPathManager(temporaryFolder.newFolder("safe_description"))

        assertFalse(configuration.toString().contains("secret-password"))
        assertFalse(configuration.toString().contains(configuration.compileCommand))
        assertFalse(configuration.toString().contains(configuration.environmentVariables))
        assertTrue(configuration.toString().contains("has_password"))
        val compileOptionsDescription = configuration.toCompileOptions(pathManager).toSafeString()
        assertFalse(compileOptionsDescription.contains(configuration.compileCommand))
        assertFalse(compileOptionsDescription.contains("secret-password"))
    }

    private fun projectInfo(vararg modules: ModuleInfo): JuggProjectInfo {
        return JuggProjectInfo(linkedMapOf(*modules.map { it.name to it }.toTypedArray()), agpR8Classpath = null)
    }

    private fun applicationModule(projectDir: File, name: String, variant: String): ModuleInfo {
        val moduleDir = File(projectDir, name)
        return ModuleInfo.virtualModule.copy(
            name = name,
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = moduleDir,
            buildVariant = variant,
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, variant, buildDirRelativePath = ""),
        )
    }

    private fun configuration(id: String, moduleName: String, variant: String): CliRunConfiguration {
        return CliRunConfiguration(
            id = id,
            name = "$moduleName $variant",
            generatedBy = "idea",
            generatedAt = 10L,
            moduleName = moduleName,
            variant = variant,
            buildTarget = BuildTarget.APP,
            compileCommand = "./gradlew :$moduleName:assemble${variant.replaceFirstChar { it.uppercase() }}",
            outputApkName = "$moduleName/build/outputs/apk/$variant/*.apk",
            isRemoteCompile = true,
            remoteSshPassword = "secret-password",
            environmentVariables = "TOKEN=secret-token",
        )
    }
}
