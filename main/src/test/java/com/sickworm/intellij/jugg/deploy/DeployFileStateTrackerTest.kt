package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeployFileStateTrackerTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deselectingCompiledInputDiscardsCombinedStagingAndRequeuesRetainedInput() {
        val tracker = DeployFileStateTracker()
        val first = createSourceFile("First.kt", "class First")
        val second = createSourceFile("Second.kt", "class Second")
        tracker.addChangedFiles(listOf(changedFile(first), changedFile(second)))
        tracker.updateUncompiledFiles(listOf(compileFile(first), compileFile(second)), emptyList())
        tracker.addStagingFiles(listOf(compileOutput(temporaryFolder.newFolder("staged"), "classes.dex", null, "combined")))

        tracker.applyFileSelection(tracker.getFileChangeCandidates(), setOf(second.absolutePath))

        assertTrue(tracker.getStagingFiles().isEmpty())
        assertTrue(tracker.getCompiledFiles().isEmpty())
        assertEquals(listOf(second), tracker.getUncompiledFiles().map { it.file })
        assertFalse(tracker.isNoFileChanges())
    }

    @Test
    fun confirmationRetriesFailedManualInputWithoutEditingSource() {
        val tracker = DeployFileStateTracker()
        val source = createSourceFile("Retry.kt", "class Retry")
        tracker.addChangedFiles(listOf(changedFile(source)))
        val candidates = tracker.getFileChangeCandidates()
        tracker.applyFileSelection(candidates, setOf(source.absolutePath))
        tracker.updateUncompiledFiles(emptyList(), listOf(compileFile(source)))
        tracker.applyFileSelection(candidates, setOf(source.absolutePath))
        assertFalse(tracker.isNoFileChanges())
        assertEquals(0, tracker.getUncompiledFiles().single().compiledTimes)
    }

    @Test
    fun manualSelectionReopensSameSnapshotAndKeepsHistoryAfterCommit() {
        val tracker = DeployFileStateTracker()
        val source = createSourceFile("Manual.kt", "class Manual")
        tracker.addChangedFiles(listOf(changedFile(source)))
        tracker.updateUncompiledFiles(listOf(compileFile(source)), emptyList())
        tracker.commitAndClear { }

        val candidates = tracker.getFileChangeCandidates()
        assertEquals(listOf(source), candidates.map { it.file })
        tracker.applyFileSelection(candidates, setOf(source.absolutePath))

        assertFalse(tracker.isNoFileChanges())
        assertTrue(tracker.getUncompiledFiles().single().isManuallySelected)
        source.appendText(" ")
        tracker.addChangedFiles(listOf(changedFile(source)))
        assertTrue(tracker.getUncompiledFiles().single().isManuallySelected)
    }

    @Test
    fun candidatesFollowLatestAdmissionAndExcludeDeletedFiles() {
        val tracker = DeployFileStateTracker()
        val first = createSourceFile("First.kt", "class First")
        val second = createSourceFile("Second.kt", "class Second")
        tracker.addChangedFiles(listOf(changedFile(first), changedFile(second)))
        tracker.addChangedFiles(listOf(changedFile(first)))
        assertEquals(listOf(first, second), tracker.getFileChangeCandidates().map { it.file })
        first.delete()
        assertEquals(listOf(second), tracker.getFileChangeCandidates().map { it.file })
    }

    @Test
    fun deselectionKeepsCandidatesAndChangesArrivingAfterDialogOpened() {
        val tracker = DeployFileStateTracker()
        val first = createSourceFile("First.kt", "class First")
        val second = createSourceFile("Second.kt", "class Second")
        tracker.addChangedFiles(listOf(changedFile(first)))
        val candidates = tracker.getFileChangeCandidates()
        tracker.addChangedFiles(listOf(changedFile(second)))
        tracker.applyFileSelection(candidates, emptySet())
        assertEquals(listOf(second), tracker.getUncompiledFiles().map { it.file })
        assertEquals(2, tracker.getFileChangeCandidates().size)
        tracker.applyFileSelection(candidates, setOf(first.absolutePath))
        assertEquals(setOf(first, second), tracker.getUncompiledFiles().map { it.file }.toSet())
    }

    @Test
    fun addChangedFiles_ignoresSameSnapshotAfterSuccessfulCompile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        val changedFile = changedFile(sourceFile)

        tracker.addChangedFiles(listOf(changedFile))
        tracker.updateUncompiledFiles(listOf(compileFile(sourceFile)), emptyList())
        val lateIdeEventFile = changedFile(sourceFile)

        val newFiles = tracker.addChangedFiles(listOf(lateIdeEventFile))

        assertTrue(newFiles.isEmpty())
        assertTrue(tracker.getUncompiledFiles().isEmpty())
        assertEquals(listOf(changedFile), tracker.getCompiledFiles())
        assertTrue(tracker.isNoFileChanges())
    }

    @Test
    fun addChangedFiles_reopensFileWhenSnapshotChangesAfterSuccessfulCompile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        tracker.addChangedFiles(listOf(changedFile(sourceFile)))
        tracker.updateUncompiledFiles(listOf(compileFile(sourceFile)), emptyList())

        Thread.sleep(2)
        sourceFile.writeText("class MainActivity { fun changed() = Unit }")
        val nextChangedFile = changedFile(sourceFile)

        val newFiles = tracker.addChangedFiles(listOf(nextChangedFile))

        assertEquals(listOf(nextChangedFile), newFiles)
        assertEquals(listOf(nextChangedFile), tracker.getUncompiledFiles())
        assertTrue(tracker.getCompiledFiles().isEmpty())
    }

    @Test
    fun addChangedFiles_ignoresSameSnapshotAfterFailedCompile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        val failedChangedFile = changedFile(sourceFile)
        tracker.addChangedFiles(listOf(failedChangedFile))
        tracker.updateUncompiledFiles(emptyList(), listOf(compileFile(sourceFile)))

        tracker.addChangedFiles(listOf(changedFile(sourceFile)))

        val uncompiledFile = tracker.getUncompiledFiles().single()
        assertSame(failedChangedFile, uncompiledFile)
        assertEquals(1, uncompiledFile.compiledTimes)
        assertTrue(tracker.isNoFileChanges())
    }

    @Test
    fun addChangedFiles_reopensFailedFileWhenSnapshotChanges() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        tracker.addChangedFiles(listOf(changedFile(sourceFile)))
        tracker.updateUncompiledFiles(emptyList(), listOf(compileFile(sourceFile)))

        Thread.sleep(2)
        sourceFile.writeText("class MainActivity { fun changed() = Unit }")
        val changedAgainFile = changedFile(sourceFile)
        tracker.addChangedFiles(listOf(changedAgainFile))

        val uncompiledFile = tracker.getUncompiledFiles().single()
        assertSame(changedAgainFile, uncompiledFile)
        assertEquals(0, uncompiledFile.compiledTimes)
        assertFalse(tracker.isNoFileChanges())
    }

    @Test
    fun resetKeepingRecentUncompiled_preservesSnapshotForRetainedFailedFile() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        val failedChangedFile = changedFile(sourceFile)
        tracker.addChangedFiles(listOf(failedChangedFile))
        tracker.updateUncompiledFiles(emptyList(), listOf(compileFile(sourceFile)))

        tracker.resetKeepingRecentUncompiled(sourceFile.lastModified() - 1)
        tracker.addChangedFiles(listOf(changedFile(sourceFile)))

        val uncompiledFile = tracker.getUncompiledFiles().single()
        assertSame(failedChangedFile, uncompiledFile)
        assertEquals(1, uncompiledFile.compiledTimes)
        assertTrue(tracker.isNoFileChanges())
    }

    @Test
    fun resetKeepingRecentUncompiled_keepsAssetObservedAfterFullBuildStartedWithOldTimestamp() {
        val tracker = DeployFileStateTracker()
        val fullBuildStart = System.currentTimeMillis() - 1
        val assetFile = temporaryFolder.newFile("font.ttf").apply {
            writeText("font")
            assertTrue(setLastModified(fullBuildStart - 10_000))
        }
        val changedFile = ChangedFile(
            type = CompileFile.Type.Asset,
            file = assetFile.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
        tracker.addChangedFiles(listOf(changedFile))

        tracker.resetKeepingRecentUncompiled(fullBuildStart)

        assertEquals(listOf(changedFile), tracker.getUncompiledFiles())
    }

    @Test
    fun resetKeepingRecentUncompiled_dropsFileObservedBeforeFullBuildStarted() {
        val tracker = DeployFileStateTracker()
        val sourceFile = createSourceFile("MainActivity.kt", "class MainActivity")
        tracker.addChangedFiles(listOf(changedFile(sourceFile)))

        tracker.resetKeepingRecentUncompiled(System.currentTimeMillis() + 1)

        assertTrue(tracker.getUncompiledFiles().isEmpty())
    }

    @Test
    fun getNotStagingDeployedFiles_keepsSameRelativePathForDifferentTargetApks() {
        val tracker = DeployFileStateTracker()
        val baseOutput = compileOutput(
            root = temporaryFolder.newFolder("base_deployed"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "base",
        )
        val testOutput = compileOutput(
            root = temporaryFolder.newFolder("test_staging"),
            relativePath = "resources.arsc",
            apkPath = "/androidTest.apk",
            content = "test",
        )

        tracker.replaceDeployedFiles(listOf(baseOutput))
        tracker.addStagingFiles(listOf(testOutput))

        assertEquals(listOf(baseOutput), tracker.getNotStagingDeployedFiles())
    }

    @Test
    fun addStagingFiles_replacesSameDeployKeyFromDifferentPhysicalPath() {
        val tracker = DeployFileStateTracker()
        val oldOutput = compileOutput(
            root = temporaryFolder.newFolder("old_staging"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "old",
        )
        val newOutput = compileOutput(
            root = temporaryFolder.newFolder("new_staging"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "new",
        )

        tracker.addStagingFiles(listOf(oldOutput, newOutput))

        assertEquals(listOf(newOutput), tracker.getStagingFiles())
    }

    @Test
    fun resetAfterReinstall_deduplicatesDeployedFilesByDeployKey() {
        val tracker = DeployFileStateTracker()
        val oldOutput = compileOutput(
            root = temporaryFolder.newFolder("old_deployed"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "old",
        )
        val newOutput = compileOutput(
            root = temporaryFolder.newFolder("new_deployed"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "new",
        )

        tracker.replaceDeployedFiles(listOf(oldOutput, newOutput))
        tracker.resetAfterReinstall(replayNativeLibraries = true)

        assertEquals(listOf(newOutput), tracker.getStagingFiles())
    }

    @Test
    fun resetAfterReinstall_replaysNativeHistoryOnlyWhenSupported() {
        val nativeOutput = compileOutput(
            root = temporaryFolder.newFolder("native_deployed"),
            relativePath = "lib/arm64-v8a/libsample.so",
            apkPath = "/base.apk",
            content = "native",
            type = CompileOutput.Type.NativeLib,
        )
        val resourceOutput = compileOutput(
            root = temporaryFolder.newFolder("resource_deployed"),
            relativePath = "res/drawable/icon.xml",
            apkPath = "/base.apk",
            content = "resource",
        )
        val currentOutput = compileOutput(
            root = temporaryFolder.newFolder("current_staging"),
            relativePath = "lib/arm64-v8a/libcurrent.so",
            apkPath = "/base.apk",
            content = "current",
            type = CompileOutput.Type.NativeLib,
        )
        val tracker = DeployFileStateTracker()
        tracker.replaceDeployedFiles(listOf(nativeOutput, resourceOutput))
        tracker.addStagingFiles(listOf(currentOutput))

        tracker.resetAfterReinstall(replayNativeLibraries = false)

        assertEquals(setOf(currentOutput, resourceOutput), tracker.getStagingFiles().toSet())

        val supportedTracker = DeployFileStateTracker()
        supportedTracker.replaceDeployedFiles(listOf(nativeOutput, resourceOutput))
        supportedTracker.resetAfterReinstall(replayNativeLibraries = true)
        assertEquals(setOf(nativeOutput, resourceOutput), supportedTracker.getStagingFiles().toSet())
    }

    @Test
    fun commitAndClear_replacesRecoveredDexShadowedByScopedStagingDex() {
        val tracker = DeployFileStateTracker()
        val oldOutput = compileOutput(
            root = temporaryFolder.newFolder("old_deployed"),
            relativePath = "com/example/SharedClass.dex",
            apkPath = null,
            content = "old",
            type = CompileOutput.Type.Dex,
        )
        val newOutput = compileOutput(
            root = temporaryFolder.newFolder("new_staging"),
            relativePath = "com/example/SharedClass.dex",
            apkPath = "/base.apk",
            content = "new",
            type = CompileOutput.Type.Dex,
        )

        tracker.replaceDeployedFiles(listOf(oldOutput))
        tracker.addStagingFiles(listOf(newOutput))
        tracker.commitAndClear { }

        assertEquals(listOf(newOutput), tracker.getDeployedFiles())
    }

    @Test
    fun commitAndClear_replacesSameDeployKeyFromDifferentPhysicalPath() {
        val tracker = DeployFileStateTracker()
        val oldOutput = compileOutput(
            root = temporaryFolder.newFolder("old_deployed"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "old",
        )
        val newOutput = compileOutput(
            root = temporaryFolder.newFolder("new_staging"),
            relativePath = "resources.arsc",
            apkPath = "/base.apk",
            content = "new",
        )

        tracker.replaceDeployedFiles(listOf(oldOutput))
        tracker.addStagingFiles(listOf(newOutput))
        tracker.commitAndClear { }

        assertEquals(listOf(newOutput), tracker.getDeployedFiles())
    }

    @Test
    fun commitAndClear_keepsSameRelativePathForDifferentTargetApks() {
        val tracker = DeployFileStateTracker()
        val baseOutput = compileOutput(
            root = temporaryFolder.newFolder("base_deployed"),
            relativePath = "com/example/SharedClass.dex",
            apkPath = "/base.apk",
            content = "base",
            type = CompileOutput.Type.Dex,
        )
        val testOutput = compileOutput(
            root = temporaryFolder.newFolder("test_staging"),
            relativePath = "com/example/SharedClass.dex",
            apkPath = "/androidTest.apk",
            content = "test",
            type = CompileOutput.Type.Dex,
        )

        tracker.replaceDeployedFiles(listOf(baseOutput))
        tracker.addStagingFiles(listOf(testOutput))
        tracker.commitAndClear { }

        assertEquals(listOf(baseOutput, testOutput), tracker.getDeployedFiles())
    }

    private fun createSourceFile(name: String, content: String): File {
        return temporaryFolder.newFile(name).apply {
            writeText(content)
        }
    }

    private fun changedFile(file: File): ChangedFile {
        return ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = file.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
    }

    private fun compileFile(file: File): CompileFile {
        return CompileFile(
            type = CompileFile.Type.Kotlin,
            file = file.absoluteFile,
            baseDir = temporaryFolder.root.absoluteFile,
            module = ModuleInfo.virtualModule,
        )
    }

    private fun compileOutput(
        root: File,
        relativePath: String,
        apkPath: String?,
        content: String,
        type: CompileOutput.Type = CompileOutput.Type.Res,
    ): CompileOutput {
        val file = File(root, relativePath).apply {
            parentFile.mkdirs()
            writeText(content)
        }
        return CompileOutput(
            type = type,
            file = file,
            baseDir = root,
            apkPath = apkPath,
        )
    }
}
