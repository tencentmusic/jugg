@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "MemberVisibilityCanBePrivate", "unused")

package com.sickworm.intellij.jugg.mock

import groovy.lang.Closure
import groovy.util.Eval
import java.io.File

class GradleSettingsDummyReader(private val projectDir: File) {

    @Suppress("UNCHECKED_CAST")
    fun readProjectDirs(): List<File> {
        val settingsContent = File(projectDir, "settings.gradle").readText()
        val result = Eval.me("""
                // parsing settings.gradle
                def delegate = new com.sickworm.intellij.jugg.mock.GradleSettingsDummyDelegate("$projectDir")
                delegate.eval {
                    $settingsContent
                    getList()
                }
        """.trimIndent()
        )
        return result as List<File>
    }

}

class GradleSettingsDummyDelegate(private val projectRootDir: String) {

    fun eval(closure: Closure<Object>): Object {
        closure.delegate = this
        return closure.call()
    }

    private val list = mutableListOf<MockGradleProject>()

    fun include(path: String) {
        list.add(MockGradleProject(path))
    }

    fun include(vararg paths: String) {
        paths.forEach {
            include(it)
        }
    }

    fun file(path: String): File {
        return File(projectRootDir, path)
    }

    fun project(path: String): MockGradleProject? {
        return list.find { it.moduleName == path }
    }

    fun getList(): List<File> {
        return list.map { File(projectRootDir, it.projectDir) }
    }
}

class MockGradleProject(
    var moduleName: String,
    var projectDir: String
) {

    constructor(moduleName: String): this(
        moduleName,
        moduleName.let {
            var filePath = it
            if (it.startsWith(':')) {
                filePath = it.substring(1, it.length)
            }
            filePath = filePath.replace(':', '/')
            filePath
        }
    )
}