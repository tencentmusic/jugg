package com.sickworm.intellij.jugg.compiler.constref

import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JavaConstParserTest {
    @Test
    fun `should parse java constant definitions including nested class`() {
        val rootDir = Files.createTempDirectory("java_const_defs").toFile()
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
    fun `should parse java constant references for field access and static imports`() {
        val rootDir = Files.createTempDirectory("java_const_refs").toFile()
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
}
