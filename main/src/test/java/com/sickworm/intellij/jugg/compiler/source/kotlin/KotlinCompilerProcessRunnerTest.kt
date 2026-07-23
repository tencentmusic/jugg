package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.compiler.isWindows
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinCompilerProcessRunnerTest {

    @Test
    fun `uses Gradle java home and opens javac modules for isolated compiler`() {
        val javaHome = File("/tmp/gradle-jdk")

        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = javaHome,
            javaFeature = 17,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgs = listOf("-version"),
        )

        assertEquals(File(javaHome, if (isWindows) "bin/java.exe" else "bin/java").path, command.first())
        assertTrue(command.contains("--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"))
        assertTrue(command.contains("--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"))
        assertTrue(command.contains("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"))
        assertEquals("-version", command.last())
    }

    @Test
    fun `does not add module flags for java 8`() {
        val command = KotlinCompilerProcessRunner.buildCommand(
            javaHome = File("/tmp/jdk8"),
            javaFeature = 8,
            compilerClasspath = listOf(File("/tmp/kotlin-compiler.jar")),
            compilerArgs = listOf("-version"),
        )

        assertFalse(command.any { it.startsWith("--add-exports") })
        assertFalse(command.any { it.startsWith("--add-opens") })
    }

    @Test
    fun `prefers java home from compile environment`() {
        val javaHome = KotlinCompilerProcessRunner.resolveJavaHome(
            compileEnv = listOf("PATH=/usr/bin", "JAVA_HOME=/tmp/gradle-jdk"),
            systemJavaHome = "/tmp/ide-jbr",
        )

        assertEquals(File("/tmp/gradle-jdk"), javaHome)
    }
}
