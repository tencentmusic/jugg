package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File

object TestProjectDependsLoader {

    private val userHome = System.getProperty("user.home")

    val transformedDepends = """
        viewbinding-7.2.2
        core-runtime-2.0.0
        lifecycle-viewmodel-2.0.0
        legacy-support-core-utils-1.0.0
        monitor-1.1.1
        lifecycle-livedata-2.0.0
        coordinatorlayout-1.0.0
        lifecycle-livedata-core-2.0.0
        constraintlayout-1.1.3
        viewpager-1.0.0
        customview-1.0.0
        swiperefreshlayout-1.0.0
        vectordrawable-animated-1.0.0
        print-1.0.0
        asynclayoutinflater-1.0.0
        vectordrawable-1.0.1
        loader-1.0.0
        appcompat-1.0.2
        fragment-1.0.0
        documentfile-1.0.0
        interpolator-1.0.0
        jetified-core-ktx-1.0.2
        cursoradapter-1.0.0
        legacy-support-core-ui-1.0.0
        core-1.0.2
        drawerlayout-1.0.0
        localbroadcastmanager-1.0.0
        lifecycle-runtime-2.0.0
        slidingpanelayout-1.0.0
    """.trimIndent()

    val depends = """
        androidx.lifecycle/lifecycle-common/2.0.0
        junit/junit/4.12
        org.jetbrains.kotlin/kotlin-stdlib-common/1.7.22
        org.hamcrest/hamcrest-library/1.3
        org.jetbrains.kotlin/kotlin-stdlib/1.7.22
        javax.inject/javax.inject/1
        com.squareup/javawriter/2.1.1
        androidx.constraintlayout/constraintlayout-solver/1.1.3
        net.sf.kxml/kxml2/2.3.0
        org.hamcrest/hamcrest-integration/1.3
        org.jetbrains.kotlin/kotlin-android-extensions-runtime/1.7.22
        com.google.code.findbugs/jsr305/2.0.1
        androidx.arch.core/core-common/2.0.0
        org.jetbrains/annotations/13.0
        androidx.collection/collection/1.0.0
        org.hamcrest/hamcrest-core/1.3
        androidx.annotation/annotation/1.1.0
        org.jetbrains.kotlin/kotlin-stdlib-jdk7/1.7.22
    """.trimIndent()

    private var cache: List<LibraryDependency>? = null

    fun parse(): List<LibraryDependency> {
        AssembleAndroidProjectOnce.ensure()

        cache?.let {
            return it
        }

        val result = mutableListOf<LibraryDependency>()

        val dependsRootDir = File(userHome, ".gradle/caches/modules-2/files-2.1")
        result += depends.split("\n").flatMap {
            val dependDir = File(dependsRootDir, it)
            if (!dependDir.exists()) {
                throw IllegalArgumentException("depends dir not exists: $dependDir")
            }
            dependDir.listFilesRecursively().filter { file ->
                file.extension == "jar" && !file.name.endsWith("-javadoc.jar") && !file.name.endsWith("-sources.jar")
            }.map { file ->
                LibraryDependency("Gradle: " + it.replace("/", ":"), file)
            }
        }

        val transformedDependsRootDir = File(userHome, ".gradle/caches/transforms-3")
        val allTransformedDepends: MutableMap<String, MutableList<File>> = mutableMapOf()
        transformedDependsRootDir
            .walkTopDown()
            .filter {
                (it.isDirectory && it.name == "jars") || (it.name == "AndroidManifest.xml")
            }
            .forEach {
                allTransformedDepends.getOrPut(it.parentFile.name) { mutableListOf(it) }.add(it)
            }
        transformedDepends.split("\n").forEach {
            val depends = allTransformedDepends[it] ?: throw IllegalArgumentException("depends dir not exists: $it")
            val version = it.substringAfterLast('-')
            val artifact = it.substringBeforeLast('-')
            val name = "Gradle: mock_group:$artifact:$version"
            depends.forEach { depend ->
                if (depend.isDirectory) {
                    depend.listFilesRecursively().map { file ->
                        result.add(LibraryDependency(name, file))
                    }
                } else {
                    result.add(LibraryDependency(name, depend))
                }
            }
        }

        cache = result
        return result
    }


}