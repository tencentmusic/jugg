package com.sickworm.intellij.jugg.compiler.databinding

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract: the incremental mapper delegate must merge the dependency mappers reported by the
 * whole _Inc_1.._Inc_N chain and by the _Full baseline, instead of returning the first non-null
 * list. Returning the first list drops library mappers, so library layouts stop being resolvable
 * through the merged mapper and DataBindingUtil.inflate / setContentView returns null.
 *
 * The delegate, holder and _Full java sources are generated from the production templates,
 * compiled with javac and asserted through reflection, so the behaviour under test is the
 * behaviour of the java code that Jugg actually writes.
 */
class DataBindingTemplatesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val packageName = "demo.databinding"
    private val packagePath = packageName.replace('.', '/')

    /**
     * The first inc mapper is a stale snapshot reporting an empty (but non-null) dependency list -
     * the shape a mapper produced from incomplete layout-info input has. The remaining inc mappers
     * each contribute one library mapper and the _Full baseline contributes a third one, so every
     * library mapper must survive the merge.
     */
    @Test
    fun delegateMergesEveryLibraryMapperFromIncChainAndBaseline() {
        assertEquals(
            listOf("LibMapperA", "LibMapperB", "LibMapperC"),
            collectDependencyNamesFromGeneratedDelegate(),
        )
    }

    /**
     * mergeDependencies is the testable twin of the merge algorithm emitted by the generated
     * mapper, so it must keep every mapper reported by the whole chain plus the baseline.
     */
    @Test
    fun mergeDependenciesKeepsEveryMapperOfChainAndBaseline() {
        val libA = Any()
        val libB = Any()
        val libC = Any()

        val merged = DataBindingTemplates(isUseAndroidX = true).mergeDependencies(
            listOf(emptyList(), listOf(libA), listOf(libB)),
            listOf(libC),
        )

        assertEquals(listOf<Any>(libA, libB, libC), merged)
    }

    @Test
    fun mergeDependenciesDeduplicatesAndKeepsFirstSeenOrder() {
        val libA = Any()
        val libB = Any()

        val merged = DataBindingTemplates(isUseAndroidX = true).mergeDependencies(
            listOf(listOf(libA, libB), null, listOf(libB)),
            listOf(libA),
        )

        assertEquals(listOf<Any>(libA, libB), merged)
    }

    @Test
    fun mergeDependenciesFallsBackToBaselineWhenChainIsEmpty() {
        val libC = Any()

        val merged = DataBindingTemplates(isUseAndroidX = true).mergeDependencies(emptyList(), listOf(libC))

        assertEquals(listOf<Any>(libC), merged)
    }

    /** getLayoutId keeps search semantics: the first inc mapper that knows the tag wins. */
    @Test
    fun delegateKeepsFirstHitLookupSemantics() {
        val delegate = loadGeneratedDelegate()

        assertEquals(7, invokeNumber(delegate, "getLayoutId", "layout/screen"))
        assertEquals("brValue", invokeString(delegate, "convertBrIdToString", 5))
    }

    private fun collectDependencyNamesFromGeneratedDelegate(): List<String> {
        val delegate = loadGeneratedDelegate()
        val list = delegate.javaClass.getMethod("collectDependencies").invoke(delegate) as List<*>
        return list.filterNotNull().map { it.javaClass.simpleName }
    }

    private fun invokeNumber(delegate: Any, method: String, argument: String): Int =
        (delegate.javaClass.getMethod(method, String::class.java).invoke(delegate, argument) as Number).toInt()

    private fun invokeString(delegate: Any, method: String, argument: Int): Any? =
        delegate.javaClass.getMethod(method, Int::class.java).invoke(delegate, argument)

    private fun loadGeneratedDelegate(): Any {
        val classes = generateAndCompileDelegate()
        val loader = URLClassLoader(arrayOf(classes.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        // the baseline mapper text must have been renamed into its _Full class
        loader.loadClass("$packageName.DataBinderMapperImpl_Full")
        return loader.loadClass("$packageName.DataBinderMapperImpl").getDeclaredConstructor().newInstance()
    }

    private fun generateAndCompileDelegate(): File {
        val root = temporaryFolder.root
        val stubRoot = File(root, "stubs")
        val fixtureRoot = File(root, "fixtures")
        val sourceRoot = File(root, "source")
        val baselineRoot = File(root, "baseline")

        // androidx / android stubs the generated java depends on
        writeSource(stubRoot, "android/view/View.java", "package android.view; public class View {}")
        writeSource(stubRoot, "androidx/databinding/DataBindingComponent.java",
            "package androidx.databinding; public interface DataBindingComponent {}")
        writeSource(stubRoot, "androidx/databinding/ViewDataBinding.java",
            "package androidx.databinding; public abstract class ViewDataBinding {}")
        writeSource(stubRoot, "androidx/databinding/DataBinderMapper.java", """
            package androidx.databinding;
            import android.view.View;
            import java.util.List;
            public abstract class DataBinderMapper {
                public abstract ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId);
                public abstract ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId);
                public abstract int getLayoutId(String tag);
                public abstract String convertBrIdToString(int localId);
                public abstract List<DataBinderMapper> collectDependencies();
            }
        """.trimIndent())

        // leaf library mappers: empty, non-null dependency list
        listOf("LibMapperA", "LibMapperB", "LibMapperC").forEach { name ->
            writeSource(fixtureRoot, "$packagePath/$name.java", mapperSource(name))
        }
        writeSource(fixtureRoot, "$packagePath/IncMapper1.java", mapperSource("IncMapper1"))
        writeSource(fixtureRoot, "$packagePath/IncMapper2.java",
            mapperSource("IncMapper2", layoutId = "7", brString = "\"brValue\"", dependencies = listOf("LibMapperA")))
        writeSource(fixtureRoot, "$packagePath/IncMapper3.java",
            mapperSource("IncMapper3", dependencies = listOf("LibMapperB")))

        // baseline mapper: the source of DataBinderMapperImpl_Full, kept outside the compile roots
        val baselineMapper = writeSource(baselineRoot, "$packagePath/DataBinderMapperImpl.java",
            mapperSource("DataBinderMapperImpl", layoutId = "42", dependencies = listOf("LibMapperC")))

        val templates = DataBindingTemplates(isUseAndroidX = true)
        templates.generateFullMapperFile(
            baselineMapper, writeSource(sourceRoot, "$packagePath/DataBinderMapperImpl_Full.java", ""))
        val incEntries = (1..3).joinToString("") { "\n                                new $packageName.IncMapper$it()," }
        writeSource(sourceRoot, "$packagePath/DataBinderMapper_IncrementalHolder.java", templates.holderTemplate
            .replace("_package_name_holder_", packageName)
            .replace("_inc_mapper_array_holder_", incEntries))
        writeSource(sourceRoot, "$packagePath/DataBinderMapperImpl.java", templates.mapperContentTemplate
            .replace("_package_name_holder_", packageName))

        val classes = File(root, "classes")
        compileJava(listOf(stubRoot, fixtureRoot, sourceRoot), classes)
        return classes
    }

    /** Emits a mapper whose collectDependencies() returns the supplied dependency list. */
    private fun mapperSource(
        className: String,
        layoutId: String = "0",
        brString: String = "null",
        dependencies: List<String> = emptyList(),
    ): String = """
        package $packageName;
        import android.view.View;
        import androidx.databinding.DataBinderMapper;
        import androidx.databinding.DataBindingComponent;
        import androidx.databinding.ViewDataBinding;
        import java.util.List;
        public class $className extends DataBinderMapper {
            @Override public ViewDataBinding getDataBinder(DataBindingComponent component, View view, int layoutId) { return null; }
            @Override public ViewDataBinding getDataBinder(DataBindingComponent component, View[] views, int layoutId) { return null; }
            @Override public int getLayoutId(String tag) { return $layoutId; }
            @Override public String convertBrIdToString(int localId) { return $brString; }
            @Override public List<DataBinderMapper> collectDependencies() {
                List<DataBinderMapper> result = new java.util.ArrayList<DataBinderMapper>();
                ${dependencies.joinToString("\n        ") { "result.add(new $packageName.$it());" }}
                return result;
            }
        }
    """.trimIndent()

    private fun compileJava(roots: List<File>, classes: File) {
        classes.mkdirs()
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("javac is required to run this test")
        val sources = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "java" }.toList() }
        assertTrue(sources.isNotEmpty(), "no java sources found in $roots")
        val succeeded = compiler.getStandardFileManager(null, null, null).use { fileManager ->
            compiler.getTask(null, fileManager, null, listOf("-d", classes.path), null,
                fileManager.getJavaFileObjectsFromFiles(sources)).call()
        }
        assertTrue(succeeded, "javac failed for generated delegate sources")
    }

    private fun writeSource(root: File, relativePath: String, text: String): File =
        File(root, relativePath).apply {
            parentFile.mkdirs()
            writeText(text)
        }
}
