package com.sickworm.intellij.jugg.compiler.databinding

import android.databinding.tool.store.GenClassInfoLog
import java.io.File

import android.databinding.tool.store.ResourceBundle

@Suppress("unused")
class LogFileMerger(private val origin: File) {

    fun merge(folder: File) {
        val originLog = GenClassInfoLog.fromFile(origin) // not exist is ok
        val newLog: GenClassInfoLog = ResourceBundle.loadClassInfoFromFolders(listOf(folder))
        originLog.addAll(newLog)
        originLog.serialize(origin)
    }
}