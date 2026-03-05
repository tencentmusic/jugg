package com.sickworm.intellij.jugg.mcp.viewhierarchy

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
)

/**
 * FindAndTapResult keeps business outcomes distinguishable from transport failures.
 */
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
