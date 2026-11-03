package com.sickworm.intellij.jugg.manager

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.projectInfo
import com.sickworm.intellij.jugg.mock.GradleBuildHelper
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class Issue75EmptyJavaReproTest {
    @Test
    fun compareEmptyGeneratedJavaWithBinaryParent() {
        val tasks = listOf(":app:assembleDebug", "-PjuggIssue75Fixture=true")
        AssembleAndroidProjectOnce.ensure(isNeedClean = false, compileCommand = tasks, forceAssemble = true)
        val root = projectInfo.projectRoot
        val empty = File(root, "build/app/generated/aidl_source_output_dir/debug/out/com/example/library1/issue75/ParentModel.java")
        assertTrue(empty.exists() && empty.length() == 0L, "AGP AIDL compilation must generate the empty Java file")
        val probe = File(root, "app/src/main/java/com/example/myapplication/issue75/Issue75Probe.kt")
        val original = probe.readText()
        try {
            probe.writeText(original.replace("baseline", "gradle-control"))
            GradleBuildHelper.assembleDebug(tasks)
            assertTrue(empty.exists() && empty.length() == 0L, "Gradle must preserve the empty Java control")
            val jugg = MockJugg()
            jugg.dryFullCompile()
            probe.writeText(original.replace("baseline", "incremental"))
            jugg.notifyFileChanges(listOf(probe))
            jugg.compileChangedFiles()
            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), "Empty generated Java must not hide the binary parent")
            assertTrue(empty.exists() && empty.length() == 0L, "Jugg must preserve the original empty Java file")
            empty.delete()
            jugg.notifyFileChanges(listOf(probe))
            jugg.compileChangedFiles()
            assertTrue(jugg.deployFileManager.getUncompiledFiles().isEmpty(), "Removing only empty Java must restore compilation")
        } finally {
            probe.writeText(original)
            empty.delete()
        }
    }
}
