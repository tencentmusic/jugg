package com.sickworm.intellij.jugg.manager

import org.junit.Test
import org.objectweb.asm.*
import java.io.File
import kotlin.test.assertTrue

class DeployManagerTest2: BasicJuggMock() {

    @Test
    fun testJavaClassAddSingle() {
        changeFileAndNotify("TestNewJavaFile.java" to "TestNewJavaFile.java",
            directory = "app/src/main/java/com/tencent/karaoke/")
        checkCompileResult(
            "TestNewJavaFile.java",
            filePackageName = "com.tencent.karaoke",
            newClassesSize = 1)
    }

    @Test
    fun testRecordModuleModifySingle() {
        changeFileAndNotify("RecordDialogHelper.kt" to "RecordDialogHelper.kt",
            directory = "module_record/src/main/java/com/tencent/wesing/record/module/recording/ui/main/logic/")
        checkCompileResult(
            "RecordDialogHelper.kt",
            filePackageName = "com.tencent.karaoke",
            hotReloadModifiedClassesSize = 1)
    }
}