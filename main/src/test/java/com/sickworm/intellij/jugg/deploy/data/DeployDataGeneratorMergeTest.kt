package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [DeployDataGenerator.merge] type-priority logic.
 *
 * When a class appears in both the regular effected list (SOURCE) and the
 * inline effected list (INLINE_IMPL_CHANGE), SOURCE should win because
 * source-level recompilation can fix inline issues, but NOT vice-versa.
 *
 * Uses reflection to call the private `merge` method directly.
 */
class DeployDataGeneratorMergeTest {

    private lateinit var generator: DeployDataGenerator

    @Before
    fun setUp() {
        clearBuild()
        generator = DeployDataGenerator(logger, buildDir)
        generator.init(projectInfo.apkInfos, emptyList())
    }

    // ---- helpers --------------------------------------------------------

    private fun node(
        className: String,
        type: EffectedClassNode.EffectedType,
        effectedBy: List<String> = listOf("Lcom/example/Trigger;"),
    ) = EffectedClassNode(
        className = className,
        sourceFileName = "Fake.java",
        effectedByClasses = effectedBy,
        effectedType = type,
    )

    private fun callMerge(
        effectedNodes: MutableList<EffectedClassNode>,
        inlineEffectedNodes: List<EffectedClassNode>,
    ) {
        val method = DeployDataGenerator::class.java.getDeclaredMethod(
            "merge",
            MutableList::class.java,
            List::class.java,
        )
        method.isAccessible = true
        method.invoke(generator, effectedNodes, inlineEffectedNodes)
    }

    // ---- merge: SOURCE should NOT be downgraded to INLINE_IMPL_CHANGE ----

    /**
     * When an existing node is SOURCE and an inline node with the same className
     * arrives, the merged result must keep SOURCE (not downgrade to INLINE_IMPL_CHANGE).
     */
    @Test
    fun testMergePreservesSourceWhenInlineNodeOverlaps() {
        val effected = mutableListOf(
            node("Lcom/example/A;", EffectedClassNode.EffectedType.SOURCE, listOf("Lcom/ref/X;")),
        )
        val inline = listOf(
            node("Lcom/example/A;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, listOf("Lcom/ref/Y;")),
        )

        callMerge(effected, inline)

        assertEquals(1, effected.size)
        val merged = effected[0]
        assertEquals("Lcom/example/A;", merged.className)
        // SOURCE must be preserved — this is the key assertion
        assertEquals(
            EffectedClassNode.EffectedType.SOURCE,
            merged.effectedType,
            "SOURCE should NOT be downgraded to INLINE_IMPL_CHANGE during merge"
        )
        // effectedByClasses should be merged from both sides
        assertEquals(
            listOf("Lcom/ref/X;", "Lcom/ref/Y;"),
            merged.effectedByClasses.sorted()
        )
    }

    // ---- merge: INLINE_IMPL_CHANGE remains when no SOURCE exists --------

    /**
     * When an existing node is INLINE_IMPL_CHANGE (not SOURCE) and an inline node arrives,
     * the merged result should stay INLINE_IMPL_CHANGE.
     */
    @Test
    fun testMergeKeepsInlineWhenNoSource() {
        val effected = mutableListOf(
            node("Lcom/example/B;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, listOf("Lcom/ref/X;")),
        )
        val inline = listOf(
            node("Lcom/example/B;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, listOf("Lcom/ref/Y;")),
        )

        callMerge(effected, inline)

        assertEquals(1, effected.size)
        assertEquals(EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, effected[0].effectedType)
    }

    // ---- merge: MINIFY_MEMBER_REMOVED + INLINE_IMPL_CHANGE → INLINE_IMPL_CHANGE

    /**
     * When an existing node is MINIFY_MEMBER_REMOVED and an inline node arrives,
     * the merged result should become INLINE_IMPL_CHANGE (not keep MINIFY_MEMBER_REMOVED).
     */
    @Test
    fun testMergeMinifyMemberRemovedWithInlineBecomesInline() {
        val effected = mutableListOf(
            node("Lcom/example/C;", EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED, listOf("Lcom/ref/X;")),
        )
        val inline = listOf(
            node("Lcom/example/C;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, listOf("Lcom/ref/Y;")),
        )

        callMerge(effected, inline)

        assertEquals(1, effected.size)
        assertEquals(EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, effected[0].effectedType)
    }

    // ---- merge: new class (no existing) is added as INLINE_IMPL_CHANGE ----

    @Test
    fun testMergeAddsNewInlineNode() {
        val effected = mutableListOf(
            node("Lcom/example/A;", EffectedClassNode.EffectedType.SOURCE),
        )
        val inline = listOf(
            node("Lcom/example/B;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE),
        )

        callMerge(effected, inline)

        assertEquals(2, effected.size)
        val added = effected.find { it.className == "Lcom/example/B;" }!!
        assertEquals(EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, added.effectedType)
    }

    // ---- merge: effectedByClasses are deduplicated ----------------------

    @Test
    fun testMergeDeduplicatesEffectedByClasses() {
        val shared = "Lcom/ref/Shared;"
        val effected = mutableListOf(
            node("Lcom/example/D;", EffectedClassNode.EffectedType.SOURCE, listOf(shared, "Lcom/ref/A;")),
        )
        val inline = listOf(
            node("Lcom/example/D;", EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE, listOf(shared, "Lcom/ref/B;")),
        )

        callMerge(effected, inline)

        assertEquals(1, effected.size)
        // shared should appear only once
        val byClasses = effected[0].effectedByClasses
        assertEquals(byClasses.distinct(), byClasses, "effectedByClasses should have no duplicates")
        assertEquals(3, byClasses.size) // Shared, A, B
    }
}
