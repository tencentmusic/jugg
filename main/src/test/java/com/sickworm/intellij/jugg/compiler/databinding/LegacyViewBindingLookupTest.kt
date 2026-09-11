package com.sickworm.intellij.jugg.compiler.databinding

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyViewBindingLookupTest {

    @Test
    fun rewriteReplacesImportedFindChildViewById() {
        val source = """
            package com.example.databinding;

            import android.view.View;
            import android.widget.TextView;
            import androidx.annotation.NonNull;
            import androidx.viewbinding.ViewBinding;
            import androidx.viewbinding.ViewBindings;

            public final class ActivityMainBinding implements ViewBinding {
              @NonNull
              public static ActivityMainBinding bind(@NonNull View rootView) {
                int id = R.id.title;
                TextView title = ViewBindings.findChildViewById(rootView, id);
                return new ActivityMainBinding(rootView, title);
              }
            }
        """.trimIndent()

        val rewritten = LegacyViewBindingLookup.rewriteJavaSource(source)

        assertFalse(rewritten.contains("ViewBindings"))
        assertFalse(rewritten.contains("import androidx.viewbinding.ViewBindings;"))
        assertTrue(rewritten.contains("TextView title = rootView.findViewById(id);"))
    }

    @Test
    fun rewriteReplacesFullyQualifiedFindChildViewById() {
        val source = "TextView title = androidx.viewbinding.ViewBindings.findChildViewById(content, R.id.title);"
        val rewritten = LegacyViewBindingLookup.rewriteJavaSource(source)
        assertEquals("TextView title = content.findViewById(R.id.title);", rewritten)
    }

    @Test
    fun rewriteLeavesLegacyFindViewByIdUnchanged() {
        val source = "TextView title = rootView.findViewById(id);"
        assertEquals(source, LegacyViewBindingLookup.rewriteJavaSource(source))
    }
}
