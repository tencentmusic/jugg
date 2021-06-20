package com.android.tools.deployer

import com.android.tools.deployer.model.DexClass
import com.sickworm.intellij.aidp.Dexer
import java.io.File

object AidpMock {

    fun getDeployDexClass(): DexComparator.ChangedClasses {
        val buildPath = File("F:/StudioProjects/MyApplicationIntellij/build/aidp/class")
        val outputPath = File("F:/StudioProjects/MyApplicationIntellij/build/aidp/dex/out.dex")
        Dexer().dex(buildPath, outputPath)
        val bytes = outputPath.readBytes()
        val activity2Dex = DexClass("com.example.myapplication.MainActivity2", 0, bytes, null)
        return DexComparator.ChangedClasses(listOf(), listOf(activity2Dex))
    }
}