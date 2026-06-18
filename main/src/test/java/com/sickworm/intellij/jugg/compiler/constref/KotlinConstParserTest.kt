package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `should skip private kotlin constant definitions`() {
        val rootDir = createTempDirectory("kotlin_private_const_defs")
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                private const val PRIVATE_TOP = "top"
                const val PUBLIC_TOP = "top"

                object Holder {
                    private const val PRIVATE_FLAG = true
                    const val PUBLIC_FLAG = true
                }

                class Config {
                    companion object {
                        private const val PRIVATE_DEFAULT = "private"
                        const val PUBLIC_DEFAULT = "public"
                    }
                }
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            val names = definitions.map { it.constName }.toSet()
            assertFalse(names.contains("PRIVATE_TOP"))
            assertFalse(names.contains("PRIVATE_FLAG"))
            assertFalse(names.contains("PRIVATE_DEFAULT"))
            assertTrue(names.contains("PUBLIC_TOP"))
            assertTrue(names.contains("PUBLIC_FLAG"))
            assertTrue(names.contains("PUBLIC_DEFAULT"))
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
    fun `should parse kotlin const reference candidates without definitions`() {
        val rootDir = createTempDirectory("kotlin_const_ref_candidates")
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example.user
                import com.example.Config.Companion.MAX
                import com.example.BasePager
                val a = MAX
                val b = BasePager.THEME_ID_DEFAULT_WHITE_ANDROID
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val candidates = parser.parseReferenceCandidates(userFile)
            assertTrue(candidates.any {
                it.constName == "MAX" &&
                    it.ownerName == "com.example.Config.Companion" &&
                    it.ownerKind == ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT
            })
            assertTrue(candidates.any {
                it.constName == "THEME_ID_DEFAULT_WHITE_ANDROID" &&
                    it.ownerName == "com.example.BasePager" &&
                    it.ownerKind == ConstReferenceOwnerKind.OWNER_EXPRESSION
            })
        } finally {
            parser.dispose()
        }
    }

    @Test
    fun `should parse kotlin constant references with alias imports`() {
        val rootDir = createTempDirectory("kotlin_const_refs_alias")
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                const val TOP = 10

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

                import com.example.TOP as ALIAS_TOP
                import com.example.Config.DEFAULT as ALIAS_DEFAULT

                fun useAlias(): String {
                    val a = ALIAS_TOP
                    val b = ALIAS_DEFAULT
                    return "${'$'}a-${'$'}b"
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
            assertEquals(setOf("com.example.ConfigKt.TOP", "com.example.Config.DEFAULT"), keys)
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

    @Test
    fun `should parse kotlin same package references without imports and ignore comments string`() {
        val rootDir = createTempDirectory("kotlin_const_refs_same_package")
        val constantsFile = File(rootDir, "Constants.kt").apply {
            writeText(
                """
                package com.example

                const val TOP = 10

                class Config {
                    companion object {
                        const val DEFAULT = "ok"
                    }
                }
                """.trimIndent()
            )
        }
        val serviceFile = File(rootDir, "Service.kt").apply {
            writeText(
                """
                package com.example

                // TOP should be ignored in comment
                private const val DESCRIPTION = "Config.DEFAULT and TOP in string"

                fun read(): String {
                    val a = TOP
                    val b = Config.DEFAULT
                    return "${'$'}a-${'$'}b-${'$'}DESCRIPTION"
                }
                """.trimIndent()
            )
        }

        val parser = KotlinConstParser(logger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            val definitionIndex = ConstDefinitionIndex(definitions)
            val references = parser.parseReferences(serviceFile, definitionIndex)
            val keys = references.map { "${it.defFqClassName}.${it.constName}" }.toSet()
            assertEquals(setOf("com.example.ConstantsKt.TOP", "com.example.Config.DEFAULT"), keys)
        } finally {
            parser.dispose()
        }
    }

    @Test
    fun `collectHintsAndParseReferences should log per-step timing breakdown`() {
        val rootDir = createTempDirectory("kotlin_const_timing")
        val constantsFile = File(rootDir, "Config.kt").apply {
            writeText(
                """
                package com.example

                const val TOP = 10

                object Holder {
                    const val FLAG = true
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.kt").apply {
            writeText(
                """
                package com.example.user

                import com.example.TOP
                import com.example.Holder

                fun use(): Int {
                    val a = TOP
                    val b = Holder.FLAG
                    return if (b) a else 0
                }
                """.trimIndent()
            )
        }

        val logOutput = mutableListOf<String>()
        val capturingLogger = object : StdLogger("KotlinConstParser") {
            override fun debug(message: String?) {
                message?.let { logOutput += it }
                super.debug(message)
            }
        }
        val parser = KotlinConstParser(capturingLogger)
        try {
            val definitions = parser.parseDefinitions(constantsFile)
            val definitionIndex = ConstDefinitionIndex(definitions)

            val references = parser.collectHintsAndParseReferences(userFile) { hints ->
                if (hints.isEmpty()) null else definitionIndex
            }
            assertTrue("should find references", references.isNotEmpty())

            val hasTimingLog = logOutput.any { msg ->
                msg.contains("collectHintsAndParseReferences") &&
                    msg.contains("parseMs=") &&
                    msg.contains("pass1Ms=") &&
                    msg.contains("pass2Ms=")
            }
            assertTrue(
                "Expected per-step timing log but got: $logOutput",
                hasTimingLog,
            )
        } finally {
            parser.dispose()
        }
    }
}
