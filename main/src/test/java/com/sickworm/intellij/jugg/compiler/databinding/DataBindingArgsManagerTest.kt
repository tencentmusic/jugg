package com.sickworm.intellij.jugg.compiler.databinding

import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract: the module-level dataBinding switch must not bypass per-layout
 * detection. A plain layout (no <layout> tag) in a dataBinding module must not
 * produce a DataBinding processor trigger solely because the module enables it.
 */
class DataBindingArgsManagerTest {

    @Test
    fun plainLayoutMustNotRunDataBindingStageEvenWhenModuleSwitchOn() {
        val module = newModuleInfo(isUseDataBinding = true, gradleDataBindingOutputExists = false)
        val plainLayout = newLayoutFile("<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>")

        assertFalse(DataBindingArgsManager.isUseDataBinding(module, listOf(plainLayout)))
    }

    @Test
    fun dataBindingLayoutStillRunsDataBindingStageWhenModuleSwitchOn() {
        val module = newModuleInfo(isUseDataBinding = true, gradleDataBindingOutputExists = false)
        val dataBindingLayout = newLayoutFile("<layout xmlns:android=\"http://schemas.android.com/apk/res/android\"><data/></layout>")

        assertTrue(DataBindingArgsManager.isUseDataBinding(module, listOf(dataBindingLayout)))
    }

    @Test
    fun mixedLayoutsRunDataBindingStageWhenAnyLayoutDeclaresLayoutTag() {
        val module = newModuleInfo(isUseDataBinding = true, gradleDataBindingOutputExists = false)
        val plainLayout = newLayoutFile("<TextView/>")
        val dataBindingLayout = newLayoutFile("<layout><data/></layout>")

        assertTrue(DataBindingArgsManager.isUseDataBinding(module, listOf(plainLayout, dataBindingLayout)))
    }

    @Test
    fun moduleLevelQueryKeepsModuleSwitchSemantics() {
        val module = newModuleInfo(isUseDataBinding = true, gradleDataBindingOutputExists = false)

        assertTrue(DataBindingArgsManager.isUseDataBinding(module))
    }

    @Test
    fun moduleLevelQueryFallsBackToGradleDataBindingOutputDir() {
        val module = newModuleInfo(isUseDataBinding = null, gradleDataBindingOutputExists = false)
        assertFalse(DataBindingArgsManager.isUseDataBinding(module))

        val moduleWithOutput = newModuleInfo(isUseDataBinding = null, gradleDataBindingOutputExists = true)
        assertTrue(DataBindingArgsManager.isUseDataBinding(moduleWithOutput))
    }

    @Test
    fun perLayoutDetectionAlsoAppliesWithoutModuleSwitch() {
        val module = newModuleInfo(isUseDataBinding = null, gradleDataBindingOutputExists = true)
        val plainLayout = newLayoutFile("<TextView/>")

        assertFalse(DataBindingArgsManager.isUseDataBinding(module, listOf(plainLayout)))
    }

    private fun newModuleInfo(isUseDataBinding: Boolean?, gradleDataBindingOutputExists: Boolean): ModuleInfo {
        val tempRoot = Files.createTempDirectory("databinding-args-manager-test").toFile()
        val moduleRoot = File(tempRoot, "lib")
        if (gradleDataBindingOutputExists) {
            File(moduleRoot, "build/generated/source/kapt/debug/androidx/databinding").mkdirs()
        }
        return ModuleInfo.virtualModule.copy(
            buildPathInfo = ModuleBuildPathInfo(
                projectRootDir = tempRoot,
                moduleRootDir = moduleRoot,
                buildVariant = "debug",
                buildDirRelativePath = "",
            ),
            isUseDataBinding = isUseDataBinding,
        )
    }

    private fun newLayoutFile(content: String): File {
        val file = Files.createTempFile("layout", ".xml").toFile()
        file.writeText(content)
        return file
    }
}
