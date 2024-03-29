package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import java.io.File

object TestProjectDependsLoader {

    private val userHome = System.getProperty("user.home")

    val transformedDepends = """
        jetified-viewbinding-7.2.2
        core-runtime-2.0.0
        lifecycle-viewmodel-2.0.0
        legacy-support-core-utils-1.0.0
        espresso-idling-resource-3.1.1
        monitor-1.1.1
        lifecycle-livedata-2.0.0
        coordinatorlayout-1.0.0
        espresso-core-3.1.1
        lifecycle-livedata-core-2.0.0
        constraintlayout-1.1.3
        viewpager-1.0.0
        customview-1.0.0
        swiperefreshlayout-1.0.0
        runner-1.1.1
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
        versionedparcelable-1.1.0
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

    private var cache: List<String>? = null

    fun parse(): List<String> {
        AssembleAndroidProjectOnce.ensure()

        cache?.let {
            return it
        }

        val result = mutableListOf<String>()

        val dependsRootDir = File(userHome, ".gradle/caches/modules-2/files-2.1")
        result += depends.split("\n").flatMap {
            val dependDir = File(dependsRootDir, it)
            if (!dependDir.exists()) {
                throw IllegalArgumentException("depends dir not exists: $dependDir")
            }
            dependDir.listFilesRecursively().filter { file ->
                file.extension == "jar" && !file.name.endsWith("-javadoc.jar") && !file.name.endsWith("-sources.jar")
            }
        }.map {
            it.absolutePath
        }

        val transformedDependsRootDir = File(userHome, ".gradle/caches/transforms-3")
        val allTransformedDepends = transformedDependsRootDir
            .walkTopDown()
            .filter {
                it.isDirectory && it.name == "jars"
            }
            .associateBy { it.parentFile.name }
        result += transformedDepends.split("\n").flatMap {
            val dependDir = allTransformedDepends[it] ?: throw IllegalArgumentException("depends dir not exists: $it")
            dependDir.listFilesRecursively()
        }.map {
            it.absolutePath
        }

        return result
    }


}