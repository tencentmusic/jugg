package com.sickworm.intellij.jugg.git

import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.Test
import kotlin.test.assertEquals

class FileMatcherTest {

    @Test
    fun test() {
        val rules = """
            *.gradle
            *.gradle.kts
            dependency.yaml
            **/mologtag/*
            
            *.config
            !ci.config
        """.trimIndent().split("\n").toList()

        val matcher = FileMatcher()
        val rootDir = projectInfo.projectRoot
        matcher.init(rootDir, rules)

        val testCase = listOf(
            // normal source
            "app/src/main/java/com/example/MainActivity.java" to false,

            // ignore file
            "build.gradle" to true,
            "settings.gradle" to true,
            "build.gradle.kts" to true,
            "dependency.yaml" to true,
            "dependency.yml" to false,

            // ignore dir
            "mologtag/log_tag.yaml" to true,
            "app/src/mologtag/log_tag.yaml" to true,

            // exclude file
            "library1/my.config" to true,
            "library1/ci.config" to false,
        )

        testCase.forEach { (path, result) ->
            val file = rootDir.resolve(path)
            assertEquals(result, matcher.isMatch(file), "file: $path")
        }
    }
}