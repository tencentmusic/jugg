package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JavaConstParserTest : ConstRefTempDirCleanupSupport() {
    @Test
    fun `should parse java constant definitions including nested class`() {
        val rootDir = createTempDirectory("java_const_defs")
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;

                public class Constants {
                    public static final int MAX = 10;
                    public static final String NAME = "jugg";
                    static class Inner {
                        static final boolean FLAG = true;
                    }
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val definitions = parser.parseDefinitions(constantsFile)

        assertTrue(definitions.any { it.fqClassName == "com.example.Constants" && it.constName == "MAX" })
        assertTrue(definitions.any { it.fqClassName == "com.example.Constants" && it.constName == "NAME" })
        assertTrue(definitions.any { it.fqClassName == "com.example.Constants.Inner" && it.constName == "FLAG" })
    }

    @Test
    fun `should skip private java constant definitions`() {
        val rootDir = createTempDirectory("java_private_const_defs")
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;

                public class Constants {
                    private static final int PRIVATE_MAX = 1;
                    public static final int PUBLIC_MAX = 2;
                    static class Inner {
                        private static final String PRIVATE_NAME = "private";
                        static final String PACKAGE_NAME = "package";
                    }
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val definitions = parser.parseDefinitions(constantsFile)
        val names = definitions.map { it.constName }.toSet()

        assertFalse(names.contains("PRIVATE_MAX"))
        assertFalse(names.contains("PRIVATE_NAME"))
        assertTrue(names.contains("PUBLIC_MAX"))
        assertTrue(names.contains("PACKAGE_NAME"))
    }

    @Test
    fun `should parse java constant references for field access and static imports`() {
        val rootDir = createTempDirectory("java_const_refs")
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;

                public class Constants {
                    public static final int MAX = 10;
                    public static final int MIN = 1;
                    public static class Inner {
                        public static final int FLAG = 2;
                    }
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                import static com.example.Constants.MAX;
                import static com.example.Constants.*;

                public class User {
                    int value1 = MAX;
                    int value2 = MIN;
                    int value3 = Constants.MAX;
                    int value4 = Constants.Inner.FLAG;
                    String onlyText = "Constants.MAX";
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val definitions = parser.parseDefinitions(constantsFile)
        val definitionIndex = ConstDefinitionIndex(definitions)
        val references = parser.parseReferences(userFile, definitionIndex)

        val keys = references.map { "${it.defFqClassName}.${it.constName}" }.toSet()
        assertEquals(setOf("com.example.Constants.MAX", "com.example.Constants.MIN", "com.example.Constants.Inner.FLAG"), keys)
    }

    @Test
    fun `should parse java const reference candidates without definitions`() {
        val rootDir = createTempDirectory("java_const_ref_candidates")
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                import com.example.Constants;
                import static com.example.Constants.MAX;
                import static com.example.Constants.*;

                public class User {
                    int value1 = MAX;
                    int value2 = MIN;
                    int value3 = Constants.MAX;
                    int value4 = Constants.Inner.FLAG;
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val candidates = parser.parseReferenceCandidates(userFile)

        assertTrue(candidates.any {
            it.constName == "MAX" &&
                it.ownerName == "com.example.Constants" &&
                it.ownerKind == ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT
        })
        assertTrue(candidates.any {
            it.constName == "MIN" &&
                it.ownerName == "com.example.Constants" &&
                it.ownerKind == ConstReferenceOwnerKind.CLASS_STAR_IMPORT
        })
        assertTrue(candidates.any {
            it.constName == "MAX" &&
                it.ownerName == "com.example.Constants" &&
                it.ownerKind == ConstReferenceOwnerKind.OWNER_EXPRESSION
        })
        assertTrue(candidates.any {
            it.constName == "FLAG" &&
                it.ownerName == "com.example.Constants.Inner" &&
                it.ownerKind == ConstReferenceOwnerKind.OWNER_EXPRESSION
        })
    }

    @Test
    fun `should parse java annotation constants`() {
        val rootDir = createTempDirectory("java_annotation_const_defs")
        val constantsFile = File(rootDir, "Flags.java").apply {
            writeText(
                """
                package com.example;

                public @interface Flags {
                    public static final int MAX = 7;
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;

                public class User {
                    int value = Flags.MAX;
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val definitions = parser.parseDefinitions(constantsFile)
        assertTrue(definitions.any { it.fqClassName == "com.example.Flags" && it.constName == "MAX" })

        val definitionIndex = ConstDefinitionIndex(definitions)
        val references = parser.parseReferences(userFile, definitionIndex)
        val keys = references.map { "${it.defFqClassName}.${it.constName}" }.toSet()
        assertEquals(setOf("com.example.Flags.MAX"), keys)
    }

    @Test
    fun `should ignore java constants in comments and string literals`() {
        val rootDir = createTempDirectory("java_const_comment_string")
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;

                public class Constants {
                    public static final int MAX = 10;
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;

                public class User {
                    // Constants.MAX should be ignored
                    /* Constants.MAX should also be ignored */
                    String onlyText = "Constants.MAX";
                }
                """.trimIndent()
            )
        }

        val parser = JavaConstParser(logger)
        val definitions = parser.parseDefinitions(constantsFile)
        val definitionIndex = ConstDefinitionIndex(definitions)
        val references = parser.parseReferences(userFile, definitionIndex)
        assertTrue(references.isEmpty())
    }

    @Test
    fun `collectHintsAndParseReferences should log per-step timing breakdown`() {
        val rootDir = createTempDirectory("java_const_timing")
        val constantsFile = File(rootDir, "Constants.java").apply {
            writeText(
                """
                package com.example;
                public class Constants {
                    public static final int MAX = 10;
                    public static final String NAME = "jugg";
                }
                """.trimIndent()
            )
        }
        val userFile = File(rootDir, "User.java").apply {
            writeText(
                """
                package com.example;
                public class User {
                    int value = Constants.MAX;
                    String name = Constants.NAME;
                }
                """.trimIndent()
            )
        }

        val logOutput = mutableListOf<String>()
        val capturingLogger = object : StdLogger("JavaConstParser") {
            override fun debug(message: String?) {
                message?.let { logOutput += it }
                super.debug(message)
            }
        }
        val parser = JavaConstParser(capturingLogger)
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
    }
}
