package com.sickworm.intellij.jugg.deploy.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [EffectedClassNode.EffectedType] classification and the
 * extension properties [sources], [inlineImplChanges], [minifyMemberRemoved].
 *
 * These tests are written TDD-first and are expected to FAIL before the
 * production code changes.
 */
class EffectedClassNodeTypeTest {

    // ---- helpers --------------------------------------------------------

    private fun node(
        className: String,
        type: EffectedClassNode.EffectedType,
    ) = EffectedClassNode(
        className = className,
        sourceFileName = "Fake.java",
        effectedByClasses = listOf("Lcom/example/Caller;"),
        effectedType = type,
    )

    // ---- MINIFY_MEMBER_REMOVED enum value exists -------------------------

    /**
     * The new enum value MINIFY_MEMBER_REMOVED must exist in EffectedType.
     */
    @Test
    fun testMinifyMemberRemovedEnumValueExists() {
        val value = EffectedClassNode.EffectedType.valueOf("MINIFY_MEMBER_REMOVED")
        assertEquals(EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED, value)
    }

    // ---- extension property: sources ------------------------------------

    @Test
    fun testSourcesFilterExcludesMinifyMemberRemoved() {
        val nodes = listOf(
            node("Lcom/a;", EffectedClassNode.EffectedType.SOURCE),
            node("Lcom/b;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE),
            node("Lcom/c;", EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED),
        )
        val result = nodes.sources
        assertEquals(1, result.size)
        assertEquals("Lcom/a;", result[0].className)
    }

    // ---- extension property: inlineImplChanges --------------------------

    @Test
    fun testInlineImplChangesFilterExcludesMinifyMemberRemoved() {
        val nodes = listOf(
            node("Lcom/a;", EffectedClassNode.EffectedType.SOURCE),
            node("Lcom/b;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE),
            node("Lcom/c;", EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED),
        )
        val result = nodes.inlineImplChanges
        assertEquals(1, result.size)
        assertEquals("Lcom/b;", result[0].className)
    }

    // ---- extension property: minifyMemberRemoved ------------------------

    @Test
    fun testMinifyMemberRemovedFilter() {
        val nodes = listOf(
            node("Lcom/a;", EffectedClassNode.EffectedType.SOURCE),
            node("Lcom/b;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE),
            node("Lcom/c;", EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED),
        )
        val result = nodes.minifyMemberRemoved
        assertEquals(1, result.size)
        assertEquals("Lcom/c;", result[0].className)
    }

    // ---- three-way disjointness -----------------------------------------

    @Test
    fun testThreeTypesAreDisjoint() {
        val nodes = listOf(
            node("Lcom/a;", EffectedClassNode.EffectedType.SOURCE),
            node("Lcom/b;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE),
            node("Lcom/c;", EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED),
        )
        val allFiltered = nodes.sources + nodes.inlineImplChanges + nodes.minifyMemberRemoved
        assertEquals(nodes.size, allFiltered.size, "Three filters should cover all nodes without overlap")
    }

    // ---- enum has exactly 3 values --------------------------------------

    @Test
    fun testEffectedTypeEnumHasThreeValues() {
        val values = EffectedClassNode.EffectedType.values()
        assertEquals(3, values.size, "EffectedType should have exactly 3 values: SOURCE, INLINE_IMPL_CHANGE, MINIFY_MEMBER_REMOVED")
        assertTrue(values.any { it.name == "SOURCE" })
        assertTrue(values.any { it.name == "INLINE_IMPL_CHANGE" })
        assertTrue(values.any { it.name == "MINIFY_MEMBER_REMOVED" })
    }
}
