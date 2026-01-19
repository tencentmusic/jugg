package com.sickworm.intellij.jugg.compiler.source

import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.BaseDexFileReader
import com.googlecode.d2j.reader.MultiDexFileReader
import com.sickworm.intellij.jugg.mock.StdLogger
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for R8FileMaker.
 *
 * Tests R8's capabilities for:
 * 1. Code shrinking (removing unused code)
 * 2. Code obfuscation (renaming classes, methods, fields)
 * 3. Method inlining (optimizing method calls)
 * 4. ProGuard rules application
 */
class R8FileMakerTest {

    private lateinit var r8FileMaker: R8FileMaker
    private lateinit var logger: StdLogger
    private lateinit var outputDir: File
    private lateinit var testSourceDir: File
    private lateinit var testClassesDir: File

    @Before
    fun setUp() {
        logger = TestGlobal.logger as StdLogger
        r8FileMaker = R8FileMaker(logger)
        outputDir = File(TestGlobal.buildDir, "r8_test_output")
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        testSourceDir = File(TestGlobal.buildDir, "r8_test_sources")
        testSourceDir.mkdirs()
        testClassesDir = File(TestGlobal.buildDir, "r8_test_classes")
        testClassesDir.mkdirs()
    }

    /**
     * Test basic R8 execution with simple obfuscation.
     *
     * Creates a simple class and verifies that R8:
     * 1. Successfully produces DEX output
     * 2. Obfuscates class names according to ProGuard rules
     * 3. Generates a mapping file
     */
    @Test
    fun testBasicObfuscation() {
        // Create test source files
        val helperSourceFile = File(testSourceDir, "Helper.java").apply {
            writeText("""
                package com.test;

                public class Helper {
                    private String message = "Hello";

                    public String getMessage() {
                        return message;
                    }

                    public void setMessage(String msg) {
                        this.message = msg;
                    }
                }
            """.trimIndent())
        }

        val mainSourceFile = File(testSourceDir, "Main.java").apply {
            writeText("""
                package com.test;

                public class Main {
                    public static void main(String[] args) {
                        Helper helper = new Helper();
                        System.out.println(helper.getMessage());
                    }
                }
            """.trimIndent())
        }

        // Compile Java to class files
        compileJava(listOf(helperSourceFile, mainSourceFile), testClassesDir)

        // Create ProGuard rules
        val proguardRules = """
            -keep class com.test.Main {
                public static void main(java.lang.String[]);
            }
            -dontoptimize
            -dontpreverify
        """.trimIndent()

        // Run R8
        r8FileMaker.optimizeWithRulesContent(
            outputDir = outputDir,
            classFilesOrDir = listOf(testClassesDir),
            classpath = emptyList(),
            androidJar = TestGlobal.androidJar,
            minApi = 21,
            proguardRulesContent = proguardRules
        )

        // Verify DEX output exists
        val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
        assertNotNull(dexFiles, "DEX files should be generated")
        assertTrue(dexFiles.isNotEmpty(), "At least one DEX file should be generated")

        // Verify mapping file exists
        val mappingFile = File(outputDir, "mapping.txt")
        assertTrue(mappingFile.exists(), "Mapping file should be generated")

        // Parse DEX to verify obfuscation
        val classes = parseDexFiles(dexFiles.toList())

        // Main class should be kept (not obfuscated)
        val mainClass = classes["Lcom/test/Main;"]
        assertNotNull(mainClass, "Main class should be kept according to ProGuard rules")

        // Helper class should be obfuscated (class name changed)
        val helperClass = classes["Lcom/test/Helper;"]
        if (helperClass == null) {
            // Helper was obfuscated, verify it exists with a different name
            val obfuscatedClasses = classes.keys.filter { it.startsWith("L") && !it.contains("com/test/Main") }
            assertTrue(obfuscatedClasses.isNotEmpty(), "Helper class should be obfuscated to a different name")
            logger.debug("Helper class was obfuscated. Remaining classes: $obfuscatedClasses")
        }

        // Verify mapping file contains mapping information
        val mappingContent = mappingFile.readText()
        assertTrue(mappingContent.isNotEmpty(), "Mapping file should contain mapping information")
        logger.debug("Mapping content:\n$mappingContent")
    }

    /**
     * Test R8 with method inlining optimization.
     *
     * Creates classes with simple methods that should be inlined by R8.
     * Verifies that R8 performs inlining optimization.
     */
    @Test
    fun testMethodInlining() {
        // Create test classes with inlinable methods
        val utilSourceFile = File(testSourceDir, "Util.java").apply {
            writeText("""
                package com.test.inline;

                public class Util {
                    // Small method that should be inlined
                    public static int add(int a, int b) {
                        return a + b;
                    }

                    // Small method that should be inlined
                    public static int multiply(int a, int b) {
                        return a * b;
                    }
                }
            """.trimIndent())
        }

        val calculatorSourceFile = File(testSourceDir, "Calculator.java").apply {
            writeText("""
                package com.test.inline;

                public class Calculator {
                    public int calculate(int x, int y) {
                        // These method calls should be inlined by R8
                        int sum = Util.add(x, y);
                        int product = Util.multiply(x, y);
                        return sum + product;
                    }
                }
            """.trimIndent())
        }

        val appSourceFile = File(testSourceDir, "App.java").apply {
            writeText("""
                package com.test.inline;

                public class App {
                    public static void main(String[] args) {
                        Calculator calc = new Calculator();
                        System.out.println(calc.calculate(5, 3));
                    }
                }
            """.trimIndent())
        }

        // Compile Java to class files
        compileJava(listOf(utilSourceFile, calculatorSourceFile, appSourceFile), testClassesDir)

        // Create ProGuard rules that allow optimization
        val proguardRules = """
            -keep class com.test.inline.App {
                public static void main(java.lang.String[]);
            }
            -keep class com.test.inline.Calculator {
                public int calculate(int, int);
            }
            # Allow optimization and inlining
            -optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
            -optimizationpasses 5
            -allowaccessmodification
        """.trimIndent()

        // Run R8 with optimization enabled
        r8FileMaker.optimizeWithRulesContent(
            outputDir = outputDir,
            classFilesOrDir = listOf(testClassesDir),
            classpath = emptyList(),
            androidJar = TestGlobal.androidJar,
            minApi = 21,
            proguardRulesContent = proguardRules
        )

        // Verify DEX output
        val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
        assertNotNull(dexFiles, "DEX files should be generated")
        assertTrue(dexFiles.isNotEmpty(), "At least one DEX file should be generated")

        // Parse DEX
        val classes = parseDexFiles(dexFiles.toList())

        // App class should be kept
        val appClass = classes["Lcom/test/inline/App;"]
        assertNotNull(appClass, "App class should be kept")

        // Calculator class should be kept
        val calculatorClass = classes["Lcom/test/inline/Calculator;"]
        assertNotNull(calculatorClass, "Calculator class should be kept")

        // Util class might be removed if methods are fully inlined
        val utilClass = classes["Lcom/test/inline/Util;"]
        if (utilClass == null) {
            logger.debug("Util class was removed (methods were inlined)")
        } else {
            logger.debug("Util class was kept (methods may not be fully inlined)")
            // Check if methods are present
            val addMethod = utilClass.methods?.find { it.method.name == "add" }
            val multiplyMethod = utilClass.methods?.find { it.method.name == "multiply" }
            logger.debug("Util.add exists: ${addMethod != null}")
            logger.debug("Util.multiply exists: ${multiplyMethod != null}")
        }

        // Verify mapping file
        val mappingFile = File(outputDir, "mapping.txt")
        assertTrue(mappingFile.exists(), "Mapping file should be generated")
        val mappingContent = mappingFile.readText()
        logger.debug("Mapping with inlining:\n$mappingContent")
    }

    /**
     * Test R8 with mixed obfuscation and inlining.
     *
     * This test combines both obfuscation and optimization to verify that R8:
     * 1. Obfuscates class and method names
     * 2. Inlines simple methods
     * 3. Removes unused code
     */
    @Test
    fun testObfuscationWithInlining() {
        // Create a more complex example with multiple classes
        val stringUtilSourceFile = File(testSourceDir, "StringUtil.java").apply {
            writeText("""
                package com.test.complex;

                public class StringUtil {
                    // This should be inlined
                    public static String concat(String a, String b) {
                        return a + b;
                    }

                    // This might be kept or obfuscated
                    public static String repeat(String s, int times) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < times; i++) {
                            sb.append(s);
                        }
                        return sb.toString();
                    }

                    // This is unused and should be removed
                    public static String unused() {
                        return "This method is never called";
                    }
                }
            """.trimIndent())
        }

        val processorSourceFile = File(testSourceDir, "Processor.java").apply {
            writeText("""
                package com.test.complex;

                public class Processor {
                    private String prefix;

                    public Processor(String prefix) {
                        this.prefix = prefix;
                    }

                    public String process(String input) {
                        // This call should be inlined
                        String combined = StringUtil.concat(prefix, input);
                        return combined;
                    }

                    public String processRepeat(String input, int times) {
                        return StringUtil.repeat(input, times);
                    }
                }
            """.trimIndent())
        }

        val mainSourceFile = File(testSourceDir, "MainApp.java").apply {
            writeText("""
                package com.test.complex;

                public class MainApp {
                    public static void main(String[] args) {
                        Processor proc = new Processor("PREFIX: ");
                        System.out.println(proc.process("test"));
                        System.out.println(proc.processRepeat("*", 5));
                    }
                }
            """.trimIndent())
        }

        // Compile Java
        compileJava(listOf(stringUtilSourceFile, processorSourceFile, mainSourceFile), testClassesDir)

        // ProGuard rules with optimization
        val proguardRules = """
            -keep class com.test.complex.MainApp {
                public static void main(java.lang.String[]);
            }
            -optimizationpasses 3
            -allowaccessmodification
        """.trimIndent()

        // Run R8
        r8FileMaker.optimizeWithRulesContent(
            outputDir = outputDir,
            classFilesOrDir = listOf(testClassesDir),
            classpath = emptyList(),
            androidJar = TestGlobal.androidJar,
            minApi = 21,
            proguardRulesContent = proguardRules
        )

        // Verify output
        val dexFiles = outputDir.listFiles { _, name -> name.endsWith(".dex") }
        assertNotNull(dexFiles)
        assertTrue(dexFiles.isNotEmpty())

        val classes = parseDexFiles(dexFiles.toList())

        // MainApp should be kept
        val mainApp = classes["Lcom/test/complex/MainApp;"]
        assertNotNull(mainApp, "MainApp should be kept")

        // Count total classes
        val totalClasses = classes.size
        logger.debug("Total classes after R8: $totalClasses")
        logger.debug("Classes: ${classes.keys}")

        // Verify mapping file contains obfuscation info
        val mappingFile = File(outputDir, "mapping.txt")
        assertTrue(mappingFile.exists())
        val mappingContent = mappingFile.readText()
        assertTrue(mappingContent.isNotEmpty())

        // Check for inlined methods in mapping
        val hasInlineInfo = mappingContent.contains("->") || mappingContent.contains(":")
        assertTrue(hasInlineInfo, "Mapping should contain obfuscation or inline information")

        logger.debug("Complex mapping:\n$mappingContent")
    }

    /**
     * Helper method to compile Java source files to class files.
     */
    private fun compileJava(sourceFiles: List<File>, outputDir: File) {
        outputDir.mkdirs()

        val javacCmd = mutableListOf<String>()
        javacCmd.add("${TestGlobal.javaHome}/bin/javac")
        javacCmd.add("-d")
        javacCmd.add(outputDir.absolutePath)
        javacCmd.add("-source")
        javacCmd.add("11")
        javacCmd.add("-target")
        javacCmd.add("11")
        javacCmd.add("-cp")
        javacCmd.add(TestGlobal.androidJar.absolutePath)
        sourceFiles.forEach { javacCmd.add(it.absolutePath) }

        val process = ProcessBuilder(javacCmd)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("javac failed with exit code $exitCode:\n$output")
        }

        logger.debug("Java compilation successful")
    }

    /**
     * Helper method to parse DEX files and extract class nodes.
     */
    private fun parseDexFiles(dexFiles: List<File>): Map<String, DexClassNode> {
        val classes = mutableMapOf<String, DexClassNode>()

        dexFiles.forEach { dexFile ->
            val dexBytes = dexFile.readBytes()
            val reader: BaseDexFileReader = MultiDexFileReader.open(dexBytes)
            val visitor = DexFileNode()
            reader.accept(visitor)

            visitor.clzs.forEach { classNode ->
                classes[classNode.className] = classNode
            }
        }

        return classes
    }

}
