package com.sickworm.intellij.jugg.ai.mcp.viewhierarchy

import com.google.gson.JsonObject

/**
 * ViewHierarchyRequest describes one socket action request sent to app-side server.
 */
data class ViewHierarchyRequest(
    val action: String,
    val params: Map<String, Any?> = emptyMap(),
)

/**
 * ViewHierarchyResponse is the normalized response envelope from app-side server.
 */
data class ViewHierarchyResponse(
    val status: String?,
    val message: String?,
    val data: JsonObject?,
    val version: String? = null,
)

/**
 * LayoutDumpResult models two transport modes: inline JSON or remote file path.
 */
data class LayoutDumpResult(
    val payloadJson: String?,
    val remoteFilePath: String?,
    val errorMessage: String? = null,
)

/**
 * MatchCandidate carries one candidate entry when multiple elements match.
 */
data class MatchCandidate(
    val text: String,
    val resourceId: String,
    val contentDesc: String,
    val className: String,
    val bounds: List<Int>?,
    val centerX: Int,
    val centerY: Int,
    val source: SourceLocation? = null,
)

/**
 * SourceLocation carries best-effort source metadata reported by the app runtime.
 */
data class SourceLocation(
    val file: String? = null,
    val line: Int? = null,
)

/**
 * FindElementsResult models a budgeted find_elements response.
 */
data class FindElementsResult(
    val matchCount: Int,
    val returnedCount: Int,
    val truncated: Boolean,
    val density: Double,
    val matches: List<MatchCandidate>,
    val errorMessage: String? = null,
)

/**
 * MatchedElementData is the structured element payload returned on element-mode success.
 */
data class MatchedElementData(
    val text: String,
    val className: String,
    val resourceId: String,
    val contentDesc: String,
    val bounds: List<Int>,
    val centerX: Int,
    val centerY: Int,
    val source: SourceLocation? = null,
)

/**
 * VerifyResult is the PASS/FAIL/ERROR outcome from a layout_verify call.
 */
data class VerifyResult(
    val result: String,        // "PASS", "FAIL", "ERROR"
    val message: String,
    val actual: Any? = null,
    val expected: Any? = null,
    val unit: String? = null,
    val candidates: List<MatchCandidate> = emptyList(),
)
sealed class FindAndTapResult {
    data class Success(
        val x: Int,
        val y: Int,
        val matchedElement: MatchedElementData,
        val matchCount: Int,
    ) : FindAndTapResult()

    data class Multiple(
        val matchCount: Int,
        val matches: List<MatchCandidate>,
        val message: String,
    ) : FindAndTapResult()

    data class NotFound(
        val candidates: List<MatchCandidate>,
        val message: String,
    ) : FindAndTapResult()

    data class Failure(
        val message: String,
    ) : FindAndTapResult()
}

/**
 * EvalViewResult models the response from an eval_view request.
 */
data class EvalViewResult(
    val className: String,
    val resourceId: String,
    val density: Double,
    val values: List<EvalViewValue>,
    val source: SourceLocation? = null,
    val errorMessage: String? = null,
)

/**
 * EvalViewValue models one expression evaluation result.
 */
data class EvalViewValue(
    val expression: String,
    val value: Any?,
    val type: String,
    val error: String? = null,
)
