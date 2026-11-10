package com.sickworm.intellij.jugg.mock

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Exercises startup-only home selection in a separate JVM instead of mutating singleton fields. */
class IsolatedHomeRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (System.getProperty("jugg.test.isolated.home") == "true") {
                base.evaluate()
                return
            }
            val home = Files.createTempDirectory("jugg-test-home").toFile()
            try {
                val classpath = System.getProperty("java.class.path")
                val process = ProcessBuilder(
                    File(System.getProperty("java.home"), "bin/java").path,
                    "-Duser.home=$home", "-Djugg.test.isolated.home=true",
                    "-cp", classpath, IsolatedHomeRule::class.java.name,
                    description.className, description.methodName,
                ).redirectErrorStream(true).redirectOutput(File(home, "result.log")).start()
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    error("Isolated test timed out: $description")
                }
                check(process.exitValue() == 0) { File(home, "result.log").readText() }
            } finally {
                home.deleteRecursively()
            }
        }
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            val result = JUnitCore().run(Request.method(Class.forName(args[0]), args[1]))
            result.failures.forEach { System.err.println(it.trace) }
            check(result.wasSuccessful()) { "Isolated test failed" }
        }
    }
}
