package com.sickworm.intellij.jugg.compiler.source

import org.junit.Test
import java.io.File
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Investigates locale behavior of [Diagnostic.getMessage] and [Diagnostic.toString] under
 * different JVM locales, to determine the correct approach for locale-independent keyword matching
 * in GitChangesRetryResolver.
 *
 * Key findings:
 * - [Diagnostic.toString] uses the locale baked in by javac's DiagnosticFormatter at compile time
 *   (which is the JVM default locale at that point). It is NOT locale-neutral.
 * - [Diagnostic.getMessage] with [Locale.ENGLISH] does NOT reliably return English. The locale
 *   parameter is effectively ignored; the message is formatted using the locale passed to
 *   [javax.tools.JavaCompiler.getStandardFileManager]. If fileManager was initialized with
 *   Locale.JAPANESE, getMessage(Locale.ENGLISH) returns Japanese (or Chinese depending on JDK).
 * - The only truly locale-independent approach is [Diagnostic.getCode], which returns a
 *   structured error code string (e.g. "compiler.err.cant.resolve.location") regardless of locale.
 *
 * This confirms that JavaCompilerInvoker should use diagnostic.code for keyword-free matching,
 * rather than relying on any string-based approach.
 */
class JavaDiagnosticLocaleTest {

    companion object {
        // Locales that javac ships with localized message bundles (OpenJDK compiler_*.properties)
        val LOCALES_WITH_JAVAC_BUNDLES = listOf(
            Locale.JAPANESE,    // ja
            Locale.CHINESE,     // zh_CN
            Locale("zh", "TW"), // zh_TW
        )

        val SOURCE_FILE: File by lazy {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "jugg_locale_test")
            tempDir.mkdirs()
            val f = File(tempDir, "SymbolNotFoundTest.java")
            f.writeText("""
                public class SymbolNotFoundTest {
                    public void test() {
                        UndefinedClass obj = new UndefinedClass();
                    }
                }
            """.trimIndent())
            f
        }
    }

    private fun compileWithLocale(locale: Locale): List<Diagnostic<out JavaFileObject>> {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("System Java compiler not available (requires JDK, not JRE)")

        val collector = DiagnosticCollector<JavaFileObject>()
        // Pass locale to file manager: this is the standard way to influence javac message locale
        val fileManager = compiler.getStandardFileManager(collector, locale, null)
        val compilationUnits = fileManager.getJavaFileObjects(SOURCE_FILE)
        val task = compiler.getTask(null, fileManager, collector, null, null, compilationUnits)
        task.call()
        fileManager.close()
        return collector.diagnostics
    }

    /**
     * Verifies that getMessage(Locale.ENGLISH) is NOT reliable for getting English text.
     * When fileManager is initialized with a non-English locale, getMessage(Locale.ENGLISH)
     * returns the localized (non-English) message, not English.
     */
    @Test
    fun `getMessage(ENGLISH) is unreliable - returns localized text when fileManager locale is non-English`() {
        val japaneseErrors = compileWithLocale(Locale.JAPANESE)
            .filter { it.kind == Diagnostic.Kind.ERROR }
        assertTrue(japaneseErrors.isNotEmpty())

        val getMessageEnglish = japaneseErrors.first().getMessage(Locale.ENGLISH)
        println("locale=ja, getMessage(ENGLISH) = $getMessageEnglish")

        // getMessage(Locale.ENGLISH) does NOT return English when fileManager was initialized with
        // Locale.JAPANESE. The locale parameter to getMessage() is effectively ignored.
        assertFalse(
            getMessageEnglish.contains("cannot find symbol"),
            "getMessage(Locale.ENGLISH) unexpectedly returned English - JDK behavior may have changed"
        )
    }

    /**
     * Verifies that diagnostic.code is always a stable, locale-independent identifier
     * regardless of the locale used during compilation.
     */
    @Test
    fun `diagnostic code is locale-independent across all locales`() {
        LOCALES_WITH_JAVAC_BUNDLES.forEach { locale ->
            val errors = compileWithLocale(locale).filter { it.kind == Diagnostic.Kind.ERROR }
            assertTrue(errors.isNotEmpty(), "Expected errors for locale=$locale")

            errors.forEach { diagnostic ->
                val code = diagnostic.code
                val message = diagnostic.getMessage(locale)
                println("[locale=$locale] code    = $code")
                println("[locale=$locale] message = $message")
                println("---")

                assertTrue(
                    code.contains("cant.resolve") || code.contains("doesnt.exist"),
                    "Expected symbol-not-found error code for locale=$locale, got: $code"
                )
            }
        }
    }

    /**
     * Documents the full locale behavior difference between toString(), getMessage(locale),
     * getMessage(ENGLISH), and diagnostic.code.
     */
    @Test
    fun `document full locale behavior comparison`() {
        val originalLocale = Locale.getDefault()
        try {
            LOCALES_WITH_JAVAC_BUNDLES.forEach { locale ->
                Locale.setDefault(locale)
                val errors = compileWithLocale(locale).filter { it.kind == Diagnostic.Kind.ERROR }
                assertTrue(errors.isNotEmpty())

                val d = errors.first()
                println("=== locale=$locale ===")
                println("code                  = ${d.code}")
                println("getMessage(locale)    = ${d.getMessage(locale)}")
                println("getMessage(ENGLISH)   = ${d.getMessage(Locale.ENGLISH)}")
                println("toString()            = ${d}")
                println()
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
