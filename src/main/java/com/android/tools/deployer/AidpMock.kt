package com.android.tools.deployer

import com.android.tools.deployer.model.DexClass
import com.sickworm.intellij.aidp.Dexer
import java.io.File

object AidpMock {

    fun getDeployDexClass(): DexComparator.ChangedClasses {
        val dexer = Dexer()
        val bytes = File("F:\\StudioProjects\\MyApplicationIntellij\\build\\aidp\\com\\example\\myapplication\\MainActivity2.class").readBytes()
        val activity2Dex = DexClass("com.example.myapplication.MainActivity2", 0, bytes, null)
        return DexComparator.ChangedClasses(listOf(), listOf(activity2Dex))
    }
}