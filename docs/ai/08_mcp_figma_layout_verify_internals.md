# Figma Layout Verification Internals

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope and Ownership

`FigmaLayoutVerifyMcpToolAction` exists but is **absent from `McpToolActionRegistry.defaultActions()`**. Neither IDEA nor Standalone advertises `figma-layout-verify` through `tools/list`; this page describes internal Kotlin behavior for maintenance, not a callable public MCP contract. App-side `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/viewhierarchy/LayoutVerifier.java` belongs to the older ViewHierarchy/layout-verify direction and does not run this Figma relationship algorithm.

| Stage | Owner |
|---|---|
| Internal action and Android dump | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/` (`FigmaLayoutVerifyMcpToolAction.kt`, `LayoutDumpHelper.kt`) |
| Orchestration and report count | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/FigmaLayoutVerifier.kt` |
| Figma parsing and DFS flattening | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/parser/FigmaJsonParser.kt` |
| Spacing/alignment extraction | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/extractor/RelationExtractor.kt` |
| Bounds matching and relation checks | `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/matcher/ElementMatcher.kt`; `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/layout/verifier/RelationVerifier.kt` |

## 2. Actual Action Boundary

`FigmaLayoutVerifyMcpToolAction.execute()` first obtains an internal Android hierarchy JSON through `LayoutDumpHelper.dumpInternal()` and returns its failure unchanged. It then reads the Figma file, calls `FigmaJsonParser.validate()` on the **top-level JSON object**, reads Android `windows[].root` nodes and `deviceInfo.screenWidth/screenHeight`, derives a Figma canvas size, and calls `FigmaLayoutVerifier.verify()` with both node sets. The Android bounds and screen size from the internal dump are in dp; Figma values are pixel coordinates until spacing conversion. The action defaults `dpr` to `1.0` and does not check that a supplied value is positive.

`FigmaJsonParser.parse()` can extract a direct node (`id` plus `layout` or `bounds`), the first entry of a `nodes` wrapper, or the first child of a `document` wrapper. **The action's earlier top-level `validate()` check requires a direct node**, and its canvas-size read also expects top-level `layout` or `bounds`. Consequently, wrapper support in the parser does not mean either wrapper works through the current action. Root validation checks `id`, presence of `layout`/`bounds`, and four coordinates; malformed children can still fail later during parsing. Invalid top-level shape returns `INVALID_FIGMA_FORMAT`; later parsing/size failures become `INTERNAL_ERROR`.

`layout` means `[x, y, width, height]` and becomes `[x, y, x+width, y+height]`; `bounds` means `[left, top, right, bottom]` and is retained. The action uses top-level `layout[2..3]` or `bounds[2..3]` as the Figma canvas width/height. For nonzero-offset root `bounds`, right/bottom are not necessarily width/height, so matching can be distorted. Zero canvas dimensions also make normalization invalid. Verify the actual root coordinates before interpreting an IoU score.

## 3. Relation Extraction

`FigmaLayoutVerifier.verify()` obtains preorder nodes from `FigmaJsonParser.flattenNodes()` for endpoint lookup, then calls `RelationExtractor.extractRelations()`, which separately flattens the tree for relation generation; both include containers and leaves. Its `extractSpacingRelations()` considers only **consecutive flattened nodes**, including parent–child neighbors; visual neighbors elsewhere in the hierarchy are omitted. Horizontal spacing requires their top coordinates to differ by less than `(20*dpr).toInt()` and the second left edge to be at or beyond the first right edge. Vertical spacing uses the analogous left-coordinate and top/bottom test. Expected gap is the edge difference divided by `dpr` and truncated to integer dp. A missing spacing relation is therefore not evidence that the visual gap is correct.

`RelationExtractor.extractAlignmentRelations()` groups nodes by `(top / tolerance) * tolerance` for the y axis and `(left / tolerance) * tolerance` for the x axis, where `tolerance=(5*dpr).toInt()`. Each bucket with at least two nodes yields one relation. Verification compares **centers**, not the top/left coordinates that formed the bucket; differently sized nodes can fail despite sharing a bucket. With `dpr` small enough to truncate tolerance to zero, extraction can fail instead of producing an empty/failed report.

## 4. Matching and Verification

For each extracted relation, `FigmaLayoutVerifier.verify()` calls `ElementMatcher.match()` for its endpoints and then `RelationVerifier.verifySpacing()` or `verifyAlignment()` for matched Android nodes. `ElementMatcher.match()` normalizes each Figma and Android rectangle to integer coordinates on a 1000×1000 canvas using its own screen width/height. It ranks Android candidates by intersection-over-union and accepts only scores **strictly greater than 0.7**. The highest candidate is used; at most three alternatives are stored by the matcher but `FigmaLayoutVerifier` does not try them. Names, text, class, ID, and content description do not affect matching. The matcher makes no one-to-one assignment, so different Figma endpoints may select the same Android node or an overlapping container.

| Relation | Actual Android value | Pass rule |
|---|---|---|
| Horizontal/vertical spacing | Second left minus first right, or second top minus first bottom, in dp | `abs(actual - expected) <= 2` **or** `abs(actual - expected) / expected <= 0.05`. |
| x/y alignment | Maximum minus minimum center coordinate among matched nodes, in dp | Difference `<= 2`. |

The percentage branch uses raw `expected`: when it is zero, the code substitutes percentage difference `0`. A negative expected value, possible for direct verifier callers but not produced by current adjacency extraction, makes the percentage negative. Thus a zero-gap relation can pass despite exceeding 2 dp. An unmatched spacing endpoint drops that relation entirely. An alignment relation is checked only if at least two endpoints matched; its reported description still counts the original group. `total`, `passed`, and `failed` count **evaluated relations**, not all extracted relations or all Figma nodes.

The internal action returns tool `status=OK` even when `report.failed > 0`, and can report `0` verified relations as OK. Consumers must inspect `data.total`, `data.failed`, and each `results[].match`; the tool-level status does not establish layout equivalence. Color, typography, and corner radius are outside this algorithm. For publicly callable UI evidence, use a Runtime that advertises `view-locate` and `view-inspect` in its `tools/list`.

## 5. Diagnostic Start Points

| Observation | Discriminating evidence |
|---|---|
| MCP says tool not found | `defaultActions()` and the connected Runtime's `tools/list`; class existence is insufficient. |
| Wrapper JSON fails despite parser support | Action's top-level `validate()` and canvas-size access before `FigmaLayoutVerifier.verify()`. |
| No relation or suspiciously small `total` | DFS order, adjacent spacing pairs, alignment buckets, and unmatched endpoint drops. |
| Wrong Android node or false pass | Canvas dimensions, normalized rectangles, competing IoU scores, and reused Android match. |
| Counterintuitive spacing success | `dpr`, truncated expected dp, and `expected <= 0` percentage branch. |
| Alignment failure among same-edge nodes | Bucketed top/left versus verified center coordinates. |

## 6. Related Documents

- `08_mcp_design.md` and `08_mcp_tools_list.md` — current public registration and response boundaries.
- `08_mcp_layout_verify_design.md` and `08_mcp_ui_verify_checklist.md` — callable UI observation workflow.
