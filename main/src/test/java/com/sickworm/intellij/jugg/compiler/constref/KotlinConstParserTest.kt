package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KotlinConstParserTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should parse kotlin constant definitions`() {
        val rootDir = createTempDirectory("kotlin_const_defs")
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                const val TOP = 10

                object Holder {
                    const val FLAG = true
                }

                class Config {
                    companion object {
                        const val DEFAULT = "ok"
                    }

                    object Nested {
                        const val LIMIT = 100
                    }
                }
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            assertTrue(definitions.any { it.fqClassName == "com.example.ConfigKt" && it.constName == "TOP" })
            assertTrue(definitions.any { it.fqClassName == "com.example.Holder" && it.constName == "FLAG" })
            assertTrue(definitions.any { it.fqClassName == "com.example.Config" && it.constName == "DEFAULT" })
            assertTrue(definitions.any { it.fqClassName == "com.example.Config.Nested" && it.constName == "LIMIT" })
        } finally {
            parser.dispose()
        }
    }

    @Test
    fun `should parse kotlin constant references with imports`() {
        val rootDir = createTempDirectory("kotlin_const_refs")
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                const val TOP = 10

                object Holder {
                    const val FLAG = true
                }

                class Config {
                    companion object {
                        const val DEFAULT = "ok"
                    }
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example.user

                import com.example.TOP
                import com.example.Config
                import com.example.Config.DEFAULT
                import com.example.Holder.*

                fun useAll(): String {
                    val a = TOP
                    val b = Config.DEFAULT
                    val c = FLAG
                    return "${'$'}a-${'$'}b-${'$'}c"
                }
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            val definitionIndex = ConstDefinitionIndex(definitions)
            val references = parser.parseReferences(userFile, definitionIndex)
            val keys = references.map { "${it.defFqClassName}.${it.constName}" }.toSet()
            assertEquals(setOf("com.example.ConfigKt.TOP", "com.example.Config.DEFAULT", "com.example.Holder.FLAG"), keys)
        } finally {
            parser.dispose()
        }
    }

    @Test
    fun `should parse kotlin top level const references from package asterisk import`() {
        val rootDir = createTempDirectory("kotlin_const_refs_pkg_asterisk")
        val constantsFile = File(rootDir, "TopConsts.kt").apply {
            writeText(
                """
                package com.example.constants

                const val TOP = 10
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example.user

                import com.example.constants.*

                val value = TOP
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            val definitionIndex = ConstDefinitionIndex(definitions)
            val references = parser.parseReferences(userFile, definitionIndex)
            val keys = references.map { "${it.defFqClassName}.${it.constName}" }.toSet()
            assertEquals(setOf("com.example.constants.TopConstsKt.TOP"), keys)
        } finally {
            parser.dispose()
        }
    }
}
